package com.webcollector.capture.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.webcollector.capture.asset.dto.AssetSummary;
import com.webcollector.capture.entity.CaptureJobStatus;

import java.time.Instant;
import java.util.List;

public record CaptureJobDetail(
        Long id,
        Long linkId,
        CaptureJobStatus status,
        int attemptCount,
        int maxAttempts,
        String errorCode,
        String errorMessage,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant nextAttemptAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant startedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant finishedAt,
        List<AssetSummary> assets
) {
}
