package net.java21.crowfoot.capture.capture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내부망 주소 판정 (11-capture/00-capture-service.md Section 3, Section 5 "주소 검사").
 * 모두 IP 리터럴이라 DNS를 거치지 않는다.
 */
class AddressGuardTest {

    private final AddressGuard guard = new AddressGuard();

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "127.255.255.254", "0.0.0.0", "0.1.2.3",
            "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "169.254.169.254", "169.254.0.1",
            "100.64.0.1", "100.127.255.255",
            "224.0.0.1", "239.255.255.255", "255.255.255.255",
            "[::1]", "::1", "[::]", "[fe80::1]", "[fc00::1]", "[fd12:3456::1]", "[ff02::1]", "[fec0::1]",
            "[::ffff:127.0.0.1]", "[::ffff:169.254.169.254]", "[::ffff:10.0.0.1]", "[::ffff:7f00:1]",
            "[::127.0.0.1]", "[64:ff9b::a9fe:a9fe]"
    })
    @DisplayName("루프백·사설·링크 로컬·통신사 공유·멀티캐스트·브로드캐스트·IPv6 고유 로컬·IPv4 대응 주소는 막는다")
    void blocksPrivate(String host) {
        assertThat(guard.check(host)).isEqualTo(AddressGuard.Verdict.BLOCKED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "93.184.215.14", "8.8.8.8", "1.1.1.1", "172.15.255.255", "172.32.0.1", "100.63.255.255",
            "100.128.0.1", "169.253.1.1", "11.0.0.1", "[2606:4700:4700::1111]", "[::ffff:8.8.8.8]"
    })
    @DisplayName("공개 주소는 통과한다 — 대역 경계 바로 밖 포함")
    void allowsPublic(String host) {
        assertThat(guard.check(host)).isEqualTo(AddressGuard.Verdict.ALLOWED);
    }

    @Test
    @DisplayName("풀린 주소 가운데 하나라도 내부망이면 막는다")
    void anyPrivateAddressBlocks() {
        AddressGuard mixed = new AddressGuard() {
            @Override
            protected InetAddress[] resolve(String host) throws UnknownHostException {
                return new InetAddress[]{InetAddress.getByName("93.184.215.14"), InetAddress.getByName("10.1.2.3")};
            }
        };
        assertThat(mixed.check("evil.example")).isEqualTo(AddressGuard.Verdict.BLOCKED);
    }

    @Test
    @DisplayName("이름이 풀리지 않으면 UNRESOLVED, 호스트가 비면 막는다")
    void unresolvedAndEmpty() {
        AddressGuard failing = new AddressGuard() {
            @Override
            protected InetAddress[] resolve(String host) throws UnknownHostException {
                throw new UnknownHostException(host);
            }
        };
        assertThat(failing.check("nowhere.invalid")).isEqualTo(AddressGuard.Verdict.UNRESOLVED);
        assertThat(guard.check("")).isEqualTo(AddressGuard.Verdict.BLOCKED);
        assertThat(guard.check(null)).isEqualTo(AddressGuard.Verdict.BLOCKED);
    }
}
