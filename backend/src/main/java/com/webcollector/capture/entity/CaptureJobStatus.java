package com.webcollector.capture.entity;

/**
 * [业务] 抓取任务的状态取值，与 V9 里 status 列的 ENUM 一一对应。
 *
 * [软件工程] 允许的迁移集中写在这里，Service 只问“能不能走”，不自己维护 if 链：
 * 新增状态时只改这一个方法，规则不会在多个调用点分叉。
 */
public enum CaptureJobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED;

    /**
     * [业务] 判断当前状态能否迁移到目标状态，白名单之外一律拒绝。
     *
     * [408] 白名单只有三条：PENDING→RUNNING、RUNNING→SUCCEEDED、RUNNING→FAILED。
     * 终态不再出去；失败重试是 M3.3 的事，届时要显式在这里加一条 FAILED→PENDING，
     * 而不是现在提前放宽规则。
     */
    public boolean canTransitionTo(CaptureJobStatus target) {
        if (target == null || target == this) {
            return false;
        }

        return switch (this) {
            case PENDING -> target == RUNNING;
            case RUNNING -> target == SUCCEEDED || target == FAILED;
            case SUCCEEDED, FAILED -> false;
        };
    }
}
