package com.webcollector.capture.browser;

import com.microsoft.playwright.Page;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

interface SnapshotFormatExporter {
    void exportHtml(String html, Path path);
    void exportPdf(Page page, Path path);
    void exportScreenshot(Page page, Path path);

    static SnapshotFormatExporter production() {
        return new SnapshotFormatExporter() {
            @Override
            public void exportHtml(String html, Path path) {
                try {
                    Files.writeString(path, html, StandardCharsets.UTF_8);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }

            @Override
            public void exportPdf(Page page, Path path) {
                page.pdf(new Page.PdfOptions().setPath(path)
                        .setFormat("A4").setPrintBackground(true));
            }

            @Override
            public void exportScreenshot(Page page, Path path) {
                page.screenshot(new Page.ScreenshotOptions().setPath(path)
                        .setFullPage(true)
                        .setType(com.microsoft.playwright.options.ScreenshotType.PNG));
            }
        };
    }
}
