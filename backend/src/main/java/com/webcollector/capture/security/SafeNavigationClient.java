package com.webcollector.capture.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 逐跳校验重定向并限制预检响应大小。 */
@Component
public class SafeNavigationClient {

    private static final int DEFAULT_MAX_REDIRECTS = 10;
    private static final long DEFAULT_MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final SafeUrlPolicy safeUrlPolicy;
    private final HttpClient httpClient;
    private final int maxRedirects;
    private final long maxResponseBytes;
    private final Duration requestTimeout;

    @Autowired
    public SafeNavigationClient(SafeUrlPolicy safeUrlPolicy) {
        this(
                safeUrlPolicy,
                DEFAULT_MAX_REDIRECTS,
                DEFAULT_MAX_RESPONSE_BYTES,
                DEFAULT_REQUEST_TIMEOUT
        );
    }

    SafeNavigationClient(
            SafeUrlPolicy safeUrlPolicy,
            int maxRedirects,
            long maxResponseBytes,
            Duration requestTimeout
    ) {
        this.safeUrlPolicy = safeUrlPolicy;
        this.maxRedirects = maxRedirects;
        this.maxResponseBytes = maxResponseBytes;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(requestTimeout)
                .build();
    }

    public URI validateRedirectChain(String rawUrl) {
        URI current = safeUrlPolicy.validateAndResolve(rawUrl);

        for (int redirect = 0; redirect <= maxRedirects; redirect++) {
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(requestTimeout)
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofInputStream()
                );
            } catch (IOException exception) {
                throw new NavigationLimitException(
                        "URL 预检失败: " + current,
                        exception
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new NavigationLimitException(
                        "URL 预检被中断: " + current,
                        exception
                );
            }

            try {
                drainLimited(response.body(), current);
            } catch (IOException exception) {
                throw new NavigationLimitException(
                        "读取 URL 响应失败: " + current,
                        exception
                );
            }

            int status = response.statusCode();
            if (status < 300 || status >= 400) {
                return current;
            }

            if (redirect == maxRedirects) {
                throw new NavigationLimitException("重定向次数超过限制");
            }

            String location = response.headers()
                    .firstValue("Location")
                    .orElseThrow(() -> new NavigationLimitException(
                            "重定向缺少 Location"
                    ));

            URI next = current.resolve(location);
            safeUrlPolicy.validate(next);
            current = next;
        }

        throw new NavigationLimitException("重定向检查失败");
    }

    private void drainLimited(InputStream input, URI current)
            throws IOException {
        try (InputStream stream = input) {
            byte[] chunk = new byte[8192];
            long total = 0;
            int read;
            while ((read = stream.read(chunk)) != -1) {
                total += read;
                if (total > maxResponseBytes) {
                    throw new NavigationLimitException(
                            "URL 响应超过大小限制: " + current
                    );
                }
            }
        }
    }
}
