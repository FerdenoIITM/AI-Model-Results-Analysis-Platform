package com.amrap.web.users;

import com.amrap.domain.user.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Size(max = 120) String displayName,
        @Email @Size(max = 255) String email,
        @Size(min = 8, max = 128) String password,
        UserRole role,
        Boolean active) {
}
