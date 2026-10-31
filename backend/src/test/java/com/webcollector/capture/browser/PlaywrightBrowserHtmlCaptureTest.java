package com.webcollector.capture.browser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webcollector.capture.security.SafeNavigationClient;
import com.webcollector.capture.security.SafeUrlPolicy;
import com.webcollector.capture.security.UnsafeUrlException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaywrightBrowserHtmlCaptureTest {

    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private HttpServer httpServer;
    private ExecutorService httpExecutor;
    private AtomicInteger navigationCount;
    private AtomicInteger blockedSubresourceCount;
    private PlaywrightBrowserHtmlCapture capture;

    @BeforeEach
    void setUp() throws IOException {
        capture = new PlaywrightBrowserHtmlCapture(
                new LocalOnlyNavigationClient(),
                new LocalOnlyUrlPolicy()
        );
        navigationCount = new AtomicInteger();
        blockedSubresourceCount = new AtomicInteger();

        httpServer = HttpServer.create(
                new InetSocketAddress("localhost", 0),
                0
        );
        httpServer.createContext("/dynamic", this::handleDynamicPage);
        httpServer.createContext("/dynamic-with-blocked-resource", this::handlePageWithPrivateImage);
        httpServer.createContext("/blocked-resource", exchange -> {
            blockedSubresourceCount.incrementAndGet();
            sendHtml(exchange, "blocked");
        });

        httpExecutor = Executors.newFixedThreadPool(2);
        httpServer.setExecutor(httpExecutor);
        httpServer.start();
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
        }
    }

    // [测试意图] 验证 M4.1 接口仍能保存浏览器执行 JavaScript 后的 DOM。
    @Test
    void shouldSaveRenderedHtmlToFile(@TempDir Path tempDir)
            throws IOException {
        Path outputPath = tempDir.resolve("archive.html");

        Path savedPath = capture.capture(
                localUrl("/dynamic"),
                outputPath
        );

        assertThat(savedPath)
                .isEqualTo(outputPath.toAbsolutePath().normalize());
        assertThat(Files.exists(savedPath)).isTrue();
        assertThat(Files.readString(savedPath, StandardCharsets.UTF_8))
                .contains("动态内容已渲染")
                .contains("data-rendered=\"true\"");
    }

    // [测试意图] 验证 M4.1 接口会创建缺失的父目录并写入非空文件。
    @Test
    void shouldCreateParentDirectoriesAndWriteNonEmptyFile(
            @TempDir Path tempDir
    ) throws IOException {
        Path outputPath = tempDir
                .resolve("nested")
                .resolve("page.html");

        Path savedPath = capture.capture(
                localUrl("/dynamic"),
                outputPath
        );

        assertThat(Files.exists(savedPath)).isTrue();
        assertThat(Files.size(savedPath)).isPositive();
    }

    // [测试意图] 验证一次导航由同一 Page 产出 HTML、A4 PDF 和全页截图。
    @Test
    void shouldCaptureHtmlPdfAndFullPageScreenshotFromOneNavigation(
            @TempDir Path tempDir
    ) throws IOException {
        Path outputDirectory = tempDir.resolve("snapshot");

        BrowserCaptureArtifacts artifacts = capture.captureSnapshot(
                localUrl("/dynamic"),
                outputDirectory
        );

        assertThat(artifacts.htmlPath()).isEqualTo(
                outputDirectory.resolve("page.html")
                        .toAbsolutePath()
                        .normalize()
        );
        assertThat(artifacts.pdfPath()).isEqualTo(
                outputDirectory.resolve("page.pdf")
                        .toAbsolutePath()
                        .normalize()
        );
        assertThat(artifacts.screenshotPath()).isEqualTo(
                outputDirectory.resolve("screenshot.png")
                        .toAbsolutePath()
                        .normalize()
        );

        assertThat(Files.size(artifacts.htmlPath())).isPositive();
        assertThat(Files.size(artifacts.pdfPath())).isPositive();
        assertThat(Files.size(artifacts.screenshotPath())).isPositive();

        assertThat(Files.readString(
                artifacts.htmlPath(),
                StandardCharsets.UTF_8
        )).contains("动态内容已渲染");

        byte[] pdfBytes = Files.readAllBytes(artifacts.pdfPath());
        assertThat(new String(
                pdfBytes,
                0,
                Math.min(pdfBytes.length, 5),
                StandardCharsets.US_ASCII
        )).isEqualTo("%PDF-");

        byte[] screenshotBytes = Files.readAllBytes(
                artifacts.screenshotPath()
        );
        assertThat(Arrays.copyOf(screenshotBytes, PNG_SIGNATURE.length))
                .containsExactly(PNG_SIGNATURE);

        var screenshot = ImageIO.read(
                artifacts.screenshotPath().toFile()
        );
        assertThat(screenshot).isNotNull();
        assertThat(screenshot.getHeight()).isGreaterThan(720);
        assertThat(navigationCount.get()).isEqualTo(1);
    }

    // [测试意图] 验证目标文件冲突时拒绝覆盖已有快照文件。
    @Test
    void shouldRefuseToOverwriteExistingSnapshotFile(
            @TempDir Path tempDir
    ) throws IOException {
        Path outputDirectory = tempDir.resolve("existing");
        Files.createDirectories(outputDirectory);

        Path existingHtml = outputDirectory.resolve("page.html");
        Files.writeString(
                existingHtml,
                "previous snapshot",
                StandardCharsets.UTF_8
        );

        assertThatThrownBy(() -> capture.captureSnapshot(
                localUrl("/dynamic"),
                outputDirectory
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝覆盖");

        assertThat(Files.readString(existingHtml, StandardCharsets.UTF_8))
                .isEqualTo("previous snapshot");
        assertThat(navigationCount.get()).isZero();
    }

    // [测试意图] 验证 URL 安全策略拒绝的子资源被 Playwright route 中止。
    @Test
    void shouldAbortPrivateSubresourceAndKeepMainPageAvailable(
            @TempDir Path tempDir
    ) throws IOException {
        Path outputPath = tempDir.resolve("archive.html");

        Path saved = capture.capture(
                localUrl("/dynamic-with-blocked-resource"),
                outputPath
        );

        assertThat(Files.exists(saved)).isTrue();
        assertThat(Files.readString(saved, StandardCharsets.UTF_8))
                .contains("动态内容已渲染");
        assertThat(blockedSubresourceCount.get()).isZero();
    }

    // [测试意图] 验证不支持的 URL scheme 在启动浏览器前被拒绝。
    @Test
    void shouldRejectNonHttpUrl(@TempDir Path tempDir) {
        assertThatThrownBy(() -> capture.capture(
                "ftp://example.com/page",
                tempDir.resolve("invalid.html")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP/HTTPS");
    }

    // [测试意图] 验证空 URL 和空输出目录被拒绝。
    @Test
    void shouldRejectBlankArguments(@TempDir Path tempDir) {
        assertThatThrownBy(() -> capture.capture(
                "",
                tempDir.resolve("invalid.html")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");

        assertThatThrownBy(() -> capture.captureSnapshot(
                localUrl("/dynamic"),
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputDirectory");
    }

    private void handleDynamicPage(HttpExchange exchange) {
        navigationCount.incrementAndGet();

        String body = """
                <!doctype html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>M4.2 Test Page</title>
                    <style>body { min-height: 1800px; }</style>
                </head>
                <body>
                    <div id="app">等待脚本执行</div>
                    <script>
                        const app = document.getElementById('app');
                        app.textContent = '动态内容已渲染';
                        app.dataset.rendered = 'true';
                    </script>
                </body>
                </html>
                """;

        sendHtml(exchange, body);
    }

    private void handlePageWithPrivateImage(HttpExchange exchange) {
        navigationCount.incrementAndGet();
        String body = """
                <!doctype html>
                <html><head><meta charset="UTF-8"></head>
                <body>
                    <div id="app">等待脚本执行</div>
                    <img src="http://192.168.1.10/blocked-resource">
                    <script>
                        document.getElementById('app').textContent = '动态内容已渲染';
                    </script>
                </body></html>
                """;
        sendHtml(exchange, body);
    }

    private void sendHtml(HttpExchange exchange, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        try {
            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "text/html; charset=UTF-8"
            );
            exchange.sendResponseHeaders(200, bytes.length);

            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            exchange.close();
        }
    }

    private String localUrl(String path) {
        return "http://localhost:"
                + httpServer.getAddress().getPort()
                + path;
    }

    /** 仅允许本地夹具，避免 M4.1 回归测试被 M4.4 SSRF 预检拦截。 */
    private static final class LocalOnlyNavigationClient
            extends SafeNavigationClient {

        private LocalOnlyNavigationClient() {
            super(new SafeUrlPolicy());
        }

        @Override
        public URI validateRedirectChain(String rawUrl) {
            return URI.create(rawUrl);
        }
    }

    private static final class LocalOnlyUrlPolicy extends SafeUrlPolicy {
        @Override
        public java.net.URI validateAndResolve(String rawUrl) {
            java.net.URI uri = java.net.URI.create(rawUrl);
            if ("192.168.1.10".equalsIgnoreCase(uri.getHost())) {
                throw new UnsafeUrlException("测试拒绝私网子资源");
            }
            if (!"localhost".equalsIgnoreCase(uri.getHost())) {
                throw new UnsafeUrlException("测试 URL 不是 localhost");
            }
            return uri;
        }

        @Override
        public void validate(java.net.URI uri) {
            validateAndResolve(uri.toString());
        }
    }
}
