package com.webcollector.search.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import com.webcollector.search.dto.SearchCursor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchCursorCodecTest {
    private SearchCursorCodec codec;

    @BeforeEach
    void setUp() {
        codec = new SearchCursorCodec(new ObjectMapper());
    }

    // [测试意图] 验证合法游标可完整往返，且条件摘要对输入变化敏感。
    @Test
    void shouldEncodeAndDecodeCursor() {
        String fingerprint = codec.fingerprint(
                "spring",
                7L,
                List.of(2L, 3L),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z")
        );

        SearchCursor decoded = codec.decode(codec.encode(42L, fingerprint));

        assertThat(decoded.version()).isEqualTo(1);
        assertThat(decoded.lastId()).isEqualTo(42L);
        assertThat(decoded.fingerprint()).isEqualTo(fingerprint);
        assertThat(codec.fingerprint(
                "spring",
                7L,
                List.of(2L, 3L),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z")
        )).isEqualTo(fingerprint);
        assertThat(codec.fingerprint(
                "spring",
                7L,
                List.of(2L, 4L),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z")
        )).isNotEqualTo(fingerprint);
    }

    // [测试意图] 验证损坏 Base64、未知版本和缺字段均转换为统一 400 错误。
    @Test
    void shouldRejectMalformedOrIncompleteCursor() {
        assertInvalid("%%%");
        assertInvalid(encoded("""
                {"version":2,"lastId":42,"fingerprint":"x"}
                """));
        assertInvalid(encoded("""
                {"version":1,"lastId":42}
                """));
        assertInvalid(encoded("""
                {"version":1,"lastId":0,"fingerprint":"x"}
                """));
    }

    private void assertInvalid(String cursor) {
        assertThatThrownBy(() -> codec.decode(cursor))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REQUEST)
                );
    }

    private static String encoded(String json) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
