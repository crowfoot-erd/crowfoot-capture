package net.java21.crowfoot.capture;

import net.java21.crowfoot.capture.config.CaptureProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 부팅 배선 — 저장소 없이 뜨고, 설정이 스펙 기본값으로 바인딩되며, 상태 확인과 내부망 차단이 실제 빈으로 동작한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationBootTest {

    @Autowired
    private CaptureProperties properties;

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("설정 기본값 (11-capture/00-capture-service.md Section 4)")
    void bindsDefaults() {
        assertThat(properties.maxConcurrency()).isEqualTo(2);
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.allowPrivate()).isFalse();
    }

    @Test
    @DisplayName("/actuator/health는 UP")
    void health() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("클라우드 메타데이터 주소는 브라우저 없이 422 BLOCKED_ADDRESS")
    void blocksMetadataAddress() throws Exception {
        mvc.perform(post("/internal/capture/sites").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://169.254.169.254/latest/meta-data/\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.header.resultCode").value("BLOCKED_ADDRESS"));
    }
}
