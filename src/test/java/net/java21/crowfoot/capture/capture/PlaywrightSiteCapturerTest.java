package net.java21.crowfoot.capture.capture;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.java21.crowfoot.capture.common.error.BusinessException;
import net.java21.crowfoot.capture.common.error.ErrorCode;
import net.java21.crowfoot.capture.config.CaptureProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 브라우저로 시험용 HTTP 서버의 페이지를 찍는다 (11-capture/00-capture-service.md Section 5 "메타 정보"·"캡처").
 *
 * <p>캡처와 메타 정보 시험은 {@code allow-private=true}다. 요청 단위 검사 시험은 {@code localhost}만 공개로 치고
 * {@code 127.0.0.1}은 내부망으로 치는 검사기를 끼워, 리다이렉트와 하위 요청이 막히는지 본다.
 */
class PlaywrightSiteCapturerTest {

    private static HttpServer server;
    private static int port;
    private static final List<String> hits = new CopyOnWriteArrayList<>();
    private static volatile String lastUserAgent;

    private static final String OG_PAGE = """
            <!doctype html><html><head><meta charset="utf-8">
            <title>문서 제목</title>
            <meta property="og:title" content="  OG 제목  ">
            <meta property="og:description" content="OG 설명입니다">
            <meta property="og:site_name" content="Example">
            <meta name="description" content="일반 설명">
            <link rel="apple-touch-icon" href="/apple.png">
            <link rel="shortcut icon" href="/static/icon.png">
            </head><body style="margin:0;background:#3366cc"><h1 style="color:white">Hello</h1></body></html>
            """;

    private static final String PLAIN_PAGE = """
            <!doctype html><html><head><meta charset="utf-8">
            <title>  그냥 제목 </title>
            <meta name="description" content="메타 설명">
            </head><body><p>plain</p></body></html>
            """;

