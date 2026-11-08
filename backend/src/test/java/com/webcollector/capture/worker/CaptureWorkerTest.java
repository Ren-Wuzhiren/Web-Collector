package com.webcollector.capture.worker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.webcollector.capture.asset.entity.AssetFormat;
import com.webcollector.capture.asset.service.AssetRegistrationService;
import com.webcollector.capture.browser.BrowserCaptureArtifacts;
import com.webcollector.capture.browser.BrowserHtmlCapture;
import com.webcollector.capture.browser.CaptureFormat;
import com.webcollector.capture.browser.CaptureFormatResult;
import com.webcollector.capture.content.ReadableContentPersistenceService;
import com.webcollector.capture.dto.CaptureJobSummary;
import com.webcollector.capture.entity.CaptureJobStatus;
import com.webcollector.capture.service.CaptureJobService;
import com.webcollector.link.entity.Link;
import com.webcollector.link.mapper.LinkMapper;
import com.webcollector.support.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptureWorkerTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-02T00:00:00Z"),
            ZoneOffset.UTC
    );

    @TempDir
    Path tempDirectory;

    private CaptureJobService captureJobService;
    private LinkMapper linkMapper;
    private BrowserHtmlCapture browserHtmlCapture;
    private AssetRegistrationService assetRegistrationService;
    private ReadableContentPersistenceService persistenceService;
    private CaptureWorker worker;

    @BeforeEach
    void setUp() throws IOException {
        captureJobService = org.mockito.Mockito.mock(CaptureJobService.class);
        linkMapper = org.mockito.Mockito.mock(LinkMapper.class);
        browserHtmlCapture = org.mockito.Mockito.mock(BrowserHtmlCapture.class);
        assetRegistrationService = org.mockito.Mockito.mock(AssetRegistrationService.class);
        persistenceService = org.mockito.Mockito.mock(ReadableContentPersistenceService.class);
        worker = newWorker(tempDirectory.resolve("capture-work"));
    }

    // [测试意图] 验证队列为空时正常返回空结果，不读取链接或启动浏览器。
    @Test
    void shouldReturnEmptyWhenQueueHasNoDueJob() {
        when(captureJobService.claimNext(eq("worker-test"), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        assertThat(worker.runOnce()).isEmpty();

        verify(linkMapper, never()).selectById(any());
        verify(browserHtmlCapture, never()).captureSnapshot(any(), any());
    }

    // [测试意图] 验证成功链路抓取三格式、持久化正文、登记资产并完成任务。
    @Test
    void shouldCapturePersistAndCompleteOneJob() throws Exception {
        CaptureJobSummary running = job(CaptureJobStatus.RUNNING);
        CaptureJobSummary succeeded = job(CaptureJobStatus.SUCCEEDED);
        BrowserCaptureArtifacts artifacts = allFormatsSucceeded();
        when(captureJobService.claimNext(eq("worker-test"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(running));
        when(linkMapper.selectById(21L)).thenReturn(link());
        when(browserHtmlCapture.captureSnapshot(
                eq("https://example.com/article"),
                any(Path.class)
        )).thenReturn(artifacts);
        when(captureJobService.complete(11L)).thenReturn(succeeded);

        Optional<CaptureJobSummary> result;

        try (LogCapture logs = LogCapture.attach(CaptureWorker.class)) {
            result = worker.runOnce();

            ILoggingEvent summary = onlyEvent(
                    logs,
                    "event=capture.job.finished"
            );
            assertThat(summary.getLevel()).isEqualTo(Level.INFO);
            assertThat(summary.getThrowableProxy()).isNull();
            assertThat(summary.getFormattedMessage())
                    .contains(
                            "workerId=worker-test",
                            "jobId=11",
                            "linkId=21",
                            "attempt=1",
                            "status=SUCCEEDED",
                            "html=1",
                            "pdf=1",
                            "screenshot=1",
                            "cleanup=SUCCEEDED",
                            "errorCode=NONE"
                    )
                    .doesNotContain(
                            "https://example.com/article",
                            tempDirectory.toString()
                    );
        }

        assertThat(result).contains(succeeded);
        verify(persistenceService).persist(21L, artifacts.html().path());
        verify(assetRegistrationService).register(11L, AssetFormat.HTML, artifacts.html().path());
        verify(assetRegistrationService).register(11L, AssetFormat.PDF, artifacts.pdf().path());
        verify(assetRegistrationService).register(
                11L,
                AssetFormat.SCREENSHOT,
                artifacts.screenshot().path()
        );
        verify(captureJobService).complete(11L);
        verify(captureJobService, never()).fail(any(), any(), any());
    }

    // [测试意图] 验证链接已删除时使用专用错误码失败收尾且不尝试抓取。
    @Test
    void shouldFailWithSpecificCodeWhenLinkNoLongerExists() {
        CaptureJobSummary running = job(CaptureJobStatus.RUNNING);
        CaptureJobSummary pending = job(CaptureJobStatus.PENDING);
        when(captureJobService.claimNext(eq("worker-test"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(running));
        when(linkMapper.selectById(21L)).thenReturn(null);
        when(captureJobService.fail(11L, "CAPTURE_LINK_NOT_FOUND", "任务关联的链接不存在"))
                .thenReturn(pending);

        try (LogCapture logs = LogCapture.attach(CaptureWorker.class)) {
            assertThat(worker.runOnce()).contains(pending);

            ILoggingEvent summary = onlyEvent(
                    logs,
                    "event=capture.job.finished"
            );
            assertThat(summary.getLevel()).isEqualTo(Level.WARN);
            assertThat(summary.getFormattedMessage())
                    .contains(
                            "status=PENDING",
                            "html=0",
                            "pdf=0",
                            "screenshot=0",
                            "cleanup=NOT_REQUIRED",
                            "errorCode=CAPTURE_LINK_NOT_FOUND"
                    )
                    .doesNotContain("https://example.com/article");
        }

        verify(browserHtmlCapture, never()).captureSnapshot(any(), any());
        verify(persistenceService, never()).persist(any(), any());
        verify(assetRegistrationService, never()).register(any(), any(), any());
    }

    // [测试意图] 验证浏览器抓取失败时任务进入既有失败重试状态机。
    @Test
    void shouldFailJobWhenBrowserCaptureThrows() throws Exception {
        CaptureJobSummary running = job(CaptureJobStatus.RUNNING);
        CaptureJobSummary pending = job(CaptureJobStatus.PENDING);
        when(captureJobService.claimNext(eq("worker-test"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(running));
        when(linkMapper.selectById(21L)).thenReturn(link());
        when(captureJobService.fail(eq(11L), eq("CAPTURE_EXECUTION_FAILED"), any()))
                .thenReturn(pending);
        when(browserHtmlCapture.captureSnapshot(eq("https://example.com/article"), any()))
                .thenThrow(new IllegalStateException("capture failed"));

        try (LogCapture logs = LogCapture.attach(CaptureWorker.class)) {
            assertThat(worker.runOnce()).contains(pending);

            ILoggingEvent summary = onlyEvent(
                    logs,
                    "event=capture.job.finished"
            );
            assertThat(summary.getLevel()).isEqualTo(Level.WARN);
            assertThat(summary.getThrowableProxy()).isNull();
            assertThat(summary.getFormattedMessage())
                    .contains(
                            "status=PENDING",
                            "cleanup=SUCCEEDED",
                            "errorCode=CAPTURE_EXECUTION_FAILED"
                    )
                    .doesNotContain(
                            "capture failed",
                            "https://example.com/article",
                            tempDirectory.toString()
                    );
        }

        verify(persistenceService, never()).persist(any(), any());
        verify(assetRegistrationService, never()).register(any(), any(), any());
        verify(captureJobService, never()).complete(11L);
    }

    // [测试意图] 验证正文持久化失败会失败收尾并删除临时 HTML。
    @Test
    void shouldFailJobWhenReadableContentPersistenceThrows() throws Exception {
        CaptureJobSummary running = job(CaptureJobStatus.RUNNING);
        CaptureJobSummary pending = job(CaptureJobStatus.PENDING);
        when(captureJobService.claimNext(eq("worker-test"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(running));
        when(linkMapper.selectById(21L)).thenReturn(link());
        when(captureJobService.fail(eq(11L), eq("CAPTURE_EXECUTION_FAILED"), any()))
                .thenReturn(pending);
        BrowserCaptureArtifacts htmlOnly = htmlOnlySucceeded();
        when(browserHtmlCapture.captureSnapshot(eq("https://example.com/article"), any()))
                .thenReturn(htmlOnly);
        org.mockito.Mockito.doThrow(new IllegalStateException("database failed"))
                .when(persistenceService).persist(eq(21L), any());

        assertThat(worker.runOnce()).contains(pending);

        verify(persistenceService).persist(21L, htmlOnly.html().path());
        verify(assetRegistrationService, never()).register(any(), any(), any());
        verify(captureJobService, never()).complete(11L);
    }

    // [测试意图] 验证 complete 自身失败时不会再错误调用 fail。
    @Test
    void shouldNotFailAgainWhenCompletionTransitionThrows() throws Exception {
        CaptureJobSummary running = job(CaptureJobStatus.RUNNING);
        when(captureJobService.claimNext(eq("worker-test"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(running));
        when(linkMapper.selectById(21L)).thenReturn(link());
        BrowserCaptureArtifacts htmlOnly = htmlOnlySucceeded();
        when(browserHtmlCapture.captureSnapshot(eq("https://example.com/article"), any()))
                .thenReturn(htmlOnly);
        org.mockito.Mockito.doThrow(new IllegalStateException("transition failed"))
                .when(captureJobService).complete(11L);

        try (LogCapture logs = LogCapture.attach(CaptureWorker.class)) {
            assertThatThrownBy(() -> worker.runOnce())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("transition failed");

            ILoggingEvent summary = onlyEvent(
                    logs,
                    "event=capture.job.finished"
            );
            assertThat(summary.getLevel()).isEqualTo(Level.WARN);
            assertThat(summary.getThrowableProxy()).isNull();
            assertThat(summary.getFormattedMessage())
                    .contains(
                            "status=UNKNOWN",
                            "errorCode=STATE_TRANSITION_FAILED"
                    )
                    .doesNotContain("transition failed");
        }

        verify(captureJobService, never()).fail(any(), any(), any());
    }

    // [测试意图] 验证临时目录清理失败只记录问题，不覆盖已完成的业务状态。
    @Test
    void shouldNotChangeSuccessfulResultWhenCleanupFails() throws Exception {
        CaptureJobSummary running = job(CaptureJobStatus.RUNNING);
        CaptureJobSummary succeeded = job(CaptureJobStatus.SUCCEEDED);
        when(captureJobService.claimNext(eq("worker-test"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(running));
        when(linkMapper.selectById(21L)).thenReturn(link());
        BrowserCaptureArtifacts htmlOnly = htmlOnlySucceeded();
        when(browserHtmlCapture.captureSnapshot(eq("https://example.com/article"), any()))
                .thenReturn(htmlOnly);
        when(captureJobService.complete(11L)).thenReturn(succeeded);
        CaptureWorker workerWithCleanupFailure = spy(worker);
        doThrow(new IOException("cleanup failed"))
                .when(workerWithCleanupFailure).cleanupWorkDirectory(any(Path.class));

        try (LogCapture logs = LogCapture.attach(CaptureWorker.class)) {
            assertThat(workerWithCleanupFailure.runOnce()).contains(succeeded);

            ILoggingEvent summary = onlyEvent(
                    logs,
                    "event=capture.job.finished"
            );
            assertThat(summary.getLevel()).isEqualTo(Level.WARN);
            assertThat(summary.getFormattedMessage())
                    .contains(
                            "status=SUCCEEDED",
                            "cleanup=FAILED",
                            "errorCode=NONE"
                    )
                    .doesNotContain("cleanup failed", tempDirectory.toString());
        }

        verify(captureJobService).complete(11L);
        verify(captureJobService, never()).fail(any(), any(), any());
    }

    // [测试意图] 验证 Worker 启动时创建 work root，且拒绝清理根目录之外的路径。
    @Test
    void shouldCreateWorkRootAndRejectCleanupOutsideIt() throws IOException {
        Path root = tempDirectory.resolve("created-on-start");
        CaptureWorker createdWorker = newWorker(root);

        assertThat(Files.isDirectory(root)).isTrue();
        assertThatThrownBy(() -> createdWorker.cleanupWorkDirectory(tempDirectory))
                .isInstanceOf(IOException.class);
    }

    // [测试意图] 验证空队列不产生周期性采集摘要噪音。
    @Test
    void shouldNotLogJobSummaryWhenQueueIsEmpty() {
        when(captureJobService.claimNext(eq("worker-test"), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        try (LogCapture logs = LogCapture.attach(CaptureWorker.class)) {
            assertThat(worker.runOnce()).isEmpty();

            assertThat(logs.containing("event=capture.job.finished")).isEmpty();
        }
    }

    private ILoggingEvent onlyEvent(LogCapture logs, String eventName) {
        assertThat(logs.containing(eventName)).hasSize(1);
        return logs.containing(eventName).get(0);
    }

    private CaptureWorker newWorker(Path workRoot) throws IOException {
        return new CaptureWorker(
                captureJobService,
                linkMapper,
                browserHtmlCapture,
                assetRegistrationService,
                persistenceService,
                CLOCK,
                "worker-test",
                120,
                workRoot.toString()
        );
    }

    private static CaptureJobSummary job(CaptureJobStatus status) {
        return new CaptureJobSummary(
                11L,
                21L,
                status,
                1,
                Instant.parse("2026-11-02T00:00:00Z")
        );
    }

    private static Link link() {
        Link link = new Link();
        link.setId(21L);
        link.setOriginalUrl("https://example.com/article");
        return link;
    }

    private BrowserCaptureArtifacts allFormatsSucceeded() {
        Path directory = tempDirectory.resolve("artifacts");
        return new BrowserCaptureArtifacts(
                CaptureFormatResult.success(CaptureFormat.HTML, directory.resolve("page.html")),
                CaptureFormatResult.success(CaptureFormat.PDF, directory.resolve("page.pdf")),
                CaptureFormatResult.success(
                        CaptureFormat.SCREENSHOT,
                        directory.resolve("screenshot.png")
                )
        );
    }

    private BrowserCaptureArtifacts htmlOnlySucceeded() {
        return new BrowserCaptureArtifacts(
                CaptureFormatResult.success(
                        CaptureFormat.HTML,
                        tempDirectory.resolve("artifacts/page.html")
                ),
                CaptureFormatResult.failed(CaptureFormat.PDF, "pdf failed"),
                CaptureFormatResult.failed(CaptureFormat.SCREENSHOT, "png failed")
        );
    }
}
