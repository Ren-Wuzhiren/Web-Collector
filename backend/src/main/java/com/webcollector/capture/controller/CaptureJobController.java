package com.webcollector.capture.controller;

import com.webcollector.capture.dto.CaptureJobDetail;
import com.webcollector.capture.service.CaptureJobQueryService;
import com.webcollector.common.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
public class CaptureJobController {
    private final CaptureJobQueryService captureJobQueryService;

    public CaptureJobController(CaptureJobQueryService captureJobQueryService) {
        this.captureJobQueryService = captureJobQueryService;
    }

    @GetMapping("/{id}")
    public ApiResponse<CaptureJobDetail> getJob(@PathVariable long id) {
        return ApiResponse.success(captureJobQueryService.getOwned(id));
    }
}
