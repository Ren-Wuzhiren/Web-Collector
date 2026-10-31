package com.webcollector.capture.browser;

import java.nio.file.Path;

/** 一次浏览器快照的逐格式结果。 */
public record BrowserCaptureArtifacts(
        ArtifactResult html,
        ArtifactResult pdf,
        ArtifactResult screenshot
) {
    public BrowserCaptureArtifacts {
        if (html == null || pdf == null || screenshot == null) {
            throw new IllegalArgumentException("三种格式结果不能为空");
        }
    }

    public boolean hasUsableSnapshot() {
        return html.isSuccess() || screenshot.isSuccess();
    }

    /** M4.2 兼容访问器：格式失败时返回 null。 */
    public Path htmlPath() { return html.path(); }
    /** M4.2 兼容访问器：格式失败时返回 null。 */
    public Path pdfPath() { return pdf.path(); }
    /** M4.2 兼容访问器：格式失败时返回 null。 */
    public Path screenshotPath() { return screenshot.path(); }
}
