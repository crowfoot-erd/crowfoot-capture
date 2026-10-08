package net.java21.crowfoot.capture.capture;

import net.java21.crowfoot.capture.config.CaptureProperties;
import net.java21.crowfoot.capture.config.CaptureProperties.HostOverride;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 호스트 연결 주소 바꾸기 (11-capture/00-capture-service.md Section 3.1) — 정확한 호스트가 와일드카드보다 앞서고,
 * 와일드카드는 하위 도메인에만 맞는다. 잘못된 설정은 뜰 때 거절한다. 운영 설정 파일이 실제로 묶이는지도 본다.
 */
class HostOverridesTest {

    private final HostOverrides overrides = new HostOverrides(List.of(
            new HostOverride("crowfoot.java21.net", "10.116.64.13"),
            new HostOverride("*.java21.net", "10.116.64.12")));

    @Test
    @DisplayName("정확한 호스트가 먼저, 그다음 와일드카드 — 대소문자·끝 점 무시, 도메인 자체와 다른 도메인은 맞지 않는다")
    void resolve() throws IOException {
        assertThat(overrides.resolve("crowfoot.java21.net")).contains(InetAddress.getByName("10.116.64.13"));
        assertThat(overrides.resolve("Crowfoot.Java21.NET.")).contains(InetAddress.getByName("10.116.64.13"));
        assertThat(overrides.resolve("crowfoot-api.java21.net")).contains(InetAddress.getByName("10.116.64.12"));
        assertThat(overrides.resolve("blog.java21.net")).contains(InetAddress.getByName("10.116.64.12"));
        assertThat(overrides.resolve("java21.net")).isEmpty();
        assertThat(overrides.resolve("evil-java21.net")).isEmpty();
        assertThat(overrides.resolve("github.com")).isEmpty();
        assertThat(HostOverrides.none().resolve("crowfoot.java21.net")).isEmpty();
    }

    @Test
    @DisplayName("주소가 IP가 아니거나 호스트가 비면 뜰 때 거절한다 — 이름 풀이를 하지 않는다")
    void rejectsInvalid() {
        assertThatThrownBy(() -> new HostOverrides(List.of(new HostOverride("a.example", "internal.host"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HostOverrides(List.of(new HostOverride(" ", "10.0.0.1"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("운영 설정(application-prod.yml)의 host-overrides가 와일드카드 키까지 그대로 묶인다")
    void prodConfigBinds() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("prod", new ClassPathResource("application-prod.yml"))
                .forEach(source -> environment.getPropertySources().addFirst(source));
        CaptureProperties properties = Binder.get(environment).bind("crowfoot.capture", CaptureProperties.class).get();

        assertThat(properties.hostOverrides()).containsExactly(
                new HostOverride("crowfoot.java21.net", "10.116.64.13"),
                new HostOverride("*.java21.net", "10.116.64.12"));
        assertThat(new HostOverrides(properties.hostOverrides()).resolve("crowfoot-api.java21.net"))
                .contains(InetAddress.getByName("10.116.64.12"));
    }
}
