package net.java21.crowfoot.capture.capture;

import net.java21.crowfoot.capture.common.error.BusinessException;
import net.java21.crowfoot.capture.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 요청 주소 검사 (11-capture/00-capture-service.md Section 2.1·2.4 — INVALID_URL).
 */
class UrlValidatorTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ", "example.com", "ftp://example.com/", "file:///etc/passwd", "javascript:alert(1)",
            "data:text/html,hi", "http://", "https:///path", "http://user:pass@example.com/",
            "http://user@example.com/", "http://exa mple.com/"
    })
    @DisplayName("없음·스킴·호스트 없음·사용자 정보·형식 오류는 INVALID_URL")
    void rejects(String url) {
        assertThatThrownBy(() -> UrlValidator.validate(url))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_URL);
    }

    @Test
    @DisplayName("2,000자까지 받고 넘으면 INVALID_URL")
    void length() {
        String base = "https://example.com/";
        String ok = base + "a".repeat(UrlValidator.MAX_LENGTH - base.length());
        assertThat(UrlValidator.validate(ok).getHost()).isEqualTo("example.com");
        assertThatThrownBy(() -> UrlValidator.validate(ok + "a"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_URL);
    }

    @Test
    @DisplayName("http·https 주소는 통과한다 — 대문자 스킴, 포트, IPv6 리터럴, 한글 도메인(punycode)")
    void accepts() {
        assertThat(UrlValidator.validate("https://blog.example.com").getHost()).isEqualTo("blog.example.com");
        assertThat(UrlValidator.validate("HTTP://Example.com:8443/a?b=c").getPort()).isEqualTo(8443);
        assertThat(UrlValidator.validate("http://[2606:4700::1111]/").getHost()).isEqualTo("[2606:4700::1111]");
        assertThat(UrlValidator.validate("https://한국.kr/경로").getHost()).isEqualTo("xn--3e0b707e.kr");
    }
}
