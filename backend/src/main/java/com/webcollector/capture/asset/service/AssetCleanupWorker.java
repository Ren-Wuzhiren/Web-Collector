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
        long startedNanos = System.nanoTime();
        int processedCount = 0;
        int completed = 0;
        int retried = 0;
        String failureType = "NONE";

        for (int index = 0; index < 50; index++) {
            AssetCleanupJob job = cleanupService.claimNext();
            if (job == null) {
                break;
            }
            processedCount++;
            try {
                storage.delete(job.getStoragePath());
                cleanupService.complete(job.getId());
                completed++;
            } catch (IOException | RuntimeException failure) {
                cleanupService.retry(job, failure);
                retried++;
                failureType = failure.getClass().getSimpleName();
            }
        }

        if (processedCount > 0) {
            logBatchSummary(
                    processedCount,
                    completed,
                    retried,
                    failureType,
                    (System.nanoTime() - startedNanos) / 1_000_000L
            );
        }
    }

    private void logBatchSummary(
            int processed,
            int completed,
            int retried,
            String failureType,
            long durationMs
    ) {
        String message = "event=asset.cleanup.batch.finished processed={}"
                + " completed={} retried={} failureType={} durationMs={}";

        if (retried > 0) {
            logger.warn(
                    message,
                    processed,
                    completed,
                    retried,
                    failureType,
                    durationMs
            );
        } else {
            logger.info(
                    message,
                    processed,
                    completed,
                    retried,
                    failureType,
                    durationMs
            );
        }
    }
}
