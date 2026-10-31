package com.webcollector.capture.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeUrlPolicyTest {

    private final SafeUrlPolicy policy = new SafeUrlPolicy();

    // [测试意图] 验证公网 HTTP/HTTPS 地址能通过策略解析。
    @Test
    void shouldAllowPublicAddress() {
        assertThat(policy.validateAndResolve("https://8.8.8.8/path").getPath())
                .isEqualTo("/path");
    }

    // [测试意图] 验证非法 scheme、userinfo、localhost 和单标签主机均被拒绝。
    @Test
    void shouldRejectUnsupportedOrLocalHostUrls() {
        assertRejected("ftp://8.8.8.8/file");
        assertRejected("http://user:password@8.8.8.8/");
        assertRejected("http://localhost/");
        assertRejected("http://service/");
        assertRejected("http://metadata.google.internal/");
    }

    // [测试意图] 验证 IPv4 loopback、私网、链路本地、共享地址和保留地址会被拒绝。
    @Test
    void shouldRejectUnsafeIpv4Ranges() {
        assertRejected("http://127.0.0.1/");
        assertRejected("http://10.1.2.3/");
        assertRejected("http://172.16.0.1/");
        assertRejected("http://192.168.1.1/");
        assertRejected("http://169.254.169.254/");
        assertRejected("http://100.64.0.1/");
        assertRejected("http://192.0.2.1/");
        assertRejected("http://198.51.100.1/");
        assertRejected("http://203.0.113.1/");
        assertRejected("http://224.0.0.1/");
    }

    // [测试意图] 验证 IPv6 loopback、ULA、链路本地、多播和文档地址会被拒绝。
    @Test
    void shouldRejectUnsafeIpv6Ranges() {
        assertRejected("http://[::1]/");
        assertRejected("http://[fc00::1]/");
        assertRejected("http://[fe80::1]/");
        assertRejected("http://[ff02::1]/");
        assertRejected("http://[2001:db8::1]/");
    }

    // [测试意图] 验证 DNS 返回多个地址时，只要任一地址危险就拒绝整个主机。
    @Test
    void shouldRejectHostWhenAnyResolvedAddressIsUnsafe() throws Exception {
        SafeUrlPolicy multiAddressPolicy = new SafeUrlPolicy(host -> new InetAddress[]{
                InetAddress.getByAddress(new byte[]{8, 8, 8, 8}),
                InetAddress.getByAddress(new byte[]{10, 0, 0, 1})
        });

        assertThatThrownBy(() -> multiAddressPolicy.validateAndResolve(
                "https://mixed-address.example/path"
        )).isInstanceOf(UnsafeUrlException.class);
    }

    private void assertRejected(String url) {
        assertThatThrownBy(() -> policy.validateAndResolve(url))
                .isInstanceOf(UnsafeUrlException.class);
    }
}
