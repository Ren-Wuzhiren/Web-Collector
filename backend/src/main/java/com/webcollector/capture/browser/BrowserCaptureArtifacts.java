package com.webcollector.capture.browser;

import java.nio.file.Path;

/** 一次浏览器快照生成的三个文件路径。 */
public record BrowserCaptureArtifacts(
        Path htmlPath,
        Path pdfPath,
        Path screenshotPath
) {
}
