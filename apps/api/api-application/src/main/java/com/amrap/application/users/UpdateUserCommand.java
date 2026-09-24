package com.amrap.application.users;

import com.amrap.domain.user.UserRole;

public record UpdateUserCommand(
        String displayName, String password, UserRole role, Boolean active) {
}
