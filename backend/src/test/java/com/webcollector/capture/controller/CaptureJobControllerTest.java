package com.webcollector.capture.controller;

import com.webcollector.capture.dto.CaptureJobDetail;
import com.webcollector.capture.entity.CaptureJobStatus;
import com.webcollector.capture.service.CaptureJobQueryService;
import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import com.webcollector.common.web.GlobalExceptionHandler;
import com.webcollector.common.web.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CaptureJobControllerTest {

    private CaptureJobQueryService queryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(CaptureJobQueryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new CaptureJobController(queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    // [测试意图] 验证任务查询返回状态、错误和资产摘要，不返回租约内部字段。
    @Test
    void shouldReturnOwnedJobDetail() throws Exception {
        when(queryService.getOwned(8L)).thenReturn(new CaptureJobDetail(
                8L,
                21L,
                CaptureJobStatus.PENDING,
                1,
                3,
                "CAPTURE_EXECUTION_FAILED",
                "抓取失败",
                Instant.parse("2026-11-02T00:00:30Z"),
                null,
                null,
                List.of()
        ));

        mockMvc.perform(get("/api/v1/jobs/8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.errorCode").value("CAPTURE_EXECUTION_FAILED"))
                .andExpect(jsonPath("$.data.lockedBy").doesNotExist());
        verify(queryService).getOwned(8L);
    }

    // [测试意图] 验证不存在或越权任务统一返回 404。
    @Test
    void shouldHideMissingOrUnownedJob() throws Exception {
        when(queryService.getOwned(99L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        mockMvc.perform(get("/api/v1/jobs/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
