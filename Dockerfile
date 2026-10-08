# crowfoot-capture — Spring Boot 실행 이미지 (CI에서 mvn package 후 빌드)
# Chromium과 그 시스템 라이브러리는 이미지 빌드 때 설치한다 — 컨테이너가 뜰 때 내려받지 않는다
# (11-capture/00-capture-service.md Section 4)
FROM eclipse-temurin:21-jre
# 서버 시간대 — 로그 타임스탬프가 한국 시간(KST, UTC+9)으로 찍힌다 (JVM은 TZ를 자체 tzdb로 해석)
ENV TZ=Asia/Seoul
# 브라우저 위치를 고정한다 — 빌드 때 설치한 곳을 실행 때도 본다. 실행 중에는 내려받지 않는다
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
WORKDIR /app
COPY target/*.jar app.jar
# 앱 jar 안의 Playwright CLI로 설치한다 — 앱과 같은 Playwright 버전의 Chromium이 깔린다.
# PropertiesLauncher가 BOOT-INF/lib의 Playwright를 클래스패스에 올리고 loader.main을 실행한다.
# --with-deps는 apt로 Chromium 시스템 라이브러리(폰트 포함)를 깐다. --only-shell은 헤드리스 셸만 받는다
# (헤드리스 캡처만 하므로 전체 Chromium은 필요 없다 — 이미지가 약 400MB 작아진다).
# 한국어 사이트가 많아 Noto CJK 글꼴을 더한다. 드라이버 임시 파일은 지운다
RUN PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD= java -Dloader.main=com.microsoft.playwright.CLI -cp app.jar \
        org.springframework.boot.loader.launch.PropertiesLauncher install --with-deps --only-shell chromium \
    && apt-get install -y --no-install-recommends fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/* /tmp/playwright-java-*
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
