package com.webcollector.capture.asset.service;

import com.webcollector.capture.asset.entity.Asset;
import com.webcollector.capture.asset.entity.AssetFormat;
import com.webcollector.capture.asset.mapper.AssetMapper;
import com.webcollector.capture.asset.storage.AssetStorage;
import com.webcollector.capture.entity.CaptureJob;
import com.webcollector.capture.mapper.CaptureJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class AssetRegistrationService {
    private static final Logger logger = LoggerFactory.getLogger(AssetRegistrationService.class);

    private final AssetMapper assetMapper;
    private final CaptureJobMapper captureJobMapper;
    private final AssetStorage storage;
    private final AssetCleanupService cleanupService;
    private final Clock clock;

    public AssetRegistrationService(
            AssetMapper assetMapper,
            CaptureJobMapper captureJobMapper,
            AssetStorage storage,
            AssetCleanupService cleanupService,
            Clock clock
    ) {
        this.assetMapper = assetMapper;
        this.captureJobMapper = captureJobMapper;
        this.storage = storage;
        this.cleanupService = cleanupService;
        this.clock = clock;
    }

    /** 将成功生成的快照提升为受管理资产；数据库失败时补偿删除新文件。 */
    @Transactional
    public Asset register(Long captureJobId, AssetFormat format, Path generatedFile) {
        if (captureJobId == null || format == null || generatedFile == null) {
            throw new IllegalArgumentException("资产登记参数不能为空");
        }

        CaptureJob captureJob = captureJobMapper.selectById(captureJobId);
        if (captureJob == null) {
            throw new IllegalArgumentException("抓取任务不存在: " + captureJobId);
        }

        try {
            Path candidate = generatedFile.toAbsolutePath().normalize();
            if (!Files.isRegularFile(candidate)) {
                throw new IllegalArgumentException("快照路径不是普通文件");
            }
            Path source = candidate.toRealPath();
            String sourceKey = storage.relativeKey(source);
            if (assetMapper.countByStoragePath(sourceKey) > 0) {
                throw new IllegalArgumentException("快照文件已被其他资产引用");
            }
            String mimeType = resolveMimeType(format, source);
            AssetStorage.StoredFile stored = storage.promote(source, extensionFor(format));

            Asset asset = new Asset();
            asset.setLinkId(captureJob.getLinkId());
            asset.setCaptureJobId(captureJobId);
            asset.setFormat(format);
            asset.setStoragePath(stored.key());
            asset.setMimeType(mimeType);
            asset.setFileSizeBytes(stored.size());
            asset.setSha256(stored.sha256());
            asset.setCreatedAt(LocalDateTime.now(clock));

            registerFileCompensation(stored.key(), sourceKey);
            try {
                assetMapper.insert(asset);
            } catch (RuntimeException failure) {
                if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                    restoreOrEnqueue(stored.key(), sourceKey);
                }
                throw failure;
            }
            return asset;
        } catch (IOException exception) {
            throw new IllegalStateException("读取快照文件元数据失败", exception);
        }
    }

    private void registerFileCompensation(String storedKey, String sourceKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    restoreOrEnqueue(storedKey, sourceKey);
                }
            }
        });
    }

    private void restoreOrEnqueue(String storedKey, String sourceKey) {
        try {
            storage.restore(storedKey, sourceKey);
        } catch (IOException | RuntimeException restoreFailure) {
            try {
                cleanupService.enqueueIndependently(storedKey);
            } catch (RuntimeException enqueueFailure) {
                restoreFailure.addSuppressed(enqueueFailure);
                logger.error("Unable to restore or persist cleanup for asset file", restoreFailure);
            }
        }
    }

    private String extensionFor(AssetFormat format) {
        return switch (format) {
            case HTML -> ".html";
            case PDF -> ".pdf";
            case SCREENSHOT -> ".png";
        };
    }

    private String resolveMimeType(AssetFormat format, Path file) throws IOException {
        String detected = Files.probeContentType(file);
        String expected = switch (format) {
            case HTML -> "text/html";
            case PDF -> "application/pdf";
            case SCREENSHOT -> "image/png";
        };
        if (detected != null && !isCompatibleMime(expected, detected)) {
            throw new IllegalArgumentException("文件 MIME 与资产格式不匹配: " + detected);
        }
        return expected;
    }

    private boolean isCompatibleMime(String expected, String detected) {
        return expected.equalsIgnoreCase(detected)
                || (expected.equals("text/html") && detected.equalsIgnoreCase("text/plain"));
    }
}
