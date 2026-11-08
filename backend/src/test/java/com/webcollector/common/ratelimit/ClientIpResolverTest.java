package com.webcollector.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(
            List.of("172.16.0.0/12")
    );

    // [测试意图] 验证非可信直连来源即使伪造 X-Forwarded-For 也只使用 TCP 来源地址。
    @Test
    void shouldIgnoreForwardedHeaderFromUntrustedPeer() {
        MockHttpServletRequest request = request(
                "198.51.100.9",
                "203.0.113.7"
        );

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    // [测试意图] 验证可信代理下从右向左取第一个非可信地址，客户端左侧伪造值不能生效。
    @Test
    void shouldUseRightmostUntrustedForwardedAddress() {
        MockHttpServletRequest request = request(
                "172.18.0.2",
                "203.0.113.7, 198.51.100.9"
        );

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    // [测试意图] 验证可信代理缺少或提供非法转发头时，稳定回退到直连地址。
    @Test
    void shouldFallBackToPeerWhenForwardedHeaderIsMissingOrInvalid() {
        MockHttpServletRequest missing = request("172.18.0.2", null);
        MockHttpServletRequest invalid = request("172.18.0.2", "not-an-ip");

        assertThat(resolver.resolve(missing)).isEqualTo("172.18.0.2");
        assertThat(resolver.resolve(invalid)).isEqualTo("172.18.0.2");
    }

    // [测试意图] 验证可信代理提供的 X-Real-IP 优先于客户端可伪造的转发链。
    @Test
    void shouldPreferRealIpOverForwardedChainFromTrustedPeer() {
        MockHttpServletRequest request = request(
                "172.19.0.5",
                "203.0.113.7, 172.19.0.1",
                "198.51.100.9"
        );

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    // [测试意图] 验证私网范围内的 X-Real-IP 仍作为客户端地址，不被当作代理跳过。
    @Test
    void shouldAcceptRealIpInsideTrustedProxyRange() {
        MockHttpServletRequest request = request(
                "172.19.0.5",
                "203.0.113.7",
                "172.19.0.1"
        );

        assertThat(resolver.resolve(request)).isEqualTo("172.19.0.1");
    }

    // [测试意图] 验证非可信 peer 同时伪造 X-Real-IP 和 XFF 时仍只使用 TCP 来源。
    @Test
    void shouldIgnoreBothForwardingHeadersFromUntrustedPeer() {
        MockHttpServletRequest request = request(
                "198.51.100.9",
                "203.0.113.7",
                "192.0.2.8"
        );

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    // [测试意图] 验证可信代理的 X-Real-IP 非法时回退到安全的 XFF 右向左解析。
    @Test
    void shouldFallBackToForwardedChainWhenRealIpIsInvalid() {
        MockHttpServletRequest request = request(
                "172.18.0.2",
                "203.0.113.7, 198.51.100.9",
                "not-an-ip"
        );

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    private MockHttpServletRequest request(
            String remoteAddress,
            String forwardedFor
    ) {
        return request(remoteAddress, forwardedFor, null);
    }

    private MockHttpServletRequest request(
            String remoteAddress,
            String forwardedFor,
            String realIp
    ) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);

        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        if (realIp != null) {
            request.addHeader("X-Real-IP", realIp);
        }

        return request;
    }
}
