package net.java21.crowfoot.capture.capture;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검사 프록시 (11-capture/00-capture-service.md Section 3) — 브라우저 없이 소켓으로 부른다.
 * {@code localhost}만 공개로 치는 검사기를 끼워 같은 시험 서버를 공개·내부 두 주소로 흉내 낸다.
 */
class CheckingProxyTest {

    private HttpServer server;
    private int port;
    private CheckingProxy proxy;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            byte[] body = ("hello " + exchange.getRequestURI()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        proxy = new CheckingProxy(new AddressGuard() {
            @Override
            protected boolean blocks(String host, InetAddress address) {
                return !"localhost".equals(host) && super.blocks(host, address);
            }
        });
    }

    @AfterEach
    void tearDown() {
        proxy.close();
        server.stop(0);
    }

    @Test
    @DisplayName("허용한 호스트의 http 요청은 원 서버 요청으로 바꿔 넘긴다")
    void forwardsAllowed() throws IOException {
        String response = send("GET http://localhost:" + port + "/a?b=c HTTP/1.1\r\nHost: localhost:" + port
                + "\r\nProxy-Connection: keep-alive\r\n\r\n");
        assertThat(response).startsWith("HTTP/1.1 200").contains("hello /a?b=c");
        assertThat(proxy.lastFailure()).isNull();
    }

    @Test
    @DisplayName("내부망 주소 http 요청은 403과 사유 헤더 — 원 서버에 닿지 않는다")
    void blocksHttp() throws IOException {
        String response = send("GET http://127.0.0.1:" + port + "/secret HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
        assertThat(response).startsWith("HTTP/1.1 403").contains(CheckingProxy.REASON_HEADER + ": blocked");
        assertThat(proxy.lastFailure().reason()).isEqualTo(CheckingProxy.Reason.BLOCKED);
    }

    @Test
    @DisplayName("내부망 주소 CONNECT는 터널을 열지 않는다 — 클라우드 메타데이터, IPv6 루프백")
    void blocksConnect() throws IOException {
        assertThat(send("CONNECT 169.254.169.254:443 HTTP/1.1\r\nHost: 169.254.169.254:443\r\n\r\n"))
                .startsWith("HTTP/1.1 403");
        assertThat(send("CONNECT [::1]:443 HTTP/1.1\r\n\r\n")).startsWith("HTTP/1.1 403");
    }

    @Test
    @DisplayName("허용한 호스트의 CONNECT는 터널을 연다")
    void tunnelsAllowed() throws IOException {
        try (Socket socket = connectProxy()) {
            OutputStream out = socket.getOutputStream();
            out.write(("CONNECT localhost:" + port + " HTTP/1.1\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            assertThat(in.readLine()).startsWith("HTTP/1.1 200");
            assertThat(in.readLine()).isEmpty();
            out.write(("GET /tunnel HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            String rest = in.lines().reduce("", (a, b) -> a + b + "\n");
            assertThat(rest).startsWith("HTTP/1.1 200").contains("hello /tunnel");
        }
    }

    @Test
    @DisplayName("연결 거부는 502와 refused")
    void refused() throws IOException {
        int closedPort;
        try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        assertThat(send("CONNECT localhost:" + closedPort + " HTTP/1.1\r\n\r\n"))
                .startsWith("HTTP/1.1 502").contains(CheckingProxy.REASON_HEADER + ": refused");
    }

    private Socket connectProxy() throws IOException {
        URI address = URI.create(proxy.address());
        Socket socket = new Socket(address.getHost(), address.getPort());
        socket.setSoTimeout(5000);
        return socket;
    }

    private String send(String request) throws IOException {
        try (Socket socket = connectProxy()) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
