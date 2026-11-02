package com.webcollector.capture.content;

import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import com.webcollector.link.mapper.LinkMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;

/** Persists extracted plain text for later full-text indexing. */
@Service
public class ReadableContentPersistenceService {

    private final ReadableContentExtractor extractor;
    private final LinkMapper linkMapper;

    public ReadableContentPersistenceService(
            ReadableContentExtractor extractor,
            LinkMapper linkMapper
    ) {
        this.extractor = extractor;
        this.linkMapper = linkMapper;
    }

    @Transactional
    public ReadableContent persist(Long linkId, Path capturedHtml) {
        if (linkId == null || linkId <= 0) {
            throw new IllegalArgumentException("linkId 必须为正数");
        }

        ReadableContent content = extractor.extract(capturedHtml);
        int updatedRows = linkMapper.updateTextContentById(linkId, content.text());
        if (updatedRows == 0 && linkMapper.selectById(linkId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        return content;
    }
}
