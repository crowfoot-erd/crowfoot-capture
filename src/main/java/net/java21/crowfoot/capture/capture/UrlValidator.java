package net.java21.crowfoot.capture.capture;

import net.java21.crowfoot.capture.common.error.BusinessException;
import net.java21.crowfoot.capture.common.error.ErrorCode;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 요청 주소 검사 (11-capture/00-capture-service.md Section 2.1·2.4) — 걸리면 400 INVALID_URL.
 *
 * <ul>
 * <li>비어 있지 않다. 2,000자 이하다
 * <li>스킴이 {@code http} 또는 {@code https}다
 * <li>호스트가 있다. 사용자 정보({@code user:pass@})가 없다
 * </ul>
 * 한글 도메인은 IDN(punycode)으로 바꿔 돌려준다. 브라우저와 주소 검사가 같은 이름을 본다.
 */
public final class UrlValidator {

    public static final int MAX_LENGTH = 2000;

    private UrlValidator() {
    }

    public static URI validate(String url) {
        if (url == null || url.isBlank()) {
            throw invalid("주소가 없습니다");
        }
        String trimmed = url.strip();
        if (trimmed.length() > MAX_LENGTH) {
            throw invalid("주소가 " + MAX_LENGTH + "자를 넘습니다");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw invalid("주소 형식이 올바르지 않습니다");
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw invalid("http 또는 https 주소만 받습니다");
        }
        if (uri.getRawUserInfo() != null || (uri.getRawAuthority() != null && uri.getRawAuthority().contains("@"))) {
            throw invalid("사용자 정보가 든 주소는 받지 않습니다");
        }
        if (uri.getHost() == null) {
            uri = withAsciiHost(uri);
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw invalid("주소에 호스트가 없습니다");
        }
        return uri;
    }

    /** 한글 등 비 ASCII 호스트 — java.net.URI는 호스트로 읽지 못한다. punycode로 바꿔 다시 읽는다 */
    private static URI withAsciiHost(URI uri) {
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty()) {
            return uri;
        }
        String host = authority;
        String port = "";
        int colon = authority.lastIndexOf(':');
        if (colon > 0 && authority.indexOf(']') < colon) {
            host = authority.substring(0, colon);
            port = authority.substring(colon);
        }
        try {
            String ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
            String rest = uri.toString().substring(uri.getScheme().length() + 3 + authority.length());
            return new URI(uri.getScheme() + "://" + ascii + port + rest);
        } catch (IllegalArgumentException | URISyntaxException e) {
            throw invalid("주소의 호스트가 올바르지 않습니다");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_URL, message);
    }
}
