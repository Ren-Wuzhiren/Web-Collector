package com.webcollector.capture.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.webcollector.capture.entity.CaptureJobStatus;

import java.time.Instant;

public record CaptureJobSummary (
        Long id,
        Long linkId,
        CaptureJobStatus status,
        int attemptCount,

        // [排错] Instant 在 standalone MockMvc 下会被序列化成数字时间戳，
        // 显式声明为 STRING 才能稳定输出 ISO 8601。
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant nextAttemptAt
) {
}
