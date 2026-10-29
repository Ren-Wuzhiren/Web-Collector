package com.webcollector.capture.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ScreenshotType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 使用同一 Chromium Page 捕获 HTML、PDF 和全页截图。
 *
 * [边界]
 * - 本类不做 SSRF 校验。
 * - 本类不做重定向安全检查。
 * - 本类负责 M4.1 HTML 和 M4.2 PDF/截图输出。
 */
@Service
public class PlaywrightBrowserHtmlCapture
        implements BrowserHtmlCapture {

    private static final int NAVIGATION_TIMEOUT_MILLIS = 30_000;
    private static final int VIEWPORT_WIDTH = 1280;
    private static final int VIEWPORT_HEIGHT = 720;

    @Override
    public Path capture(String url, Path outputPath) {
        validateUrl(url);
        if (outputPath == null) {
            throw new IllegalArgumentException("outputPath 不能为空");
        }

        Path target = outputPath.toAbsolutePath().normalize();
        createParentDirectories(target);

        return withPage(url, page -> {
            Files.writeString(
                    target,
                    page.content(),
                    StandardCharsets.UTF_8
            );
            return target;
        });
    }

    @Override
    public BrowserCaptureArtifacts captureSnapshot(
            String url,
            Path outputDirectory
    ) {
        validateUrl(url);
        if (outputDirectory == null) {
            throw new IllegalArgumentException(
                    "outputDirectory 不能为空"
            );
        }

        Path directory = outputDirectory.toAbsolutePath().normalize();
        Path htmlPath = directory.resolve("page.html");
        Path pdfPath = directory.resolve("page.pdf");
        Path screenshotPath = directory.resolve("screenshot.png");
        List<Path> outputs = List.of(htmlPath, pdfPath, screenshotPath);

        for (Path output : outputs) {
            if (Files.exists(output)) {
                throw new IllegalStateException(
                        "快照文件已存在，拒绝覆盖: " + output
                );
            }
        }

        createDirectories(directory);

        try {
            return withPage(url, page -> {
                Files.writeString(
                        htmlPath,
                        page.content(),
                        StandardCharsets.UTF_8
                );

                // 先截图，再导出 PDF，避免打印介质影响截图样式。
                page.screenshot(
                        new Page.ScreenshotOptions()
                                .setPath(screenshotPath)
                                .setFullPage(true)
                                .setType(ScreenshotType.PNG)
                );

                page.pdf(
                        new Page.PdfOptions()
                                .setPath(pdfPath)
                                .setFormat("A4")
                                .setPrintBackground(true)
                );

                return new BrowserCaptureArtifacts(
                        htmlPath,
                        pdfPath,
                        screenshotPath
                );
            });
        } catch (RuntimeException exception) {
            deletePartialOutputs(outputs, exception);
            throw exception;
        }
    }

    private void createParentDirectories(Path outputPath) {
        Path parent = outputPath.getParent();
        if (parent != null) {
            createDirectories(parent);
        }
    }

    private void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "创建输出目录失败: " + directory,
                    exception
            );
        }
    }

    private void deletePartialOutputs(
            List<Path> outputs,
            RuntimeException originalFailure
    ) {
        List<IOException> cleanupFailures = new ArrayList<>();

        for (Path output : outputs) {
            try {
                Files.deleteIfExists(output);
            } catch (IOException cleanFailure) {
                cleanupFailures.add(cleanFailure);
            }
        }

        cleanupFailures.forEach(originalFailure::addSuppressed);
    }

    private <T> T withPage(String url, PageAction<T> action) {
        try (
                Playwright playwright = Playwright.create();
                Browser browser = playwright.chromium().launch(
                        new BrowserType.LaunchOptions().setHeadless(true)
                );
                BrowserContext context = browser.newContext(
                        new Browser.NewContextOptions().setViewportSize(
                                VIEWPORT_WIDTH,
                                VIEWPORT_HEIGHT
                        )
                );
                Page page = context.newPage();
        ) {
            page.setDefaultNavigationTimeout(
                    NAVIGATION_TIMEOUT_MILLIS
            );
            page.navigate(url);
            return action.apply(page);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "快照文件操作失败",
                    exception
            );
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "网页捕获失败" + url,
                    exception
            );
        }
    }

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }

        String normalized = url.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("http://")
        && !normalized.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "仅允许 HTTP/HTTPS URL"
            );
        }
    }
    
    @FunctionalInterface
    private interface PageAction<T> {
        T apply(Page page) throws IOException;
    }
}
