package net.java21.crowfoot.capture.capture;

import java.net.URI;
import java.time.Instant;

/**
 * 사이트 한 건을 찍는다 (11-capture/00-capture-service.md Section 2.2). 시험에서 바꿔 끼울 수 있게 인터페이스로 둔다.
 */
public interface SiteCapturer {

    /**
     * @param url      검사를 마친 주소
     * @param deadline 이 시각까지 끝내야 한다. 넘으면 CAPTURE_FAILED
     * @throws net.java21.crowfoot.capture.common.error.BusinessException BLOCKED_ADDRESS, CAPTURE_FAILED
     */
    Result capture(URI url, Instant deadline);

    /**
     * @param finalUrl 리다이렉트를 따라간 뒤의 주소
     * @param metadata 대체값·길이 제한을 적용한 메타 정보
     * @param jpeg     800×500 JPEG
     */
    record Result(String finalUrl, PageMetadata metadata, byte[] jpeg) {
    }
}
