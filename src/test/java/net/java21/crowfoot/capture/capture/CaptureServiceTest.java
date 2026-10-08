package net.java21.crowfoot.capture.capture;

import net.java21.crowfoot.capture.capture.dto.CaptureResponse;
import net.java21.crowfoot.capture.common.error.BusinessException;
import net.java21.crowfoot.capture.common.error.ErrorCode;
import net.java21.crowfoot.capture.config.CaptureProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 캡처 흐름 — 주소 검사, 내부망 사전 검사, 동시 실행 제한 (11-capture/00-capture-service.md Section 2.3·3, Section 5 "동시 실행").
 * 브라우저 없이 캡처기를 바꿔 끼운다.
 */
class CaptureServiceTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};

    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void tearDown() {
        release.countDown();
    }

    @Test
    @DisplayName("자리가 없으면 기다렸다가 CAPTURE_BUSY — 자리가 나면 다시 받는다")
    void busyWhenSlotsTaken() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        SiteCapturer blocking = (url, deadline) -> {
            started.countDown();
            await(release);
            return result();
        };
        CaptureService service = service(false, blocking, Duration.ofMillis(300));

        CompletableFuture<CaptureResponse> first = CompletableFuture.supplyAsync(() -> service.capture("https://93.184.215.14/a"));
        CompletableFuture<CaptureResponse> second = CompletableFuture.supplyAsync(() -> service.capture("https://93.184.215.14/b"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        long begin = System.nanoTime();
        assertThatThrownBy(() -> service.capture("https://93.184.215.14/c"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CAPTURE_BUSY);
        assertThat(Duration.ofNanos(System.nanoTime() - begin)).isGreaterThanOrEqualTo(Duration.ofMillis(250));

        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).imageType()).isEqualTo("image/jpeg");
        assertThat(second.get(5, TimeUnit.SECONDS).width()).isEqualTo(800);
        // 자리를 돌려받았다
        assertThat(service.capture("https://93.184.215.14/d").height()).isEqualTo(500);
    }

    @Test
    @DisplayName("캡처가 실패해도 자리를 돌려준다")
    void releasesSlotOnFailure() {
        SiteCapturer failing = (url, deadline) -> {
            calls.incrementAndGet();
            throw new BusinessException(ErrorCode.CAPTURE_FAILED, "boom");
        };
        CaptureService service = service(false, failing, Duration.ofMillis(100));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.capture("https://93.184.215.14/"))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CAPTURE_FAILED);
        }
        assertThat(calls).hasValue(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:8080", "http://169.254.169.254/latest/meta-data/", "http://10.0.0.5/",
            "http://[::1]/", "http://[fd00::1]/", "http://[::ffff:192.168.0.1]/", "http://0.0.0.0/"})
    @DisplayName("내부망 주소는 브라우저를 띄우기 전에 BLOCKED_ADDRESS")
    void blocksBeforeCapture(String url) {
        CaptureService service = service(false, countingCapturer(), Duration.ofMillis(100));
        assertThatThrownBy(() -> service.capture(url))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BLOCKED_ADDRESS);
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("allow-private=true면 내부망 검사를 건너뛴다")
    void allowPrivateSkipsCheck() {
        CaptureService service = service(true, countingCapturer(), Duration.ofMillis(100));
        CaptureResponse response = service.capture("http://127.0.0.1:8080");
        assertThat(response.url()).isEqualTo("http://127.0.0.1:8080");
        assertThat(Base64.getDecoder().decode(response.image())).isEqualTo(JPEG);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("주소가 틀리면 INVALID_URL — 캡처기를 부르지 않는다")
    void invalidUrl() {
        CaptureService service = service(false, countingCapturer(), Duration.ofMillis(100));
        assertThatThrownBy(() -> service.capture("ftp://example.com"))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_URL);
        assertThatThrownBy(() -> service.capture(null))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_URL);
        assertThat(calls).hasValue(0);
    }

    // --- helpers

    private SiteCapturer countingCapturer() {
        return (url, deadline) -> {
            calls.incrementAndGet();
            return result();
        };
    }

    private static SiteCapturer.Result result() {
        return new SiteCapturer.Result("https://93.184.215.14/",
                new PageMetadata("t", null, null, "https://93.184.215.14/favicon.ico"), JPEG);
    }

    private static CaptureService service(boolean allowPrivate, SiteCapturer capturer, Duration acquireWait) {
        return new CaptureService(new CaptureProperties(2, Duration.ofSeconds(20), allowPrivate, null),
                new AddressGuard(), capturer, acquireWait);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
