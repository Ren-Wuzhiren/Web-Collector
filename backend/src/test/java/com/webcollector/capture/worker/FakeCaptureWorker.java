package com.webcollector.capture.worker;

import com.webcollector.capture.dto.CaptureJobSummary;
import com.webcollector.capture.service.CaptureJobService;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * [测试辅助] 不访问真实网页，只验证任务领取和收尾状态。
 *
 * [M3.4] 用它把 M3.1、M3.2、M3.3 的规则串起来：
 * claimNext -> complete/fail，或者模拟 Worker 崩溃而不执行收尾。
 */
public final class FakeCaptureWorker {

    private final CaptureJobService captureJobService;
    private final String workerId;
    private final LocalDateTime lockedUntil;

    public FakeCaptureWorker(
            CaptureJobService captureJobService,
            String workerId,
            LocalDateTime lockedUntil
    ) {
        this.captureJobService = captureJobService;
        this.workerId = workerId;
        this.lockedUntil = lockedUntil;
    }

    /** [测试辅助] Worker 成功处理一条任务。 */
    public Optional<CaptureJobSummary> runSuccessfully() {
        Optional<CaptureJobSummary> claimed =
                captureJobService.claimNext(workerId, lockedUntil);

        if (claimed.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(
                captureJobService.complete(claimed.orElseThrow().id())
        );
    }

    /** [测试辅助] Worker 失败处理一条任务。 */
    public Optional<CaptureJobSummary> runWithFailure(
            String errorCode,
            String errorMessage
    ) {
        Optional<CaptureJobSummary> claimed =
                captureJobService.claimNext(workerId, lockedUntil);

        if (claimed.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(
                captureJobService.fail(
                        claimed.orElseThrow().id(),
                        errorCode,
                        errorMessage
                )
        );
    }

    /**
     * [测试辅助] 模拟 Worker 崩溃：只领取，不调用 complete 或 fail。
     */
    public Optional<CaptureJobSummary> runAndCrash() {
        return captureJobService.claimNext(workerId, lockedUntil);
    }

    /** [测试辅助] 只领取一条任务，不自动收尾。 */
    public Optional<CaptureJobSummary> claimOnly() {
        return captureJobService.claimNext(workerId, lockedUntil);
    }
}