    @BeforeAll
    static void startServer() throws IOException {
        // 드라이버 풀기는 기동 때 한다(CaptureWarmUp) — 시험의 제한 시간에서 뺀다
        PlaywrightSiteCapturer.warmUp();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.add(path);
            if (path.equals("/og")) {
                lastUserAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            }
            switch (path) {
                case "/og" -> html(exchange, 200, OG_PAGE);
                case "/plain" -> html(exchange, 200, PLAIN_PAGE);
                case "/missing" -> html(exchange, 404, "<h1>not found</h1>");
                case "/img-redirect" -> redirect(exchange, "http://127.0.0.1:" + port + "/secret-img.png");
                case "/with-redirect-image" -> html(exchange, 200,
                        "<html><head><title>img</title></head><body><img src=\"/img-redirect\"></body></html>");
                case "/redirect-internal" -> redirect(exchange, "http://127.0.0.1:" + port + "/secret");
                case "/with-internal-image" -> html(exchange, 200,
                        "<html><head><title>img</title></head><body><img src=\"http://127.0.0.1:" + port
                                + "/secret.png\"></body></html>");
                default -> html(exchange, 404, "");
            }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void clearHits() {
        hits.clear();
    }

    @Test
    @DisplayName("og 태그를 읽고 아이콘 링크를 절대 주소로 바꾸며 800×500 JPEG를 만든다")
    void capturesOgPage() throws IOException {
        SiteCapturer.Result result = capturer(true, new AddressGuard()).capture(url("localhost", "/og"), deadline());

        assertThat(result.finalUrl()).isEqualTo("http://localhost:" + port + "/og");
        assertThat(result.metadata().title()).isEqualTo("OG 제목");
        assertThat(result.metadata().description()).isEqualTo("OG 설명입니다");
        assertThat(result.metadata().siteName()).isEqualTo("Example");
        // shortcut icon이 apple-touch-icon보다 먼저다
        assertThat(result.metadata().faviconUrl()).isEqualTo("http://localhost:" + port + "/static/icon.png");
        assertJpeg800x500(result.jpeg());
        // 사용자 에이전트 — Chromium 기본값 끝에 CrowfootCapture를 붙인다 (Section 2.2)
        assertThat(lastUserAgent).startsWith("Mozilla/5.0").contains("Chrome/")
                .endsWith("Safari/537.36 CrowfootCapture/1.0 (+https://crowfoot.java21.net)");
        // 파란 배경이 찍혔다 — 빈 화면이 아니다
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.jpeg()));
        int rgb = image.getRGB(700, 450);
        assertThat(rgb & 0xff).isGreaterThan(150);          // blue
        assertThat((rgb >> 16) & 0xff).isLessThan(100);     // red
    }

    @Test
    @DisplayName("og 태그가 없으면 title·meta description, 아이콘이 없으면 {origin}/favicon.ico")
    void capturesPlainPage() throws IOException {
        SiteCapturer.Result result = capturer(true, new AddressGuard()).capture(url("localhost", "/plain"), deadline());

        assertThat(result.metadata().title()).isEqualTo("그냥 제목");
        assertThat(result.metadata().description()).isEqualTo("메타 설명");
        assertThat(result.metadata().siteName()).isNull();
        assertThat(result.metadata().faviconUrl()).isEqualTo("http://localhost:" + port + "/favicon.ico");
        assertJpeg800x500(result.jpeg());
    }

    @Test
    @DisplayName("HTTP 4xx 응답은 CAPTURE_FAILED")
    void httpErrorFails() {
        assertThatThrownBy(() -> capturer(true, new AddressGuard()).capture(url("localhost", "/missing"), deadline()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CAPTURE_FAILED);
                    assertThat(e.getMessage()).contains("404");
                });
    }

    @Test
    @DisplayName("연결 거부는 CAPTURE_FAILED — 원인을 한 줄로 적는다")
    void connectionRefusedFails() throws IOException {
        int closedPort;
        try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        URI target = URI.create("http://localhost:" + closedPort + "/");
        assertThatThrownBy(() -> capturer(true, new AddressGuard()).capture(target, deadline()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CAPTURE_FAILED);
                    assertThat(e.getMessage()).contains("ERR_CONNECTION_REFUSED").doesNotContain("\n");
                });
    }

    @Test
    @DisplayName("리다이렉트 대상이 내부망이면 그 요청을 중단하고 BLOCKED_ADDRESS — 대상 서버에 닿지 않는다")
    void redirectToInternalBlocked() {
        assertThatThrownBy(() -> capturer(false, localhostOnlyGuard())
                .capture(url("localhost", "/redirect-internal"), deadline()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BLOCKED_ADDRESS);
        assertThat(hits).contains("/redirect-internal").doesNotContain("/secret");
    }

    @Test
    @DisplayName("하위 요청(이미지)의 리다이렉트 대상이 내부망이어도 닿지 않는다 — 페이지는 찍힌다")
    void subresourceRedirectToInternalBlocked() throws IOException {
        SiteCapturer.Result result = capturer(false, localhostOnlyGuard())
                .capture(url("localhost", "/with-redirect-image"), deadline());
        assertJpeg800x500(result.jpeg());
        assertThat(hits).contains("/with-redirect-image", "/img-redirect").doesNotContain("/secret-img.png");
    }

    @Test
    @DisplayName("페이지가 부르는 하위 요청(이미지)도 검사해 중단한다 — 페이지는 찍힌다")
    void subresourceToInternalAborted() throws IOException {
        SiteCapturer.Result result = capturer(false, localhostOnlyGuard())
                .capture(url("localhost", "/with-internal-image"), deadline());
        assertJpeg800x500(result.jpeg());
        assertThat(hits).contains("/with-internal-image").doesNotContain("/secret.png");
    }

    @Test
    @DisplayName("첫 문서 요청이 내부망이면 BLOCKED_ADDRESS")
    void mainDocumentBlocked() {
        assertThatThrownBy(() -> capturer(false, localhostOnlyGuard()).capture(url("127.0.0.1", "/og"), deadline()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BLOCKED_ADDRESS);
        assertThat(hits).doesNotContain("/og");
    }

    // --- helpers

    private static PlaywrightSiteCapturer capturer(boolean allowPrivate, AddressGuard guard) {
        return new PlaywrightSiteCapturer(
                new CaptureProperties(2, Duration.ofSeconds(20), allowPrivate, TestBrowsers.channel()), guard);
    }

    /** localhost는 공개, 그 밖(127.0.0.1 등)은 실제 판정 — 시험 서버 하나로 공개·내부 두 주소를 흉내 낸다 */
    private static AddressGuard localhostOnlyGuard() {
        return new AddressGuard() {
            @Override
            protected boolean blocks(String host, InetAddress address) {
                return !"localhost".equals(host) && super.blocks(host, address);
            }
        };
    }

    private static URI url(String host, String path) {
        return URI.create("http://" + host + ":" + port + path);
    }

    private static Instant deadline() {
        return Instant.now().plusSeconds(20);
    }

    private static void assertJpeg800x500(byte[] jpeg) throws IOException {
        assertThat(jpeg[0] & 0xff).isEqualTo(0xFF);
        assertThat(jpeg[1] & 0xff).isEqualTo(0xD8);
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(jpeg))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            ImageReader reader = readers.next();
            assertThat(reader.getFormatName()).isEqualToIgnoringCase("jpeg");
            reader.setInput(in);
            assertThat(reader.getWidth(0)).isEqualTo(800);
            assertThat(reader.getHeight(0)).isEqualTo(500);
            reader.dispose();
        }
    }

    private static void html(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }
}
