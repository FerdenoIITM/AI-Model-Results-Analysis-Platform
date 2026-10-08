package com.amrap.application.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amrap.application.auth.UserSummary;
import com.amrap.application.common.AppException;
import com.amrap.domain.user.User;
import com.amrap.domain.user.UserRole;
import com.amrap.persistence.user.UserRepository;
import com.amrap.security.AuthenticatedUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserManagementServiceTest {

    private UserRepository repository;
    private PasswordEncoder encoder;
    private UserManagementService service;

    private final AuthenticatedUser superadmin = actor(UserRole.SUPERADMIN);
    private final AuthenticatedUser admin = actor(UserRole.ADMIN);
    private final AuthenticatedUser member = actor(UserRole.MEMBER);

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenAnswer(inv -> "hash:" + inv.getArgument(0));
        when(repository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });
        service = new UserManagementService(repository, encoder);
    }

    @Nested
    class ListUsers {

        @Test
        void superadminSeesEveryoneExceptSelf() {
            User self = user(superadmin.getId(), UserRole.SUPERADMIN, null);
            User other = user(UUID.randomUUID(), UserRole.ADMIN, superadmin.getId());
            when(repository.findAll()).thenReturn(List.of(self, other));

            assertThat(service.listUsers(superadmin)).extracting(UserSummary::id).containsExactly(other.getId());
        }

        @Test
        void adminSeesOnlyMembersTheyCreated() {
            User mine = user(UUID.randomUUID(), UserRole.MEMBER, admin.getId());
            when(repository.findByCreatedBy(admin.getId())).thenReturn(List.of(mine));

            assertThat(service.listUsers(admin)).extracting(UserSummary::id).containsExactly(mine.getId());
            verify(repository, never()).findAll();
        }

        @Test
        void memberIsForbidden() {
            assertStatus(() -> service.listUsers(member), 403, "Members cannot access user management");
        }

        @Test
        void emptyListWhenNobodyElseExists() {
            when(repository.findAll()).thenReturn(List.of(user(superadmin.getId(), UserRole.SUPERADMIN, null)));

            assertThat(service.listUsers(superadmin)).isEmpty();
        }
    }

    @Nested
    class CreateUser {

        @Test
        void superadminCreatesAdminWithNormalizedEmailAndHashedPassword() {
            UserSummary created = service.createUser(superadmin,
                    new CreateUserCommand("  New.Admin@Example.COM ", "password123", "  New Admin ", UserRole.ADMIN));

            assertThat(created.email()).isEqualTo("new.admin@example.com");
            assertThat(created.displayName()).isEqualTo("New Admin");
            assertThat(created.role()).isEqualTo(UserRole.ADMIN);
            assertThat(created.createdBy()).isEqualTo(superadmin.getId());
            assertThat(created.active()).isTrue();
            verify(repository).save(org.mockito.ArgumentMatchers.argThat(u -> u.getPasswordHash().equals("hash:password123")));
        }

        @Test
        void adminCanCreateMemberOnly() {
            assertThat(service.createUser(admin, cmd("m@x.io", UserRole.MEMBER)).role()).isEqualTo(UserRole.MEMBER);
            assertStatus(() -> service.createUser(admin, cmd("a@x.io", UserRole.ADMIN)), 403, "Admins can only create member users");
        }

        @Test
        void nobodyCanCreateSuperadmin() {
            assertStatus(() -> service.createUser(superadmin, cmd("s@x.io", UserRole.SUPERADMIN)), 403,
                    "Cannot create superadmin through the API");
        }

        @Test
        void memberCannotCreate() {
            assertStatus(() -> service.createUser(member, cmd("m@x.io", UserRole.MEMBER)), 403, "Members cannot create users");
        }

        @Test
        void duplicateEmailIsConflictCaseInsensitive() {
            when(repository.existsByEmail("dup@x.io")).thenReturn(true);

            assertStatus(() -> service.createUser(superadmin, cmd("DUP@x.io", UserRole.MEMBER)), 409, "Email already exists");
            verify(repository, never()).save(any());
        }

        @Test
        void validatesRolePasswordAndDisplayName() {
            assertStatus(() -> service.createUser(superadmin, cmd("a@x.io", null)), 400, "Role is required");
            assertStatus(() -> service.createUser(superadmin,
                    new CreateUserCommand("a@x.io", "short", "Name", UserRole.MEMBER)), 400, "Password must be at least 8 characters");
            assertStatus(() -> service.createUser(superadmin,
                    new CreateUserCommand("a@x.io", null, "Name", UserRole.MEMBER)), 400, "Password must be at least 8 characters");
            assertStatus(() -> service.createUser(superadmin,
                    new CreateUserCommand("a@x.io", "password123", " ", UserRole.MEMBER)), 400, "Display name is required");
            assertStatus(() -> service.createUser(superadmin,
                    new CreateUserCommand(null, "password123", "Name", UserRole.MEMBER)), 400, "Email is required");
            assertStatus(() -> service.createUser(superadmin,
                    new CreateUserCommand("  ", "password123", "Name", UserRole.MEMBER)), 400, "Email is required");
        }

        @Test
        void passwordOfExactlyEightCharactersIsAccepted() {
            assertThat(service.createUser(superadmin, new CreateUserCommand("a@x.io", "12345678", "N", UserRole.MEMBER)))
                    .isNotNull();
        }
    }

    @Nested
    class UpdateUser {

        @Test
        void superadminUpdatesFieldsAndRole() {
            User target = stored(UserRole.MEMBER, admin.getId());

            UserSummary updated = service.updateUser(superadmin, target.getId(),
                    new UpdateUserCommand(" Renamed ", "NEW@x.io", "newpassword", UserRole.ADMIN, false));

            assertThat(updated.displayName()).isEqualTo("Renamed");
            assertThat(updated.email()).isEqualTo("new@x.io");
            assertThat(updated.role()).isEqualTo(UserRole.ADMIN);
            assertThat(updated.active()).isFalse();
            assertThat(target.getPasswordHash()).isEqualTo("hash:newpassword");
            assertThat(target.getPasswordChangedAt()).isNotNull();
        }

        @Test
        void blankFieldsAreIgnored() {
            User target = stored(UserRole.MEMBER, admin.getId());
            String hash = target.getPasswordHash();

            service.updateUser(superadmin, target.getId(), new UpdateUserCommand(" ", "", " ", null, null));

            assertThat(target.getDisplayName()).isEqualTo("Original");
            assertThat(target.getEmail()).isEqualTo("orig@x.io");
            assertThat(target.getPasswordHash()).isEqualTo(hash);
        }

        @Test
        void keepingSameEmailInDifferentCaseIsNotAConflict() {
            User target = stored(UserRole.MEMBER, admin.getId());

            service.updateUser(superadmin, target.getId(), new UpdateUserCommand(null, "ORIG@x.io", null, null, null));

            verify(repository, never()).existsByEmail(anyString());
        }

        @Test
        void changingToTakenEmailIsConflict() {
            User target = stored(UserRole.MEMBER, admin.getId());
            when(repository.existsByEmail("taken@x.io")).thenReturn(true);

            assertStatus(() -> service.updateUser(superadmin, target.getId(),
                    new UpdateUserCommand(null, "taken@x.io", null, null, null)), 409, "Email already exists");
        }

        @Test
        void shortPasswordRejected() {
            User target = stored(UserRole.MEMBER, admin.getId());

            assertStatus(() -> service.updateUser(superadmin, target.getId(),
                    new UpdateUserCommand(null, null, "1234567", null, null)), 400, "Password must be at least 8 characters");
        }

        @Test
        void adminManagesOnlyOwnMembers() {
            User own = stored(UserRole.MEMBER, admin.getId());
            User foreign = stored(UserRole.MEMBER, UUID.randomUUID());
            User otherAdmin = stored(UserRole.ADMIN, superadmin.getId());

            assertThat(service.updateUser(admin, own.getId(), new UpdateUserCommand("Ok", null, null, null, null)).displayName())
                    .isEqualTo("Ok");
            assertStatus(() -> service.updateUser(admin, foreign.getId(), new UpdateUserCommand("x", null, null, null, null)),
                    403, "Admins may only manage members they created");
            assertStatus(() -> service.updateUser(admin, otherAdmin.getId(), new UpdateUserCommand("x", null, null, null, null)),
                    403, "Admins may only manage members they created");
        }

        @Test
        void adminCannotChangeRoles() {
            User own = stored(UserRole.MEMBER, admin.getId());

            assertStatus(() -> service.updateUser(admin, own.getId(), new UpdateUserCommand(null, null, null, UserRole.ADMIN, null)),
                    403, "Only superadmin can change roles");
        }

        @Test
        void memberCannotManageAnyone() {
            User target = stored(UserRole.MEMBER, admin.getId());

            assertStatus(() -> service.updateUser(member, target.getId(), new UpdateUserCommand("x", null, null, null, null)),
                    403, "Not allowed to manage users");
        }

        @Test
        void superadminRoleIsProtected() {
            User otherSuper = stored(UserRole.SUPERADMIN, null);
            User regular = stored(UserRole.MEMBER, admin.getId());

            assertStatus(() -> service.updateUser(superadmin, regular.getId(),
                    new UpdateUserCommand(null, null, null, UserRole.SUPERADMIN, null)), 403, "Cannot change to or from superadmin via API");
            assertStatus(() -> service.updateUser(superadmin, otherSuper.getId(),
                    new UpdateUserCommand(null, null, null, UserRole.MEMBER, null)), 403, "Cannot change to or from superadmin via API");
            assertStatus(() -> service.updateUser(superadmin, otherSuper.getId(),
                    new UpdateUserCommand(null, null, null, null, false)), 403, "Cannot deactivate a superadmin via API");
        }

        @Test
        void unknownUserIs404() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());

            assertStatus(() -> service.updateUser(superadmin, id, new UpdateUserCommand("x", null, null, null, null)),
                    404, "User not found");
        }

        @Test
        void deactivateSetsInactiveAndReactivateWorks() {
            User target = stored(UserRole.MEMBER, admin.getId());

            assertThat(service.deactivateUser(admin, target.getId()).active()).isFalse();
            assertThat(service.updateUser(admin, target.getId(), new UpdateUserCommand(null, null, null, null, true)).active())
                    .isTrue();
        }
    }

    @Nested
    class DeleteUser {

        @Test
        void superadminDeletesMember() {
            User target = stored(UserRole.MEMBER, admin.getId());

            service.deleteUser(superadmin, target.getId());

            verify(repository).deleteById(target.getId());
            verify(repository, never()).reassignCreatedBy(any(), any());
        }

        @Test
        void deletingAdminReassignsTheirMembersFirst() {
            User target = stored(UserRole.ADMIN, superadmin.getId());

            service.deleteUser(superadmin, target.getId());

            InOrder order = inOrder(repository);
            order.verify(repository).reassignCreatedBy(target.getId(), superadmin.getId());
            order.verify(repository).deleteById(target.getId());
        }

        @Test
        void onlySuperadminMayDelete() {
            User target = stored(UserRole.MEMBER, admin.getId());

            assertStatus(() -> service.deleteUser(admin, target.getId()), 403, "Only superadmin can delete users");
            assertStatus(() -> service.deleteUser(member, target.getId()), 403, "Only superadmin can delete users");
            verify(repository, never()).deleteById(any());
        }

        @Test
        void cannotDeleteSelfOrAnotherSuperadmin() {
            User otherSuper = stored(UserRole.SUPERADMIN, null);

            assertStatus(() -> service.deleteUser(superadmin, superadmin.getId()), 403, "Cannot delete your own account");
            assertStatus(() -> service.deleteUser(superadmin, otherSuper.getId()), 403, "Cannot delete a superadmin via API");
        }

        @Test
        void unknownUserIs404() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());

            assertStatus(() -> service.deleteUser(superadmin, id), 404, "User not found");
        }
    }

    private User stored(UserRole role, UUID createdBy) {
        User u = user(UUID.randomUUID(), role, createdBy);
        when(repository.findById(u.getId())).thenReturn(Optional.of(u));
        return u;
    }

    private static User user(UUID id, UserRole role, UUID createdBy) {
        User u = new User();
        u.setId(id);
        u.setEmail("orig@x.io");
        u.setDisplayName("Original");
        u.setPasswordHash("hash:original");
        u.setRole(role);
        u.setCreatedBy(createdBy);
        u.setActive(true);
        return u;
    }

    private static CreateUserCommand cmd(String email, UserRole role) {
        return new CreateUserCommand(email, "password123", "Name", role);
    }

    private static AuthenticatedUser actor(UserRole role) {
        return new AuthenticatedUser(UUID.randomUUID(), role.toDbValue() + "@x.io", role.name(), role, "", true);
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AppException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getMessage()).isEqualTo(message);
        });
    }
}
