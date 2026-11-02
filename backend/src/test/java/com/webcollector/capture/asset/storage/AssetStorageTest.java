package com.webcollector.capture.asset.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssetStorageTest {
    // [测试意图] 验证文件只在原子移动后出现，并且返回元数据对应实际内容。
    @Test
    void shouldWriteFileAndReturnItsSizeAndSha256(@TempDir Path tempDir) throws Exception {
        AssetStorage storage = new AssetStorage(tempDir.toString());
        byte[] content = "<html>asset</html>".getBytes(StandardCharsets.UTF_8);

        AssetStorage.StoredFile stored = storage.write(
                new ByteArrayInputStream(content), ".html");

        Path target = storage.resolve(stored.key());
        assertThat(target).exists();
        assertThat(Files.size(target)).isEqualTo(content.length);
        assertThat(stored.size()).isEqualTo(content.length);
        assertThat(stored.sha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content)));
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".asset-")).toList()).isEmpty();
        }
    }

    // [测试意图] 验证登记采用原子提升，事务回滚时可将文件恢复到原始暂存路径。
    @Test
    void shouldPromoteAndRestoreStagedFileAtomically(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.resolve("assets");
        AssetStorage storage = new AssetStorage(root.toString());
        Path source = root.resolve("staging/page.html");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "<html>staged</html>");
        String originalKey = storage.relativeKey(source);

        AssetStorage.StoredFile promoted = storage.promote(source, ".html");

        assertThat(source).doesNotExist();
        assertThat(storage.resolve(promoted.key())).exists();
        storage.restore(promoted.key(), originalKey);
        assertThat(source).exists();
        assertThat(storage.resolve(promoted.key())).doesNotExist();
    }

    // [测试意图] 验证抓取工作目录中的外部文件会被复制进资产根且原文件保留。
    @Test
    void shouldCopyExternalFileWithoutRemovingSource(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.resolve("assets");
        AssetStorage storage = new AssetStorage(root.toString());
        Path source = tempDir.resolve("capture-work/page.html");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "<html>external</html>");

        AssetStorage.StoredFile stored = storage.copyExternal(source, ".html");

        assertThat(source).exists();
        assertThat(storage.resolve(stored.key())).hasContent("<html>external</html>");
    }

    // [测试意图] 验证不能通过 key 穿越到存储根目录之外。
    @Test
    void shouldRejectPathTraversal(@TempDir Path tempDir) throws Exception {
        AssetStorage storage = new AssetStorage(tempDir.toString());

        assertThatThrownBy(() -> storage.resolve("../outside.html"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // [测试意图] 验证读文件时拒绝解析到根目录外的符号链接。
    @Test
    void shouldRejectSymlinkOutsideRoot(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.resolve("assets");
        Files.createDirectories(root);
        Path outside = tempDir.resolve("outside.html");
        Files.writeString(outside, "outside");
        Path link = root.resolve("linked.html");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "符号链接当前不可用");
        } catch (java.io.IOException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "符号链接当前不可用");
        }
        AssetStorage storage = new AssetStorage(root.toString());

        assertThatThrownBy(() -> storage.open("linked.html"))
                .isInstanceOf(java.io.IOException.class);
    }

    // [测试意图] 验证删除操作不会沿着根目录内的符号链接删除根目录外文件。
    @Test
    void shouldNotDeleteFileThroughSymlinkedParent(@TempDir Path tempDir) throws Exception {
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
        } catch (IOException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "符号链接当前不可用");
        }
        AssetStorage storage = new AssetStorage(root.toString());

        assertThatThrownBy(() -> storage.delete("escape/victim.html"))
                .isInstanceOf(IOException.class);
        assertThat(victim).exists();
    }

    // [测试意图] 验证不支持的扩展名会在创建任何文件之前失败。
    @Test
    void shouldRejectUnsupportedExtensionBeforeWriting(@TempDir Path tempDir) throws Exception {
        AssetStorage storage = new AssetStorage(tempDir.toString());

        assertThatThrownBy(() -> storage.write(
                new ByteArrayInputStream(new byte[]{1}), ".exe"))
                .isInstanceOf(IllegalArgumentException.class);
        try (var files = Files.list(tempDir)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    // [测试意图] 验证文件系统不支持原子移动时不会留下正式文件或临时文件。
    @Test
    void shouldRemoveTemporaryFileWhenAtomicMoveIsUnsupported(@TempDir Path tempDir)
            throws Exception {
        AssetStorage storage = new AssetStorage(tempDir.toString()) {
            @Override
            protected void moveAtomically(Path source, Path target) throws IOException {
                throw new java.nio.file.AtomicMoveNotSupportedException(
                        source.toString(), target.toString(), "test filesystem");
            }
        };

        assertThatThrownBy(() -> storage.write(
                new ByteArrayInputStream(new byte[]{1, 2, 3}), ".png"))
                .isInstanceOf(IOException.class);
        try (var files = Files.list(tempDir)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    // [测试意图] 验证源流中断时删除未完成的临时文件且不生成正式文件。
    @Test
    void shouldRemoveTemporaryFileWhenInputFails(@TempDir Path tempDir) throws Exception {
        AssetStorage storage = new AssetStorage(tempDir.toString());
        InputStream broken = new InputStream() {
            private int reads;

            @Override
            public int read() throws IOException {
                if (reads++ == 0) {
                    return 0x41;
                }
                throw new IOException("source failed");
            }
        };

        assertThatThrownBy(() -> storage.write(broken, ".pdf"))
                .isInstanceOf(IOException.class)
                .hasMessage("source failed");
        try (var files = Files.list(tempDir)) {
            assertThat(files.toList()).isEmpty();
        }
    }
}
