package com.webcollector.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record AdminFailedJobItem(
        Long jobId,
        Long linkId,
        String ownerUsername,
        String sourceHost,
        int attemptCount,
        int maxAttempts,
        String errorCode,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant createdAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant finishedAt
) {
}
