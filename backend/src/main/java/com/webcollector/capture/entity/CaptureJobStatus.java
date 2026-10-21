package com.webcollector.capture.entity;

/**
 * [业务] 抓取任务的状态取值，与 V9 里 status 列的 ENUM 一一对应。
 *
 * [软件工程] 本轮只会写入 PENDING；另外三态先定义出来，是让 M3 的状态迁移
 * 有一个编译期契约，而不是到处散落魔法字符串。
 */
public enum CaptureJobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED
}
