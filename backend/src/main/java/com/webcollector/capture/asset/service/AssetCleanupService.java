package com.webcollector.capture.asset.service;

import com.webcollector.capture.asset.entity.AssetCleanupJob;
import com.webcollector.capture.asset.mapper.AssetCleanupJobMapper;
import com.webcollector.capture.asset.mapper.AssetMapper;
import com.webcollector.capture.asset.storage.AssetStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
public class AssetCleanupService {
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(5);
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(6);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final AssetCleanupJobMapper cleanupJobMapper;
    private final AssetMapper assetMapper;
    private final AssetStorage storage;
    private final Clock clock;

    public AssetCleanupService(
            AssetCleanupJobMapper cleanupJobMapper,
            AssetMapper assetMapper,
            AssetStorage storage,
            Clock clock
    ) {
        this.cleanupJobMapper = cleanupJobMapper;
        this.assetMapper = assetMapper;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional
    public void enqueueForLink(long linkId) {
        LocalDateTime now = now();
        for (String path : assetMapper.selectStoragePathsByLink(linkId)) {
            storage.resolve(path);
            cleanupJobMapper.enqueueIfAbsent(path, now);
        }
    }

    @Transactional
    public void enqueue(String storagePath) {
        storage.resolve(storagePath);
        cleanupJobMapper.enqueueIfAbsent(storagePath, now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueIndependently(String storagePath) {
        storage.resolve(storagePath);
        cleanupJobMapper.enqueueIfAbsent(storagePath, now());
    }

    @Transactional
    public AssetCleanupJob claimNext() {
        LocalDateTime now = now();
        AssetCleanupJob candidate = cleanupJobMapper.selectNextClaimable(now);
        if (candidate == null) {
            return null;
        }
        int updated = cleanupJobMapper.claim(candidate.getId(), now, now.plus(LEASE));
        if (updated == 1) {
            long attemptCount = candidate.getAttemptCount() == null
                    ? 0L
                    : candidate.getAttemptCount();
            candidate.setAttemptCount(attemptCount + 1L);
        }
        return updated == 1 ? candidate : null;
    }

    @Transactional
    public void complete(long jobId) {
        cleanupJobMapper.deleteCompleted(jobId);
    }

    @Transactional
    public void retry(AssetCleanupJob job, Exception failure) {
        LocalDateTime now = now();
        long attempt = Math.max(1L, job.getAttemptCount() == null ? 1L : job.getAttemptCount());
        long exponent = Math.min(13L, attempt - 1L);
        Duration delay = INITIAL_RETRY_DELAY.multipliedBy(1L << exponent);
        if (delay.compareTo(MAX_RETRY_DELAY) > 0) delay = MAX_RETRY_DELAY;
        String message = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        if (message.length() > MAX_ERROR_LENGTH) {
            message = message.substring(0, MAX_ERROR_LENGTH);
        }
        cleanupJobMapper.scheduleRetry(
                job.getId(), now.plus(delay), message, now);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }
}
