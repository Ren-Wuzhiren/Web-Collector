package com.webcollector.capture.browser;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ScreenshotType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** 生产环境使用的 Playwright 导出器。 */
@Component
final class PlaywrightPageArtifactExporter implements PageArtifactExporter {
    @Override
    public void writeHtml(Page page, Path target) throws Exception {
        Files.writeString(target, page.content(), StandardCharsets.UTF_8);
    }

    @Override
    public void writePdf(Page page, Path target) {
        page.pdf(new Page.PdfOptions().setPath(target).setFormat("A4").setPrintBackground(true));
    }

    @Override
    public void writeScreenshot(Page page, Path target) {
        page.screenshot(new Page.ScreenshotOptions().setPath(target).setFullPage(true).setType(ScreenshotType.PNG));
    }
}
