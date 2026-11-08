package com.webcollector.capture.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls persisted capture jobs and processes a bounded batch per tick. */
@Component
@ConditionalOnProperty(
        name = "app.capture.scheduler-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class CaptureWorkerScheduler {

    private static final Logger logger =
            LoggerFactory.getLogger(CaptureWorkerScheduler.class);

    private final CaptureWorker captureWorker;
    private final int batchSize;

    public CaptureWorkerScheduler(
            CaptureWorker captureWorker,
            @Value("${app.capture.batch-size:10}") int batchSize
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("app.capture.batch-size 必须大于零");
        }
        this.captureWorker = captureWorker;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${app.capture.poll-interval-ms:5000}",
            initialDelayString = "${app.capture.initial-delay-ms:10000}"
    )
    public void processDueJobs() {
        long startedNanos = System.nanoTime();
        int processedCount = 0;
        int succeeded = 0;
        int retried = 0;
        int failed = 0;
        int unexpected = 0;
        String failureType = "NONE";

        try {
            for (int index = 0; index < batchSize; index++) {
                try {
                    var outcome = captureWorker.runOnce();
                    if (outcome.isEmpty()) {
                        return;
                    }

                    processedCount++;
                    switch (outcome.orElseThrow().status()) {
                        case SUCCEEDED -> succeeded++;
                        case PENDING -> retried++;
                        default -> failed++;
                    }
                } catch (RuntimeException failure) {
                    unexpected = 1;
                    failureType = failure.getClass().getSimpleName();
                    break;
                }
            }
        } finally {
            if (processedCount > 0 || unexpected > 0) {
                logBatchSummary(
                        processedCount,
                        succeeded,
                        retried,
                        failed,
                        unexpected,
                        failureType,
                        (System.nanoTime() - startedNanos) / 1_000_000L
                );
            }
        }
    }

    private void logBatchSummary(
            int processed,
            int succeeded,
            int retried,
            int failed,
            int unexpected,
            String failureType,
            long durationMs
    ) {
        String message = "event=capture.batch.finished processed={}"
                + " succeeded={} retried={} failed={} unexpected={}"
                + " failureType={} durationMs={}";

        if (unexpected > 0 || retried > 0 || failed > 0) {
            logger.warn(
                    message,
                    processed,
                    succeeded,
                    retried,
                    failed,
                    unexpected,
                    failureType,
                    durationMs
            );
        } else {
            logger.info(
                    message,
                    processed,
                    succeeded,
                    retried,
                    failed,
                    unexpected,
                    failureType,
                    durationMs
            );
        }
    }
}
