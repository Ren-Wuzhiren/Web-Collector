package com.webcollector.link.support;

import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlNormalizerTest {

    // [测试意图] 验证 host 大小写、默认端口、空路径和 fragment 按契约规范化。
    @Test
    void shouldNormalizeHostDefaultPortEmptyPathAndFragment() {
        assertThat(UrlNormalizer.normalize(" HTTP://Example.COM:80#part "))
                .isEqualTo("http://example.com/");
    }

    // [测试意图] 验证查询参数原样保留且不排序，确保 URL 规范化不改写查询语义。
    @Test
    void shouldPreserveQueryParameterOrder() {
        assertThat(UrlNormalizer.normalize(
                "https://example.com/search?b=2&a=1#section"
        )).isEqualTo("https://example.com/search?b=2&a=1");
    }

    // [测试意图] 验证 HTTPS 默认端口被移除而非默认端口仍保留。
    @Test
    void shouldRemoveOnlySchemeDefaultPort() {
        assertThat(UrlNormalizer.normalize("https://example.com:443/a"))
                .isEqualTo("https://example.com/a");
        assertThat(UrlNormalizer.normalize("https://example.com:8443/a"))
                .isEqualTo("https://example.com:8443/a");
    }

    // [测试意图] 验证 IPv6 host 的方括号只出现一层且 host 字母统一小写。
    @Test
    void shouldNormalizeIpv6HostWithoutDuplicatingBrackets() {
        assertThat(UrlNormalizer.normalize("http://[2001:DB8::1]:80/a"))
                .isEqualTo("http://[2001:db8::1]/a");
    }

    // [测试意图] 验证空值、非 HTTP(S)、userinfo 和格式错误作为客户端请求错误处理。
    @Test
    void shouldRejectUnsupportedOrMalformedUrlsAsBadRequest() {
        for (String url : new String[]{
                " ",
                "ftp://example.com/file",
                "https://user:secret@example.com/",
                "https://bad host/"
        }) {
            assertThatThrownBy(() -> UrlNormalizer.normalize(url))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ErrorCode.INVALID_REQUEST)
                    );
        }
    }

    // [测试意图] 验证 SHA-256 使用 UTF-8 并输出稳定的 64 位小写十六进制值。
    @Test
    void shouldReturnStableSha256Hex() {
        assertThat(UrlNormalizer.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223"
                        + "b00361a396177a9cb410ff61f20015ad")
                .hasSize(64);
    }
}
