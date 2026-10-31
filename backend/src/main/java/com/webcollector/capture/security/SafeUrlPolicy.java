package com.webcollector.capture.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * SSRF URL 安全策略。
 *
 * [M4.4] 只负责判断一个 URI 是否允许被服务端访问，
 * 不负责发起 HTTP 请求，也不负责 Playwright 生命周期。
 */
@Component
public class SafeUrlPolicy {

    private static final String METADATA_HOST = "169.254.169.254";
    private final HostResolver hostResolver;

    public SafeUrlPolicy() {
        this(InetAddress::getAllByName);
    }

    SafeUrlPolicy(HostResolver hostResolver) {
        this.hostResolver = hostResolver;
    }

    public URI validateAndResolve(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new UnsafeUrlException("URL 不能为空");
        }

        try {
            URI uri = new URI(rawUrl.strip()).parseServerAuthority();
            validate(uri);
            return uri;
        } catch (URISyntaxException exception) {
            throw new UnsafeUrlException(
                    "URL 格式不合法",
                    exception
            );
        }
    }

    public void validate(URI uri) {
        if (uri == null) {
            throw new UnsafeUrlException("URL 不能为空");
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http")
                || scheme.equalsIgnoreCase("https"))) {
            throw new UnsafeUrlException("仅允许 HTTP/HTTPS URL");
        }

        if (uri.getRawUserInfo() != null) {
            throw new UnsafeUrlException("不允许 URL userinfo");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new UnsafeUrlException("URL 主机不合法");
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);

        if (normalizedHost.equals("localhost")
                || normalizedHost.endsWith(".localhost")
                || normalizedHost.equals(METADATA_HOST)
                || isSingleLabelHost(normalizedHost)) {
            throw new UnsafeUrlException("URL 主机不安全" + host);
        }

        try {
            List<InetAddress> addresses = List.of(
                    hostResolver.resolve(normalizedHost)
            );

            for (InetAddress address : addresses) {
                if (isUnsafeAddress(address)) {
                    throw new UnsafeUrlException(
                            "URL 解析到不安全地址" + address.getHostAddress()
                    );
                }
            }
        } catch (UnknownHostException exception) {
            throw new UnsafeUrlException("URL 主机无法解析: " + host, exception);
        }
    }

    private boolean isSingleLabelHost(String host) {
        return !host.contains(".") && !host.contains(":");
    }

    private boolean isUnsafeAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || address.isSiteLocalAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isUnsafeIpv4(bytes);
        }

        if (address instanceof Inet6Address ipv6) {
            return isUnsafeIpv6(ipv6);
        }

        return false;
    }

    private boolean isUnsafeIpv4(byte[] bytes) {
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);

        return first == 0
                || first == 10
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 0 && third == 0)
                || (first == 192 && second == 0 && third == 2)
                || (first == 192 && second == 88 && third == 99)
                || (first == 192 && second == 168)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)
                || first >= 224;
    }

    private boolean isUnsafeIpv6(Inet6Address address) {
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);

        // fc00::/7 unique local, fe80::/10 link local,
        // ff00::/8 multicast and 2001:db8::/32 documentation range.

        return (first & 0xFE) == 0xFC
                || (first == 0xFE && (second & 0xC0) == 0x80)
                || first == 0xFF
                || (first == 0x20
                && second == 0x01
                && bytes[2] == 0x0D
                && bytes[3] == (byte) 0xB8);
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
