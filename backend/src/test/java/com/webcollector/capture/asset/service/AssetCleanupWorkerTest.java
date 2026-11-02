package com.webcollector.capture.asset.service;

import com.webcollector.capture.asset.entity.AssetCleanupJob;
import com.webcollector.capture.asset.storage.AssetStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

        worker.processDueJobs();

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

        worker.processDueJobs();

        verify(cleanupService).retry(job, org.mockito.ArgumentMatchers.any(Exception.class));
        org.assertj.core.api.Assertions.assertThat(victim).exists();
    }
}
