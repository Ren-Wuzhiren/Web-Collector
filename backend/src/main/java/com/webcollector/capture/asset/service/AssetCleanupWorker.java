package com.webcollector.capture.asset.service;

import com.webcollector.capture.asset.entity.AssetCleanupJob;
import com.webcollector.capture.asset.storage.AssetStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class AssetCleanupWorker {
    private static final Logger logger = LoggerFactory.getLogger(AssetCleanupWorker.class);

    private final AssetCleanupService cleanupService;
    private final AssetStorage storage;

    public AssetCleanupWorker(AssetCleanupService cleanupService, AssetStorage storage) {
        this.cleanupService = cleanupService;
        this.storage = storage;
    }

    @Scheduled(fixedDelayString = "${app.asset.cleanup.poll-interval-ms:5000}")
    public void processDueJobs() {
        for (int processed = 0; processed < 50; processed++) {
            AssetCleanupJob job = cleanupService.claimNext();
            if (job == null) {
                return;
            }
            try {
                storage.delete(job.getStoragePath());
                cleanupService.complete(job.getId());
            } catch (IOException | RuntimeException failure) {
                cleanupService.retry(job, failure);
                logger.warn("Asset cleanup job {} failed; it will be retried", job.getId(), failure);
            }
        }
    }
}
