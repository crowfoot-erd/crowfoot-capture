package net.java21.crowfoot.capture.capture;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 내부망 접근 차단 (11-capture/00-capture-service.md Section 3) — 호스트를 이름 풀이해 풀린 주소 가운데
 * 하나라도 내부망이면 막는다. 캡처 전 사전 검사와 브라우저의 요청 단위 검사가 같은 판정을 쓴다.
 *
 * <p>막는 대역: 루프백, 지정되지 않은 주소(0.0.0.0/8, ::), 사설망(10/8, 172.16/12, 192.168/16),
 * IPv6 고유 로컬(fc00::/7), 링크 로컬(169.254/16 — 클라우드 메타데이터 포함, fe80::/10), 통신사 공유 주소(100.64/10),
 * 멀티캐스트, 브로드캐스트(255.255.255.255). IPv6 안에 IPv4를 넣은 주소(::ffff:a.b.c.d, ::a.b.c.d, 64:ff9b::/96)는
 * 안의 IPv4로 판정한다.
 */
public class AddressGuard {

    /** 판정 결과 */
    public enum Verdict {
        ALLOWED,
        BLOCKED,
        /** 이름 풀이 실패 — 막지는 않지만 열 수도 없다(CAPTURE_FAILED) */
        UNRESOLVED
    }

    /**
     * 판정과 풀린 주소.
     *
     * @param verdict   판정
     * @param addresses ALLOWED일 때 연결해도 되는 주소(검사한 바로 그 주소 — 프록시가 이 주소로만 붙는다). 그 밖에는 빈 배열
     */
    public record Inspection(Verdict verdict, InetAddress[] addresses) {
    }

    /** 호스트 판정 — URI 호스트 그대로 받는다(IPv6 리터럴의 대괄호 포함 가능) */
    public Verdict check(String host) {
        return inspect(host).verdict();
    }

    /** 호스트를 풀고 판정한다. 풀린 주소 가운데 하나라도 내부망이면 BLOCKED */
    public Inspection inspect(String host) {
        if (host == null || host.isEmpty()) {
            return new Inspection(Verdict.BLOCKED, new InetAddress[0]);
        }
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress[] addresses;
        try {
            addresses = resolve(bare);
        } catch (UnknownHostException e) {
            return new Inspection(Verdict.UNRESOLVED, new InetAddress[0]);
        }
        if (addresses.length == 0) {
            return new Inspection(Verdict.UNRESOLVED, new InetAddress[0]);
        }
        for (InetAddress address : addresses) {
            if (blocks(bare, address)) {
                return new Inspection(Verdict.BLOCKED, new InetAddress[0]);
            }
        }
        return new Inspection(Verdict.ALLOWED, addresses);
    }

    /** 주소 하나의 판정 — 시험에서 특정 호스트를 공개로 칠 수 있게 열어 둔다 */
    protected boolean blocks(String host, InetAddress address) {
        return isBlocked(address);
    }

    /** 이름 풀이 — IP 리터럴은 DNS를 거치지 않는다. 시험에서 바꿔 끼울 수 있게 열어 둔다 */
    protected InetAddress[] resolve(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    public static boolean isBlocked(InetAddress address) {
        if (address instanceof Inet4Address v4) {
            return isBlockedV4(v4.getAddress());
        }
        if (address instanceof Inet6Address v6) {
            byte[] b = v6.getAddress();
            byte[] embedded = embeddedV4(b);
            if (embedded != null) {
                return isBlockedV4(embedded);
            }
            return v6.isLoopbackAddress()
                    || v6.isAnyLocalAddress()
                    || v6.isLinkLocalAddress()      // fe80::/10
                    || v6.isSiteLocalAddress()      // fec0::/10 (폐기된 사이트 로컬)
                    || v6.isMulticastAddress()      // ff00::/8
                    || (b[0] & 0xfe) == 0xfc;       // fc00::/7 고유 로컬
        }
        return true;
    }

    private static boolean isBlockedV4(byte[] b) {
        int a0 = b[0] & 0xff;
        int a1 = b[1] & 0xff;
        return a0 == 0                                   // 0.0.0.0/8 지정되지 않은 주소
                || a0 == 127                             // 루프백
                || a0 == 10                              // 사설망
                || (a0 == 172 && (a1 & 0xf0) == 16)      // 172.16.0.0/12
                || (a0 == 192 && a1 == 168)              // 192.168.0.0/16
                || (a0 == 169 && a1 == 254)              // 링크 로컬·클라우드 메타데이터
                || (a0 == 100 && (a1 & 0xc0) == 64)      // 100.64.0.0/10 통신사 공유 주소
                || (a0 >= 224 && a0 <= 239)              // 멀티캐스트
                || (a0 == 255 && a1 == 255 && (b[2] & 0xff) == 255 && (b[3] & 0xff) == 255); // 브로드캐스트
    }

    /** IPv6 안의 IPv4 — IPv4 대응(::ffff:0:0/96), IPv4 호환(::/96, ::와 ::1 제외), NAT64(64:ff9b::/96) */
    private static byte[] embeddedV4(byte[] b) {
        boolean firstTenZero = true;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                firstTenZero = false;
                break;
            }
        }
        boolean mapped = firstTenZero && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
        boolean compatible = firstTenZero && b[10] == 0 && b[11] == 0
                && !(b[12] == 0 && b[13] == 0 && b[14] == 0 && (b[15] == 0 || b[15] == 1));
        boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b
                && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0 && b[8] == 0 && b[9] == 0 && b[10] == 0 && b[11] == 0;
        if (mapped || compatible || nat64) {
            return new byte[]{b[12], b[13], b[14], b[15]};
        }
        return null;
    }
}
