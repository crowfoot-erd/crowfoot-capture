package net.java21.crowfoot.capture.common.error;

import lombok.Getter;

/**
 * 도메인 규칙 위반 — GlobalExceptionHandler가 공통 실패 포맷으로 변환한다.
 *
 * <p>문구는 생성자 리터럴 문구가 있으면 그것, 없으면 {@link ErrorCode} 기본 문구다.
 * CAPTURE_FAILED는 원인을 한 줄로 적는다(11-capture/00-capture-service.md Section 2.4).
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final boolean customMessage;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), false, null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, true, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, true, cause);
    }

    private BusinessException(ErrorCode errorCode, String message, boolean customMessage, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.customMessage = customMessage;
    }
}
