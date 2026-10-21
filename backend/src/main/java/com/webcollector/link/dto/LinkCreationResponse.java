package com.webcollector.link.dto;

import com.webcollector.capture.dto.CaptureJobSummary;

public record LinkCreationResponse(
        LinkSummary link,
        CaptureJobSummary job
) {

}
