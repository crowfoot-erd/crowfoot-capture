package net.java21.crowfoot.capture;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Crowfoot 캡처 서비스 — 사이트 주소를 헤드리스 Chromium으로 열어 썸네일과 메타 정보를 돌려준다.
 * 저장소가 없고 다른 서비스를 부르지 않는다. core만 클러스터 안에서 부른다(11-capture/00-capture-service.md Section 1.2).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CrowfootCaptureApplication {

    public static void main(String[] args) {
        // 썸네일 축소(java.awt)는 화면 없는 서버에서 돈다 (00-capture-service.md Section 2.2)
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(CrowfootCaptureApplication.class, args);
    }
}
