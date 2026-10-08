package net.java21.crowfoot.capture.capture.dto;

/**
 * 사이트 캡처 요청 (11-capture/00-capture-service.md Section 2.1).
 *
 * <p>{@code url}이 없을 때도 INVALID_URL로 답해야 하므로 Bean Validation을 걸지 않고 {@code UrlValidator}가 검사한다.
 *
 * @param url http·https 주소. 2,000자 이하. 사용자 정보가 든 주소는 받지 않는다
 */
public record CaptureRequest(String url) {
}
