package net.java21.crowfoot.capture.capture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메타 정보 대체값·길이 제한 (11-capture/00-capture-service.md Section 2.1).
 */
class PageMetadataTest {

    @Test
    @DisplayName("og 값이 먼저, 없으면 title·meta description. 앞뒤 공백을 지운다")
    void fallbacks() {
        PageMetadata og = PageMetadata.from(new PageMetadata.Raw("  OG 제목 ", "제목", "OG 설명", "설명", "사이트",
                "https://cdn.example.com/i.png"), "https://blog.example.com/a");
        assertThat(og).isEqualTo(new PageMetadata("OG 제목", "OG 설명", "사이트", "https://cdn.example.com/i.png"));

        PageMetadata plain = PageMetadata.from(new PageMetadata.Raw(" ", "\n 제목\n ", null, "설명", null, null),
                "https://blog.example.com:8443/a/b?c=d");
        assertThat(plain).isEqualTo(new PageMetadata("제목", "설명", null, "https://blog.example.com:8443/favicon.ico"));

        PageMetadata none = PageMetadata.from(new PageMetadata.Raw(null, null, null, null, null, null),
                "https://x.example/");
        assertThat(none).isEqualTo(new PageMetadata(null, null, null, "https://x.example/favicon.ico"));
    }

    @Test
    @DisplayName("200·500·100자로 자른다 — 코드 포인트 단위")
    void truncates() {
        String emoji = "😀";
        PageMetadata m = PageMetadata.from(new PageMetadata.Raw(emoji.repeat(250), null, "가".repeat(600), null,
                "s".repeat(150), null), "https://x.example/");
        assertThat(m.title().codePointCount(0, m.title().length())).isEqualTo(200);
        assertThat(m.description()).hasSize(500);
        assertThat(m.siteName()).hasSize(100);
    }

    @Test
    @DisplayName("data:·javascript: 아이콘은 쓰지 않고 {origin}/favicon.ico")
    void nonHttpIconFallsBack() {
        assertThat(PageMetadata.favicon("data:image/png;base64,AAAA", "https://x.example/p"))
                .isEqualTo("https://x.example/favicon.ico");
        assertThat(PageMetadata.favicon("javascript:alert(1)", "http://x.example/p"))
                .isEqualTo("http://x.example/favicon.ico");
    }
}
