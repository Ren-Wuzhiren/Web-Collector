package com.webcollector.capture.browser;

import com.microsoft.playwright.*;
import com.webcollector.capture.security.NavigationLimitException;
import com.webcollector.capture.security.SafeNavigationClient;
import com.webcollector.capture.security.SafeUrlPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;


/** M4.4 安全与资源限制接入点。 */
@Service
public class PlaywrightBrowserHtmlCapture
        implements BrowserHtmlCapture {

    private static final int NAVIGATION_TIMEOUT_MILLIS = 30_000;
    private static final long MAX_HTML_BYTES = 10 * 1024 * 1024;

    private final SafeUrlPolicy safeUrlPolicy;
    private final SafeNavigationClient safeNavigationClient;
    private final PageArtifactExporter artifactExporter;

    @Autowired
    public PlaywrightBrowserHtmlCapture(
            SafeNavigationClient safeNavigationClient,
            SafeUrlPolicy safeUrlPolicy,
            PageArtifactExporter artifactExporter
    ) {
        this.safeNavigationClient = safeNavigationClient;
        this.safeUrlPolicy = safeUrlPolicy;
        this.artifactExporter = artifactExporter;
    }

    public PlaywrightBrowserHtmlCapture(
            SafeNavigationClient safeNavigationClient,
            SafeUrlPolicy safeUrlPolicy
    ) {
        this(safeNavigationClient, safeUrlPolicy, new PlaywrightPageArtifactExporter());
    }

    /** 兼容 M4.1/M4.2 的直接测试构造方式。 */
    public PlaywrightBrowserHtmlCapture() {
        this(
                new SafeNavigationClient(new SafeUrlPolicy()),
                new SafeUrlPolicy(),
                new PlaywrightPageArtifactExporter()
        );
    }

    @Override
    public Path capture(String url, Path outputPath) {
        validateBasicUrl(url);
        if (outputPath == null) {
            throw new IllegalArgumentException("outputPath 不能为空");
        }
        URI safeUrl = safeNavigationClient.validateRedirectChain(url);

        try (
                Playwright playwright = Playwright.create();
                Browser browser = playwright.chromium().launch(
                        new BrowserType.LaunchOptions().setHeadless(true)
                );
                BrowserContext context = browser.newContext();
                Page page = context.newPage()
        ) {
            context.setDefaultTimeout(NAVIGATION_TIMEOUT_MILLIS);
            page.route("**/*", route -> handleSubresource(route));
            page.navigate(safeUrl.toString());

            String html = page.content();
            int htmlBytes = html.getBytes(StandardCharsets.UTF_8).length;
            if (htmlBytes > MAX_HTML_BYTES) {
                throw new NavigationLimitException(
                        "渲染后的 HTML 超过大小限制"
                );
            }

            Path target = outputPath.toAbsolutePath().normalize();
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, html, StandardCharsets.UTF_8);
            return target;
        } catch (NavigationLimitException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "安全网页捕获失败: " + url,
                    exception
            );
        }
    }

    @Override
    public BrowserCaptureArtifacts captureSnapshot(
            String url,
            Path outputDirectory
    ) {
        validateBasicUrl(url);
        URI safeUrl = safeNavigationClient.validateRedirectChain(url);
        if (outputDirectory == null) {
            throw new IllegalArgumentException("outputDirectory 不能为空");
        }

        Path directory = outputDirectory.toAbsolutePath().normalize();
        Path htmlPath = directory.resolve("page.html");
        Path pdfPath = directory.resolve("page.pdf");
        Path screenshotPath = directory.resolve("screenshot.png");
        List<Path> outputs = List.of(htmlPath, pdfPath, screenshotPath);

        for (Path output : outputs) {
            if (Files.exists(output)) {
                throw new IllegalStateException("快照文件已存在，拒绝覆盖: " + output);
            }
        }

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(
                     new BrowserType.LaunchOptions().setHeadless(true));
             BrowserContext context = browser.newContext(
                     new Browser.NewContextOptions().setViewportSize(1280, 720));
             Page page = context.newPage()) {
            context.setDefaultTimeout(NAVIGATION_TIMEOUT_MILLIS);
            page.route("**/*", this::handleSubresource);
            page.navigate(safeUrl.toString());
            String renderedHtml = page.content();
            ensureHtmlSize(renderedHtml);
            Files.createDirectories(directory);
            ArtifactResult html = exportOne(page, htmlPath, "HTML", artifactExporter::writeHtml);
            ArtifactResult pdf = exportOne(page, pdfPath, "PDF", artifactExporter::writePdf);
            ArtifactResult screenshot = exportOne(page, screenshotPath, "截图", artifactExporter::writeScreenshot);
            return new BrowserCaptureArtifacts(html, pdf, screenshot);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("安全网页快照失败: " + url, exception);
        }
    }

    @FunctionalInterface
    private interface ExportAction {
        void write(Page page, Path target) throws Exception;
    }

    private ArtifactResult exportOne(Page page, Path target, String format, ExportAction action) {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime());
        try {
            action.write(page, temporary);
            moveWithoutOverwrite(temporary, target);
            return ArtifactResult.succeeded(target);
        } catch (Exception exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            return ArtifactResult.failed("ARTIFACT_EXPORT_FAILED", format + " 导出失败: " + stableMessage(exception));
        }
    }

    private void moveWithoutOverwrite(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private String stableMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private void handleSubresource(Route route) {
        try {
            safeUrlPolicy.validateAndResolve(route.request().url());
            route.resume();
        } catch (RuntimeException exception) {
            route.abort();
        }
    }

    private void validateBasicUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        String normalized = url.strip().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("http://")
                && !normalized.startsWith("https://")) {
            throw new IllegalArgumentException("仅允许 HTTP/HTTPS URL");
        }
    }

    private void ensureHtmlSize(String html) {
        if (html.getBytes(StandardCharsets.UTF_8).length > MAX_HTML_BYTES) {
            throw new NavigationLimitException("渲染后的 HTML 超过大小限制");
        }
    }

}
