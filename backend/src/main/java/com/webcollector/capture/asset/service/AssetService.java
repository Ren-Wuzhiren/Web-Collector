package com.webcollector.capture.asset.service;

import com.webcollector.capture.asset.entity.Asset;
import com.webcollector.capture.asset.entity.AssetFormat;
import com.webcollector.capture.asset.mapper.AssetMapper;
import com.webcollector.capture.asset.storage.AssetStorage;
import com.webcollector.capture.asset.service.AssetCleanupService;
import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;

@Service
public class AssetService {
    private final AssetMapper assetMapper;
    private final AssetStorage storage;
    private final AssetCleanupService cleanupService;

    public AssetService(
            AssetMapper assetMapper,
            AssetStorage storage,
            AssetCleanupService cleanupService
    ) {
        this.assetMapper = assetMapper;
        this.storage = storage;
        this.cleanupService = cleanupService;
    }

    @Transactional(readOnly = true)
    public AssetContent openOwned(long assetId, long userId) {
        Asset asset = assetMapper.findOwnedAsset(assetId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        try {
            return new AssetContent(
                    storage.open(asset.getStoragePath()),
                    asset.getMimeType(),
                    asset.getFileSizeBytes(),
                    asset.getFormat()
            );
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    @Transactional
    public void deleteOwned(long assetId, long userId) {
        Asset asset = assetMapper.findOwnedAsset(assetId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        cleanupService.enqueue(asset.getStoragePath());
        assetMapper.deleteById(assetId);
    }

    public record AssetContent(
            InputStream stream,
            String mimeType,
            long size,
            AssetFormat format
    ) {}
}
