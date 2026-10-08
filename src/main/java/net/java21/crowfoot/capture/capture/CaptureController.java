package net.java21.crowfoot.capture.capture;

import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.capture.capture.dto.CaptureRequest;
import net.java21.crowfoot.capture.capture.dto.CaptureResponse;
import net.java21.crowfoot.capture.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사이트 캡처 내부 API (11-capture/00-capture-service.md Section 2.1). core만 클러스터 안에서 부른다 — 사용자 헤더를 읽지 않는다.
 */
@RestController
@RequestMapping("/internal/capture")
@RequiredArgsConstructor
public class CaptureController {

    private final CaptureService captureService;

    @PostMapping("/sites")
    public ApiResponse<CaptureResponse> captureSite(@RequestBody CaptureRequest request) {
        return ApiResponse.success(captureService.capture(request.url()));
    }
}
