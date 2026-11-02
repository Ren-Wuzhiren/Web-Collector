package com.webcollector.capture.worker;

import com.webcollector.auth.entity.AppUser;
import com.webcollector.auth.mapper.AppUserMapper;
import com.webcollector.capture.dto.CaptureJobSummary;
import com.webcollector.capture.entity.CaptureJob;
import com.webcollector.capture.entity.CaptureJobStatus;
import com.webcollector.capture.mapper.CaptureJobMapper;
import com.webcollector.capture.service.CaptureJobService;
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
 * [M3.4] 使用假 Worker 验证任务状态机、并发领取、失败重试和租约恢复。
 *
 * [测试边界]
 * - 不访问真实网页
 * - 不启动 Playwright
 * - 不新增 HTTP 接口
 * - 不模拟数据库状态，所有状态变化都经过 CaptureJobService
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FakeCaptureWorkerTest {

    @DynamicPropertySource
    static void registerContainerProperties(
            DynamicPropertyRegistry registry
    ) {
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
        // [测试基础设施] app_user 是所有权根，级联清理集合、链接和任务。
        jdbcTemplate.update("DELETE FROM app_user");
    }

    // [测试意图] 验证假 Worker 能领取并成功完成一条任务。
    @Test
    void shouldRunFakeWorkerSuccessfully() {
        Long jobId = createPendingJob("fake-success");

        FakeCaptureWorker worker = newWorker(
                "worker-success",
                now().plusMinutes(5)
        );

        Optional<CaptureJobSummary> result =
                worker.runSuccessfully();

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().id()).isEqualTo(jobId);
        assertThat(result.orElseThrow().status())
                .isEqualTo(CaptureJobStatus.SUCCEEDED);

        CaptureJob loaded = captureJobMapper.selectById(jobId);
        assertThat(loaded.getStatus()).isEqualTo(CaptureJobStatus.SUCCEEDED);
        assertThat(loaded.getAttemptCount()).isEqualTo(1);
        assertThat(loaded.getLockedBy()).isNull();
        assertThat(loaded.getLockedUntil()).isNull();
        assertThat(loaded.getFinishedAt()).isNotNull();
    }

    // [测试意图] 验证假 Worker 失败后任务按 M3.3 规则回到 PENDING。
    @Test
    void shouldRunFakeWorkerWithFailureAndRequeue() {
        Long jobId = createPendingJob("fake-failure");

        FakeCaptureWorker worker = newWorker(
                "worker-failure",
                now().plusMinutes(5)
        );

        Optional<CaptureJobSummary> result =
                worker.runWithFailure(
                        "FAKE_NETWORK_TIMEOUT",
                        "假 Worker 模拟网络超时"
                );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().id()).isEqualTo(jobId);
        assertThat(result.orElseThrow().status())
                .isEqualTo(CaptureJobStatus.PENDING);

        CaptureJob loaded = captureJobMapper.selectById(jobId);
        assertThat(loaded.getStatus()).isEqualTo(CaptureJobStatus.PENDING);
        assertThat(loaded.getAttemptCount()).isEqualTo(1);
        assertThat(loaded.getErrorCode())
                .isEqualTo("FAKE_NETWORK_TIMEOUT");
        assertThat(loaded.getLockedBy()).isNull();
        assertThat(loaded.getLockedUntil()).isNull();
        assertThat(loaded.getFinishedAt()).isNull();
    }

    // [测试意图] 验证空队列时假 Worker 返回 empty，而不是抛出异常。
    @Test
    void shouldReturnEmptyWhenFakeWorkerFindsNoJob() {
        FakeCaptureWorker worker = newWorker(
                "worker-empty",
                now().plusMinutes(5)
        );

        Optional<CaptureJobSummary> result =
                worker.claimOnly();

        assertThat(result).isEmpty();
    }

    // [测试意图] 验证两个假 Worker 并发争抢一条任务时只有一个成功执行。
    @Test
    void shouldAllowOnlyOneFakeWorkerToProcessSameJob()
            throws Exception {
        Long jobId = createPendingJob("fake-concurrent-one");

        List<WorkerOutcome> outcomes = runWorkersConcurrently(
                List.of("worker-a", "worker-b"),
                WorkerAction.SUCCESS
        );

        List<WorkerOutcome> successful = outcomes.stream()
                .filter(outcome -> outcome.result().isPresent())
                .toList();

        assertThat(successful).hasSize(1);
        assertThat(successful.get(0).result().orElseThrow().id())
                .isEqualTo(jobId);
        assertThat(successful.get(0).result().orElseThrow().status())
                .isEqualTo(CaptureJobStatus.SUCCEEDED);

        CaptureJob loaded = captureJobMapper.selectById(jobId);
        assertThat(loaded.getStatus())
                .isEqualTo(CaptureJobStatus.SUCCEEDED);
        assertThat(loaded.getAttemptCount()).isEqualTo(1);
    }

    // [测试意图] 验证两个假 Worker 并发处理两条任务时各自拿到不同任务。
    @Test
    void shouldAllowTwoFakeWorkersToProcessDifferentJobs()
            throws Exception {
        Long firstJobId = createPendingJob("fake-concurrent-two-a");
        Long secondJobId = createPendingJob("fake-concurrent-two-b");

        List<WorkerOutcome> outcomes = runWorkersConcurrently(
                List.of("worker-a", "worker-b"),
                WorkerAction.SUCCESS
        );

        List<Long> processedJobIds = outcomes.stream()
                .filter(outcome -> outcome.result().isPresent())
                .map(outcome -> outcome.result().orElseThrow().id())
                .toList();

        assertThat(processedJobIds)
                .hasSize(2)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrder(firstJobId, secondJobId);

        assertThat(captureJobMapper.selectById(firstJobId).getStatus())
                .isEqualTo(CaptureJobStatus.SUCCEEDED);
        assertThat(captureJobMapper.selectById(secondJobId).getStatus())
                .isEqualTo(CaptureJobStatus.SUCCEEDED);
    }

    // [测试意图] 验证假 Worker 崩溃后不收尾，租约恢复后可以再次执行。
    @Test
    void shouldRecoverFakeWorkerCrashAndRunAgain() {
        Long jobId = createPendingJob("fake-crash-recovery");

        FakeCaptureWorker crashedWorker = newWorker(
                "worker-crashed",
                now().plusMinutes(5)
        );

        Optional<CaptureJobSummary> claimed =
                crashedWorker.runAndCrash();

        assertThat(claimed).isPresent();
        assertThat(claimed.orElseThrow().id()).isEqualTo(jobId);

        setLockedUntil(jobId, now().minusMinutes(1));

        assertThat(captureJobService.recoverExpiredJobs())
                .isEqualTo(1);

        FakeCaptureWorker recoveryWorker = newWorker(
                "worker-recovery",
                now().plusMinutes(5)
        );

        Optional<CaptureJobSummary> completed =
                recoveryWorker.runSuccessfully();

        assertThat(completed).isPresent();
        assertThat(completed.orElseThrow().id()).isEqualTo(jobId);
        assertThat(completed.orElseThrow().status())
                .isEqualTo(CaptureJobStatus.SUCCEEDED);

        CaptureJob loaded = captureJobMapper.selectById(jobId);
        assertThat(loaded.getAttemptCount()).isEqualTo(2);
        assertThat(loaded.getStatus())
                .isEqualTo(CaptureJobStatus.SUCCEEDED);
    }

    // [测试意图] 验证旧 Worker 在任务恢复后不能继续完成已经变化的任务。
    @Test
    void shouldRejectOldFakeWorkerCompletionAfterRecovery() {
        Long jobId = createPendingJob("fake-old-worker");

        FakeCaptureWorker oldWorker = newWorker(
                "worker-old",
                now().plusMinutes(5)
        );

        Optional<CaptureJobSummary> claimed =
                oldWorker.claimOnly();

        assertThat(claimed).isPresent();
        setLockedUntil(jobId, now().minusMinutes(1));

        assertThat(captureJobService.recoverExpiredJobs())
                .isEqualTo(1);

        assertThatThrownBy(
                () -> captureJobService.complete(jobId)
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        ErrorCode.CAPTURE_JOB_INVALID_STATE
                                )
                );

        assertThat(captureJobMapper.selectById(jobId).getStatus())
                .isEqualTo(CaptureJobStatus.PENDING);
    }

    // [测试意图] 验证失败后经过退避时间，假 Worker 可以再次领取并成功完成。
    @Test
    void shouldRetryFakeWorkerAfterBackoff() {
        Long jobId = createPendingJob("fake-backoff");

        FakeCaptureWorker firstWorker = newWorker(
                "worker-first",
                now().plusMinutes(5)
        );

        Optional<CaptureJobSummary> failed =
                firstWorker.runWithFailure(
                        "FAKE_TIMEOUT",
                        "第一次假失败"
                );

        assertThat(failed).get().extracting(CaptureJobSummary::status)
                .isEqualTo(CaptureJobStatus.PENDING);

        assertThat(newWorker(
                "worker-before-backoff",
                now().plusMinutes(5)
        ).claimOnly()).isEmpty();

        moveNextAttemptToNow(jobId);

        Optional<CaptureJobSummary> retried =
                newWorker(
                        "worker-after-backoff",
                        now().plusMinutes(5)
                ).runSuccessfully();

        assertThat(retried).get().extracting(CaptureJobSummary::id)
                .isEqualTo(jobId);
        assertThat(retried).get().extracting(CaptureJobSummary::status)
                .isEqualTo(CaptureJobStatus.SUCCEEDED);
    }

    private FakeCaptureWorker newWorker(
            String workerId,
            LocalDateTime lockedUntil
    ) {
        return new FakeCaptureWorker(
                captureJobService,
                workerId,
                lockedUntil
        );
    }

    private List<WorkerOutcome> runWorkersConcurrently(
            List<String> workerIds,
            WorkerAction action
    ) throws Exception {
        ExecutorService executor =
                Executors.newFixedThreadPool(workerIds.size());
        CountDownLatch ready =
                new CountDownLatch(workerIds.size());
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<WorkerOutcome>> futures = new ArrayList<>();

            for (String workerId : workerIds) {
                futures.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "假 Worker 未能同步启动"
                        );
                    }

                    FakeCaptureWorker worker = newWorker(
                            workerId,
                            now().plusMinutes(5)
                    );

                    Optional<CaptureJobSummary> result =
                            switch (action) {
                                case SUCCESS -> worker.runSuccessfully();
                                case FAILURE -> worker.runWithFailure(
                                        "FAKE_FAILURE",
                                        "假 Worker 模拟失败"
                                );
                                case CRASH -> worker.runAndCrash();
                            };

                    return new WorkerOutcome(workerId, result);
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<WorkerOutcome> outcomes = new ArrayList<>();
            for (Future<WorkerOutcome> future : futures) {
                outcomes.add(future.get(5, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Long createPendingJob(String username) {
        LocalDateTime createdAt = now();

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash("$2a$10$placeholder");
        user.setCreatedAt(createdAt);
        user.setUpdatedAt(createdAt);
        appUserMapper.insert(user);

        LinkCollection collection = new LinkCollection();
        collection.setUserId(user.getId());
        collection.setName("Inbox");
        collection.setDescription("");
        collection.setIsInbox(true);
        collection.setCreatedAt(createdAt);
        collection.setUpdatedAt(createdAt);
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
        link.setCreatedAt(createdAt);
        link.setUpdatedAt(createdAt);
        linkMapper.insert(link);

        return captureJobService.createPending(
                link.getId(),
                user.getId(),
                createdAt
        ).id();
    }

    private void moveNextAttemptToNow(Long jobId) {
        jdbcTemplate.update(
                "UPDATE capture_job SET next_attempt_at = ? WHERE id = ?",
                Timestamp.valueOf(now().minusSeconds(1)),
                jobId
        );
    }

    private void setLockedUntil(Long jobId, LocalDateTime lockedUntil) {
        jdbcTemplate.update(
                "UPDATE capture_job SET locked_until = ? WHERE id = ?",
                Timestamp.valueOf(lockedUntil),
                jobId
        );
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(Clock.systemUTC())
                .truncatedTo(ChronoUnit.MILLIS);
    }

    private enum WorkerAction {
        SUCCESS,
        FAILURE,
        CRASH
    }

    private record WorkerOutcome(
            String workerId,
            Optional<CaptureJobSummary> result
    ) {
    }
}