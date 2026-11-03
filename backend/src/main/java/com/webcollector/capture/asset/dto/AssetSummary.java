package com.webcollector.capture.asset.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.webcollector.capture.asset.entity.AssetFormat;

import java.time.Instant;

public record AssetSummary(
        Long id,
        AssetFormat format,
        String mimeType,
        long fileSizeBytes,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant createdAt
) {
}
