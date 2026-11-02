package com.webcollector.capture.worker;

import com.webcollector.capture.asset.entity.AssetFormat;
import com.webcollector.capture.asset.service.AssetRegistrationService;
import com.webcollector.capture.browser.BrowserCaptureArtifacts;
import com.webcollector.capture.browser.BrowserHtmlCapture;
import com.webcollector.capture.browser.CaptureFormatResult;
import com.webcollector.capture.browser.CaptureFormatStatus;
import com.webcollector.capture.content.ReadableContentPersistenceService;
import com.webcollector.capture.dto.CaptureJobSummary;
import com.webcollector.capture.service.CaptureJobService;
import com.webcollector.link.entity.Link;
import com.webcollector.link.mapper.LinkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Component
public class CaptureWorker {

    private static final Logger logger = LoggerFactory.getLogger(CaptureWorker.class);
    private static final String LINK_NOT_FOUND = "CAPTURE_LINK_NOT_FOUND";
    private static final String EXECUTION_FAILED = "CAPTURE_EXECUTION_FAILED";

    private final CaptureJobService captureJobService;
    private final LinkMapper linkMapper;
    private final BrowserHtmlCapture browserHtmlCapture;
    private final AssetRegistrationService assetRegistrationService;
    private final ReadableContentPersistenceService readableContentPersistenceService;
    private final String workerId;
    private final Duration lease;
    private final Path workRoot;
    private final Clock clock;

    public CaptureWorker(
            CaptureJobService captureJobService,
            LinkMapper linkMapper,
            BrowserHtmlCapture browserHtmlCapture,
            AssetRegistrationService assetRegistrationService,
            ReadableContentPersistenceService readableContentPersistenceService,
            Clock clock,
            @Value("${app.capture.worker-id:capture-worker-local}") String workerId,
            @Value("${app.capture.lease-seconds:120}") long leaseSeconds,
            @Value("${app.capture.work-root:./data/capture-work}") String workRoot
    ) throws IOException {
        if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
            throw new IllegalArgumentException("app.capture.worker-id 必须为 1 到 100 个字符");
        }

        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("app.capture.lease-seconds 必须大于零");
        }

        this.captureJobService = captureJobService;
        this.linkMapper = linkMapper;
        this.browserHtmlCapture = browserHtmlCapture;
        this.assetRegistrationService = assetRegistrationService;
        this.readableContentPersistenceService = readableContentPersistenceService;
        this.clock = clock;
        this.workerId = workerId;
        this.lease = Duration.ofSeconds(leaseSeconds);

        Path configuredRoot = Path.of(workRoot).toAbsolutePath().normalize();
        Files.createDirectories(configuredRoot);
        this.workRoot = configuredRoot.toRealPath();
    }

    public Optional<CaptureJobSummary> runOnce() {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        Optional<CaptureJobSummary> claimed = captureJobService.claimNext(
                workerId,
                now.plus(lease)
        );
        if (claimed.isEmpty()) {
            return Optional.empty();
        }

        CaptureJobSummary job = claimed.orElseThrow();
        Link link = linkMapper.selectById(job.linkId());
        if (link == null) {
            return Optional.of(captureJobService.fail(
                    job.id(),
                    LINK_NOT_FOUND,
                    "任务关联的链接不存在"
            ));
        }

        Path jobDirectory = null;
        try {
            try {
                jobDirectory = createJobDirectory(job.id());
                BrowserCaptureArtifacts artifacts = browserHtmlCapture.captureSnapshot(
                        link.getOriginalUrl(),
                        jobDirectory
                );
                if (!artifacts.hasAnySuccess()) {
                    throw new IllegalStateException("快照没有成功生成的格式");
                }

                persistReadableText(job, artifacts.html());
                registerSucceededFormats(job.id(), artifacts);
            } catch (Exception failure) {
                logger.warn(
                        "Capture job {} failed during execution ({})",
                        job.id(),
                        failure.getClass().getSimpleName()
                );
                return Optional.of(captureJobService.fail(
                        job.id(),
                        EXECUTION_FAILED,
                        "抓取、正文或资产登记失败: " + failure.getClass().getSimpleName()
                ));
            }
            return Optional.of(captureJobService.complete(job.id()));
        } finally {
            if (jobDirectory != null) {
                try {
                    cleanupWorkDirectory(jobDirectory);
                } catch (IOException | RuntimeException cleanupFailure) {
                    logger.warn(
                            "Temporary directory cleanup for capture job {} failed ({})",
                            job.id(),
                            cleanupFailure.getClass().getSimpleName()
                    );
                }
            }
        }
    }

    private void persistReadableText(
            CaptureJobSummary job,
            CaptureFormatResult html
    ) {
        if (html.status() == CaptureFormatStatus.SUCCESS) {
            readableContentPersistenceService.persist(
                    job.linkId(),
                    html.path()
            );
        }
    }

    private void registerSucceededFormats(
            Long jobId,
            BrowserCaptureArtifacts artifacts
    ) {
        registerIfSucceeded(jobId, artifacts.html(), AssetFormat.HTML);
        registerIfSucceeded(jobId, artifacts.pdf(), AssetFormat.PDF);
        registerIfSucceeded(jobId, artifacts.screenshot(), AssetFormat.SCREENSHOT);
    }

    private void registerIfSucceeded(
            Long jobId,
            CaptureFormatResult result,
            AssetFormat format
    ) {
        if (result.status() == CaptureFormatStatus.SUCCESS) {
            assetRegistrationService.register(jobId, format, result.path());
        }
    }

    private Path createJobDirectory(Long jobId) throws IOException {
        Path directory = workRoot.resolve(
                "job-" + jobId + "-" + UUID.randomUUID()
        ).normalize();
        if (!directory.startsWith(workRoot) || directory.equals(workRoot)) {
            throw new IOException("Capture work directory resolves outside its root");
        }
        return Files.createDirectory(directory);
    }

    void cleanupWorkDirectory(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(workRoot) || normalized.equals(workRoot)) {
            throw new IOException("Refusing to clean a path outside the capture work root");
        }
        if (!Files.exists(normalized)) {
            return;
        }

        Path realDirectory = normalized.toRealPath();
        if (!realDirectory.startsWith(workRoot) || realDirectory.equals(workRoot)) {
            throw new IOException("Capture work directory resolves outside its root");
        }

        Files.walkFileTree(realDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
