package com.webcollector.admin.dto;

public record AdminOverviewResponse(
        long totalUsers,
        long totalLinks,
        long totalAssets,
        long pendingJobs,
        long runningJobs,
        long succeededJobs,
        long failedJobs
) {
}
