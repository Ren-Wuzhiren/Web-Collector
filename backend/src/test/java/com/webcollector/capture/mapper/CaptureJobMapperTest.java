package com.webcollector.capture.mapper;

import com.webcollector.auth.entity.AppUser;
import com.webcollector.auth.mapper.AppUserMapper;
import com.webcollector.capture.entity.CaptureJob;
import com.webcollector.capture.entity.CaptureJobStatus;
import com.webcollector.collection.entity.LinkCollection;
import com.webcollector.collection.mapper.LinkCollectionMapper;
import com.webcollector.link.entity.Link;
import com.webcollector.link.mapper.LinkMapper;
import com.webcollector.link.support.UrlNormalizer;
import com.webcollector.support.SharedTestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CaptureJobMapperTest {
    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.registerProperties(registry);
    }

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
        jdbcTemplate.update("DELETE FROM app_user");
    }

    // [测试意图] 验证任务能由 Mapper 落库，并由 MySQL 填充重试字段默认值。
    @Test
    void shouldInsertAndSelectCaptureJobWithDatabaseDefaults() {
        LinkFixture fixture = createLinkFixture("capture-defaults");
        LocalDateTime now = now();
        CaptureJob job = newJob(fixture.linkId(), fixture.userId(), now);

        int affectedRows = captureJobMapper.insert(job);

        assertThat(affectedRows).isEqualTo(1);
        assertThat(job.getId()).isNotNull();

        CaptureJob loaded = captureJobMapper.selectById(job.getId());

        assertThat(loaded).isNotNull();
        assertThat(loaded.getLinkId()).isEqualTo(fixture.linkId());
        assertThat(loaded.getRequestedBy()).isEqualTo(fixture.userId());
        assertThat(loaded.getStatus()).isEqualTo(CaptureJobStatus.PENDING);
        assertThat(loaded.getAttemptCount()).isZero();
        assertThat(loaded.getMaxAttempts()).isEqualTo(3);
        assertThat(loaded.getNextAttemptAt()).isEqualTo(now);
        assertThat(loaded.getCreatedAt()).isEqualTo(now);
        assertThat(loaded.getUpdatedAt()).isEqualTo(now);
    }

    // [测试意图] 验证任务引用不存在的链接或请求人时，数据库外键会拒绝写入。
    @Test
    void shouldRejectUnknownLinkOrRequester() {
        LinkFixture fixture = createLinkFixture("capture-invalid-fk");
        LocalDateTime now = now();

        assertThatThrownBy(() -> captureJobMapper.insert(
                newJob(99999L, fixture.userId(), now)
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> captureJobMapper.insert(
                newJob(fixture.linkId(), 99999L, now)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    // [测试意图] 验证 status 列只接受契约声明的 MySQL ENUM 值。
    @Test
    void shouldRejectStatusOutsideEnum() {
        LinkFixture fixture = createLinkFixture("capture-invalid-status");
        LocalDateTime now = now();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO capture_job
                    (link_id, requested_by, status, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, 'QUEUED', ?, ?, ?)
                """,
                fixture.linkId(),
                fixture.userId(),
                now,
                now,
                now
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    // [测试意图] 验证同一链接允许存在多条采集任务，为后续手动重采集留出数据空间。
    @Test
    void shouldAllowMultipleJobsForSameLink() {
        LinkFixture fixture = createLinkFixture("capture-multiple-jobs");
        LocalDateTime firstTime = now();
        LocalDateTime secondTime = firstTime.plusSeconds(1);

        CaptureJob first = newJob(fixture.linkId(), fixture.userId(), firstTime);
        CaptureJob second = newJob(fixture.linkId(), fixture.userId(), secondTime);

        captureJobMapper.insert(first);
        captureJobMapper.insert(second);

        assertThat(captureJobMapper.selectList(null))
                .extracting(CaptureJob::getLinkId)
                .containsExactlyInAnyOrder(fixture.linkId(), fixture.linkId());
    }

    // [测试意图] 验证删除链接时，关联的抓取任务通过外键级联删除。
    @Test
    void shouldCascadeDeleteJobsWhenLinkIsDeleted() {
        LinkFixture fixture = createLinkFixture("capture-link-cascade");
        CaptureJob job = newJob(fixture.linkId(), fixture.userId(), now());
        captureJobMapper.insert(job);

        linkMapper.deleteById(fixture.linkId());

        assertThat(captureJobMapper.selectById(job.getId())).isNull();
    }

    // [测试意图] 验证删除请求用户时，相关抓取任务也会级联消失。
    @Test
    void shouldCascadeDeleteJobsWhenRequesterIsDeleted() {
        LinkFixture fixture = createLinkFixture("capture-user-cascade");
        AppUser requester = createUser("capture-separate-requester", now());
        CaptureJob job = newJob(fixture.linkId(), requester.getId(), now());
        captureJobMapper.insert(job);

        appUserMapper.deleteById(requester.getId());

        assertThat(captureJobMapper.selectById(job.getId())).isNull();
        assertThat(linkMapper.selectById(fixture.linkId())).isNotNull();
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

    private static CaptureJob newJob(
            Long linkId,
            Long requestedBy,
            LocalDateTime now
    ) {
        CaptureJob job = new CaptureJob();
        job.setLinkId(linkId);
        job.setRequestedBy(requestedBy);
        job.setStatus(CaptureJobStatus.PENDING);
        job.setNextAttemptAt(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        return job;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(Clock.systemUTC())
                .truncatedTo(ChronoUnit.MILLIS);
    }

    private record LinkFixture(Long userId, Long linkId) {
    }
}
