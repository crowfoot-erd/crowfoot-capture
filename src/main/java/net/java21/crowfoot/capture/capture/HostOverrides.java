package net.java21.crowfoot.capture.capture;

import net.java21.crowfoot.capture.config.CaptureProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 호스트 연결 주소 바꾸기 (11-capture/00-capture-service.md Section 3.1 — v1.41). 클러스터에서 자기 공인 주소로
 * 되돌아 나가지 못해 같은 서버에 올린 사이트(java21.net)를 찍지 못했다 — 그 호스트만 설정한 내부 주소로 잇는다.
 *
 * <p>설정에 적은 호스트만 예외다. 내부 주소여도 검사 프록시가 막지 않는다(운영자가 정한 값이라 믿는다).
 * 정확한 호스트가 와일드카드({@code *.java21.net})보다 앞선다. 와일드카드는 하위 도메인에만 맞고 그 도메인 자체에는 맞지 않는다.
 * TLS는 터널 안에서 원래 호스트 이름(SNI)으로 맺으므로 인증서 검사는 그대로다.
 */
public final class HostOverrides {

    private static final Pattern IP_LITERAL = Pattern.compile("^(?:\\d{1,3}(?:\\.\\d{1,3}){3}|[0-9a-fA-F:]+)$");

    private record Rule(String host, boolean wildcard, InetAddress address) {

        boolean matches(String target) {
            return wildcard ? target.endsWith("." + host) : target.equals(host);
        }
    }

    private final List<Rule> exact = new ArrayList<>();
    private final List<Rule> wildcards = new ArrayList<>();

    public HostOverrides(List<CaptureProperties.HostOverride> overrides) {
        for (CaptureProperties.HostOverride override : overrides == null ? List.<CaptureProperties.HostOverride>of() : overrides) {
            String host = override.host() == null ? "" : override.host().strip().toLowerCase(Locale.ROOT);
            String address = override.address() == null ? "" : override.address().strip();
            if (host.isEmpty() || !IP_LITERAL.matcher(address).matches()) {
                throw new IllegalArgumentException("crowfoot.capture.host-overrides 항목이 잘못됐다 — host와 IP 주소가 있어야 한다: " + override);
            }
            InetAddress ip;
            try {
                ip = InetAddress.getByName(address); // IP 리터럴만 받으므로 이름 풀이가 일어나지 않는다
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("crowfoot.capture.host-overrides 주소가 IP가 아니다: " + address, e);
            }
            if (host.startsWith("*.")) {
                wildcards.add(new Rule(host.substring(2), true, ip));
            } else {
                exact.add(new Rule(host, false, ip));
            }
        }
    }

    public static HostOverrides none() {
        return new HostOverrides(List.of());
    }

    /** 이 호스트를 이을 주소. 설정에 없으면 비어 있다 */
    public Optional<InetAddress> resolve(String host) {
        if (host == null) {
            return Optional.empty();
        }
        String target = host.strip().toLowerCase(Locale.ROOT);
        if (target.endsWith(".")) {
            target = target.substring(0, target.length() - 1);
        }
        for (Rule rule : exact) {
            if (rule.matches(target)) {
                return Optional.of(rule.address());
            }
        }
        for (Rule rule : wildcards) {
            if (rule.matches(target)) {
                return Optional.of(rule.address());
            }
        }
        return Optional.empty();
    }
}
