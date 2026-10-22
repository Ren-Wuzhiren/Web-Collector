package com.webcollector.capture.service;
import com.webcollector.capture.dto.CaptureJobSummary;
import com.webcollector.capture.entity.CaptureJob;
import com.webcollector.capture.entity.CaptureJobStatus;
import com.webcollector.capture.mapper.CaptureJobMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * [业务] 抓取任务的创建入口。
 *
 * [软件工程] 它只负责"生成一条符合队列初始契约的 PENDING 任务"：
 * 不读会话、不校验链接归属，那些是 LinkService 的职责。
 * 这样 M3 的 Worker 或手动重新采集也能复用同一个创建方法。
 */
@Service
public class CaptureJobService {
    // [业务] 与 M3 的指数退避读同一个含义：这条任务总共允许尝试几次。
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final CaptureJobMapper captureJobMapper;

    public CaptureJobService(CaptureJobMapper captureJobMapper) {
        this.captureJobMapper = captureJobMapper;
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
