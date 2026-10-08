# crowfoot-capture

Crowfoot ERD 에디터의 **캡처 서비스**다. 웹 사이트 주소 하나를 받아 헤드리스 Chromium으로 열고, 화면 썸네일(800×500 JPEG)과 메타 정보(제목, 설명, 사이트 이름, 파비콘 주소)를 돌려준다. 사이트 쇼케이스에서 문서에 등록한 사이트의 썸네일을 채울 때 core가 부른다.

The capture service of the Crowfoot ERD editor: opens a web page in headless Chromium and returns a thumbnail and page metadata.

## 구조

- Java 21 · Spring Boot · Playwright for Java(Chromium). **저장소가 없다.** 찍은 결과를 돌려주기만 하고, 저장은 core가 한다.
- 클러스터 안에서만 부른다. gateway 라우트와 외부 도메인이 없다. core가 Service 이름(`http://crowfoot-capture`)으로 `POST /internal/capture/sites`를 부른다. 사용자 헤더(`X-USER-ID`)를 읽지 않는다.
- 캡처마다 Playwright와 Chromium을 새로 띄우고 끝나면 닫는다. 동시에 2건까지 찍고, 자리가 10초 안에 나지 않으면 `503 CAPTURE_BUSY`다.
- **내부망 접근을 막는다.** 사용자가 준 주소를 서버가 열기 때문이다.
  - 브라우저를 띄우기 전에 주소의 호스트를 이름 풀이해 사설망·루프백·링크 로컬(클라우드 메타데이터)·IPv6 고유 로컬 등이면 `422 BLOCKED_ADDRESS`다.
  - 브라우저의 모든 요청은 요청 가로채기(route)로 한 번, 캡처마다 띄우는 검사 프록시(`CheckingProxy`)로 한 번 더 검사한다. Playwright의 route는 리다이렉트를 보지 못해서, 리다이렉트 대상은 프록시가 연결 단계에서 막는다. 프록시는 검사한 바로 그 주소로만 붙으므로 DNS 재바인딩도 막힌다.

스펙은 docs 리포의 `11-capture/00-capture-service.md`가 원천이다.

## 로컬 실행

처음 한 번 Chromium을 내려받는다. 앱과 같은 버전의 Playwright CLI를 쓴다(`~/.cache/ms-playwright`, macOS는 `~/Library/Caches/ms-playwright`에 받는다).

```sh
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install --only-shell chromium"
```

macOS 13처럼 Playwright Chromium을 받을 수 없는 환경에서는 설치된 Google Chrome을 쓴다. `application-local.yml`에서 `crowfoot.capture.channel: chrome`을 켠다.

```sh
mvn spring-boot:run        # 포트 8086, 기본 프로필 local
mvn test                   # 테스트 — 브라우저 시험은 실제 Chromium(또는 macOS의 Chrome)을 띄운다

curl -s -X POST localhost:8086/internal/capture/sites \
  -H 'content-type: application/json' -d '{"url":"https://example.com"}'
```

자기 컴퓨터의 사이트(`localhost` 등)를 찍어 보려면 `crowfoot.capture.allow-private: true`로 내부망 검사를 끈다. 운영에서는 끄지 않는다.

필요한 시크릿이 없다. 그래서 `.env-local.example`도 두지 않는다.

## 설정

| 속성 | 기본값 | 설명 |
|---|---|---|
| `crowfoot.capture.max-concurrency` | `2` | 동시 캡처 수 |
| `crowfoot.capture.timeout` | `20s` | 캡처 한 건의 제한 시간 |
| `crowfoot.capture.allow-private` | `false` | 내부망 주소 검사를 끈다. 로컬 개발에서만 쓴다 |
| `crowfoot.capture.channel` | (비움) | 브라우저 채널. 비우면 Playwright Chromium, `chrome`이면 설치된 Google Chrome |

환경 변수로도 바꿀 수 있다(`CROWFOOT_CAPTURE_MAX_CONCURRENCY` 등).

## Docker

```sh
mvn -q package -DskipTests
docker build -t crowfoot-capture .
docker run --rm -p 18086:8080 -e SPRING_PROFILES_ACTIVE=prod crowfoot-capture
```

- 실행 이미지는 `eclipse-temurin:21-jre`다. 이미지 빌드 때 앱 jar 안의 Playwright CLI로 Chromium 헤드리스 셸과 시스템 라이브러리를 설치한다(`install --with-deps --only-shell chromium`). 앱과 Chromium의 버전이 늘 맞는다.
- 브라우저는 `/ms-playwright`(`PLAYWRIGHT_BROWSERS_PATH`)에 있다. 컨테이너가 뜰 때 내려받지 않는다(`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`).
- 한국어 사이트를 위해 Noto CJK 글꼴을 더 깐다.
- 기동 직후 Playwright 드라이버를 임시 디렉터리에 미리 풀어 둔다. 첫 캡처가 그 시간만큼 제한 시간을 잃지 않는다.

## 라이선스

Apache License 2.0
