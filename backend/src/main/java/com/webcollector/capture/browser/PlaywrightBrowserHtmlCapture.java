package com.webcollector.capture.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * [业务] 使用 Playwright Chromium 把网页保存为渲染后的 HTML。
 *
 * [边界]
 * - 本类不做 SSRF 校验。
 * - 本类不做重定向安全检查。
 * - 本类不生成 PDF 或截图。
 * - 本类只负责一次浏览器导航和一个 HTML 文件的写入。
 */
@Service
public class PlaywrightBrowserHtmlCapture
        implements BrowserHtmlCapture {

    private static final int NAVIGATION_TIMEOUT_MILLIS = 30_000;


    @Override
    public Path capture(String url, Path outputPath) {
        validateArguments(url, outputPath);

        Path absoluteOutputPath = outputPath.toAbsolutePath();

        try {
            Path parent = absoluteOutputPath.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (
                    Playwright playwright = Playwright.create();
                    Browser browser = playwright.chromium().launch(
                            new BrowserType.LaunchOptions()
                                    .setHeadless(true)
                    )
            ) {
                try (
                        BrowserContext context = browser.newContext();
                        Page page = context.newPage()
            ) {
                    page.setDefaultNavigationTimeout(
                            NAVIGATION_TIMEOUT_MILLIS
                    );

                    page.navigate(url);

                    String renderedHtml = page.content();

                    Files.writeString(
                            absoluteOutputPath,
                            renderedHtml,
                            StandardCharsets.UTF_8
                    );
                }
            }

            return absoluteOutputPath;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "HTML 文件写入失败: " + absoluteOutputPath,
                    exception
            );
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "网页采集失败: " + url,
                    exception
            );
        }
    }

    private void validateArguments(
            String url,
            Path outputPath
    ) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }

        if (outputPath == null) {
            throw new IllegalArgumentException("outputPath 不能为空");
        }

        String lowerCaseUrl = url.trim().toLowerCase();

        if (!lowerCaseUrl.startsWith("http://")
                && !lowerCaseUrl.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "M4.1 只允许 HTTP/HTTPS URL"
            );
        }
    }
}