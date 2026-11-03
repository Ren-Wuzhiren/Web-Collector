package com.webcollector.capture.worker;

import com.webcollector.auth.entity.AppUser;
import com.webcollector.auth.mapper.AppUserMapper;
import com.webcollector.capture.asset.entity.Asset;
import com.webcollector.capture.asset.entity.AssetFormat;
import com.webcollector.capture.asset.mapper.AssetMapper;
import com.webcollector.capture.browser.BrowserCaptureArtifacts;
import com.webcollector.capture.browser.BrowserHtmlCapture;
import com.webcollector.capture.browser.CaptureFormat;
import com.webcollector.capture.browser.CaptureFormatResult;
import com.webcollector.capture.dto.CaptureJobSummary;
import com.webcollector.capture.entity.CaptureJob;
import com.webcollector.capture.entity.CaptureJobStatus;
import com.webcollector.capture.mapper.CaptureJobMapper;
import com.webcollector.capture.service.CaptureJobService;
import com.webcollector.collection.entity.LinkCollection;
import com.webcollector.collection.mapper.LinkCollectionMapper;
import com.webcollector.link.entity.Link;
import com.webcollector.link.mapper.LinkMapper;
import com.webcollector.link.support.UrlNormalizer;
import com.webcollector.support.SharedTestContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CaptureWorkerIntegrationTest {

    private static final Path WORK_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"),
            "webcollector-capture-worker-it-" + UUID.randomUUID()
    );
    private static final Path STORAGE_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"),
            "webcollector-capture-assets-it-" + UUID.randomUUID()
    );

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.registerProperties(registry);
        registry.add("app.capture.work-root", WORK_ROOT::toString);
        registry.add("app.storage.root", STORAGE_ROOT::toString);
    }

    @AfterAll
    static void cleanWorkRoot() throws IOException {
        if (Files.exists(WORK_ROOT)) {
            deleteTree(WORK_ROOT);
        }
        if (Files.exists(STORAGE_ROOT)) {
            deleteTree(STORAGE_ROOT);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                        throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
    }

    @Autowired
    private CaptureWorker captureWorker;

    @Autowired
    private CaptureJobService captureJobService;

    @Autowired
    private Clock clock;

    @Autowired
    private CaptureJobMapper captureJobMapper;

    @Autowired
    private LinkMapper linkMapper;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private AppUserMapper appUserMapper;

    @Autowired
    private LinkCollectionMapper collectionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private BrowserHtmlCapture browserHtmlCapture;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM app_user");
    }

    // [测试意图] 验证真实 MySQL 中任务成功后状态为 SUCCEEDED，且中文正文写入 link.text_content。
    @Test
    void shouldCaptureAndPersistReadableTextInMySql() throws Exception {
        Link link = createLink("worker-success");
        Long jobId = createPendingJob(link.getId(), link.getUserId());
        when(browserHtmlCapture.captureSnapshot(eq(link.getOriginalUrl()), any(Path.class)))
                .thenAnswer(invocation -> {
                    Path directory = invocation.getArgument(1);
                    Path html = directory.resolve("page.html");
                    Path pdf = directory.resolve("page.pdf");
                    Path screenshot = directory.resolve("screenshot.png");
                    Files.writeString(
                            html,
                            "<article><p>真实 Worker 写入的中文正文。</p><nav>噪音</nav></article>"
                    );
                    Files.write(pdf, new byte[]{0x25, 0x50, 0x44, 0x46});
                    Files.write(screenshot, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47});
                    return new BrowserCaptureArtifacts(
                            CaptureFormatResult.success(CaptureFormat.HTML, html),
                            CaptureFormatResult.success(CaptureFormat.PDF, pdf),
                            CaptureFormatResult.success(CaptureFormat.SCREENSHOT, screenshot)
                    );
                });

        CaptureJobSummary result = captureWorker.runOnce().orElseThrow();

        assertThat(result.id()).isEqualTo(jobId);
        assertThat(result.status()).isEqualTo(CaptureJobStatus.SUCCEEDED);
        assertThat(linkMapper.selectById(link.getId()).getTextContent())
                .isEqualTo("真实 Worker 写入的中文正文。");
        assertThat(captureJobMapper.selectById(jobId).getStatus())
                .isEqualTo(CaptureJobStatus.SUCCEEDED);
        assertThat(assetMapper.selectList(null))
                .extracting(Asset::getFormat)
                .containsExactlyInAnyOrder(
                        AssetFormat.HTML,
                        AssetFormat.PDF,
                        AssetFormat.SCREENSHOT
                );
        assertThat(captureJobMapper.selectOwnedById(jobId, link.getUserId()))
                .isNotNull();
        assertThat(captureJobMapper.selectOwnedById(jobId, -1L)).isNull();
        assertThat(assetMapper.findOwnedByLink(link.getId(), link.getUserId()))
                .hasSize(3);
        assertThat(assetMapper.findOwnedByLink(link.getId(), -1L)).isEmpty();
    }

    // [测试意图] 验证真实状态机在抓取失败后增加尝试次数并安排重试。
    @Test
    void shouldRequeueCaptureFailureUsingExistingRetryPolicy() throws Exception {
        Link link = createLink("worker-failure");
        Long jobId = createPendingJob(link.getId(), link.getUserId());
        doThrow(new IllegalStateException("simulated capture failure"))
                .when(browserHtmlCapture).captureSnapshot(
                        eq(link.getOriginalUrl()),
                        any(Path.class)
                );

        CaptureJobSummary result = captureWorker.runOnce().orElseThrow();
        CaptureJob persisted = captureJobMapper.selectById(jobId);

        assertThat(result.status()).isEqualTo(CaptureJobStatus.PENDING);
        assertThat(persisted.getStatus()).isEqualTo(CaptureJobStatus.PENDING);
        assertThat(persisted.getAttemptCount()).isEqualTo(1);
        assertThat(persisted.getErrorCode()).isEqualTo("CAPTURE_EXECUTION_FAILED");
        assertThat(persisted.getNextAttemptAt()).isAfter(persisted.getStartedAt());
    }

    private Link createLink(String usernamePrefix) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        String suffix = UUID.randomUUID().toString().replace("-", "");

        AppUser user = new AppUser();
        user.setUsername(usernamePrefix.substring(0, Math.min(8, usernamePrefix.length()))
                + "-" + suffix.substring(0, 20));
        user.setEmail(usernamePrefix + "-" + suffix + "@example.com");
        user.setPasswordHash("$2a$10$placeholder");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        appUserMapper.insert(user);

        LinkCollection collection = new LinkCollection();
        collection.setUserId(user.getId());
        collection.setName("Reading");
        collection.setDescription("");
        collection.setIsInbox(false);
        collection.setCreatedAt(now);
        collection.setUpdatedAt(now);
        collectionMapper.insert(collection);

        String url = "https://example.com/" + suffix;
        Link link = new Link();
        link.setUserId(user.getId());
        link.setCollectionId(collection.getId());
        link.setOriginalUrl(url);
        link.setNormalizedUrl(UrlNormalizer.normalize(url));
        link.setUrlHash(UrlNormalizer.sha256(link.getNormalizedUrl()));
        link.setTitle("");
        link.setDescription("");
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        linkMapper.insert(link);
        return link;
    }

    private Long createPendingJob(Long linkId, Long userId) {
        return captureJobService.createPending(
                linkId,
                userId,
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS)
        ).id();
    }
}
