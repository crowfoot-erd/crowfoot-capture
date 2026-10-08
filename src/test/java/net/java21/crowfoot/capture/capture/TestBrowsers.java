package net.java21.crowfoot.capture.capture;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 시험이 띄울 브라우저 채널.
 *
 * <p>{@code CAPTURE_TEST_CHANNEL} 환경 변수가 있으면 그것. 없으면 macOS에 Google Chrome이 깔려 있을 때 {@code chrome}
 * (Playwright Chromium은 macOS 13을 지원하지 않는다). 그 밖에는 Playwright Chromium(README의 설치 명령으로 받는다).
 */
final class TestBrowsers {

    private TestBrowsers() {
    }

    static String channel() {
        String env = System.getenv("CAPTURE_TEST_CHANNEL");
        if (env != null) {
            return env.isBlank() ? null : env;
        }
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        if (mac && Files.isDirectory(Path.of("/Applications/Google Chrome.app"))) {
            return "chrome";
        }
        return null;
    }
}
