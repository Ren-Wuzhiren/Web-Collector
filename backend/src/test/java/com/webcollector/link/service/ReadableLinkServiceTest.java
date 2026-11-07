package com.webcollector.link.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.webcollector.auth.session.SessionTokenService;
import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import com.webcollector.link.dto.ReadableLinkResponse;
import com.webcollector.link.entity.Link;
import com.webcollector.link.mapper.LinkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadableLinkServiceTest {
    private static final Instant FIXED_INSTANT =
            Instant.parse("2026-11-03T10:00:00Z");
    private static final LocalDateTime FIXED_TIME =
            LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

    private LinkMapper linkMapper;
    private SessionTokenService sessionTokenService;
    private ReadableLinkService service;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(LinkMapper.class);

        linkMapper = mock(LinkMapper.class);
        sessionTokenService = mock(SessionTokenService.class);
        service = new ReadableLinkService(linkMapper, sessionTokenService);

        when(sessionTokenService.requireLoginId()).thenReturn(7L);
    }

    // [测试意图] 验证本人正文按原样映射到响应，包含链接摘要、UTC 时间和换行。
    @Test
    void shouldReturnOwnedReadableContentWithoutChangingParagraphs() {
        Link link = linkWithContent("  第一段\n\n第二段  ");
        when(linkMapper.selectOne(any())).thenReturn(link);

        ReadableLinkResponse response = service.getReadableLink(42L);

        assertThat(response.textContent())
                .isEqualTo("  第一段\n\n第二段  ");
        assertThat(response.link().id()).isEqualTo(42L);
        assertThat(response.link().collectionId()).isEqualTo(8L);
        assertThat(response.link().originalUrl())
                .isEqualTo("https://example.com/article");
        assertThat(response.link().title()).isEqualTo("示例文章");
        assertThat(response.link().description()).isEqualTo("示例描述");
        assertThat(response.link().createdAt()).isEqualTo(FIXED_INSTANT);
        assertThat(response.link().updatedAt()).isEqualTo(FIXED_INSTANT);
        verify(sessionTokenService).requireLoginId();
    }

    // [测试意图] 验证链接不存在或不属于当前用户时统一返回 NOT_FOUND。
    @Test
    void shouldRejectMissingOrUnownedLink() {
        when(linkMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.getReadableLink(99L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.NOT_FOUND)
                );
    }

    // [测试意图] 验证正文为 null、空串、空格或换行时均视为尚未生成。
    @Test
    void shouldRejectMissingOrBlankReadableContent() {
        assertReadableContentNotFound(null);
        assertReadableContentNotFound("");
        assertReadableContentNotFound("   ");
        assertReadableContentNotFound("\n\t");
    }

    private void assertReadableContentNotFound(String textContent) {
        Link link = linkWithContent(textContent);
        when(linkMapper.selectOne(any())).thenReturn(link);

        assertThatThrownBy(() -> service.getReadableLink(42L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.getErrorCode())
                                    .isEqualTo(ErrorCode.NOT_FOUND);
                            assertThat(exception.getMessage())
                                    .isEqualTo("正文尚未生成");
                        }
                );
    }

    private Link linkWithContent(String textContent) {
        Link link = new Link();
        link.setId(42L);
        link.setUserId(7L);
        link.setCollectionId(8L);
        link.setOriginalUrl("https://example.com/article");
        link.setTitle("示例文章");
        link.setDescription("示例描述");
        link.setTextContent(textContent);
        link.setCreatedAt(FIXED_TIME);
        link.setUpdatedAt(FIXED_TIME);
        return link;
    }
}
