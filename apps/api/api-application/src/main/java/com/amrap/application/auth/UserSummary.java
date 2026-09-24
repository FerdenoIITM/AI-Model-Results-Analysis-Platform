package com.amrap.application.auth;

import com.amrap.domain.user.UserRole;
import java.util.UUID;

public record UserSummary(
        UUID id,
        String email,
        String displayName,
        UserRole role,
        UUID createdBy,
        boolean active,
        java.time.Instant lastLoginAt,
        java.time.Instant createdAt) {
}
