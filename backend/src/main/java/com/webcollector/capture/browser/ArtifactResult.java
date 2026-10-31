package com.webcollector.capture.browser;

import java.nio.file.Path;
import java.util.Objects;

/** 一个快照格式的独立成功或失败结果。 */
public record ArtifactResult(Path path, String errorCode, String errorMessage) {

    public ArtifactResult {
        if (path == null && (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("失败结果必须包含错误码");
        }
        if (path != null && errorCode != null) {
            throw new IllegalArgumentException("成功结果不能包含错误码");
        }
    }

    public static ArtifactResult succeeded(Path path) {
        return new ArtifactResult(Objects.requireNonNull(path, "path"), null, null);
    }

    public static ArtifactResult failed(String errorCode, String errorMessage) {
        return new ArtifactResult(null, Objects.requireNonNull(errorCode, "errorCode"), errorMessage);
    }

    public boolean isSuccess() {
        return path != null;
    }

    public boolean succeeded() {
        return isSuccess();
    }
}
