package com.webcollector.link.controller;

import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import com.webcollector.common.web.GlobalExceptionHandler;
import com.webcollector.common.web.RequestIdFilter;
import com.webcollector.link.dto.LinkSummary;
import com.webcollector.link.dto.ReadableLinkResponse;
import com.webcollector.link.service.ReadableLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReadableLinkControllerTest {
    private static final Instant CREATED_AT =
            Instant.parse("2026-11-03T10:00:00Z");
    private static final Instant UPDATED_AT =
            Instant.parse("2026-11-03T10:01:00Z");

    private ReadableLinkService readableLinkService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        readableLinkService = mock(ReadableLinkService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReadableLinkController(readableLinkService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    // [测试意图] 验证阅读接口返回完整 JSON、requestId 和保留换行的正文。
    @Test
    void shouldReturnReadableLinkJson() throws Exception {
        when(readableLinkService.getReadableLink(42L))
                .thenReturn(new ReadableLinkResponse(
                        new LinkSummary(
                                42L,
                                8L,
                                "https://example.com/article",
                                "示例文章",
                                "示例描述",
                                CREATED_AT,
                                UPDATED_AT
                        ),
                        "第一段\n\n第二段"
                ));

        mockMvc.perform(get("/api/v1/links/42/readable")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "readable-req-001"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        RequestIdFilter.REQUEST_ID_HEADER,
                        "readable-req-001"
                ))
                .andExpect(jsonPath("$.data.link.id").value(42))
                .andExpect(jsonPath("$.data.link.collectionId").value(8))
                .andExpect(jsonPath("$.data.link.originalUrl")
                        .value("https://example.com/article"))
                .andExpect(jsonPath("$.data.link.title").value("示例文章"))
                .andExpect(jsonPath("$.data.link.description").value("示例描述"))
                .andExpect(jsonPath("$.data.link.createdAt")
                        .value("2026-11-03T10:00:00Z"))
                .andExpect(jsonPath("$.data.link.updatedAt")
                        .value("2026-11-03T10:01:00Z"))
                .andExpect(jsonPath("$.data.textContent")
                        .value("第一段\n\n第二段"))
                .andExpect(jsonPath("$.requestId").value("readable-req-001"));
    }

    // [测试意图] 验证业务层的正文缺失错误稳定映射为 404 NOT_FOUND。
    @Test
    void shouldMapMissingReadableContentToNotFound() throws Exception {
        when(readableLinkService.getReadableLink(99L))
                .thenThrow(new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "正文尚未生成"
                ));

        mockMvc.perform(get("/api/v1/links/99/readable")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "readable-req-002"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("正文尚未生成"))
                .andExpect(jsonPath("$.requestId").value("readable-req-002"));
    }
}
