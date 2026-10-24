package com.webcollector.capture.service;

import com.webcollector.auth.entity.AppUser;
import com.webcollector.auth.mapper.AppUserMapper;
import com.webcollector.capture.dto.CaptureJobSummary;
import com.webcollector.capture.entity.CaptureJob;
import com.webcollector.capture.entity.CaptureJobStatus;
import com.webcollector.capture.mapper.CaptureJobMapper;
import com.webcollector.collection.entity.LinkCollection;
import com.webcollector.collection.mapper.LinkCollectionMapper;
import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import com.webcollector.link.entity.Link;
import com.webcollector.link.mapper.LinkMapper;
import com.webcollector.link.support.UrlNormalizer;
import com.webcollector.support.SharedTestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [软件工程] 状态机用真实 MySQL 验证：条件更新是否命中、受影响行数是否为 0，
 * 只有真实数据库才能给出可信答案，Mockito 模拟不出 UPDATE 的语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CaptureJobServiceTest {
    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.registerProperties(registry);
    }

    @Autowired
    private CaptureJobService captureJobService;

    @Autowired
    private CaptureJobMapper captureJobMapper;

    @Autowired
    private AppUserMapper appUserMapper;

    @Autowired
    private LinkCollectionMapper linkCollectionMapper;

    @Autowired
    private LinkMapper linkMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void cleanUp() {
        // [业务] app_user 是所有权根，删它会把集合、链接、任务一起级联清掉。
        jdbcTemplate.update("DELETE FROM app_user");
    }

    // [测试意图] 验证认领会把 PENDING 改成 RUNNING，并写入租约与开始时间。
    @Test
    void shouldClaimPendingJobAndStampTheLease() {
        Long jobId = createPendingJob(createLinkFixture("capture-claim"));
        LocalDateTime leaseEnd = now().plusMinutes(5);

        CaptureJobSummary summary = captureJobService.claim(jobId, "worker-1", leaseEnd);

        assertThat(summary.status()).isEqualTo(CaptureJobStatus.RUNNING);

        CaptureJob loaded = captureJobMapper.selectById(jobId);
        assertThat(loaded.getStatus()).isEqualTo(CaptureJobStatus.RUNNING);
        assertThat(loaded.getAttemptCount()).isEqualTo(1);
        assertThat(loaded.getLockedBy()).isEqualTo("worker-1");
        assertThat(loaded.getLockedUntil()).isEqualTo(leaseEnd);
        assertThat(loaded.getStartedAt()).isNotNull();
        assertThat(loaded.getFinishedAt()).isNull();
    }

    // [测试意图] 验证正常收尾会写入完成时间，并清空两个租约字段。
    @Test
    void shouldCompleteRunningJobAndClearTheLease() {
        Long jobId = createPendingJob(createLinkFixture("capture-complete"));
        captureJobService.claim(jobId, "worker-1", now().plusMinutes(5));

        CaptureJobSummary summary = captureJobService.complete(jobId);

        assertThat(summary.status()).isEqualTo(CaptureJobStatus.SUCCEEDED);

        CaptureJob loaded = captureJobMapper.selectById(jobId);
        assertThat(loaded.getStatus()).isEqualTo(CaptureJobStatus.SUCCEEDED);
        assertThat(loaded.getFinishedAt()).isNotNull();
        assertThat(loaded.getLockedBy()).isNull();
        assertThat(loaded.getLockedUntil()).isNull();
        assertThat(loaded.getErrorCode()).isNull();
        assertThat(loaded.getErrorMessage()).isNull();
    }

    // [测试意图] 验证失败收尾会写入错误信息，并且同样清空租约。
    @Test
    void shouldFailRunningJobWithErrorDetails() {
        Long jobId = createPendingJob(createLinkFixture("capture-fail"));
        captureJobService.claim(jobId, "worker-1", now().plusMinutes(5));

        CaptureJobSummary summary = captureJobService.fail(
                jobId,
                "NETWORK_TIMEOUT",
                "目标站点响应超时"
        );

        assertThat(summary.status()).isEqualTo(CaptureJobStatus.FAILED);

        CaptureJob loaded = captureJobMapper.selectById(jobId);
        assertThat(loaded.getStatus()).isEqualTo(CaptureJobStatus.FAILED);
        assertThat(loaded.getErrorCode()).isEqualTo("NETWORK_TIMEOUT");
        assertThat(loaded.getErrorMessage()).isEqualTo("目标站点响应超时");
        assertThat(loaded.getFinishedAt()).isNotNull();
        assertThat(loaded.getLockedBy()).isNull();
        assertThat(loaded.getLockedUntil()).isNull();
    }

    // [测试意图] 验证已经 RUNNING 的任务不能被第二个 Worker 再次认领，首个持有者不被改写。
    @Test
    void shouldRejectSecondClaimAndKeepTheFirstLeaseHolder() {
        Long jobId = createPendingJob(createLinkFixture("capture-double-claim"));
        captureJobService.claim(jobId, "worker-1", now().plusMinutes(5));

        assertThatThrownBy(() -> captureJobService.claim(jobId, "worker-2", now().plusMinutes(5)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.CAPTURE_JOB_INVALID_STATE)
                );

        CaptureJob loaded = captureJobMapper.selectById(jobId);
        assertThat(loaded.getLockedBy()).isEqualTo("worker-1");
        assertThat(loaded.getStatus()).isEqualTo(CaptureJobStatus.RUNNING);
    }

    // [测试意图] 验证 PENDING 任务不能被直接收尾，避免跳过认领这一步。
    @Test
    void shouldRejectCompletionWhenJobIsStillPending() {
        Long jobId = createPendingJob(createLinkFixture("capture-complete-pending"));

        assertThatThrownBy(() -> captureJobService.complete(jobId))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.CAPTURE_JOB_INVALID_STATE)
                );

        CaptureJob loaded = captureJobMapper.selectById(jobId);
        assertThat(loaded.getStatus()).isEqualTo(CaptureJobStatus.PENDING);
        assertThat(loaded.getFinishedAt()).isNull();
    }

    // [测试意图] 验证终态任务不能再被收尾一次，重复收尾返回 409 而不是静默成功。
    @Test
    void shouldRejectCompletingAnAlreadyTerminalJob() {
        Long jobId = createPendingJob(createLinkFixture("capture-complete-twice"));
        captureJobService.claim(jobId, "worker-1", now().plusMinutes(5));
        captureJobService.complete(jobId);

        assertThatThrownBy(() -> captureJobService.complete(jobId))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.CAPTURE_JOB_INVALID_STATE)
                );
    }

    // [测试意图] 验证三个迁移方法遇到不存在的任务都返回 404，而不是 409 或空指针。
    @Test
    void shouldReturnNotFoundForUnknownJob() {
        long unknownJobId = 999_999L;

        assertThatThrownBy(
                () -> captureJobService.claim(unknownJobId, "worker-1", now().plusMinutes(5))
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
                );

        assertThatThrownBy(() -> captureJobService.complete(unknownJobId))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
                );

        assertThatThrownBy(() -> captureJobService.fail(unknownJobId, "X", "Y"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
                );
    }

    // [测试意图] 验证只领取已经到期的任务，并按 next_attempt_at、id 的优先级选取。
    @Test
    void shouldClaimEarliestEligibleJobAndKeepFutureJobPending() {
        LocalDateTime baseTime = now();
        Long futureJobId = createPendingJob(createLinkFixture("capture-order-future"));
        Long earliestJobId = createPendingJob(createLinkFixture("capture-order-earliest"));
        Long laterJobId = createPendingJob(createLinkFixture("capture-order-later"));

        setNextAttemptAt(futureJobId, baseTime.plusHours(1));
        setNextAttemptAt(earliestJobId, baseTime.minusMinutes(2));
        setNextAttemptAt(laterJobId, baseTime.minusMinutes(1));

        Optional<CaptureJobSummary> first = captureJobService.claimNext(
                "worker-1",
                baseTime.plusMinutes(5)
        );
        Optional<CaptureJobSummary> second = captureJobService.claimNext(
                "worker-2",
                baseTime.plusMinutes(5)
        );
        Optional<CaptureJobSummary> third = captureJobService.claimNext(
                "worker-3",
                baseTime.plusMinutes(5)
        );

        assertThat(first).get().extracting(CaptureJobSummary::id).isEqualTo(earliestJobId);
        assertThat(second).get().extracting(CaptureJobSummary::id).isEqualTo(laterJobId);
        assertThat(third).isEmpty();

        CaptureJob futureJob = captureJobMapper.selectById(futureJobId);
        assertThat(futureJob.getStatus()).isEqualTo(CaptureJobStatus.PENDING);
        assertThat(futureJob.getAttemptCount()).isZero();
    }

    // [测试意图] 验证队列没有到期任务时返回空结果，而不是异常或 404。
    @Test
    void shouldReturnEmptyWhenNoJobIsEligible() {
        LocalDateTime baseTime = now();
        Long futureJobId = createPendingJob(createLinkFixture("capture-empty"));
        setNextAttemptAt(futureJobId, baseTime.plusHours(1));

        Optional<CaptureJobSummary> claimed = captureJobService.claimNext(
                "worker-1",
                baseTime.plusMinutes(5)
        );

        assertThat(claimed).isEmpty();
        CaptureJob futureJob = captureJobMapper.selectById(futureJobId);
        assertThat(futureJob.getStatus()).isEqualTo(CaptureJobStatus.PENDING);
        assertThat(futureJob.getAttemptCount()).isZero();
    }

    // [测试意图] 验证两个 Worker 并发争抢一条任务时，只有一个能够成功领取。
    @Test
    void shouldAllowOnlyOneWorkerToClaimTheSameJob() throws Exception {
        Long jobId = createPendingJob(createLinkFixture("capture-concurrent-one"));

        List<ClaimOutcome> outcomes = claimConcurrently(
                List.of("worker-1", "worker-2"),
                now().plusMinutes(5)
        );
        List<ClaimOutcome> winners = outcomes.stream()
                .filter(outcome -> outcome.job().isPresent())
                .toList();

        assertThat(winners).hasSize(1);
        assertThat(winners.get(0).job().orElseThrow().id()).isEqualTo(jobId);

        CaptureJob claimed = captureJobMapper.selectById(jobId);
        assertThat(claimed.getStatus()).isEqualTo(CaptureJobStatus.RUNNING);
        assertThat(claimed.getAttemptCount()).isEqualTo(1);
        assertThat(claimed.getLockedBy()).isEqualTo(winners.get(0).workerId());
    }

    // [测试意图] 验证两个 Worker 并发领取两条任务时拿到不同任务，且每条只增加一次尝试次数。
    @Test
    void shouldGiveConcurrentWorkersDifferentJobs() throws Exception {
        Long firstJobId = createPendingJob(createLinkFixture("capture-concurrent-two-a"));
        Long secondJobId = createPendingJob(createLinkFixture("capture-concurrent-two-b"));

        List<ClaimOutcome> outcomes = claimConcurrently(
                List.of("worker-1", "worker-2"),
                now().plusMinutes(5)
        );

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.job()).isPresent());
        List<Long> claimedIds = outcomes.stream()
                .map(outcome -> outcome.job().orElseThrow().id())
                .toList();
        assertThat(claimedIds)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrder(firstJobId, secondJobId);

        for (ClaimOutcome outcome : outcomes) {
            CaptureJob claimed = captureJobMapper.selectById(outcome.job().orElseThrow().id());
            assertThat(claimed.getStatus()).isEqualTo(CaptureJobStatus.RUNNING);
            assertThat(claimed.getAttemptCount()).isEqualTo(1);
            assertThat(claimed.getLockedBy()).isEqualTo(outcome.workerId());
        }
    }

    // [测试意图] 验证其他事务锁住唯一任务时 SKIP LOCKED 立即返回空，释放后任务仍可领取。
    @Test
    void shouldSkipJobLockedByAnotherTransactionAndClaimAfterRelease() throws Exception {
        Long jobId = createPendingJob(createLinkFixture("capture-skip-locked"));
        LocalDateTime leaseEnd = now().plusMinutes(5);

        try (Connection lockConnection = dataSource.getConnection()) {
            lockConnection.setAutoCommit(false);
            ExecutorService executor = Executors.newSingleThreadExecutor();

            try {
                lockEligibleJob(lockConnection, jobId);

                Future<Optional<CaptureJobSummary>> blockedClaim = executor.submit(
                        () -> captureJobService.claimNext("worker-2", leaseEnd)
                );

                assertThat(blockedClaim.get(2, TimeUnit.SECONDS)).isEmpty();
                assertThat(captureJobMapper.selectById(jobId).getStatus())
                        .isEqualTo(CaptureJobStatus.PENDING);
            } finally {
                lockConnection.rollback();
                executor.shutdownNow();
            }
        }

        Optional<CaptureJobSummary> claimed = captureJobService.claimNext("worker-2", leaseEnd);

        assertThat(claimed).get().extracting(CaptureJobSummary::id).isEqualTo(jobId);
        CaptureJob loaded = captureJobMapper.selectById(jobId);
        assertThat(loaded.getStatus()).isEqualTo(CaptureJobStatus.RUNNING);
        assertThat(loaded.getAttemptCount()).isEqualTo(1);
        assertThat(loaded.getLockedBy()).isEqualTo("worker-2");
    }

    // [测试意图] 验证空白或超长 Worker、空租约和过期租约在访问数据库前被拒绝。
    @Test
    void shouldRejectInvalidClaimParameters() {
        LocalDateTime validLeaseEnd = now().plusMinutes(5);

        assertThatThrownBy(() -> captureJobService.claimNext(null, validLeaseEnd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workerId");

        assertThatThrownBy(() -> captureJobService.claimNext("   ", validLeaseEnd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workerId");

        assertThatThrownBy(() -> captureJobService.claimNext("x".repeat(101), validLeaseEnd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");

        assertThatThrownBy(() -> captureJobService.claimNext("worker-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lockedUntil");

        assertThatThrownBy(
                () -> captureJobService.claimNext("worker-1", now().minusSeconds(1))
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("晚于当前时间");
    }

    private Long createPendingJob(LinkFixture fixture) {
        return captureJobService
                .createPending(fixture.linkId(), fixture.userId(), now())
                .id();
    }

    private void setNextAttemptAt(Long jobId, LocalDateTime nextAttemptAt) {
        jdbcTemplate.update(
                "UPDATE capture_job SET next_attempt_at = ? WHERE id = ?",
                Timestamp.valueOf(nextAttemptAt),
                jobId
        );
    }

    private List<ClaimOutcome> claimConcurrently(
            List<String> workerIds,
            LocalDateTime lockedUntil
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workerIds.size());
        CountDownLatch ready = new CountDownLatch(workerIds.size());
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<ClaimOutcome>> futures = new ArrayList<>();

            for (String workerId : workerIds) {
                futures.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发测试未能同时启动");
                    }

                    return new ClaimOutcome(
                            workerId,
                            captureJobService.claimNext(workerId, lockedUntil)
                    );
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ClaimOutcome> outcomes = new ArrayList<>();
            for (Future<ClaimOutcome> future : futures) {
                outcomes.add(future.get(5, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private void lockEligibleJob(Connection connection, Long expectedJobId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT id
                FROM capture_job
                WHERE status = 'PENDING'
                  AND next_attempt_at <= ?
                ORDER BY next_attempt_at, id
                LIMIT 1
                FOR UPDATE
                """
        )) {
            statement.setTimestamp(1, Timestamp.valueOf(now().plusMinutes(1)));

            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("id")).isEqualTo(expectedJobId);
            }
        }
    }

    private LinkFixture createLinkFixture(String username) {
        LocalDateTime now = now();

        AppUser user = createUser(username, now);

        LinkCollection collection = new LinkCollection();
        collection.setUserId(user.getId());
        collection.setName("Inbox");
        collection.setDescription("");
        collection.setIsInbox(true);
        collection.setCreatedAt(now);
        collection.setUpdatedAt(now);
        linkCollectionMapper.insert(collection);

        String url = "https://" + username + ".example.com/article";
        String normalizedUrl = UrlNormalizer.normalize(url);
        Link link = new Link();
        link.setUserId(user.getId());
        link.setCollectionId(collection.getId());
        link.setOriginalUrl(url);
        link.setNormalizedUrl(normalizedUrl);
        link.setUrlHash(UrlNormalizer.sha256(normalizedUrl));
        link.setTitle("");
        link.setDescription("");
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        linkMapper.insert(link);

        return new LinkFixture(user.getId(), link.getId());
    }

    private AppUser createUser(String username, LocalDateTime now) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash("$2a$10$placeholder");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        appUserMapper.insert(user);
        return user;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(Clock.systemUTC()).truncatedTo(ChronoUnit.MILLIS);
    }

    private record LinkFixture(Long userId, Long linkId) {
    }

    private record ClaimOutcome(
            String workerId,
            Optional<CaptureJobSummary> job
    ) {
    }
}
