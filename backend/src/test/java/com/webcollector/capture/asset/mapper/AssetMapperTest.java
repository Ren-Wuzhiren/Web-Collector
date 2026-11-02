package com.webcollector.capture.asset.mapper;

import com.webcollector.auth.entity.AppUser;
import com.webcollector.auth.mapper.AppUserMapper;
import com.webcollector.capture.asset.entity.Asset;
import com.webcollector.capture.asset.entity.AssetFormat;
import com.webcollector.capture.asset.service.AssetCleanupService;
import com.webcollector.capture.asset.service.AssetCleanupWorker;
import com.webcollector.capture.asset.service.AssetRegistrationService;
import com.webcollector.capture.asset.service.AssetService;
import com.webcollector.capture.asset.storage.AssetStorage;
import com.webcollector.capture.entity.CaptureJob;
import com.webcollector.capture.entity.CaptureJobStatus;
import com.webcollector.capture.mapper.CaptureJobMapper;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AssetMapperTest {
    private static final Path STORAGE_ROOT = createStorageRoot();

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.registerProperties(registry);
        registry.add("app.storage.root", () -> STORAGE_ROOT.toString());
        registry.add("app.asset.cleanup.poll-interval-ms", () -> "3600000");
    }

    @Autowired private AssetMapper assetMapper;
    @Autowired private AssetRegistrationService assetRegistrationService;
    @Autowired private AssetCleanupService assetCleanupService;
    @Autowired private AssetCleanupWorker assetCleanupWorker;
    @Autowired private AssetService assetService;
    @Autowired private AssetStorage assetStorage;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private CaptureJobMapper captureJobMapper;
    @Autowired private AppUserMapper appUserMapper;
    @Autowired private LinkCollectionMapper linkCollectionMapper;
    @Autowired private LinkMapper linkMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM asset_cleanup_job");
        jdbcTemplate.update("DELETE FROM app_user");
        jdbcTemplate.update("DELETE FROM asset");
    }

    // [测试意图] 验证 MyBatis-Plus 能将资产元数据写入 MySQL 并正确读回。
    @Test
    void shouldRegisterAndLoadAssetMetadata() throws IOException {
        CaptureJob job = createJob("asset-mapping");
        Path file = STORAGE_ROOT.resolve("mapping/page.html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<html>mysql</html>");

        Asset registered = assetRegistrationService.register(
                job.getId(), AssetFormat.HTML, file);
        Asset loaded = assetMapper.selectById(registered.getId());

        assertThat(loaded).isNotNull();
        assertThat(loaded.getLinkId()).isEqualTo(job.getLinkId());
        assertThat(loaded.getCaptureJobId()).isEqualTo(job.getId());
        assertThat(loaded.getFormat()).isEqualTo(AssetFormat.HTML);
        assertThat(loaded.getStoragePath()).endsWith(".html");
        assertThat(loaded.getMimeType()).isEqualTo("text/html");
        assertThat(loaded.getFileSizeBytes()).isEqualTo(
                "<html>mysql</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThat(loaded.getSha256()).isEqualTo(registered.getSha256());
    }

    // [测试意图] 验证数据库唯一约束拒绝同一任务重复登记同一格式。
    @Test
    void shouldRejectDuplicateFormatForSameCaptureJob() throws IOException {
        CaptureJob job = createJob("asset-duplicate");
        Path firstFile = STORAGE_ROOT.resolve("duplicate/first.html");
        Path secondFile = STORAGE_ROOT.resolve("duplicate/second.html");
        Files.createDirectories(firstFile.getParent());
        Files.writeString(firstFile, "first");
        Files.writeString(secondFile, "second");
        assetRegistrationService.register(job.getId(), AssetFormat.HTML, firstFile);

        assertThatThrownBy(() -> assetRegistrationService.register(
                job.getId(), AssetFormat.HTML, secondFile))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // [测试意图] 验证链接外键与任务外键都由真实 MySQL 强制执行。
    @Test
    void shouldRejectAssetWithUnknownLinkOrCaptureJob() {
        Asset unknownLink = newAsset(999999L, createJob("asset-fk-link").getId());
        Asset unknownJob = newAsset(createJob("asset-fk-job").getLinkId(), 999999L);

        assertThatThrownBy(() -> assetMapper.insert(unknownLink))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> assetMapper.insert(unknownJob))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // [测试意图] 验证删除链接级联删除资产行时，独立清理记录仍保留并可删除文件。
    @Test
    void shouldKeepCleanupJobAfterLinkCascadeDeletesAsset() throws IOException {
        CaptureJob job = createJob("asset-cleanup-cascade");
        Path source = STORAGE_ROOT.resolve("cleanup/cascade.html");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "<html>cleanup</html>");
        Asset registered = assetRegistrationService.register(
                job.getId(), AssetFormat.HTML, source);
        Path managedFile = assetStorage.resolve(registered.getStoragePath());

        new TransactionTemplate(transactionManager).execute(status -> {
            assetCleanupService.enqueueForLink(job.getLinkId());
            linkMapper.deleteById(job.getLinkId());
            return null;
        });

        assertThat(assetMapper.selectById(registered.getId())).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM asset_cleanup_job WHERE storage_path = ?",
                Integer.class,
                registered.getStoragePath())).isEqualTo(1);
        assertThat(managedFile).exists();

        assetCleanupWorker.processDueJobs();

        assertThat(managedFile).doesNotExist();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM asset_cleanup_job WHERE storage_path = ?",
                Integer.class,
                registered.getStoragePath())).isZero();
    }

    // [测试意图] 验证授权删除资产会在删除数据库行后保留独立文件清理记录。
    @Test
    void shouldQueueFileCleanupWhenDeletingOwnedAsset() throws IOException {
        CaptureJob job = createJob("asset-delete-owned");
        Path source = STORAGE_ROOT.resolve("cleanup/owned.html");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "<html>owned</html>");
        Asset registered = assetRegistrationService.register(
                job.getId(), AssetFormat.HTML, source);
        Path managedFile = assetStorage.resolve(registered.getStoragePath());

        assetService.deleteOwned(registered.getId(), job.getRequestedBy());

        assertThat(assetMapper.selectById(registered.getId())).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM asset_cleanup_job WHERE storage_path = ?",
                Integer.class,
                registered.getStoragePath())).isEqualTo(1);
        assetCleanupWorker.processDueJobs();
        assertThat(managedFile).doesNotExist();
    }

    // [测试意图] 验证并发领取者不会同时拿到同一条待清理记录。
    @Test
    void shouldAllowOnlyOneConcurrentCleanupClaim() throws Exception {
        assetCleanupService.enqueue("concurrent/missing.html");
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<com.webcollector.capture.asset.entity.AssetCleanupJob> claim = () -> {
                ready.countDown();
                start.await();
                return assetCleanupService.claimNext();
            };
            var first = executor.submit(claim);
            var second = executor.submit(claim);
            ready.await();
            start.countDown();

            var firstResult = first.get();
            var secondResult = second.get();
            assertThat(java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(java.util.Objects::nonNull)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    // [测试意图] 验证链接删除事务回滚时清理记录也一起回滚，资产行和文件保持可用。
    @Test
    void shouldRollbackCleanupRecordWhenLinkDeletionRollsBack() throws IOException {
        CaptureJob job = createJob("asset-cleanup-rollback");
        Path source = STORAGE_ROOT.resolve("cleanup/rollback.html");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "<html>rollback</html>");
        Asset registered = assetRegistrationService.register(
                job.getId(), AssetFormat.HTML, source);
        Path managedFile = assetStorage.resolve(registered.getStoragePath());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new TransactionTemplate(transactionManager).execute(status -> {
                    assetCleanupService.enqueueForLink(job.getLinkId());
                    linkMapper.deleteById(job.getLinkId());
                    throw new IllegalStateException("force rollback");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(assetMapper.selectById(registered.getId())).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM asset_cleanup_job WHERE storage_path = ?",
                Integer.class,
                registered.getStoragePath())).isZero();
        assertThat(managedFile).exists();
    }

    // [测试意图] 验证过期租约能重新领取，避免进程崩溃后清理任务永久卡住。
    @Test
    void shouldReclaimCleanupJobAfterLeaseExpires() {
        assetCleanupService.enqueue("lease/missing.html");
        var firstClaim = assetCleanupService.claimNext();
        assertThat(firstClaim).isNotNull();
        jdbcTemplate.update(
                "UPDATE asset_cleanup_job SET locked_until = ? WHERE id = ?",
                now().minusSeconds(1),
                firstClaim.getId());

        var secondClaim = assetCleanupService.claimNext();

        assertThat(secondClaim).isNotNull();
        assertThat(secondClaim.getId()).isEqualTo(firstClaim.getId());
        assertThat(secondClaim.getAttemptCount()).isEqualTo(2L);
    }

    // [测试意图] 验证清理失败会持久化错误和未来重试时间，不丢弃任务。
    @Test
    void shouldPersistCleanupRetryAfterFailure() {
        assetCleanupService.enqueue("retry/missing.html");
        var claimed = assetCleanupService.claimNext();

        assetCleanupService.retry(claimed, new IOException("disk unavailable"));

        var persisted = jdbcTemplate.queryForMap(
                "SELECT attempt_count, next_attempt_at, locked_until, last_error "
                        + "FROM asset_cleanup_job WHERE id = ?",
                claimed.getId());
        assertThat(((Number) persisted.get("attempt_count")).longValue()).isEqualTo(1L);
        assertThat(persisted.get("locked_until")).isNull();
        assertThat(persisted.get("last_error")).isEqualTo("IOException: disk unavailable");
        assertThat(persisted.get("next_attempt_at")).isNotNull();
    }

    private CaptureJob createJob(String username) {
        LocalDateTime now = now();
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash("$2a$10$placeholder");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        appUserMapper.insert(user);

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

        CaptureJob job = new CaptureJob();
        job.setLinkId(link.getId());
        job.setRequestedBy(user.getId());
        job.setStatus(CaptureJobStatus.PENDING);
        job.setNextAttemptAt(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        captureJobMapper.insert(job);
        return job;
    }

    private Asset newAsset(Long linkId, Long jobId) {
        Asset asset = new Asset();
        asset.setLinkId(linkId);
        asset.setCaptureJobId(jobId);
        asset.setFormat(AssetFormat.HTML);
        asset.setStoragePath("fixture/page.html");
        asset.setMimeType("text/html");
        asset.setFileSizeBytes(1L);
        asset.setSha256("0".repeat(64));
        asset.setCreatedAt(now());
        return asset;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(Clock.systemUTC())
                .truncatedTo(ChronoUnit.MILLIS);
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("webcollector-asset-it-")
                    .toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
