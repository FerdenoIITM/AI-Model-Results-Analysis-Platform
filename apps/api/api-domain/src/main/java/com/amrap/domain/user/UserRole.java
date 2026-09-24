package com.amrap.domain.user;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum UserRole {
    SUPERADMIN,
    ADMIN,
    MEMBER;

    @JsonValue
    public String toDbValue() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static UserRole fromDbValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("role is required");
        }
        return UserRole.valueOf(value.trim().toUpperCase());
    }
}
