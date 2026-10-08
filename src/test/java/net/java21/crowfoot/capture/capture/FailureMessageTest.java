package net.java21.crowfoot.capture.capture;

import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CAPTURE_FAILED 원인 한 줄 (11-capture/00-capture-service.md Section 2.4).
 */
class FailureMessageTest {

    @Test
    @DisplayName("Chromium 네트워크 오류 코드를 사람이 읽는 말로 — 한 줄")
    void netErrors() {
        assertThat(describe("net::ERR_NAME_NOT_RESOLVED at https://x.invalid/\nCall log:\n  - navigating"))
                .isEqualTo("주소의 이름을 풀 수 없습니다 (net::ERR_NAME_NOT_RESOLVED)");
        assertThat(describe("Error {\n  message='net::ERR_CERT_DATE_INVALID at https://expired.badssl.com/\n  name='Error'\n}"))
                .isEqualTo("인증서 오류입니다 (net::ERR_CERT_DATE_INVALID)");
        assertThat(describe("net::ERR_CONNECTION_REFUSED at http://x/")).contains("연결이 거부");
    }

    @Test
    @DisplayName("Playwright 예외 형식(Error { message='...' })에서 message 첫 줄만 쓴다")
    void playwrightFormat() {
        assertThat(describe("Error {\n  message='Download is starting\nCall log:\n'\n  name='Error'\n  stack='...'\n}"))
                .isEqualTo("페이지가 아니라 파일 내려받기입니다");
        assertThat(describe("Error {\n  message='Something odd happened'\n  name='Error'\n}"))
                .isEqualTo("페이지를 열지 못했습니다: Something odd happened");
    }

    private static String describe(String message) {
        return PlaywrightSiteCapturer.describe(new PlaywrightException(message));
    }
}
