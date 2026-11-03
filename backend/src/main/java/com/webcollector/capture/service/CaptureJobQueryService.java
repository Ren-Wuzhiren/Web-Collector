package com.webcollector.capture.service;

import com.webcollector.auth.session.SessionTokenService;
import com.webcollector.capture.asset.dto.AssetSummary;
import com.webcollector.capture.asset.mapper.AssetMapper;
import com.webcollector.capture.dto.CaptureJobDetail;
import com.webcollector.capture.entity.CaptureJob;
import com.webcollector.capture.mapper.CaptureJobMapper;
import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;

@Service
public class CaptureJobQueryService {

    private final CaptureJobMapper captureJobMapper;
    private final AssetMapper assetMapper;
    private final SessionTokenService sessionTokenService;

    public CaptureJobQueryService(
            CaptureJobMapper captureJobMapper,
            AssetMapper assetMapper,
            SessionTokenService sessionTokenService
    ) {
        this.captureJobMapper = captureJobMapper;
        this.assetMapper = assetMapper;
        this.sessionTokenService = sessionTokenService;
    }

    @Transactional(readOnly = true)
    public CaptureJobDetail getOwned(long jobId) {
        CaptureJob job = captureJobMapper.selectOwnedById(
                jobId,
                sessionTokenService.requireLoginId()
        );
        if (job == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        return new CaptureJobDetail(
                job.getId(),
                job.getLinkId(),
                job.getStatus(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                job.getErrorCode(),
                job.getErrorMessage(),
                job.getNextAttemptAt().toInstant(ZoneOffset.UTC),
                instantOrNull(job.getStartedAt()),
                instantOrNull(job.getFinishedAt()),
                assetMapper.findByCaptureJobId(jobId).stream()
                        .map(asset -> new AssetSummary(
                                asset.getId(),
                                asset.getFormat(),
                                asset.getMimeType(),
                                asset.getFileSizeBytes(),
                                asset.getCreatedAt().toInstant(ZoneOffset.UTC)
                        ))
                        .toList()
        );
    }

    private java.time.Instant instantOrNull(java.time.LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
