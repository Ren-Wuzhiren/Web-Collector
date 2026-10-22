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

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

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

        assertThatThrownBy(() -> captureJobService.claim(unknownJobId, "worker-1", now()))
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

    private Long createPendingJob(LinkFixture fixture) {
        return captureJobService
                .createPending(fixture.linkId(), fixture.userId(), now())
                .id();
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
}
