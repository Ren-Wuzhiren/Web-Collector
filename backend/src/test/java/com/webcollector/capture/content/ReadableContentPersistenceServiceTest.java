package com.webcollector.capture.content;

import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import com.webcollector.link.entity.Link;
import com.webcollector.link.mapper.LinkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class ReadableContentPersistenceServiceTest {

    @TempDir
    Path tempDirectory;

    private LinkMapper linkMapper;
    private ReadableContentPersistenceService service;

    @BeforeEach
    void setUp() {
        linkMapper = mock(LinkMapper.class);
        service = new ReadableContentPersistenceService(
                new ReadableContentExtractor(),
                linkMapper
        );
    }

    // [测试意图] 验证正文抽取成功后将纯文本写入 link.text_content。
    @Test
    void shouldPersistExtractedText() throws IOException {
        Path html = writeHtml("<article><p>你好，正文可检索。</p></article>");
        when(linkMapper.updateTextContentById(12L, "你好，正文可检索。"))
                .thenReturn(1);

        ReadableContent result = service.persist(12L, html);

        assertThat(result.text()).isEqualTo("你好，正文可检索。");
        verify(linkMapper).updateTextContentById(12L, "你好，正文可检索。");
    }

    // [测试意图] 验证有效 HTML 即使抽取出空正文也会清空旧文本并成功完成。
    @Test
    void shouldPersistEmptyExtractedText() throws IOException {
        Path html = writeHtml("<html><body><nav>导航</nav></body></html>");
        when(linkMapper.updateTextContentById(13L, "")).thenReturn(1);

        ReadableContent result = service.persist(13L, html);

        assertThat(result.text()).isEmpty();
        verify(linkMapper).updateTextContentById(13L, "");
    }

    // [测试意图] 验证空白 HTML 在抽取阶段失败，不会覆盖数据库中的旧正文。
    @Test
    void shouldRejectBlankHtmlBeforeDatabaseUpdate() throws IOException {
        Path html = writeHtml("  \n\t ");

        assertThatThrownBy(() -> service.persist(14L, html))
                .isInstanceOf(IllegalArgumentException.class);

        verify(linkMapper, never()).updateTextContentById(any(), any());
    }

    // [测试意图] 验证重复写入相同正文导致更新行数为零时，不误判为链接不存在。
    @Test
    void shouldAcceptUnchangedTextForExistingLink() throws IOException {
        Path html = writeHtml("<article>same</article>");
        when(linkMapper.updateTextContentById(15L, "same")).thenReturn(0);
        when(linkMapper.selectById(15L)).thenReturn(new Link());

        assertThat(service.persist(15L, html).text()).isEqualTo("same");
    }

    // [测试意图] 验证缺失链接或数据库写入异常不会被服务伪报为成功。
    @Test
    void shouldPropagateMissingLinkAndDatabaseFailure() throws IOException {
        Path html = writeHtml("<article>text</article>");
        when(linkMapper.updateTextContentById(16L, "text")).thenReturn(0);
        when(linkMapper.selectById(16L)).thenReturn(null);
        when(linkMapper.updateTextContentById(17L, "text"))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> service.persist(16L, html))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessException) exception).getErrorCode()
                ).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.persist(17L, html))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    private Path writeHtml(String html) throws IOException {
        Path path = tempDirectory.resolve("page-" + System.nanoTime() + ".html");
        Files.writeString(path, html, StandardCharsets.UTF_8);
        return path;
    }
}
