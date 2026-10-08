package net.java21.crowfoot.capture.capture;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import net.java21.crowfoot.capture.common.error.BusinessException;
import net.java21.crowfoot.capture.common.error.ErrorCode;
import net.java21.crowfoot.capture.config.CaptureProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Playwright(Chromium)로 사이트를 찍는다 (11-capture/00-capture-service.md Section 2.2·3).
 *
 * <p>내부망 검사는 두 겹이다. 요청 가로채기(route)가 스킴과 주소를 보고, 브라우저의 모든 연결은 검사 프록시
 * ({@link CheckingProxy})를 거친다 — route는 리다이렉트를 보지 못하기 때문이다.
 *
 * <p>캡처마다 Playwright와 브라우저를 새로 띄우고 finally에서 닫는다(Section 1.2 원칙 5). Playwright Java 객체는
 * 스레드 안전하지 않다 — 만든 스레드에서만 쓰고 닫는다. 호출하는 쪽(CaptureService)이 캡처 한 건을 스레드 하나에서 돌린다.
 */
@Slf4j
@Component
public class PlaywrightSiteCapturer implements SiteCapturer {

    static final int VIEWPORT_WIDTH = 1280;
    static final int VIEWPORT_HEIGHT = 800;
    /** load 뒤 더 기다리는 시간 — JavaScript로 그리는 화면도 그려진 뒤에 찍는다 (Section 2.2) */
    static final long SETTLE_MILLIS = 1500;
    static final String USER_AGENT_SUFFIX = " CrowfootCapture/1.0 (+https://crowfoot.java21.net)";

