package com.webcollector.capture.asset.service;

import com.webcollector.capture.asset.entity.AssetCleanupJob;
import com.webcollector.capture.asset.mapper.AssetCleanupJobMapper;
import com.webcollector.capture.asset.mapper.AssetMapper;
import com.webcollector.capture.asset.storage.AssetStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetCleanupServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 0, 0);

    private AssetCleanupJobMapper cleanupJobMapper;
    private AssetMapper assetMapper;
    private AssetStorage storage;
    private AssetCleanupService service;

    @BeforeEach
    void setUp() {
        cleanupJobMapper = mock(AssetCleanupJobMapper.class);
        assetMapper = mock(AssetMapper.class);
        storage = mock(AssetStorage.class);
        service = new AssetCleanupService(
                cleanupJobMapper,
                assetMapper,
                storage,
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC));
    }

    // [测试意图] 验证链接删除前查询到的每个资产路径都会进入独立清理队列。
    @Test
    void shouldEnqueueAllAssetPathsForLink() {
        when(assetMapper.selectStoragePathsByLink(23L)).thenReturn(
                java.util.List.of("a.html", "b.pdf"));
        when(storage.resolve("a.html")).thenReturn(Path.of("/assets/a.html"));
        when(storage.resolve("b.pdf")).thenReturn(Path.of("/assets/b.pdf"));

        service.enqueueForLink(23L);

        verify(cleanupJobMapper).enqueueIfAbsent("a.html", NOW);
        verify(cleanupJobMapper).enqueueIfAbsent("b.pdf", NOW);
    }

    // [测试意图] 验证并发安全领取后返回的尝试次数已包含本次领取。
    @Test
    void shouldIncrementAttemptCountOnClaim() {
        AssetCleanupJob candidate = new AssetCleanupJob();
        candidate.setId(5L);
        candidate.setStoragePath("snapshot.html");
        candidate.setAttemptCount(2L);
        when(cleanupJobMapper.selectNextClaimable(NOW)).thenReturn(candidate);
        when(cleanupJobMapper.claim(5L, NOW, NOW.plusMinutes(2))).thenReturn(1);

        AssetCleanupJob claimed = service.claimNext();

        assertThat(claimed.getAttemptCount()).isEqualTo(3);
    }

    // [测试意图] 验证清理失败使用封顶退避并保留错误，方便后续自动恢复。
    @Test
    void shouldScheduleRetryWithCappedBackoff() {
        AssetCleanupJob job = new AssetCleanupJob();
        job.setId(8L);
        job.setAttemptCount(14L);

        service.retry(job, new java.io.IOException("disk unavailable"));

        verify(cleanupJobMapper).scheduleRetry(
                8L,
                NOW.plusHours(6),
                "IOException: disk unavailable",
                NOW);
    }
}
