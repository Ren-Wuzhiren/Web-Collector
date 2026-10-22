package com.webcollector.capture.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureJobStatusTest {

    // [测试意图] 验证状态机放行的三条迁移，保证正常认领与收尾链路不会被规则挡住。
    @Test
    void shouldAllowWhitelistedTransitions() {
        assertThat(CaptureJobStatus.PENDING.canTransitionTo(CaptureJobStatus.RUNNING)).isTrue();
        assertThat(CaptureJobStatus.RUNNING.canTransitionTo(CaptureJobStatus.SUCCEEDED)).isTrue();
        assertThat(CaptureJobStatus.RUNNING.canTransitionTo(CaptureJobStatus.FAILED)).isTrue();
    }

    // [测试意图] 验证跳过中间态、终态回退、原地迁移和 null 目标都会被拒绝。
    @Test
    void shouldRejectSkippedTerminalSelfAndNullTransitions() {
        assertThat(CaptureJobStatus.PENDING.canTransitionTo(CaptureJobStatus.SUCCEEDED)).isFalse();
        assertThat(CaptureJobStatus.PENDING.canTransitionTo(CaptureJobStatus.FAILED)).isFalse();
        assertThat(CaptureJobStatus.SUCCEEDED.canTransitionTo(CaptureJobStatus.RUNNING)).isFalse();
        assertThat(CaptureJobStatus.FAILED.canTransitionTo(CaptureJobStatus.PENDING)).isFalse();
        assertThat(CaptureJobStatus.RUNNING.canTransitionTo(CaptureJobStatus.RUNNING)).isFalse();
        assertThat(CaptureJobStatus.PENDING.canTransitionTo(null)).isFalse();
    }
}
