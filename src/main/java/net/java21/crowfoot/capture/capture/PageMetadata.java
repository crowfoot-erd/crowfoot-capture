package net.java21.crowfoot.capture.capture;

import java.net.URI;
import java.util.Locale;

/**
 * 페이지 메타 정보 (11-capture/00-capture-service.md Section 2.1) — 페이지에서 읽은 원래 값에 대체값과 길이 제한을 적용한다.
 *
 * @param title       og:title → title. 앞뒤 공백 제거, 200자
 * @param description og:description → meta description. 500자
 * @param siteName    og:site_name. 100자
 * @param faviconUrl  아이콘 링크의 절대 주소 → {origin}/favicon.ico
 */
public record PageMetadata(String title, String description, String siteName, String faviconUrl) {

    public static final int TITLE_MAX = 200;
    public static final int DESCRIPTION_MAX = 500;
    public static final int SITE_NAME_MAX = 100;

    /**
     * 페이지에서 읽은 원래 값.
     *
     * @param iconHref 브라우저가 절대 주소로 바꾼 아이콘 링크(icon·shortcut icon 우선, 없으면 apple-touch-icon)
     */
    public record Raw(String ogTitle, String title, String ogDescription, String description,
                      String ogSiteName, String iconHref) {
    }

    public static PageMetadata from(Raw raw, String finalUrl) {
        return new PageMetadata(
                firstPresent(TITLE_MAX, raw.ogTitle(), raw.title()),
                firstPresent(DESCRIPTION_MAX, raw.ogDescription(), raw.description()),
                firstPresent(SITE_NAME_MAX, raw.ogSiteName()),
                favicon(raw.iconHref(), finalUrl));
    }

    /** 앞뒤 공백을 지운 값 가운데 처음으로 비어 있지 않은 것을 max자(코드 포인트)로 자른다 */
    static String firstPresent(int max, String... values) {
        for (String value : values) {
            if (value == null) {
                continue;
            }
            // 줄바꿈·연속 공백은 한 칸으로 — 화면이 한 줄로 보여 준다
            String normalized = value.replaceAll("\\s+", " ").strip();
            if (!normalized.isEmpty()) {
                return truncate(normalized, max);
            }
        }
        return null;
    }

    static String truncate(String value, int max) {
        if (value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max)).strip();
    }

    /** 아이콘 링크가 http·https 절대 주소면 그것, 아니면(없음, data: 등) {origin}/favicon.ico. 내려받지 않는다 */
    static String favicon(String iconHref, String finalUrl) {
        if (iconHref != null && !iconHref.isBlank()) {
            try {
                URI icon = new URI(iconHref.strip());
                String scheme = icon.getScheme() == null ? "" : icon.getScheme().toLowerCase(Locale.ROOT);
                if (("http".equals(scheme) || "https".equals(scheme)) && icon.getHost() != null
                        && iconHref.length() <= UrlValidator.MAX_LENGTH) {
                    return icon.toString();
                }
            } catch (Exception ignored) {
                // 잘못된 링크는 대체값으로
            }
        }
        try {
            URI page = new URI(finalUrl);
            if (page.getScheme() == null || page.getRawAuthority() == null) {
                return null;
            }
            return page.getScheme() + "://" + page.getRawAuthority() + "/favicon.ico";
        } catch (Exception e) {
            return null;
        }
    }
}
