package net.java21.crowfoot.capture.capture;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 주소를 검사하는 HTTP 프록시 (11-capture/00-capture-service.md Section 3). 캡처 한 건마다 루프백에 하나 띄우고
 * 브라우저의 모든 연결을 이 프록시로 보낸다.
 *
 * <p>Playwright의 요청 가로채기(route)는 리다이렉트를 보지 못한다 — 브라우저가 3xx를 따라간 다음 요청은 route를
 * 거치지 않는다(문서·하위 요청 모두, Playwright 1.63 Chromium에서 확인). 그래서 연결 단계에서 막는다.
 * <ul>
 * <li>{@code CONNECT host:port}(https, wss) — 호스트를 풀어 검사하고, 검사한 바로 그 주소로만 터널을 연다
 * <li>절대 주소 요청(http) — 같은 검사 뒤 원 서버에 요청 하나를 넘기고 연결을 닫는다({@code Connection: close})
 * </ul>
 * 검사한 주소로 직접 붙으므로 검사와 연결 사이에 이름 풀이가 바뀌는 경우(DNS 재바인딩)도 막힌다.
 * 막거나 실패하면 {@code X-Crowfoot-Proxy} 헤더에 사유(blocked, unresolved, refused, unreachable)를 싣고,
 * 마지막 사유를 {@link #lastFailure()}로 남긴다 — https 터널 실패는 브라우저가 사유를 알려 주지 않기 때문이다.
 */
@Slf4j
public class CheckingProxy implements AutoCloseable {

    public static final String REASON_HEADER = "X-Crowfoot-Proxy";
    private static final int HEAD_MAX = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int HEAD_READ_TIMEOUT_MILLIS = 15_000;

    /** 연결 실패 사유 */
    public enum Reason {
        BLOCKED("blocked"),
        UNRESOLVED("unresolved"),
        REFUSED("refused"),
        UNREACHABLE("unreachable");

        private final String token;

        Reason(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        public static Reason fromToken(String token) {
            for (Reason r : values()) {
                if (r.token.equalsIgnoreCase(token)) {
                    return r;
                }
            }
            return null;
        }
    }

    /** 마지막 실패 — 사유와 대상(host:port) */
    public record Failure(Reason reason, String target) {
    }

    private final AddressGuard guard;
    private final ServerSocket server;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Failure> lastFailure = new AtomicReference<>();
    private volatile boolean closed;

    public CheckingProxy(AddressGuard guard) throws IOException {
        this.guard = guard;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("capture-proxy-accept").start(this::acceptLoop);
    }

    /** 브라우저에 넘길 프록시 주소 */
    public String address() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    public Failure lastFailure() {
        return lastFailure.get();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket client = server.accept();
                track(client);
                Thread.ofVirtual().name("capture-proxy").start(() -> handle(client));
            } catch (IOException e) {
                if (!closed) {
                    log.debug("proxy accept failed: {}", e.toString());
                }
                return;
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(HEAD_READ_TIMEOUT_MILLIS);
            InputStream in = client.getInputStream();
            byte[] head = readHead(in);
            if (head == null) {
                closeQuietly(client);
                return;
            }
            client.setSoTimeout(0);
            String headText = new String(head, StandardCharsets.ISO_8859_1);
            int lineEnd = headText.indexOf("\r\n");
            String[] requestLine = headText.substring(0, lineEnd).split(" ");
            if (requestLine.length != 3) {
                respond(client, 400, "Bad Request", null);
                return;
            }
            if (requestLine[0].equalsIgnoreCase("CONNECT")) {
                tunnel(client, requestLine[1]);
            } else {
                forward(client, requestLine, headText.substring(lineEnd + 2), in);
            }
        } catch (IOException e) {
            closeQuietly(client);
        }
    }

    /** https·wss — CONNECT host:port */
    private void tunnel(Socket client, String authority) throws IOException {
        int colon = authority.lastIndexOf(':');
        if (colon <= 0) {
            respond(client, 400, "Bad Request", null);
            return;
        }
        String host = authority.substring(0, colon);
        int port = parsePort(authority.substring(colon + 1));
        Socket upstream = open(client, host, port);
        if (upstream == null) {
            return;
        }
        client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        client.getOutputStream().flush();
        pipe(client, upstream, client.getInputStream());
    }

    /** http — 절대 주소 요청을 원 서버 요청으로 바꿔 하나만 넘긴다 */
    private void forward(Socket client, String[] requestLine, String headers, InputStream clientIn) throws IOException {
        URI target;
        try {
            target = new URI(requestLine[1]);
        } catch (Exception e) {
            respond(client, 400, "Bad Request", null);
            return;
        }
        if (!"http".equalsIgnoreCase(target.getScheme()) || target.getHost() == null) {
            respond(client, 400, "Bad Request", null);
            return;
        }
        int port = target.getPort() == -1 ? 80 : target.getPort();
        Socket upstream = open(client, target.getHost(), port);
        if (upstream == null) {
            return;
        }
        StringBuilder out = new StringBuilder();
        String path = target.getRawPath() == null || target.getRawPath().isEmpty() ? "/" : target.getRawPath();
        if (target.getRawQuery() != null) {
            path += "?" + target.getRawQuery();
        }
        out.append(requestLine[0]).append(' ').append(path).append(' ').append(requestLine[2]).append("\r\n");
        for (String line : headers.split("\r\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String name = line.substring(0, Math.max(0, line.indexOf(':'))).strip().toLowerCase(Locale.ROOT);
            if (name.equals("proxy-connection") || name.equals("proxy-authorization")
                    || name.equals("connection") || name.equals("keep-alive")) {
                continue;
            }
            out.append(line).append("\r\n");
        }
        out.append("Connection: close\r\n\r\n");
        OutputStream upOut = upstream.getOutputStream();
        upOut.write(out.toString().getBytes(StandardCharsets.ISO_8859_1));
        upOut.flush();
        pipe(client, upstream, clientIn);
    }

    /** 검사하고 검사한 주소로 붙는다. 막거나 실패하면 응답을 쓰고 null */
    private Socket open(Socket client, String host, int port) throws IOException {
        String target = host + ":" + port;
        if (port <= 0 || port > 65535) {
            respond(client, 400, "Bad Request", null);
            return null;
        }
        AddressGuard.Inspection inspection = guard.inspect(host);
        if (inspection.verdict() == AddressGuard.Verdict.BLOCKED) {
            log.info("proxy blocked connection: {}", abbreviate(target));
            fail(client, Reason.BLOCKED, target, 403, "Forbidden");
            return null;
        }
        if (inspection.verdict() == AddressGuard.Verdict.UNRESOLVED) {
            fail(client, Reason.UNRESOLVED, target, 502, "Bad Gateway");
            return null;
        }
        IOException last = null;
        for (InetAddress address : inspection.addresses()) {
            Socket upstream = new Socket();
            try {
                upstream.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS);
                track(upstream);
                return upstream;
            } catch (IOException e) {
                closeQuietly(upstream);
                last = e;
            }
        }
        boolean refused = last != null && !(last instanceof SocketTimeoutException)
                && String.valueOf(last.getMessage()).toLowerCase(Locale.ROOT).contains("refused");
        fail(client, refused ? Reason.REFUSED : Reason.UNREACHABLE, target, 502, "Bad Gateway");
        return null;
    }

    private void fail(Socket client, Reason reason, String target, int status, String text) throws IOException {
        lastFailure.set(new Failure(reason, target));
        respond(client, status, text, reason);
    }

    private void respond(Socket client, int status, String text, Reason reason) throws IOException {
        String response = "HTTP/1.1 " + status + " " + text + "\r\n"
                + (reason != null ? REASON_HEADER + ": " + reason.token() + "\r\n" : "")
                + "Content-Length: 0\r\nConnection: close\r\n\r\n";
        try {
            client.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
            client.getOutputStream().flush();
        } finally {
            closeQuietly(client);
        }
    }

    /** 양방향 복사 — 한쪽이 끝나면 둘 다 닫는다 */
    private void pipe(Socket client, Socket upstream, InputStream clientIn) throws IOException {
        InputStream upIn = upstream.getInputStream();
        OutputStream upOut = upstream.getOutputStream();
        OutputStream clientOut = client.getOutputStream();
        Thread up = Thread.ofVirtual().name("capture-proxy-up").start(() -> copy(clientIn, upOut, client, upstream));
        copy(upIn, clientOut, client, upstream);
        up.interrupt();
    }

    private static void copy(InputStream from, OutputStream to, Socket a, Socket b) {
        byte[] buffer = new byte[16 * 1024];
        try {
            int n;
            while ((n = from.read(buffer)) != -1) {
                to.write(buffer, 0, n);
                to.flush();
            }
        } catch (IOException ignored) {
            // 상대가 닫았다
        } finally {
            closeQuietly(a);
            closeQuietly(b);
        }
    }

    /** 요청 머리(빈 줄까지)를 읽는다. 연결이 그냥 닫히면 null */
    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream(1024);
        int state = 0;
        while (head.size() < HEAD_MAX) {
            int b = in.read();
            if (b == -1) {
                return null;
            }
            head.write(b);
            state = switch (state) {
                case 0, 2 -> b == '\r' ? state + 1 : 0;
                case 1 -> b == '\n' ? 2 : (b == '\r' ? 1 : 0);
                case 3 -> b == '\n' ? 4 : (b == '\r' ? 1 : 0);
                default -> 0;
            };
            if (state == 4) {
                return head.toByteArray();
            }
        }
        throw new IOException("request head too large");
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void track(Socket socket) {
        sockets.add(socket);
        if (closed) {
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 닫는 중
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "…";
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // 닫는 중
        }
        for (Socket socket : sockets) {
            closeQuietly(socket);
        }
        sockets.clear();
    }
}
