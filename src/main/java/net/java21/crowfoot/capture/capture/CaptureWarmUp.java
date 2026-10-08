package net.java21.crowfoot.capture.capture;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 뒤 Playwright 드라이버를 미리 풀어 둔다 (11-capture/00-capture-service.md Section 2.2 — 캡처 한 건 20초).
 * 백그라운드 스레드에서 돌린다. 실패해도 기동을 막지 않는다 — 첫 캡처가 다시 시도한다.
 */
@Slf4j
@Component
public class CaptureWarmUp {

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        Thread.ofPlatform().name("capture-warmup").daemon(true).start(() -> {
            long started = System.nanoTime();
            try {
                PlaywrightSiteCapturer.warmUp();
                log.info("Playwright driver ready in {} ms", (System.nanoTime() - started) / 1_000_000);
            } catch (RuntimeException e) {
                log.warn("Playwright warm-up failed: {}", e.getMessage());
            }
        });
    }
}
