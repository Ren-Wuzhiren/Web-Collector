package com.webcollector.admin.controller;

import com.webcollector.admin.dto.AdminFailedJobPage;
import com.webcollector.admin.dto.AdminOverviewResponse;
import com.webcollector.admin.dto.AdminRetryResponse;
import com.webcollector.admin.service.AdminOperationsService;
import com.webcollector.common.web.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@Validated
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminOperationsService adminOperationsService;

    public AdminController(AdminOperationsService adminOperationsService) {
        this.adminOperationsService = adminOperationsService;
    }

    @GetMapping("/overview")
    public ApiResponse<AdminOverviewResponse> overview() {
        return ApiResponse.success(adminOperationsService.overview());
    }

    @GetMapping("/capture-jobs/failed")
    public ApiResponse<AdminFailedJobPage> failedJobs(
            @RequestParam(defaultValue = "50")
            @Min(1)
            @Max(100)
            int limit,
            @RequestParam(required = false)
            @Positive
            Long beforeId
    ) {
        return ApiResponse.success(
                adminOperationsService.failedJobs(beforeId, limit)
        );
    }

    @PostMapping("/capture-jobs/{jobId}/retry")
    public ResponseEntity<ApiResponse<AdminRetryResponse>> retryFailedJob(
            @PathVariable
            @Positive
            long jobId
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        adminOperationsService.retryFailedJob(jobId),
                        "已创建新的抓取任务"
                ));
    }
}
