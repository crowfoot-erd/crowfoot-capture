package net.java21.crowfoot.capture.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 캡처 서비스 에러 코드 (11-capture/00-capture-service.md Section 2.4 + 01-architecture/api-design.md Section 5.4).
 *
 * <p>부르는 쪽은 core 하나다. core가 resultCode로 갈라 처리하므로 문구는 한국어 하나만 둔다(번들 없음).
 */
@Getter
public enum ErrorCode {

    // 공통 (api-design.md Section 5.4)
    SUCCESS(HttpStatus.OK, "SUCCESS", "SUCCESS"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "리소스를 찾을 수 없습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다"),

    // 사이트 캡처 (00-capture-service.md Section 2.4)
    INVALID_URL(HttpStatus.BAD_REQUEST, "INVALID_URL", "주소가 올바르지 않습니다"),
    BLOCKED_ADDRESS(HttpStatus.UNPROCESSABLE_CONTENT, "BLOCKED_ADDRESS", "내부망 주소는 열 수 없습니다"),
    CAPTURE_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "CAPTURE_FAILED", "페이지를 열지 못했습니다"),
    CAPTURE_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "CAPTURE_BUSY", "캡처할 자리가 없습니다. 잠시 뒤 다시 시도하세요");

    private final HttpStatus status;
    private final String code;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String code, String defaultMessage) {
        this.status = status;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
