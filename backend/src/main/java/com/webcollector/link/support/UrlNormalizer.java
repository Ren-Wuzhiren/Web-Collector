package com.webcollector.link.support;

import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class UrlNormalizer {
    private UrlNormalizer() {
    }

    /**
     * [业务] 将 HTTP(S) 地址转成用于去重的稳定形式。
     * 保留查询参数顺序和原始 URL，避免改变用户保存的地址语义。
     */
    public static String normalize(String originalUrl) {
        if (originalUrl == null || originalUrl.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "URL 不能为空");
        }

        try {
            URI uri = new URI(originalUrl.strip()).parseServerAuthority();
            String scheme = uri.getScheme();

            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http")
                    || scheme.equalsIgnoreCase("https"))) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "仅支持 HTTP 或 HTTPS URL");
            }

            if (uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "URL 主机格式不合法");
            }

            scheme = scheme.toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();

            if ((scheme.equals("http") && port == 80)
                    || (scheme.equals("https") && port == 443)) {
                port = -1;
            }

            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }

            // 直接拼接 raw 部分，避免 URI 重建时转义或改写查询参数。
            StringBuilder normalized = new StringBuilder()
                    .append(scheme)
                    .append("://");

            if (host.contains(":")) {
                if (host.startsWith("[")) {
                    normalized.append(host);
                } else {
                    normalized.append('[').append(host).append(']');
                }
            } else {
                normalized.append(host);
            }

            if (port != -1) {
                normalized.append(':').append(port);
            }

            normalized.append(path);

            if (uri.getRawQuery() != null) {
                normalized.append('?').append(uri.getRawQuery());
            }

            // fragment 不参与去重；query 的原始顺序保持不变。
            return normalized.toString();
        } catch (URISyntaxException exception) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "URL 格式不合法"
            );
        }
    }

    /**
     * [Java] SHA-256 输出为固定 64 位小写十六进制字符串。
     * 算法由 JDK 保证可用；缺失时属于运行环境错误。
     */
    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }
}
