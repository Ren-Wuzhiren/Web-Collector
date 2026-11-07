package com.webcollector.admin.dto;

import java.util.List;

public record AdminFailedJobPage(
        List<AdminFailedJobItem> items,
        Long nextBeforeId
) {
}
