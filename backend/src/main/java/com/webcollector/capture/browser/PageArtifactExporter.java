package com.webcollector.capture.browser;

import com.microsoft.playwright.Page;

import java.nio.file.Path;

/** 页面格式导出的可替换测试缝。 */
interface PageArtifactExporter {
    void writeHtml(Page page, Path target) throws Exception;
    void writePdf(Page page, Path target) throws Exception;
    void writeScreenshot(Page page, Path target) throws Exception;
}
