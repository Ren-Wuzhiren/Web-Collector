package com.webcollector.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 从 HTTP 请求提取不可伪造的客户端 IP。
 *
 * <p>生产实现只在直连来源属于可信代理时解析转发头，并从右向左
 * 跳过可信代理，返回第一个非可信地址。</p>
 */
public class ClientIpResolver {
    private static final Pattern IPV4_LITERAL =
            Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    private static final Pattern IPV6_LITERAL =
            Pattern.compile("[0-9A-Fa-f:.]+");

    private final List<CidrRange> trustedProxyRanges;

    public ClientIpResolver(List<String> trustedProxyCidrs) {
        this.trustedProxyRanges = trustedProxyCidrs.stream()
                .map(CidrRange::parse)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = normalize(request.getRemoteAddr());

        if (peer == null) {
            return "unknown";
        }

        if (!isTrustedProxy(peer)) {
            return peer;
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");

        if (forwardedFor == null || forwardedFor.isBlank()) {
            return peer;
        }

        String[] chain = forwardedFor.split(",", -1);

        for (int index = chain.length - 1; index >= 0; index--) {
            String candidate = normalize(chain[index]);

            if (candidate == null) {
                return peer;
            }

            if (!isTrustedProxy(candidate)) {
                return candidate;
            }
        }

        return peer;
    }

    private boolean isTrustedProxy(String ip) {
        InetAddress address = parseLiteral(ip);

        if (address == null) {
            return false;
        }

        return trustedProxyRanges.stream().anyMatch(range -> range.matches(address));
    }

    private static String normalize(String value) {
        InetAddress address = parseLiteral(value);
        return address == null ? null : address.getHostAddress();
    }

    private static InetAddress parseLiteral(String value) {
        if (value == null) {
            return null;
        }

        String candidate = value.trim();

        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }

        boolean ipv4 = IPV4_LITERAL.matcher(candidate).matches();
        boolean ipv6 = candidate.contains(":")
                && IPV6_LITERAL.matcher(candidate).matches();

        if (!ipv4 && !ipv6) {
            return null;
        }

        try {
            return InetAddress.getByName(candidate);
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private record CidrRange(InetAddress network, int prefixLength) {
        private static CidrRange parse(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("可信代理 CIDR 不能为空");
            }

            String[] parts = value.trim().split("/", -1);

            if (parts.length > 2) {
                throw new IllegalArgumentException("可信代理 CIDR 格式不正确: " + value);
            }

            InetAddress address = parseLiteral(parts[0]);

            if (address == null) {
                throw new IllegalArgumentException("可信代理 IP 格式不正确: " + value);
            }

            int maxPrefix = address.getAddress().length * 8;
            int prefixLength = parts.length == 2
                    ? parsePrefix(parts[1], maxPrefix, value)
                    : maxPrefix;

            return new CidrRange(address, prefixLength);
        }

        private static int parsePrefix(
                String value,
                int maxPrefix,
                String original
        ) {
            try {
                int prefix = Integer.parseInt(value);

                if (prefix < 0 || prefix > maxPrefix) {
                    throw new IllegalArgumentException(
                            "可信代理 CIDR 前缀越界: " + original
                    );
                }

                return prefix;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "可信代理 CIDR 前缀不正确: " + original,
                        exception
                );
            }
        }

        private boolean matches(InetAddress address) {
            byte[] networkBytes = network.getAddress();
            byte[] candidateBytes = address.getAddress();

            if (networkBytes.length != candidateBytes.length) {
                return false;
            }

            int remainingBits = prefixLength;

            for (int index = 0; index < networkBytes.length; index++) {
                if (remainingBits <= 0) {
                    return true;
                }

                int mask = remainingBits >= 8
                        ? 0xFF
                        : (0xFF << (8 - remainingBits)) & 0xFF;

                if ((networkBytes[index] & mask) != (candidateBytes[index] & mask)) {
                    return false;
                }

                remainingBits -= 8;
            }

            return true;
        }
    }
}
