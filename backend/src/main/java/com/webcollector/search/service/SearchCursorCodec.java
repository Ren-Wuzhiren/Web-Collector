package com.webcollector.search.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import com.webcollector.search.dto.SearchCursor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 搜索游标编解码器。
 *
 * <p>[边界] Base64 只负责 URL 安全传输，不是加密；用户隔离始终由 SQL 的
 * user_id 条件保证。</p>
 */
@Component
public class SearchCursorCodec {
    private static final int CURRENT_VERSION = 1;

    private final ObjectMapper objectMapper;

    public SearchCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(Long lastId, String fingerprint) {
        if (lastId == null || lastId <= 0
                || fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("搜索游标参数不完整");
        }

        SearchCursor cursor = new SearchCursor(
                CURRENT_VERSION,
                lastId,
                fingerprint
        );

        try {
            byte[] json = objectMapper.writeValueAsBytes(cursor);
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码搜索游标", exception);
        }
    }

    public SearchCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw invalidCursor();
        }

        try {
            byte[] json = Base64.getUrlDecoder().decode(encoded);
            SearchCursor cursor = objectMapper.readValue(json, SearchCursor.class);

            if (cursor == null
                    || cursor.version() != CURRENT_VERSION
                    || cursor.lastId() == null
                    || cursor.lastId() <= 0
                    || cursor.fingerprint() == null
                    || cursor.fingerprint().isBlank()) {
                throw invalidCursor();
            }

            return cursor;
        } catch (IllegalArgumentException | IOException exception) {
            throw invalidCursor();
        }
    }

    public String fingerprint(
            String query,
            Long collectionId,
            List<Long> tagIds,
            Instant from,
            Instant to
    ) {
        StringBuilder canonical = new StringBuilder();
        appendPart(canonical, query);
        appendPart(
                canonical,
                collectionId == null ? null : collectionId.toString()
        );
        appendPart(
                canonical,
                tagIds == null
                        ? null
                        : tagIds.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","))
        );
        appendPart(canonical, from == null ? null : from.toString());
        appendPart(canonical, to == null ? null : to.toString());

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JRE 不提供 SHA-256", exception);
        }
    }

    private static void appendPart(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.length()).append(':').append(value);
        }

        target.append('|');
    }

    private static BusinessException invalidCursor() {
        return new BusinessException(
                ErrorCode.INVALID_REQUEST,
                "搜索游标无效"
        );
    }
}
