package net.java21.crowfoot.capture.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * 캡처 설정 (11-capture/00-capture-service.md Section 4) — 값은 application.yml의 crowfoot.capture.
 *
 * @param maxConcurrency 동시 캡처 수 (Section 2.3)
 * @param timeout        캡처 한 건의 제한 시간 (Section 2.2)
 * @param allowPrivate   내부망 주소 검사를 끈다 (Section 3). 로컬 개발·시험에서만 쓴다
 * @param channel        Playwright 브라우저 채널. 비우면 Playwright가 내려받은 Chromium이다.
 *                       Playwright Chromium을 받을 수 없는 로컬 환경(예: macOS 13)에서 {@code chrome}으로 설치된 Chrome을 쓴다
 * @param hostOverrides  호스트 연결 주소 바꾸기 (Section 3.1) — 같은 서버에 올린 사이트를 내부 주소로 잇는다. 비우면 없음
 */
@ConfigurationProperties(prefix = "crowfoot.capture")
public record CaptureProperties(
        @DefaultValue("2") int maxConcurrency,
        @DefaultValue("20s") Duration timeout,
        @DefaultValue("false") boolean allowPrivate,
        String channel,
        List<HostOverride> hostOverrides
) {

    /** @param host 정확한 호스트 또는 {@code *.도메인} 와일드카드, @param address 이을 IP 주소 */
    public record HostOverride(String host, String address) {
    }
}
