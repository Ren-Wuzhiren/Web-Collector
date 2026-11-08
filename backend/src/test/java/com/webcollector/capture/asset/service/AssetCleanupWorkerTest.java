package com.webcollector.capture.asset.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.webcollector.capture.asset.entity.AssetCleanupJob;
import com.webcollector.capture.asset.storage.AssetStorage;
import com.webcollector.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class AssetCleanupWorkerTest {
    // [测试意图] 验证目标文件已不存在时按幂等成功处理并移除清理记录。
    @Test
    void shouldTreatMissingFileAsCompleted(@TempDir Path tempDir) throws Exception {
        AssetCleanupService cleanupService = mock(AssetCleanupService.class);
        AssetStorage storage = new AssetStorage(tempDir.toString());
        AssetCleanupJob job = new AssetCleanupJob();
        job.setId(4L);
        job.setStoragePath("already-removed.html");
        when(cleanupService.claimNext()).thenReturn(job, (AssetCleanupJob) null);
        AssetCleanupWorker worker = new AssetCleanupWorker(cleanupService, storage);

        try (LogCapture logs = LogCapture.attach(AssetCleanupWorker.class)) {
            worker.processDueJobs();

            ILoggingEvent summary = onlyEvent(
                    logs,
                    "event=asset.cleanup.batch.finished"
            );
            assertThat(summary.getLevel()).isEqualTo(Level.INFO);
            assertThat(summary.getThrowableProxy()).isNull();
            assertThat(summary.getFormattedMessage())
                    .contains("processed=1", "completed=1", "retried=0")
                    .containsPattern("durationMs=\\d+");
        }

        verify(cleanupService).complete(4L);
    }

    // [测试意图] 验证路径逃逸风险导致的删除失败会保留并安排重试，不触碰外部文件。
    @Test
    void shouldRetryWhenParentResolvesOutsideRoot(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.resolve("assets");
        Files.createDirectories(root);
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Path victim = outside.resolve("victim.html");
        Files.writeString(victim, "keep");
        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
        } catch (UnsupportedOperationException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "符号链接当前不可用");
        } catch (java.io.IOException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "符号链接当前不可用");
        }
        AssetCleanupService cleanupService = mock(AssetCleanupService.class);
        AssetCleanupJob job = new AssetCleanupJob();
        job.setId(9L);
        job.setStoragePath("escape/victim.html");
        job.setAttemptCount(1L);
        when(cleanupService.claimNext()).thenReturn(job, (AssetCleanupJob) null);
        AssetCleanupWorker worker = new AssetCleanupWorker(
                cleanupService, new AssetStorage(root.toString()));

        try (LogCapture logs = LogCapture.attach(AssetCleanupWorker.class)) {
            worker.processDueJobs();

            ILoggingEvent summary = onlyEvent(
                    logs,
                    "event=asset.cleanup.batch.finished"
            );
            assertThat(summary.getLevel()).isEqualTo(Level.WARN);
            assertThat(summary.getThrowableProxy()).isNull();
            assertThat(summary.getFormattedMessage())
                    .contains("processed=1", "completed=0", "retried=1")
                    .doesNotContain(root.toString(), victim.toString(), "victim.html");
        }
        verify(cleanupService).retry(job, org.mockito.ArgumentMatchers.any(Exception.class));
        org.assertj.core.api.Assertions.assertThat(victim).exists();
    }

    // [测试意图] 验证同一批完成与重试的任务只产生一条汇总日志。
    @Test
    void shouldAggregateCompletedAndRetriedCounts() throws Exception {
        AssetCleanupJob completed = job(1L, "completed.html");
        AssetCleanupJob retried = job(2L, "retry.html");
        AssetCleanupService cleanupService = mock(AssetCleanupService.class);
        AssetStorage storage = mock(AssetStorage.class);
        org.mockito.Mockito.doThrow(new java.io.IOException(
                        "sensitive /private/path/retry.html"
                ))
                .when(storage).delete("retry.html");
        when(cleanupService.claimNext()).thenReturn(
                completed,
                retried,
                (AssetCleanupJob) null
        );
        AssetCleanupWorker worker = new AssetCleanupWorker(
                cleanupService,
                storage
        );

        try (LogCapture logs = LogCapture.attach(AssetCleanupWorker.class)) {
            worker.processDueJobs();

            ILoggingEvent summary = onlyEvent(
                    logs,
                    "event=asset.cleanup.batch.finished"
            );
            assertThat(summary.getLevel()).isEqualTo(Level.WARN);
            assertThat(summary.getFormattedMessage())
                    .contains("processed=2", "completed=1", "retried=1")
                    .doesNotContain(
                            "/private/path",
                            "retry.html",
                            "completed.html"
                    );
        }
        verify(cleanupService).complete(1L);
        verify(cleanupService).retry(
                org.mockito.ArgumentMatchers.eq(retried),
                org.mockito.ArgumentMatchers.any(Exception.class)
        );
    }

    // [测试意图] 验证空清理队列不输出周期性批次摘要。
    @Test
    void shouldNotLogBatchSummaryWhenQueueIsEmpty() {
        AssetCleanupService cleanupService = mock(AssetCleanupService.class);
        when(cleanupService.claimNext()).thenReturn(null);
        AssetCleanupWorker worker = new AssetCleanupWorker(
                cleanupService,
                mock(AssetStorage.class)
        );

        try (LogCapture logs = LogCapture.attach(AssetCleanupWorker.class)) {
            worker.processDueJobs();

            assertThat(
                    logs.containing("event=asset.cleanup.batch.finished")
            ).isEmpty();
        }
    }

    private AssetCleanupJob job(long id, String storagePath) {
        AssetCleanupJob job = new AssetCleanupJob();
        job.setId(id);
        job.setStoragePath(storagePath);
        job.setAttemptCount(1L);
        return job;
    }

    private ILoggingEvent onlyEvent(LogCapture logs, String eventName) {
        assertThat(logs.containing(eventName)).hasSize(1);
        return logs.containing(eventName).get(0);
    }
}
