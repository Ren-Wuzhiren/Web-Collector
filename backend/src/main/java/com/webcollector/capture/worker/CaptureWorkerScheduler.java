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
        for (int processed = 0; processed < batchSize; processed++) {
            try {
                if (captureWorker.runOnce().isEmpty()) {
                    return;
                }
            } catch (RuntimeException failure) {
                logger.error(
                        "Capture worker scheduler stopped this batch after an unexpected failure",
                        failure
                );
                return;
            }
        }
    }
}
