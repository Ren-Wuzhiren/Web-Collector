package com.webcollector.capture.browser;

import java.nio.file.Path;

public record CaptureFormatResult(
        CaptureFormat format,
        CaptureFormatStatus status,
        Path path,
        String errorMessage
) {
    public static CaptureFormatResult success(
            CaptureFormat format,
            Path path
    ) {
        return new CaptureFormatResult(format, CaptureFormatStatus.SUCCESS, path, null);
    }

    public static CaptureFormatResult failed(CaptureFormat format, String message) {
        return new CaptureFormatResult(format, CaptureFormatStatus.FAILED, null, message);
    }
}
