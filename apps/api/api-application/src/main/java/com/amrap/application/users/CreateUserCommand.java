package com.amrap.application.users;

import com.amrap.domain.user.UserRole;

public record CreateUserCommand(
        String email, String password, String displayName, UserRole role) {
}
