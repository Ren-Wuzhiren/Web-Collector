package com.webcollector.link.controller;

import com.webcollector.common.web.ApiResponse;
import com.webcollector.link.dto.ReadableLinkResponse;
import com.webcollector.link.service.ReadableLinkService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 链接清理正文阅读接口。
 */
@RestController
@RequestMapping("/api/v1/links")
public class ReadableLinkController {
    private final ReadableLinkService readableLinkService;

    public ReadableLinkController(
            ReadableLinkService readableLinkService
    ) {
        this.readableLinkService = readableLinkService;
    }

    @GetMapping("/{id}/readable")
    public ApiResponse<ReadableLinkResponse> getReadableLink(
            @PathVariable("id") Long linkId
    ) {
        return ApiResponse.success(
                readableLinkService.getReadableLink(linkId)
        );
    }
}
