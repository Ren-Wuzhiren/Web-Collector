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
    private final SnapshotFormatExporter formatExporter;

    @Autowired
    public PlaywrightBrowserHtmlCapture(
            SafeNavigationClient safeNavigationClient,
            SafeUrlPolicy safeUrlPolicy
    ) {
        this(safeNavigationClient, safeUrlPolicy, SnapshotFormatExporter.production());
    }

    PlaywrightBrowserHtmlCapture(
            SafeNavigationClient safeNavigationClient,
            SafeUrlPolicy safeUrlPolicy,
            SnapshotFormatExporter formatExporter
    ) {
        this.safeNavigationClient = safeNavigationClient;
        this.safeUrlPolicy = safeUrlPolicy;
        this.formatExporter = formatExporter;
    }

    /** 兼容 M4.1/M4.2 的直接测试构造方式。 */
    public PlaywrightBrowserHtmlCapture() {
        this(
                new SafeNavigationClient(new SafeUrlPolicy()),
                new SafeUrlPolicy()
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

        if (outputDirectory == null) {
            throw new IllegalArgumentException("outputDirectory 不能为空");
        }

        URI safeUrl = safeNavigationClient.validateRedirectChain(url);
        Path directory = outputDirectory.toAbsolutePath().normalize();
        Path html = directory.resolve("page.html");
        Path pdf = directory.resolve("page.pdf");
        Path screenshot = directory.resolve("screenshot.png");


        for (Path path : List.of(html, pdf, screenshot)) {
            if (Files.exists(path)) {
                throw new IllegalStateException("快照文件已存在，拒绝覆盖: " + path);
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

            CaptureFormatResult htmlResult = writeHtml(html, renderedHtml);
            CaptureFormatResult screenshotResult = writeScreenshot(page, screenshot);
            CaptureFormatResult pdfResult = writePdf(page, pdf);
            return new BrowserCaptureArtifacts(htmlResult, pdfResult, screenshotResult);
        } catch (NavigationLimitException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("安全网页导航失败: " + url, exception);
        }
    }

    private void ensureHtmlSize(String html) {
        if (html.getBytes(StandardCharsets.UTF_8).length > MAX_HTML_BYTES) {
            throw new NavigationLimitException("渲染后的 HTML 超过大小限制");
        }
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

    private CaptureFormatResult writeHtml(Path path, String html) {
        try {
            formatExporter.exportHtml(html, path);
            return CaptureFormatResult.success(CaptureFormat.HTML, path);
        } catch (Exception exception) {
            deleteQuietly(path, exception);
            return CaptureFormatResult.failed(CaptureFormat.HTML, messageOf(exception));
        }
    }

    private CaptureFormatResult writeScreenshot(Page page, Path path) {
        try {
            formatExporter.exportScreenshot(page, path);
            return CaptureFormatResult.success(CaptureFormat.SCREENSHOT, path);
        } catch (Exception exception) {
            deleteQuietly(path, exception);
            return CaptureFormatResult.failed(CaptureFormat.SCREENSHOT, messageOf(exception));
        }
    }

    private CaptureFormatResult writePdf(Page page, Path path) {

        try {
            formatExporter.exportPdf(page, path);
            return CaptureFormatResult.success(CaptureFormat.PDF, path);
        } catch (Exception exception) {
            deleteQuietly(path, exception);
            return CaptureFormatResult.failed(CaptureFormat.PDF, messageOf(exception));
        }
    }

    private void deleteQuietly(Path path, Exception original) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private String messageOf(Exception exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
