package com.webcollector.capture.asset.controller;

import com.webcollector.auth.session.SessionTokenService;
import com.webcollector.capture.asset.entity.AssetFormat;
import com.webcollector.capture.asset.service.AssetService;
import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import com.webcollector.common.web.GlobalExceptionHandler;
import com.webcollector.common.web.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssetControllerTest {
    private AssetService assetService;
    private SessionTokenService sessionTokenService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        sessionTokenService = mock(SessionTokenService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AssetController(assetService, sessionTokenService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
        when(sessionTokenService.requireLoginId()).thenReturn(7L);
    }

    // [测试意图] 验证 HTML 资产通过附件响应提供，避免在应用源站内执行快照脚本。
    @Test
    void shouldReturnHtmlAssetAsAttachmentWithNosniff() throws Exception {
        byte[] html = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        when(assetService.openOwned(12L, 7L)).thenReturn(new AssetService.AssetContent(
                new ByteArrayInputStream(html), "text/html", html.length, AssetFormat.HTML));

        mockMvc.perform(get("/api/v1/assets/12"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.TEXT_HTML))
                .andExpect(content().bytes(html))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"snapshot.html\""));
        verify(assetService).openOwned(12L, 7L);
    }

    // [测试意图] 验证非 HTML 资产保持 MIME 响应并将资源查询限制在当前用户。
    @Test
    void shouldUseCurrentUserIdWhenOpeningNonHtmlAsset() throws Exception {
        byte[] pdf = "%PDF-test".getBytes(StandardCharsets.UTF_8);
        when(assetService.openOwned(13L, 7L)).thenReturn(new AssetService.AssetContent(
                new ByteArrayInputStream(pdf), "application/pdf", pdf.length, AssetFormat.PDF));

        mockMvc.perform(get("/api/v1/assets/13"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(pdf))
                .andExpect(header().doesNotExist("Content-Disposition"));
        verify(assetService).openOwned(13L, 7L);
    }

    // [测试意图] 验证缺失或越权资产通过统一 404 契约隐藏资源是否存在。
    @Test
    void shouldReturnNotFoundWhenAssetIsNotOwned() throws Exception {
        when(assetService.openOwned(99L, 7L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

        mockMvc.perform(get("/api/v1/assets/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // [测试意图] 验证删除资产只对当前用户执行并返回异步清理状态 202。
    @Test
    void shouldQueueOwnedAssetDeletion() throws Exception {
        doNothing().when(assetService).deleteOwned(14L, 7L);

        mockMvc.perform(delete("/api/v1/assets/14"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("资产已加入清理队列"));
        verify(assetService).deleteOwned(14L, 7L);
    }
}
