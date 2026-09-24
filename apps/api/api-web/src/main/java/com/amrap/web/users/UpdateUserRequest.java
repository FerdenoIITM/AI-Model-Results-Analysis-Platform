package com.amrap.web.users;

import com.amrap.domain.user.UserRole;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Size(max = 120) String displayName,
        @Size(min = 8, max = 128) String password,
        UserRole role,
        Boolean active) {
}
