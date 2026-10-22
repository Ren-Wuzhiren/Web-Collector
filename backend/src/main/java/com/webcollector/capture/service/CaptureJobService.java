package com.webcollector.capture.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.webcollector.capture.dto.CaptureJobSummary;
import com.webcollector.capture.entity.CaptureJob;
import com.webcollector.capture.entity.CaptureJobStatus;
import com.webcollector.capture.mapper.CaptureJobMapper;
import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * [业务] 抓取任务的创建与状态流转入口。
 *
 * [软件工程] 它只负责“生成符合队列契约的任务”和“按状态机迁移”，
 * 不读会话、不校验链接归属，那些是 LinkService 的职责。
 * 这样 M3 的 Worker 或手动重新采集也能复用同一套方法。
 */
@Service
public class CaptureJobService {
    // [业务] 与 M3 的指数退避读同一个含义：这条任务总共允许尝试几次。
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final CaptureJobMapper captureJobMapper;
    private final Clock clock;

    public CaptureJobService(CaptureJobMapper captureJobMapper, Clock clock) {
        this.captureJobMapper = captureJobMapper;
        this.clock = clock;
    }

    /**
     * [业务] 建一条待抓取任务。
     *
     * [软件工程] 这里不写 propagation 参数，用的就是默认 REQUIRED：
     * 调用方 LinkService 已经开了事务，本方法加入它，
     * 于是链接、标签关联、任务三者同成同败。
     *
     * [排错] 如果改成 REQUIRES_NEW，任务会在独立事务里提交；
     * 链接随后回滚时任务仍然留着，Worker 会去抓一条不存在的链接。
     */

    @Transactional
    public CaptureJobSummary createPending(Long linkId, Long requestedBy, LocalDateTime now) {
        CaptureJob job = new CaptureJob();
        job.setLinkId(linkId);
        job.setRequestedBy(requestedBy);
        job.setStatus(CaptureJobStatus.PENDING);
        job.setAttemptCount(0);
        job.setMaxAttempts(DEFAULT_MAX_ATTEMPTS);

        // [排错] 初值必须是 now 而不是 null：M3 的领取条件是
        // status='PENDING' AND next_attempt_at <= now()，NULL 永远不满足比较。
        job.setNextAttemptAt(now);

        job.setCreatedAt(now);
        job.setUpdatedAt(now);

        captureJobMapper.insert(job);

        return toSummary(job);
    }

    /**
     * [业务] 认领一条待抓取任务：PENDING → RUNNING，并登记租约。
     *
     * [408] 这里用“条件更新 + 受影响行数”而不是“先查后改”：UPDATE 的 WHERE 里直接带上
     * status='PENDING'，判断和修改在同一条 SQL 里完成，中间没有可插入的时间窗。
     * 并发下两个调用方最多只有一个能拿到 1 行，另一个拿到 0 行。
     *
     * [业务] 锁的有效期由调用方决定：Worker 应该传“现在 + 预计最长处理时间”。
     * 到期后怎么恢复属于 M3.3，本方法只负责把租约写下来。
     */
    @Transactional
    public CaptureJobSummary claim(Long jobId, String workerId, LocalDateTime lockedUntil) {
        // [排错] 租约不能为空：M3.3 的恢复扫描依赖 locked_until，
        // 写成 NULL 的任务永远等不到“锁过期”那一刻。
        if (lockedUntil == null) {
            throw new IllegalArgumentException("lockedUntil 不能为空");
        }

        LocalDateTime now = now();

        int updated = captureJobMapper.update(
                null,
                new LambdaUpdateWrapper<CaptureJob>()
                        .eq(CaptureJob::getId, jobId)
                        .eq(CaptureJob::getStatus, CaptureJobStatus.PENDING)
                        .set(CaptureJob::getStatus, CaptureJobStatus.RUNNING)
                        .set(CaptureJob::getLockedBy, workerId)
                        .set(CaptureJob::getLockedUntil, lockedUntil)
                        .set(CaptureJob::getStartedAt, now)
                        .set(CaptureJob::getUpdatedAt, now)
        );

        if (updated == 0) {
            throw transitionFailure(jobId);
        }

        return toSummary(requireJob(jobId));
    }

    /**
     * [业务] 正常收尾：RUNNING → SUCCEEDED。
     *
     * [软件工程] 委托给 finish 统一处理收尾字段，避免 complete 与 fail 各写一套、
     * 将来漏掉某个字段（尤其是租约清理）。
     */
    @Transactional
    public CaptureJobSummary complete(Long jobId) {
        return finish(jobId, CaptureJobStatus.SUCCEEDED, null, null);
    }

    /**
     * [业务] 失败收尾：RUNNING → FAILED，并记下错误信息。
     *
     * [业务] 本轮 fail 直接进终态；失败重试与指数退避排在 M3.3，不在这里提前实现。
     */
    @Transactional
    public CaptureJobSummary fail(Long jobId, String errorCode, String errorMessage) {
        return finish(jobId, CaptureJobStatus.FAILED, errorCode, errorMessage);
    }

    private CaptureJobSummary finish(
            Long jobId,
            CaptureJobStatus target,
            String errorCode,
            String errorMessage
    ) {
        LocalDateTime now = now();

        LambdaUpdateWrapper<CaptureJob> update = new LambdaUpdateWrapper<CaptureJob>()
                .eq(CaptureJob::getId, jobId)
                .eq(CaptureJob::getStatus, CaptureJobStatus.RUNNING)
                .set(CaptureJob::getStatus, target)
                .set(CaptureJob::getFinishedAt, now)
                .set(CaptureJob::getUpdatedAt, now)
                // [业务] 收尾必须清空租约：M3.3 的恢复扫描只看 status='RUNNING' 与 locked_until，
                // 留着旧租约会让人误判成“锁过期”，把已经结束的任务重新捞出来。
                .set(CaptureJob::getLockedBy, null)
                .set(CaptureJob::getLockedUntil, null);

        if (target == CaptureJobStatus.FAILED) {
            update.set(CaptureJob::getErrorCode, errorCode)
                    .set(CaptureJob::getErrorMessage, errorMessage);
        }

        int updated = captureJobMapper.update(null, update);

        if (updated == 0) {
            throw transitionFailure(jobId);
        }

        return toSummary(requireJob(jobId));
    }

    /**
     * [业务] 条件更新影响 0 行时，区分“任务不存在”和“状态不允许”两种情况。
     *
     * [软件工程] 只有更新失败才需要这次额外查询；成功路径不为诊断付一次读的成本。
     */
    private BusinessException transitionFailure(Long jobId) {
        return captureJobMapper.selectById(jobId) == null
                ? new BusinessException(ErrorCode.NOT_FOUND)
                : new BusinessException(ErrorCode.CAPTURE_JOB_INVALID_STATE);
    }

    private CaptureJob requireJob(Long jobId) {
        CaptureJob job = captureJobMapper.selectById(jobId);

        if (job == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        return job;
    }

    // [Java] 时间通过注入的 Clock 生成，测试里可以替换成固定时钟。
    // [408] 截断到毫秒是为了与 MySQL DATETIME(3) 的精度对齐。
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    private CaptureJobSummary toSummary(CaptureJob job) {
        return new CaptureJobSummary(
                job.getId(),
                job.getLinkId(),
                job.getStatus(),
                job.getAttemptCount(),
                // [Java] 库里是不带时区的 LocalDateTime，按 UTC 语义转成 Instant 再对外输出。
                job.getNextAttemptAt().toInstant(ZoneOffset.UTC)
        );
    }
}
