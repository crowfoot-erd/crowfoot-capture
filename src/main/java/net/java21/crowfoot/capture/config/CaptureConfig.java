package net.java21.crowfoot.capture.config;

import net.java21.crowfoot.capture.capture.AddressGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 캡처 구성 — 주소 검사기는 시험에서 이름 풀이를 바꿔 끼울 수 있게 빈으로 둔다 (11-capture/00-capture-service.md Section 3).
 */
@Configuration
public class CaptureConfig {

    @Bean
    public AddressGuard addressGuard() {
        return new AddressGuard();
    }
}
