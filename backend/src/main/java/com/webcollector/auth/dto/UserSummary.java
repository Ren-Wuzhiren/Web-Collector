package com.webcollector.auth.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.webcollector.auth.entity.UserRole;

import java.time.Instant;

public record UserSummary (
        Long id,
        String username,
        String email,
        UserRole role,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant createdAt
) {
}