    /** 브라우저가 보낼 수 있는 스킴 (Section 3). 나머지(file, ftp, chrome 등)는 중단한다 */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "data", "blob");
    private static final Pattern NET_ERROR = Pattern.compile("net::(ERR_[A-Z_]+)");
    private static final Pattern PLAYWRIGHT_MESSAGE = Pattern.compile("message='([^\\n]*)");

    /**
     * WebRTC가 STUN으로 내부 주소에 닿지 않게 한다 — 요청 가로채기(route)는 WebRTC를 보지 못한다.
     */
    private static final List<String> LAUNCH_ARGS = List.of(
            "--force-webrtc-ip-handling-policy",
            "--webrtc-ip-handling-policy=disable_non_proxied_udp");

    /** 메타 정보 읽기 — og:*는 property·name 둘 다 본다. 아이콘은 icon(shortcut icon 포함) 먼저, 없으면 apple-touch-icon */
    private static final String METADATA_SCRIPT = """
            () => {
              const meta = (key) => {
                const el = document.querySelector(`meta[property="${key}" i], meta[name="${key}" i]`);
                return el ? el.getAttribute('content') : null;
              };
              const links = Array.from(document.querySelectorAll('link[rel][href]'));
              const tokens = (l) => (l.getAttribute('rel') || '').toLowerCase().split(/\\s+/);
              const icon = links.find(l => tokens(l).includes('icon'))
                        || links.find(l => tokens(l).some(t => t.startsWith('apple-touch-icon')));
              return {
                ogTitle: meta('og:title'),
                title: document.title || null,
                ogDescription: meta('og:description'),
                description: meta('description'),
                ogSiteName: meta('og:site_name'),
                iconHref: icon ? icon.href : null
              };
            }
            """;

    /** 브라우저 버전별 기본 사용자 에이전트 — 버전이 같으면 한 번만 읽는다 */
    private static final Map<String, String> DEFAULT_USER_AGENTS = new ConcurrentHashMap<>();

    private final CaptureProperties properties;
    private final AddressGuard addressGuard;

    public PlaywrightSiteCapturer(CaptureProperties properties, AddressGuard addressGuard) {
        this.properties = properties;
        this.addressGuard = addressGuard;
    }

    /**
     * 브라우저 설치는 이미지 빌드(또는 README의 로컬 설치) 때 한다 — 캡처 중에 내려받지 않는다 (Section 4)
     */
    static Playwright.CreateOptions createOptions() {
        return new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));
    }

    /**
     * Playwright 드라이버(Node.js)를 미리 풀어 둔다. 첫 Playwright.create는 jar에서 드라이버를 임시 디렉터리로 푸느라
     * 수 초가 걸린다 — 첫 캡처가 제한 시간을 그 시간만큼 잃지 않게 기동 때 한 번 한다(CaptureWarmUp).
     */
    public static void warmUp() {
        try (Playwright ignored = Playwright.create(createOptions())) {
            // 만들고 닫기만 한다
        }
    }

    @Override
    public Result capture(URI url, Instant deadline) {
        // 연결 단계 검사 — 리다이렉트까지 막는다. allow-private면 띄우지 않는다
        CheckingProxy proxy = properties.allowPrivate() ? null : startProxy();
        long started = System.nanoTime();
        try (Playwright playwright = Playwright.create(createOptions())) {
            log.debug("playwright created in {} ms", (System.nanoTime() - started) / 1_000_000);
            Browser browser = launch(playwright, deadline, proxy);
            log.debug("browser launched in {} ms", (System.nanoTime() - started) / 1_000_000);
            try {
                return captureWith(browser, url, deadline, proxy);
            } finally {
                browser.close();
            }
        } catch (TimeoutError e) {
            throw timedOut();
        } finally {
            if (proxy != null) {
                proxy.close();
            }
        }
    }

    private CheckingProxy startProxy() {
        try {
            return new CheckingProxy(addressGuard);
        } catch (IOException e) {
            throw new UncheckedIOException("검사 프록시를 띄우지 못했습니다", e);
        }
    }

    private Browser launch(Playwright playwright, Instant deadline, CheckingProxy proxy) {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(LAUNCH_ARGS)
                .setTimeout(remainingMillis(deadline));
        if (proxy != null) {
            // <-loopback> — Chromium은 기본으로 루프백 주소를 프록시 없이 직접 연다. 루프백도 프록시를 거치게 한다
            options.setProxy(new Proxy(proxy.address()).setBypass("<-loopback>"));
        }
        if (properties.channel() != null && !properties.channel().isBlank()) {
            options.setChannel(properties.channel());
        }
        try {
            return playwright.chromium().launch(options);
        } catch (TimeoutError e) {
            throw e;
        } catch (PlaywrightException e) {
            // 브라우저가 없거나 뜨지 않는다 — 요청 탓이 아니라 서버 문제다(500)
            throw new IllegalStateException("Chromium을 띄우지 못했습니다: " + firstLine(e.getMessage()), e);
        }
    }

    private Result captureWith(Browser browser, URI url, Instant deadline, CheckingProxy proxy) {
        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                .setDeviceScaleFactor(1)
                .setUserAgent(defaultUserAgent(browser) + USER_AGENT_SUFFIX)
                // 인증서 오류는 무시하지 않는다 (Section 2.2)
                .setIgnoreHTTPSErrors(false)
                .setAcceptDownloads(false)
                // 서비스 워커의 요청은 route가 보지 못한다 — 아예 막는다 (Section 3)
                .setServiceWorkers(ServiceWorkerPolicy.BLOCK);
        BrowserContext context = browser.newContext(contextOptions);
        try {
            AtomicBoolean mainDocumentBlocked = new AtomicBoolean(false);
            // 문서, 이미지, 스크립트, 팝업 — route가 보는 모든 요청을 검사한다 (Section 3).
            // 리다이렉트 다음 요청은 route가 보지 못한다 — 그것은 CheckingProxy가 연결 단계에서 막는다
            context.route("**/*", route -> guard(route, mainDocumentBlocked));
            // WebSocket(ws·wss)은 허용 스킴이 아니다 — 서버에 잇지 않고 닫는다
            context.routeWebSocket(Pattern.compile(".*"), ws -> ws.close());

            Page page = context.newPage();
            page.setDefaultTimeout(remainingMillis(deadline));
            long navStarted = System.nanoTime();
            Response response = navigate(page, url, deadline, mainDocumentBlocked, proxy);
            log.debug("loaded {} in {} ms", abbreviate(url.toString()), (System.nanoTime() - navStarted) / 1_000_000);
            if (response == null) {
                throw failed("페이지 응답이 없습니다");
            }
            // http 리다이렉트 대상을 프록시가 막았다 — 프록시의 응답이 문서 응답으로 온다
            CheckingProxy.Reason proxyReason = CheckingProxy.Reason.fromToken(
                    String.valueOf(response.headers().get(CheckingProxy.REASON_HEADER.toLowerCase(Locale.ROOT))));
            if (proxy != null && proxyReason != null) {
                throw proxyFailure(proxyReason);
            }
            if (response.status() >= 400) {
                throw failed("HTTP " + response.status() + " 응답입니다");
            }

            settle(page, deadline);
            if (mainDocumentBlocked.get()) {
                // load 뒤 스크립트가 내부망 주소로 옮기려 했다
                throw new BusinessException(ErrorCode.BLOCKED_ADDRESS, "페이지가 내부망 주소로 이동하려 했습니다");
            }

            byte[] screenshot = page.screenshot(new Page.ScreenshotOptions()
                    .setType(ScreenshotType.PNG)
                    .setFullPage(false)
                    .setTimeout(remainingMillis(deadline)));
            String finalUrl = page.url();
            PageMetadata metadata = PageMetadata.from(readMetadata(page), finalUrl);
            return new Result(finalUrl, metadata, ThumbnailEncoder.toJpeg(screenshot));
        } catch (TimeoutError e) {
            throw timedOut();
        } catch (PlaywrightException e) {
            log.info("capture failed: {} — {}", abbreviate(url.toString()),
                    abbreviate(String.valueOf(e.getMessage()).replace('\n', ' ')));
            throw failed(describe(e));
        } finally {
            context.close();
        }
    }

    private Response navigate(Page page, URI url, Instant deadline, AtomicBoolean mainDocumentBlocked,
                              CheckingProxy proxy) {
        try {
            return page.navigate(url.toASCIIString(), new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.LOAD)
                    .setTimeout(remainingMillis(deadline)));
        } catch (TimeoutError e) {
            throw e;
        } catch (PlaywrightException e) {
            if (mainDocumentBlocked.get()) {
                throw new BusinessException(ErrorCode.BLOCKED_ADDRESS, "주소(또는 리다이렉트 대상)가 내부망 주소로 풀립니다");
            }
            // 리다이렉트 대상을 프록시가 거절했다 — 브라우저는 사유 없이 ERR_TUNNEL_CONNECTION_FAILED(https)나
            // ERR_HTTP_RESPONSE_CODE_FAILURE(http)만 알린다. 첫 문서가 오기 전이라 하위 요청의 실패일 수 없다
            if (proxy != null && proxy.lastFailure() != null) {
                throw proxyFailure(proxy.lastFailure().reason());
            }
            throw e;
        }
    }

    /** load 뒤 1.5초 — 남은 시간이 모자라면 기다리지 않고 실패한다 */
    private void settle(Page page, Instant deadline) {
        if (remainingMillis(deadline) <= SETTLE_MILLIS) {
            throw new TimeoutError("settle");
        }
        page.waitForTimeout(SETTLE_MILLIS);
    }

    /**
     * 요청 단위 검사 (Section 3) — 막으면 중단하고, 그것이 첫 문서(메인 프레임 이동)면 표시한다.
     * 핸들러가 예외로 빠지면 요청이 멈춘 채 남는다 — 어떤 경우에도 resume 또는 abort로 끝낸다.
     */
    private void guard(Route route, AtomicBoolean mainDocumentBlocked) {
        Request request = route.request();
        String decision;
        try {
            decision = decide(request.url());
        } catch (RuntimeException e) {
            log.warn("request check failed — aborting: {}", e.toString());
            decision = "blockedbyclient";
        }
        if (decision == null) {
            route.resume();
            return;
        }
        route.abort(decision);
        if (decision.equals("blockedbyclient")) {
            log.info("blocked request: {} {}", request.resourceType(), abbreviate(request.url()));
            if (isMainFrameNavigation(request)) {
                mainDocumentBlocked.set(true);
            }
        }
    }

    /** null이면 통과, 아니면 중단 사유(Playwright abort 코드) */
    private String decide(String requestUrl) {
        URI uri;
        try {
            uri = new URI(requestUrl);
        } catch (Exception e) {
            return "blockedbyclient";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            return "blockedbyclient";
        }
        if ((scheme.equals("http") || scheme.equals("https")) && !properties.allowPrivate()) {
            AddressGuard.Verdict verdict = addressGuard.check(uri.getHost());
            if (verdict == AddressGuard.Verdict.BLOCKED) {
                return "blockedbyclient";
            }
            if (verdict == AddressGuard.Verdict.UNRESOLVED) {
                // 이름이 풀리지 않는다 — 브라우저도 열 수 없다. 막은 것이 아니라 실패다(CAPTURE_FAILED)
                return "namenotresolved";
            }
        }
        return null;
    }

    /** 메인 프레임 이동 요청인가 — 리다이렉트 중인 요청은 frame()을 못 줄 수 있어 문서 요청이면 메인으로 친다 */
    private static boolean isMainFrameNavigation(Request request) {
        if (!request.isNavigationRequest()) {
            return false;
        }
        try {
            return request.frame().parentFrame() == null;
        } catch (RuntimeException e) {
            return "document".equals(request.resourceType());
        }
    }

    private PageMetadata.Raw readMetadata(Page page) {
        Object value = page.evaluate(METADATA_SCRIPT);
        if (!(value instanceof Map<?, ?> map)) {
            return new PageMetadata.Raw(null, null, null, null, null, null);
        }
        return new PageMetadata.Raw(str(map.get("ogTitle")), str(map.get("title")), str(map.get("ogDescription")),
                str(map.get("description")), str(map.get("ogSiteName")), str(map.get("iconHref")));
    }

    /** Chromium 기본 사용자 에이전트 — 빈 페이지에서 navigator.userAgent를 읽는다. 브라우저 버전별로 한 번 */
    private static String defaultUserAgent(Browser browser) {
        return DEFAULT_USER_AGENTS.computeIfAbsent(browser.version(), version -> {
            try (BrowserContext probe = browser.newContext()) {
                return String.valueOf(probe.newPage().evaluate("() => navigator.userAgent"));
            }
        });
    }

    /** 원인 한 줄 (Section 2.4) — Chromium 네트워크 오류 코드를 사람이 읽는 말로 */
    static String describe(PlaywrightException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        Matcher m = NET_ERROR.matcher(message);
        if (m.find()) {
            String code = m.group(1);
            if (code.equals("ERR_NAME_NOT_RESOLVED")) {
                return "주소의 이름을 풀 수 없습니다 (net::" + code + ")";
            }
            if (code.equals("ERR_CONNECTION_REFUSED")) {
                return "연결이 거부되었습니다 (net::" + code + ")";
            }
            if (code.startsWith("ERR_CERT_") || code.startsWith("ERR_SSL_")) {
                return "인증서 오류입니다 (net::" + code + ")";
            }
            if (code.equals("ERR_TIMED_OUT") || code.equals("ERR_CONNECTION_TIMED_OUT")) {
                return "연결 시간이 초과되었습니다 (net::" + code + ")";
            }
            return "페이지를 열지 못했습니다 (net::" + code + ")";
        }
        String detail = playwrightMessage(message);
        if (detail.contains("Download is starting")) {
            return "페이지가 아니라 파일 내려받기입니다";
        }
        return "페이지를 열지 못했습니다: " + abbreviate(detail);
    }

    /** Playwright Java 예외 문구 "Error {\n  message='...'\n  name=...}"에서 message 첫 줄만 꺼낸다 */
    static String playwrightMessage(String message) {
        Matcher m = PLAYWRIGHT_MESSAGE.matcher(message);
        String detail = m.find() ? m.group(1) : firstLine(message);
        detail = detail.strip();
        if (detail.endsWith("'")) {
            detail = detail.substring(0, detail.length() - 1);
        }
        return detail;
    }

    private static BusinessException proxyFailure(CheckingProxy.Reason reason) {
        return switch (reason) {
            case BLOCKED -> new BusinessException(ErrorCode.BLOCKED_ADDRESS, "주소(또는 리다이렉트 대상)가 내부망 주소로 풀립니다");
            case UNRESOLVED -> failed("주소의 이름을 풀 수 없습니다");
            case REFUSED -> failed("연결이 거부되었습니다");
            case UNREACHABLE -> failed("서버에 연결할 수 없습니다");
        };
    }

    private BusinessException timedOut() {
        return failed("제한 시간(" + properties.timeout().toSeconds() + "초) 안에 끝나지 않았습니다");
    }

    private static BusinessException failed(String message) {
        return new BusinessException(ErrorCode.CAPTURE_FAILED, message);
    }

    static long remainingMillis(Instant deadline) {
        long remaining = Duration.between(Instant.now(), deadline).toMillis();
        if (remaining <= 0) {
            throw new TimeoutError("deadline");
        }
        return remaining;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        return (newline < 0 ? message : message.substring(0, newline)).strip();
    }

    private static String abbreviate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "…";
    }
}
