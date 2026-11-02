package com.webcollector.capture.asset.service;

import com.webcollector.capture.asset.entity.Asset;
import com.webcollector.capture.asset.entity.AssetFormat;
import com.webcollector.capture.asset.mapper.AssetMapper;
import com.webcollector.capture.asset.storage.AssetStorage;
import com.webcollector.capture.entity.CaptureJob;
import com.webcollector.capture.mapper.CaptureJobMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetRegistrationServiceTest {
    @Mock
    private AssetMapper assetMapper;
    @Mock
    private CaptureJobMapper captureJobMapper;
    @Mock
    private AssetCleanupService cleanupService;

    private AssetStorage storage;
    private Path captureWorkRoot;
    private AssetRegistrationService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        storage = new AssetStorage(tempDir.resolve("assets").toString());
        captureWorkRoot = tempDir.resolve("capture-work");
        service = new AssetRegistrationService(
                assetMapper,
                captureJobMapper,
                storage,
                cleanupService,
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC),
                captureWorkRoot.toString()
        );
        CaptureJob job = new CaptureJob();
        job.setId(42L);
        job.setLinkId(17L);
        lenient().when(captureJobMapper.selectById(42L)).thenReturn(job);
    }

    // [测试意图] 验证登记会从任务派生链接，并按文件字节生成大小、MIME 和 SHA-256。
    @Test
    void shouldRegisterFileMetadataUsingLinkFromCaptureJob(@TempDir Path tempDir)
            throws Exception {
        Path file = storage.getRoot().resolve("page.html");
        Files.createDirectories(file.getParent());
        byte[] content = "<html>收藏</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, content);

        Asset asset = service.register(42L, AssetFormat.HTML, file);

        assertThat(asset.getLinkId()).isEqualTo(17L);
        assertThat(asset.getCaptureJobId()).isEqualTo(42L);
        assertThat(asset.getFormat()).isEqualTo(AssetFormat.HTML);
        assertThat(asset.getStoragePath()).endsWith(".html");
        assertThat(storage.resolve(asset.getStoragePath())).exists();
        assertThat(asset.getMimeType()).isEqualTo("text/html");
        assertThat(asset.getFileSizeBytes()).isEqualTo(content.length);
        assertThat(asset.getSha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content)));
        assertThat(asset.getCreatedAt()).isEqualTo(
                java.time.LocalDateTime.of(2026, 11, 1, 0, 0));
        verify(assetMapper).insert(asset);
    }

    // [测试意图] 验证不存在的任务不能产生资产记录。
    @Test
    void shouldRejectUnknownCaptureJob(@TempDir Path tempDir) {
        when(captureJobMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.register(
                999L, AssetFormat.HTML, tempDir.resolve("missing.html")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("抓取任务不存在");
        verify(assetMapper, never()).insert(any(Asset.class));
    }

    // [测试意图] 验证缺失文件拒绝登记，而抓取工作目录中的外部文件会被复制入库。
    @Test
    void shouldRejectMissingFileAndCopyExternalCaptureFile(@TempDir Path tempDir)
            throws IOException {
        Path root = storage.getRoot();
        Path outside = captureWorkRoot.resolve("outside.html");
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "outside");

        assertThatThrownBy(() -> service.register(
                42L, AssetFormat.HTML, root.resolve("missing.html")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("普通文件");
        Asset asset = service.register(42L, AssetFormat.HTML, outside);

        assertThat(storage.resolve(asset.getStoragePath())).hasContent("outside");
        assertThat(outside).hasContent("outside");
        verify(assetMapper).insert(asset);
    }

    // [测试意图] 验证存储根与抓取工作目录之外的文件仍不能登记。
    @Test
    void shouldRejectExternalFileOutsideCaptureWorkRoot(@TempDir Path tempDir)
            throws IOException {
        Path outside = tempDir.resolve("private.html");
        Files.writeString(outside, "private");

        assertThatThrownBy(() -> service.register(42L, AssetFormat.HTML, outside))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("抓取工作目录");
        verify(assetMapper, never()).insert(any(Asset.class));
    }

    // [测试意图] 验证同一任务同一格式重复登记时直接复用已有资产。
    @Test
    void shouldReuseExistingAssetForSameJobAndFormat(@TempDir Path tempDir) throws Exception {
        Asset existing = new Asset();
        existing.setId(99L);
        when(assetMapper.findByJobAndFormat(42L, AssetFormat.HTML))
                .thenReturn(java.util.Optional.of(existing));
        Path source = captureWorkRoot.resolve("external.html");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "external");

        assertThat(service.register(42L, AssetFormat.HTML, source)).isSameAs(existing);

        verify(assetMapper, never()).insert(any(Asset.class));
    }

    // [测试意图] 验证数据库登记失败时会补偿删除已原子落盘的正式文件。
    @Test
    void shouldDeleteStoredFileWhenDatabaseInsertFails(@TempDir Path tempDir) throws IOException {
        Path file = storage.getRoot().resolve("page.html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<html>rollback</html>");
        org.mockito.Mockito.doThrow(new IllegalStateException("database failure"))
                .when(assetMapper).insert(any(Asset.class));

        assertThatThrownBy(() -> service.register(42L, AssetFormat.HTML, file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database failure");
        assertThat(file).exists();
        assertThat(Files.readString(file)).isEqualTo("<html>rollback</html>");
        try (var files = Files.list(file.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".asset-") || name.endsWith(".html"))
                    .count()).isEqualTo(1);
        }
    }

    // [测试意图] 验证指向存储根目录外的符号链接不能绕过根目录校验。
    @Test
    void shouldRejectSymlinkResolvingOutsideStorageRoot(@TempDir Path tempDir)
            throws IOException {
        Path root = storage.getRoot();
        Files.createDirectories(root);
        Path outside = tempDir.resolve("outside.html");
        Files.writeString(outside, "outside");
        Path link = root.resolve("linked.html");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    false, "当前文件系统不支持创建符号链接");
        }

        assertThatThrownBy(() -> service.register(42L, AssetFormat.HTML, link))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("under storage root");
        verify(assetMapper, never()).insert(any(Asset.class));
    }
}
