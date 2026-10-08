package net.java21.crowfoot.capture.capture;

import net.java21.crowfoot.capture.capture.dto.CaptureResponse;
import net.java21.crowfoot.capture.common.error.BusinessException;
import net.java21.crowfoot.capture.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 응답 형식과 에러 코드 (11-capture/00-capture-service.md Section 2.1·2.4).
 */
@WebMvcTest(CaptureController.class)
class CaptureControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CaptureService captureService;

    @Test
    @DisplayName("200 — header와 response, 없는 메타 정보는 null로 내보낸다")
    void success() throws Exception {
        when(captureService.capture("https://blog.example.com")).thenReturn(new CaptureResponse(
                "https://blog.example.com", "https://blog.example.com/", "예제 블로그", null, null,
                "https://blog.example.com/favicon.ico", "image/jpeg", "/9j/2Q==", 800, 500,
                Instant.parse("2026-10-08T07:40:00Z")));

        mvc.perform(post("/internal/capture/sites").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://blog.example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.header.resultCode").value("SUCCESS"))
                .andExpect(jsonPath("$.response.finalUrl").value("https://blog.example.com/"))
                .andExpect(jsonPath("$.response.title").value("예제 블로그"))
                .andExpect(jsonPath("$.response.description").isEmpty())
                .andExpect(jsonPath("$.response.siteName").isEmpty())
                .andExpect(jsonPath("$.response.imageType").value("image/jpeg"))
                .andExpect(jsonPath("$.response.width").value(800))
                .andExpect(jsonPath("$.response.height").value(500))
                .andExpect(jsonPath("$.response.capturedAt").value("2026-10-08T07:40:00Z"));
    }

    @Test
    @DisplayName("INVALID_URL 400 · BLOCKED_ADDRESS 422 · CAPTURE_FAILED 422 · CAPTURE_BUSY 503")
    void errors() throws Exception {
        expectError(new BusinessException(ErrorCode.INVALID_URL, "주소가 없습니다"), 400, "INVALID_URL");
        expectError(new BusinessException(ErrorCode.BLOCKED_ADDRESS, "내부망"), 422, "BLOCKED_ADDRESS");
        expectError(new BusinessException(ErrorCode.CAPTURE_FAILED, "HTTP 404 응답입니다"), 422, "CAPTURE_FAILED");
        expectError(new BusinessException(ErrorCode.CAPTURE_BUSY), 503, "CAPTURE_BUSY");
    }

    @Test
    @DisplayName("본문이 JSON이 아니면 INVALID_REQUEST 400")
    void malformedBody() throws Exception {
        mvc.perform(post("/internal/capture/sites").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
    }

    private void expectError(BusinessException ex, int status, String code) throws Exception {
        doThrow(ex).when(captureService).capture(any());
        mvc.perform(post("/internal/capture/sites").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"x\"}"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value(code))
                .andExpect(jsonPath("$.header.resultMessage").value(ex.getMessage()))
                .andExpect(jsonPath("$.response").doesNotExist());
    }
}
