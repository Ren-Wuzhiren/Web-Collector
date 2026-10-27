package com.webcollector.capture.browser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [M4.1] 验证 Playwright 能打开页面，并把渲染后的 HTML 保存到磁盘。
 *
 * [测试边界]
 * - 使用本地 HttpServer，避免主测试依赖外部网站。
 * - 不涉及 SSRF。
 * - 不涉及 PDF、截图、正文抽取和 asset 表。
 */
public class PlaywrightBrowserHtmlCaptureTest {

    private HttpServer httpServer;

    private PlaywrightBrowserHtmlCapture capture;

    @BeforeEach
    void setUp() throws IOException {
        capture = new PlaywrightBrowserHtmlCapture();

        httpServer = HttpServer.create(
                new InetSocketAddress("localhost", 0),
                0
        );

        httpServer.createContext(
                "/dynamic",
                this::handleDynamicPage
        );

        httpServer.setExecutor(
                Executors.newCachedThreadPool()
        );

        httpServer.start();
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    // [测试意图] 验证浏览器执行 JavaScript 后，保存的是渲染后的 DOM。
    @Test
    void shouldSaveRenderedHtmlToFile(@TempDir Path tempDir) {
        Path outputPath = tempDir.resolve("archive.html");

        Path savedPath = capture.capture(
                localUrl("/dynamic"),
                outputPath
        );

        assertThat(savedPath).isEqualTo(outputPath.toAbsolutePath());
        assertThat(Files.exists(savedPath)).isTrue();

        String html = readFile(savedPath);

        assertThat(html)
                .contains("动态内容已渲染")
                .contains("data-rendered=\"true\"");
    }

    // [测试意图] 验证目标目录不存在时会自动创建，并且 HTML 文件非空。
    @Test
    void shouldCreateParentDirectoriesAndWriteNonEmptyFile(
            @TempDir Path tempDir
    ) throws IOException  {
        Path outputPath = tempDir
                .resolve("nested")
                .resolve("archives")
                .resolve("page.html");

        Path savedPath = capture.capture(
                localUrl("/dynamic"),
                outputPath
        );

        assertThat(Files.exists(savedPath)).isTrue();
        assertThat(Files.size(savedPath)).isGreaterThan(0L);
    }

    // [测试意图] 验证不支持的 URL scheme 在启动浏览器前被拒绝。
    @Test
    void shouldRejectNonHttpUrl(@TempDir Path tempDir) {
        Path outputPath = tempDir.resolve("invalid.html");

        assertThatThrownBy(() -> capture.capture(
                "ftp://example.com/page",
                outputPath
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP/HTTPS");
    }

    // [测试意图] 验证空 URL 和空输出路径被拒绝。
    @Test
    void shouldRejectBlankArguments(@TempDir Path tempDir) {
        Path outputPath = tempDir.resolve("invalid.html");

        assertThatThrownBy(() -> capture.capture("", outputPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");

        assertThatThrownBy(() -> capture.capture(
                localUrl("/dynamic"),
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputPath");
    }

    private void handleDynamicPage(HttpExchange exchange) {
        String body = """
            <!doctype html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>M4.1 Test Page</title>
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
            throw new IllegalStateException(
                    "测试 HTTP 页面写入失败",
                    exception
            );
        } finally {
            exchange.close();
        }
    }

    private String localUrl(String path) {
        return "http://localhost:"
                + httpServer.getAddress().getPort()
                + path;
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("读取 HTML 文件失败: " + path, exception);
        }
    }
}
