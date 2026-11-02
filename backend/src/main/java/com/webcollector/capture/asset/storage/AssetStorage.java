package com.webcollector.capture.asset.storage;


import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AssetStorage {
    private final Path root;

    public AssetStorage(@Value("${app.storage.root:./data/assets}") String root) throws IOException {
        Path configuredRoot = Path.of(root).toAbsolutePath().normalize();
        Files.createDirectories(configuredRoot);
        this.root = configuredRoot.toRealPath();
    }

    public StoredFile write(InputStream input, String extension) throws IOException {
        if (input == null || extension == null || !extension.matches("\\.(html|pdf|png)")) {
            throw new IllegalArgumentException("资产输入或扩展名不合法");
        }
        String key = UUID.randomUUID() + extension;
        Path target = resolve(key);
        Path temp = Files.createTempFile(root, ".asset-", ".tmp");

        try {
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            StoredFile metadata = metadata(key, temp);

            moveAtomically(temp, target);
            return metadata;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    public StoredFile promote(Path source, String extension) throws IOException {
        if (extension == null || !extension.matches("\\.(html|pdf|png)")) {
            throw new IllegalArgumentException("资产扩展名不合法");
        }
        Path realSource = source.toRealPath();
        relativeKey(realSource);
        if (!Files.isRegularFile(realSource)) {
            throw new IllegalArgumentException("快照路径不是普通文件");
        }
        String key = UUID.randomUUID() + extension;
        Path target = resolve(key);
        StoredFile metadata = metadata(key, realSource);
        moveAtomically(realSource, target);
        return metadata;
    }

    public StoredFile copyExternal(Path source, String extension) throws IOException {
        Path realSource = source.toRealPath();
        if (!Files.isRegularFile(realSource)) {
            throw new IllegalArgumentException("快照路径不是普通文件");
        }
        try (InputStream input = Files.newInputStream(realSource)) {
            return write(input, extension);
        }
    }

    public void restore(String promotedKey, String originalKey) throws IOException {
        Path source = resolve(promotedKey);
        Path target = resolve(originalKey);
        Path parent = target.getParent();
        if (parent == null || !parent.toRealPath().startsWith(root)) {
            throw new IOException("Asset restore path resolves outside storage root");
        }
        moveAtomically(source, target);
    }

    public Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Asset key is required");
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Invalid asset key");
        }
        return path;
    }

    public String relativeKey(Path file) throws IOException {
        Path realFile = file.toRealPath();
        if (!realFile.startsWith(root) || !Files.isRegularFile(realFile)) {
            throw new IllegalArgumentException("Asset file must be a regular file under storage root");
        }
        return root.relativize(realFile).toString().replace('\\', '/');
    }

    public InputStream open(String key) throws IOException {
        Path path = resolve(key).toRealPath();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            throw new IOException("Asset is not a regular file under storage root");
        }
        return Files.newInputStream(path, StandardOpenOption.READ);
    }

    public void delete(String key) throws IOException {
        Path path = resolve(key);
        Path parent = path.getParent();
        if (parent != null && Files.exists(parent)) {
            Path realParent = parent.toRealPath();
            if (!realParent.startsWith(root)) {
                throw new IOException("Asset parent resolves outside storage root");
            }
            path = realParent.resolve(path.getFileName());
        }
        Files.deleteIfExists(path);
    }

    public Path getRoot() {
        return root;
    }

    protected void moveAtomically(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.FileAlreadyExistsException(target.toString());
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            throw new IOException("Asset filesystem does not support atomic move", exception);
        }
    }

    private StoredFile metadata(String key, Path file) throws IOException {
        return new StoredFile(key, Files.size(file), sha256(file));
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream input = Files.newInputStream(file)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JRE does not provide SHA-256", exception);
        }
    }

    public record StoredFile(String key, long size, String sha256) {}
}
