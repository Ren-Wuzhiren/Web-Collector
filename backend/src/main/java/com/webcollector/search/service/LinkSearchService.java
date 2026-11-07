package com.webcollector.search.service;

import com.webcollector.auth.session.SessionTokenService;
import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import com.webcollector.link.dto.LinkSummary;
import com.webcollector.link.entity.Link;
import com.webcollector.link.mapper.LinkMapper;
import com.webcollector.search.dto.SearchCursor;
import com.webcollector.search.dto.SearchPage;
import com.webcollector.search.dto.SearchRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 用户范围内的搜索与游标分页用例。
 */
@Service
public class LinkSearchService {
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    private final LinkMapper linkMapper;
    private final SessionTokenService sessionTokenService;
    private final SearchCursorCodec cursorCodec;

    public LinkSearchService(
            LinkMapper linkMapper,
            SessionTokenService sessionTokenService,
            SearchCursorCodec cursorCodec
    ) {
        this.linkMapper = linkMapper;
        this.sessionTokenService = sessionTokenService;
        this.cursorCodec = cursorCodec;
    }

    @Transactional(readOnly = true)
    public SearchPage search(SearchRequest request) {
        if (request.limit() < MIN_LIMIT || request.limit() > MAX_LIMIT) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "limit 必须在 1 到 100 之间"
            );
        }
        if (request.from() != null
                && request.to() != null
                && !request.from().isBefore(request.to())) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "from 必须早于 to"
            );
        }

        String normalizedQuery = normalizeQuery(request.query());
        List<Long> normalizedTagIds = normalizeTagIds(request.tagIds());
        String fingerprint = cursorCodec.fingerprint(
                normalizedQuery,
                request.collectionId(),
                normalizedTagIds,
                request.from(),
                request.to()
        );
        SearchCursor cursor = decodeCursor(
                request.cursor(),
                fingerprint
        );
        Long userId = sessionTokenService.requireLoginId();

        List<Link> fetched = linkMapper.searchOwnedLinkPage(
                userId,
                normalizedQuery,
                request.collectionId(),
                normalizedTagIds,
                toUtcLocalDateTime(request.from()),
                toUtcLocalDateTime(request.to()),
                cursor == null ? null : cursor.lastId(),
                request.limit() + 1
        );

        boolean hasMore = fetched.size() > request.limit();
        List<Link> page = hasMore
                ? List.copyOf(fetched.subList(0, request.limit()))
                : fetched;
        List<LinkSummary> items = page.stream()
                .map(this::toSummary)
                .toList();
        String nextCursor = hasMore
                ? cursorCodec.encode(
                page.get(page.size() - 1).getId(),
                fingerprint
        )
                : null;

        return new SearchPage(items, nextCursor, hasMore);
    }

    private SearchCursor decodeCursor(String encoded, String fingerprint) {
        if (encoded == null) {
            return null;
        }

        SearchCursor cursor = cursorCodec.decode(encoded);
        if (!fingerprint.equals(cursor.fingerprint())) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "搜索游标与查询条件不匹配"
            );
        }
        return cursor;
    }

    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return query.trim();
    }

    private static List<Long> normalizeTagIds(List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        if (tagIds.stream().anyMatch(tagId -> tagId == null || tagId <= 0)) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "tagId 必须为正数"
            );
        }
        return tagIds.stream()
                .distinct()
                .sorted()
                .toList();
    }

    private static LocalDateTime toUtcLocalDateTime(Instant instant) {
        return instant == null
                ? null
                : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private LinkSummary toSummary(Link link) {
        return new LinkSummary(
                link.getId(),
                link.getCollectionId(),
                link.getOriginalUrl(),
                link.getTitle(),
                link.getDescription(),
                link.getCreatedAt().toInstant(ZoneOffset.UTC),
                link.getUpdatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}