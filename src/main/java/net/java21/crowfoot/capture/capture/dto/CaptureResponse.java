package net.java21.crowfoot.capture.capture.dto;

import java.time.Instant;

/**
 * 사이트 캡처 응답 (11-capture/00-capture-service.md Section 2.1). 값이 없는 메타 정보는 {@code null}로 내보낸다.
 *
 * @param url         요청한 주소
 * @param finalUrl    리다이렉트를 따라간 뒤의 주소
 * @param title       og:title, 없으면 title. 200자
 * @param description og:description, 없으면 meta description. 500자
 * @param siteName    og:site_name. 100자
 * @param faviconUrl  아이콘 링크의 절대 주소, 없으면 {origin}/favicon.ico
 * @param imageType   항상 image/jpeg
 * @param image       Base64 JPEG
 * @param width       800
 * @param height      500
 * @param capturedAt  찍은 시각(UTC)
 */
public record CaptureResponse(
        String url,
        String finalUrl,
        String title,
        String description,
        String siteName,
        String faviconUrl,
        String imageType,
        String image,
        int width,
        int height,
        Instant capturedAt
) {
}
