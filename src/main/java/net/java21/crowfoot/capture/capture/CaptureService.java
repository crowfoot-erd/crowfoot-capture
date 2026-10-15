package net.java21.crowfoot.capture.capture;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.java21.crowfoot.capture.capture.dto.CaptureResponse;
import net.java21.crowfoot.capture.common.error.BusinessException;
import net.java21.crowfoot.capture.common.error.ErrorCode;
import net.java21.crowfoot.capture.config.CaptureProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 사이트 캡처 (11-capture/00-capture-service.md Section 2).
 *
 * <ol>
 * <li>주소 검사 — INVALID_URL (Section 2.1)
 * <li>내부망 사전 검사 — 브라우저를 띄우기 전에 BLOCKED_ADDRESS (Section 3)
 * <li>동시 실행 자리 — 10초까지 기다리고 없으면 CAPTURE_BUSY (Section 2.3)
 * <li>캡처 — 한 건을 전용 스레드 하나에서 돌린다. Playwright 객체는 그 스레드에서 만들고 닫는다
 * </ol>
 *
 * <p>요청 스레드는 제한 시간(+여유)까지만 기다린다. 페이지가 Playwright 호출을 붙잡아도 응답은 CAPTURE_FAILED로 나가고,
 * 캡처 스레드는 인터럽트로 정리된다. 동시 실행 자리는 캡처 스레드가 실제로 끝날 때 돌려준다 — 브라우저 수가 한도를 넘지 않는다.
 */
@Slf4j
@Service
public class CaptureService {

    /** 자리가 날 때까지 기다리는 시간 (Section 2.3) */
    static final Duration ACQUIRE_WAIT = Duration.ofSeconds(10);
    /** 제한 시간 뒤 캡처 스레드가 스스로 실패하고 정리할 여유 */
    static final Duration GRACE = Duration.ofSeconds(5);

    private final CaptureProperties properties;
    private final AddressGuard addressGuard;
    private final SiteCapturer capturer;
    private final Semaphore slots;
    private final Duration acquireWait;
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofPlatform().name("capture-", 1).daemon(true).factory());

    @Autowired
    public CaptureService(CaptureProperties properties, AddressGuard addressGuard, SiteCapturer capturer) {
        this(properties, addressGuard, capturer, ACQUIRE_WAIT);
    }

    /** 시험용 — 자리 대기 시간을 줄인다 */
    CaptureService(CaptureProperties properties, AddressGuard addressGuard, SiteCapturer capturer, Duration acquireWait) {
        this.properties = properties;
        this.addressGuard = addressGuard;
        this.capturer = capturer;
        this.slots = new Semaphore(Math.max(1, properties.maxConcurrency()), true);
        this.acquireWait = acquireWait;
    }

    public CaptureResponse capture(String requestedUrl) {
        URI url = UrlValidator.validate(requestedUrl);
        precheck(url);
        acquire();
        Instant deadline = Instant.now().plus(properties.timeout());
        Future<SiteCapturer.Result> future;
        try {
            future = workers.submit(() -> {
                try {
                    return capturer.capture(url, deadline);
                } finally {
                    slots.release();
                }
            });
        } catch (RuntimeException e) {
            slots.release();
            throw e;
        }
        SiteCapturer.Result result = await(future);
        return new CaptureResponse(
                requestedUrl.strip(),
                result.finalUrl(),
                result.metadata().title(),
                result.metadata().description(),
                result.metadata().siteName(),
                result.metadata().faviconUrl(),
                "image/jpeg",
                Base64.getEncoder().encodeToString(result.jpeg()),
                ThumbnailEncoder.WIDTH,
                ThumbnailEncoder.HEIGHT,
                Instant.now());
    }

    /** 브라우저를 띄우기 전 이름 풀이 검사 (Section 3). allow-private면 건너뛴다 */
    private void precheck(URI url) {
        if (properties.allowPrivate()) {
            return;
        }
        switch (addressGuard.check(url.getHost())) {
            case BLOCKED -> throw new BusinessException(ErrorCode.BLOCKED_ADDRESS, "주소의 호스트가 내부망 주소로 풀립니다");
            case UNRESOLVED -> throw new BusinessException(ErrorCode.CAPTURE_FAILED, "주소의 이름을 풀 수 없습니다");
            case ALLOWED -> {
            }
        }
    }

    private void acquire() {
        try {
            if (!slots.tryAcquire(acquireWait.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new BusinessException(ErrorCode.CAPTURE_BUSY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.CAPTURE_BUSY, e);
        }
    }

    private SiteCapturer.Result await(Future<SiteCapturer.Result> future) {
        try {
            return future.get(properties.timeout().plus(GRACE).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 캡처 스레드가 Playwright 호출에 붙잡혔다 — 인터럽트로 깨워 브라우저를 닫게 한다
            future.cancel(true);
            log.warn("capture thread did not finish within timeout + grace — interrupted");
            throw new BusinessException(ErrorCode.CAPTURE_FAILED,
                    "제한 시간(" + properties.timeout().toSeconds() + "초) 안에 끝나지 않았습니다", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            // 캡처 스레드의 예외를 그대로 꺼내 던진다 — ExecutionException은 Future 포장일 뿐이다
            if (cause instanceof RuntimeException re) {
                throw re; // NOPMD - PreserveStackTrace: 원인 예외 자체를 다시 던진다(스택 보존)
            }
            throw new IllegalStateException(cause); // NOPMD - PreserveStackTrace: 원인 예외를 cause로 넘긴다
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.CAPTURE_FAILED, "캡처가 중단되었습니다", e);
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }
}
