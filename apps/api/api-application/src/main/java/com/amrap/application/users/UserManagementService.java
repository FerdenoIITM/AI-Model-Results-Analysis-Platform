package com.amrap.application.users;

import com.amrap.application.auth.AuthService;
import com.amrap.application.auth.UserSummary;
import com.amrap.application.common.AppException;
import com.amrap.domain.user.User;
import com.amrap.domain.user.UserRole;
import com.amrap.persistence.user.UserRepository;
import com.amrap.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserManagementService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserManagementService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<UserSummary> listUsers(AuthenticatedUser actor) {
        return switch (actor.getRole()) {
            case SUPERADMIN -> userRepository.findAll().stream().map(AuthService::toSummary).toList();
            case ADMIN -> userRepository.findVisibleToAdmin(actor.getId()).stream()
                    .map(AuthService::toSummary)
                    .toList();
            case MEMBER -> throw new AppException(403, "Members cannot access user management");
        };
    }

    @Transactional
    public UserSummary createUser(AuthenticatedUser actor, CreateUserCommand command) {
        if (actor.getRole() == UserRole.MEMBER) {
            throw new AppException(403, "Members cannot create users");
        }

        UserRole requestedRole = command.role();
        if (requestedRole == null) {
            throw new AppException(400, "Role is required");
        }
        if (requestedRole == UserRole.SUPERADMIN) {
            throw new AppException(403, "Cannot create superadmin through the API");
        }
        if (actor.getRole() == UserRole.ADMIN && requestedRole != UserRole.MEMBER) {
            throw new AppException(403, "Admins can only create member users");
        }

        String email = AuthService.normalizeEmail(command.email());
        if (userRepository.existsByEmail(email)) {
            throw new AppException(409, "Email already exists");
        }
        if (command.password() == null || command.password().length() < 8) {
            throw new AppException(400, "Password must be at least 8 characters");
        }
        if (command.displayName() == null || command.displayName().isBlank()) {
            throw new AppException(400, "Display name is required");
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(command.password()));
        user.setRole(requestedRole);
        user.setDisplayName(command.displayName().trim());
        user.setCreatedBy(actor.getId());
        user.setActive(true);
        user.setFailedLoginAttempts(0);
        user.setPasswordChangedAt(Instant.now());

        return AuthService.toSummary(userRepository.save(user));
    }

    @Transactional
    public UserSummary updateUser(AuthenticatedUser actor, UUID targetId, UpdateUserCommand command) {
        User target = userRepository
                .findById(targetId)
                .orElseThrow(() -> new AppException(404, "User not found"));

        assertCanManage(actor, target);

        if (command.displayName() != null && !command.displayName().isBlank()) {
            target.setDisplayName(command.displayName().trim());
        }

        if (command.password() != null && !command.password().isBlank()) {
            if (command.password().length() < 8) {
                throw new AppException(400, "Password must be at least 8 characters");
            }
            target.setPasswordHash(passwordEncoder.encode(command.password()));
            target.setPasswordChangedAt(Instant.now());
        }

        if (command.role() != null) {
            if (actor.getRole() != UserRole.SUPERADMIN) {
                throw new AppException(403, "Only superadmin can change roles");
            }
            if (target.getRole() == UserRole.SUPERADMIN || command.role() == UserRole.SUPERADMIN) {
                throw new AppException(403, "Cannot change to or from superadmin via API");
            }
            if (command.role() != UserRole.ADMIN && command.role() != UserRole.MEMBER) {
                throw new AppException(400, "Role must be admin or member");
            }
            target.setRole(command.role());
        }

        if (command.active() != null) {
            if (target.getRole() == UserRole.SUPERADMIN && !command.active()) {
                throw new AppException(403, "Cannot deactivate a superadmin via API");
            }
            target.setActive(command.active());
        }

        return AuthService.toSummary(userRepository.save(target));
    }

    @Transactional
    public UserSummary deactivateUser(AuthenticatedUser actor, UUID targetId) {
        return updateUser(actor, targetId, new UpdateUserCommand(null, null, null, false));
    }

    private void assertCanManage(AuthenticatedUser actor, User target) {
        if (actor.getRole() == UserRole.SUPERADMIN) {
            return;
        }
        if (actor.getRole() == UserRole.ADMIN) {
            boolean ownsMember = target.getRole() == UserRole.MEMBER
                    && actor.getId().equals(target.getCreatedBy());
            if (!ownsMember) {
                throw new AppException(403, "Admins may only manage members they created");
            }
            return;
        }
        throw new AppException(403, "Not allowed to manage users");
    }
}
