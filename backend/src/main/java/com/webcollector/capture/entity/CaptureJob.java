package com.webcollector.capture.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
/**
 * [业务] 抓取任务实体，对应 capture_job 表。
 *
 * [软件工程] 和 Link 一样保持哑数据：谁能领取、状态怎么迁移属于 M3 的 Worker 规则，
 * 实体只负责表结构映射，不写任何业务判断。
 */
@TableName("capture_job")
public class CaptureJob {
    // [Java] AUTO 表示由数据库自增生成并回写；capture_job.id 是自增列。
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long linkId;

    // [业务] 发起人，来自会话里的 userId，不由客户端传入。
    private Long requestedBy;

    // [Java] 枚举按 name() 写入，和 DDL 里的 ENUM 字符串一致。
    private CaptureJobStatus status;

    private Integer attemptCount;

    private Integer maxAttempts;

    // [Java] LocalDateTime 对应 DATETIME(3)，毫秒精度；UTC 语义由业务代码负责。
    private LocalDateTime nextAttemptAt;

    private String lockedBy;

    private LocalDateTime lockedUntil;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private String errorCode;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getLinkId() {
        return linkId;
    }

    public void setLinkId(Long linkId) {
        this.linkId = linkId;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(Long requestedBy) {
        this.requestedBy = requestedBy;
    }

    public CaptureJobStatus getStatus() {
        return status;
    }

    public void setStatus(CaptureJobStatus status) {
        this.status = status;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(Integer maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(LocalDateTime nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(LocalDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
