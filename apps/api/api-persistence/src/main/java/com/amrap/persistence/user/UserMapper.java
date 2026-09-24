package com.amrap.persistence.user;

import com.amrap.domain.user.User;

final class UserMapper {

    private UserMapper() {
    }

    static User toDomain(UserEntity entity) {
        if (entity == null) {
            return null;
        }
        User user = new User();
        user.setId(entity.getId());
        user.setEmail(entity.getEmail());
        user.setPasswordHash(entity.getPasswordHash());
        user.setRole(entity.getRole());
        user.setDisplayName(entity.getDisplayName());
        user.setCreatedBy(entity.getCreatedBy());
        user.setActive(entity.isActive());
        user.setLastLoginAt(entity.getLastLoginAt());
        user.setFailedLoginAttempts(entity.getFailedLoginAttempts());
        user.setLockedUntil(entity.getLockedUntil());
        user.setPasswordChangedAt(entity.getPasswordChangedAt());
        user.setCreatedAt(entity.getCreatedAt());
        user.setUpdatedAt(entity.getUpdatedAt());
        return user;
    }

    static void copyToEntity(User user, UserEntity entity) {
        entity.setEmail(user.getEmail());
        entity.setPasswordHash(user.getPasswordHash());
        entity.setRole(user.getRole());
        entity.setDisplayName(user.getDisplayName());
        entity.setCreatedBy(user.getCreatedBy());
        entity.setActive(user.isActive());
        entity.setLastLoginAt(user.getLastLoginAt());
        entity.setFailedLoginAttempts(user.getFailedLoginAttempts());
        entity.setLockedUntil(user.getLockedUntil());
        entity.setPasswordChangedAt(user.getPasswordChangedAt());
        if (user.getId() != null) {
            entity.setId(user.getId());
        }
    }
}
