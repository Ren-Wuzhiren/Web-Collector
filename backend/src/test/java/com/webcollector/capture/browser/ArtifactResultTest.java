package com.webcollector.capture.browser;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactResultTest {

    // [测试意图] 验证格式成功结果只暴露已发布路径。
    @Test
    void shouldRepresentSuccessfulArtifact() {
        ArtifactResult result = ArtifactResult.succeeded(Path.of("page.html"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.path()).isEqualTo(Path.of("page.html"));
        assertThat(result.errorCode()).isNull();
    }

    // [测试意图] 验证格式失败结果必须带稳定错误码且不带路径。
    @Test
    void shouldRepresentFailedArtifact() {
        ArtifactResult result = ArtifactResult.failed("EXPORT_FAILED", "PDF 导出失败");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.path()).isNull();
        assertThat(result.errorCode()).isEqualTo("EXPORT_FAILED");
        assertThat(result.errorMessage()).isEqualTo("PDF 导出失败");
    }

    // [测试意图] 验证结果契约拒绝同时声明成功路径和错误码。
    @Test
    void shouldRejectContradictoryResult() {
        assertThatThrownBy(() -> new ArtifactResult(Path.of("page.html"), "ERROR", "bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // [测试意图] 验证只有 HTML 或截图成功才算可用快照，PDF 单独成功不够。
    @Test
    void shouldUseHtmlOrScreenshotAsUsableSnapshotRule() {
        ArtifactResult failed = ArtifactResult.failed("ERROR", "failed");
        ArtifactResult pdf = ArtifactResult.succeeded(Path.of("page.pdf"));

        assertThat(new BrowserCaptureArtifacts(failed, pdf, failed).hasUsableSnapshot()).isFalse();
        assertThat(new BrowserCaptureArtifacts(ArtifactResult.succeeded(Path.of("page.html")), pdf, failed)
                .hasUsableSnapshot()).isTrue();
        assertThat(new BrowserCaptureArtifacts(failed, pdf, ArtifactResult.succeeded(Path.of("shot.png")))
                .hasUsableSnapshot()).isTrue();
    }
}
