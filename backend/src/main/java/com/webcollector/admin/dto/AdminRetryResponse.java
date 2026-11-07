package com.webcollector.admin.dto;

import com.webcollector.capture.dto.CaptureJobSummary;

public record AdminRetryResponse(
        Long sourceJobId,
        CaptureJobSummary job
) {
}
