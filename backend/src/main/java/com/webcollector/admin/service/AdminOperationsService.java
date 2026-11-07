package com.webcollector.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.webcollector.admin.dto.AdminFailedJobItem;
import com.webcollector.admin.dto.AdminFailedJobPage;
import com.webcollector.admin.dto.AdminOverviewResponse;
import com.webcollector.admin.dto.AdminRetryResponse;
import com.webcollector.admin.entity.AdminActionLog;
import com.webcollector.admin.mapper.AdminActionLogMapper;
import com.webcollector.admin.mapper.AdminFailedJobRow;
import com.webcollector.admin.mapper.AdminMapper;
import com.webcollector.auth.mapper.AppUserMapper;
import com.webcollector.auth.entity.AppUser;
import com.webcollector.capture.asset.service.AssetCleanupService;
import com.webcollector.capture.asset.mapper.AssetMapper;
import com.webcollector.capture.entity.CaptureJob;
import com.webcollector.capture.entity.CaptureJobStatus;
import com.webcollector.capture.mapper.CaptureJobMapper;
import com.webcollector.capture.service.CaptureJobService;
import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import com.webcollector.link.mapper.LinkMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class AdminOperationsService {

    private final AdminAuthorizationService authorizationService;
    private final AdminMapper adminMapper;
    private final AppUserMapper appUserMapper;
    private final LinkMapper linkMapper;
    private final AssetMapper assetMapper;
    private final CaptureJobMapper captureJobMapper;
    private final AssetCleanupService assetCleanupService;
    private final CaptureJobService captureJobService;
    private final AdminActionLogMapper adminActionLogMapper;
    private final Clock clock;

    public AdminOperationsService(
            AdminAuthorizationService authorizationService,
            AdminMapper adminMapper,
            AppUserMapper appUserMapper,
            LinkMapper linkMapper,
            AssetMapper assetMapper,
            CaptureJobMapper captureJobMapper,
            AssetCleanupService assetCleanupService,
            CaptureJobService captureJobService,
            AdminActionLogMapper adminActionLogMapper,
            Clock clock
    ) {
        this.authorizationService = authorizationService;
        this.adminMapper = adminMapper;
        this.appUserMapper = appUserMapper;
        this.linkMapper = linkMapper;
        this.assetMapper = assetMapper;
        this.captureJobMapper = captureJobMapper;
        this.assetCleanupService = assetCleanupService;
        this.captureJobService = captureJobService;
        this.adminActionLogMapper = adminActionLogMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminOverviewResponse overview() {
        authorizationService.requireAdmin();

        return new AdminOverviewResponse(
                countUsers(),
                countLinks(),
                countAssets(),
                countJobs(CaptureJobStatus.PENDING),
                countJobs(CaptureJobStatus.RUNNING),
                countJobs(CaptureJobStatus.SUCCEEDED),
                countJobs(CaptureJobStatus.FAILED)
        );
    }

    @Transactional(readOnly = true)
    public AdminFailedJobPage failedJobs(Long beforeId, int limit) {
        authorizationService.requireAdmin();

        List<AdminFailedJobRow> rows = adminMapper.findLatestFailedJobs(
                beforeId,
                limit + 1
        );
        boolean hasMore = rows.size() > limit;
        List<AdminFailedJobRow> visible = hasMore
                ? rows.subList(0, limit)
                : rows;
        List<AdminFailedJobItem> items = visible.stream()
                .map(this::toFailedJobItem)
                .toList();
        Long nextBeforeId = hasMore && !items.isEmpty()
                ? items.get(items.size() - 1).jobId()
                : null;

        return new AdminFailedJobPage(items, nextBeforeId);
    }

    @Transactional
    public AdminRetryResponse retryFailedJob(long jobId) {
        AppUser admin = authorizationService.requireAdmin();
        CaptureJob sourceSnapshot = adminMapper.findCaptureJob(jobId);
        if (sourceSnapshot == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (adminMapper.lockLink(sourceSnapshot.getLinkId()) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        CaptureJob source = adminMapper.lockCaptureJob(jobId);
        if (source == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        boolean newerJobExists = adminMapper.lockNewerJob(
                source.getLinkId(),
                source.getId()
        ) != null;
        if (source.getStatus() != CaptureJobStatus.FAILED || newerJobExists) {
            throw new BusinessException(
                    ErrorCode.CAPTURE_JOB_NOT_RETRYABLE
            );
        }

        assetCleanupService.enqueueForCaptureJob(source.getId());
        assetMapper.deleteByCaptureJobId(source.getId());

        LocalDateTime now = LocalDateTime.now(clock)
                .truncatedTo(ChronoUnit.MILLIS);
        var created = captureJobService.createPending(
                source.getLinkId(),
                source.getRequestedBy(),
                now
        );

        AdminActionLog audit = new AdminActionLog();
        audit.setAdminUserId(admin.getId());
        audit.setAction("RETRY_CAPTURE_JOB");
        audit.setSourceJobId(source.getId());
        audit.setCreatedJobId(created.id());
        audit.setCreatedAt(now);
        adminActionLogMapper.insert(audit);

        return new AdminRetryResponse(source.getId(), created);
    }

    private long countUsers() {
        Long count = appUserMapper.selectCount(null);
        return count == null ? 0L : count;
    }

    private long countLinks() {
        Long count = linkMapper.selectCount(null);
        return count == null ? 0L : count;
    }

    private long countAssets() {
        Long count = assetMapper.selectCount(null);
        return count == null ? 0L : count;
    }

    private long countJobs(CaptureJobStatus status) {
        Long count = captureJobMapper.selectCount(
                new LambdaQueryWrapper<CaptureJob>()
                        .eq(CaptureJob::getStatus, status)
        );
        return count == null ? 0L : count;
    }

    private AdminFailedJobItem toFailedJobItem(AdminFailedJobRow row) {
        return new AdminFailedJobItem(
                row.getJobId(),
                row.getLinkId(),
                row.getOwnerUsername(),
                sourceHost(row.getOriginalUrl()),
                row.getAttemptCount(),
                row.getMaxAttempts(),
                row.getErrorCode(),
                row.getCreatedAt().toInstant(ZoneOffset.UTC),
                row.getFinishedAt() == null
                        ? null
                        : row.getFinishedAt().toInstant(ZoneOffset.UTC)
        );
    }

    private String sourceHost(String originalUrl) {
        try {
            return URI.create(originalUrl).getHost();
        }
        catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
