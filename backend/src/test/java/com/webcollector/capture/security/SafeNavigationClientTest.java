package com.webcollector.capture.security;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeNavigationClientTest {

    private HttpServer server;
    private ExecutorService executor;
    private AtomicInteger loopHits;
    private AtomicInteger blockedHits;

    @BeforeEach
    void setUp() throws IOException {
        loopHits = new AtomicInteger();
        blockedHits = new AtomicInteger();
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                0
        );
        server.createContext("/ok", exchange -> send(exchange, 200, bytes("ok")));
        server.createContext("/redirect-blocked", exchange -> redirect(exchange, "/blocked"));
        server.createContext("/blocked", exchange -> {
            blockedHits.incrementAndGet();
            send(exchange, 200, bytes("must not be requested"));
        });
        server.createContext("/redirect-loopback", exchange -> {
            exchange.getResponseHeaders().set(
                    "Location",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/ok"
            );
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/loop", exchange -> {
            loopHits.incrementAndGet();
            redirect(exchange, "/loop");
        });
        server.createContext("/large", exchange ->
                send(exchange, 200, new byte[4096]));
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(600);
                send(exchange, 200, bytes("late"));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });

        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // [测试意图] 验证安全预检可完成普通 HTTP 响应并返回最终 URI。
    @Test
    void shouldReturnUriForSuccessfulResponse() {
        SafeNavigationClient client = client(new LocalhostTestPolicy(), 3, 1024, 2);

        assertThat(client.validateRedirectChain(url("/ok")).getPath())
                .isEqualTo("/ok");
    }

    // [测试意图] 验证重定向目标会重新检查，危险 Location 不会被请求。
    @Test
    void shouldRevalidateEachRedirectTarget() {
        SafeNavigationClient client = client(new LocalhostTestPolicy(), 3, 1024, 2);

        assertThatThrownBy(() -> client.validateRedirectChain(url("/redirect-blocked")))
                .isInstanceOf(UnsafeUrlException.class);
        assertThat(blockedHits.get()).isZero();
    }

    // [测试意图] 验证重定向到 127.0.0.1 会被生产 URL 策略拒绝。
    @Test
    void shouldRejectRedirectToIpv4Loopback() {
        SafeNavigationClient client = client(new InitialLocalhostPolicy(), 3, 1024, 2);

        assertThatThrownBy(() -> client.validateRedirectChain(url("/redirect-loopback")))
                .isInstanceOf(UnsafeUrlException.class);
    }

    // [测试意图] 验证循环跳转在配置跳数耗尽后终止。
    @Test
    void shouldStopRedirectLoopAtConfiguredLimit() {
        SafeNavigationClient client = client(new LocalhostTestPolicy(), 2, 1024, 2);

        assertThatThrownBy(() -> client.validateRedirectChain(url("/loop")))
                .isInstanceOf(NavigationLimitException.class)
                .hasMessageContaining("重定向次数");
        assertThat(loopHits.get()).isEqualTo(3);
    }

    // [测试意图] 验证超大响应由流式读取上限拦截。
    @Test
    void shouldStopReadingResponseAboveConfiguredLimit() {
        SafeNavigationClient client = client(new LocalhostTestPolicy(), 3, 1024, 2);

        assertThatThrownBy(() -> client.validateRedirectChain(url("/large")))
                .isInstanceOf(NavigationLimitException.class)
                .hasMessageContaining("大小限制");
    }

    // [测试意图] 验证慢响应超过请求时限后转换为导航限制异常。
    @Test
    void shouldFailSlowResponseByTimeout() {
        SafeNavigationClient client = new SafeNavigationClient(
                new LocalhostTestPolicy(),
                3,
                1024,
                Duration.ofMillis(100)
        );

        assertThatThrownBy(() -> client.validateRedirectChain(url("/slow")))
                .isInstanceOf(NavigationLimitException.class);
    }

    private SafeNavigationClient client(
            SafeUrlPolicy policy,
            int redirects,
            long maxBytes,
            long timeoutSeconds
    ) {
        return new SafeNavigationClient(
                policy,
                redirects,
                maxBytes,
                Duration.ofSeconds(timeoutSeconds)
        );
    }

    private String url(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    private void redirect(HttpExchange exchange, String location)
            throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void send(HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static class LocalhostTestPolicy extends SafeUrlPolicy {
        @Override
        public URI validateAndResolve(String rawUrl) {
            URI uri = URI.create(rawUrl);
            validate(uri);
            return uri;
        }

        @Override
        public void validate(URI uri) {
            if ("/blocked".equals(uri.getPath())) {
                throw new UnsafeUrlException("测试策略拒绝该路径");
            }
            if (!"localhost".equalsIgnoreCase(uri.getHost())) {
                throw new UnsafeUrlException("测试策略仅允许 localhost");
            }
        }
    }

    private static final class InitialLocalhostPolicy extends SafeUrlPolicy {
        private final SafeUrlPolicy productionPolicy = new SafeUrlPolicy();

        @Override
        public URI validateAndResolve(String rawUrl) {
            URI uri = URI.create(rawUrl);
            if ("localhost".equalsIgnoreCase(uri.getHost())) {
                return uri;
            }
            productionPolicy.validate(uri);
            return uri;
        }

        @Override
        public void validate(URI uri) {
            productionPolicy.validate(uri);
        }
    }
}
