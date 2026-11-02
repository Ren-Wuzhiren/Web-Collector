package com.webcollector.capture.browser;

import java.nio.file.Path;

/** 一次浏览器快照生成的三个文件路径。 */
public record BrowserCaptureArtifacts(
        CaptureFormatResult html,
        CaptureFormatResult pdf,
        CaptureFormatResult screenshot
) {
    public boolean hasAnySuccess() {
        return html.status() == CaptureFormatStatus.SUCCESS ||
                pdf.status() == CaptureFormatStatus.SUCCESS ||
                screenshot.status() == CaptureFormatStatus.SUCCESS;
    }
}
