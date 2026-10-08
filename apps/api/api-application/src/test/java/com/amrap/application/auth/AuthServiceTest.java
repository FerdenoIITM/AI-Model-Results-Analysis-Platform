package com.amrap.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amrap.application.common.AppException;
import com.amrap.domain.user.User;
import com.amrap.domain.user.UserRole;
import com.amrap.persistence.user.UserRepository;
import com.amrap.security.JwtService;
import io.jsonwebtoken.Claims;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceTest {

    private static final String SECRET = "unit-test-secret-that-is-at-least-32-characters";

    private UserRepository repository;
    private JwtService jwtService;
    private AuthService service;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        jwtService = new JwtService(SECRET, 60_000);
        when(repository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new AuthService(repository, encoder, jwtService);
    }

    @Test
    void loginReturnsSignedTokenAndUpdatesLastLogin() {
        User user = user(true);
        when(repository.findByEmail("admin@x.io")).thenReturn(Optional.of(user));

        LoginResult result = service.login(new LoginCommand("  Admin@X.io ", "correct-password"));

        Claims claims = jwtService.parseClaims(result.accessToken());
        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.get("role", String.class)).isEqualTo("admin");
        assertThat(claims.get("email", String.class)).isEqualTo("admin@x.io");
        assertThat(result.user().email()).isEqualTo("admin@x.io");
        assertThat(user.getLastLoginAt()).isNotNull();
        verify(repository).save(user);
    }

    @Test
    void unknownEmailAndWrongPasswordGiveTheSame401() {
        when(repository.findByEmail("admin@x.io")).thenReturn(Optional.of(user(true)));

        assertStatus(() -> service.login(new LoginCommand("nobody@x.io", "whatever")), 401, "Invalid email or password");
        assertStatus(() -> service.login(new LoginCommand("admin@x.io", "wrong-password")), 401, "Invalid email or password");
        assertStatus(() -> service.login(new LoginCommand("admin@x.io", "")), 401, "Invalid email or password");
        verify(repository, never()).save(any());
    }

    @Test
    void deactivatedAccountIsForbidden() {
        when(repository.findByEmail("admin@x.io")).thenReturn(Optional.of(user(false)));

        assertStatus(() -> service.login(new LoginCommand("admin@x.io", "correct-password")), 403, "Account is deactivated");
    }

    @Test
    void blankEmailIsBadRequest() {
        assertStatus(() -> service.login(new LoginCommand(null, "x")), 400, "Email is required");
        assertStatus(() -> service.login(new LoginCommand("   ", "x")), 400, "Email is required");
    }

    @Test
    void meReturnsSummaryOr404() {
        User user = user(true);
        when(repository.findById(user.getId())).thenReturn(Optional.of(user));
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());

        assertThat(service.me(user.getId()).displayName()).isEqualTo("Admin");
        assertStatus(() -> service.me(missing), 404, "User not found");
    }

    @Test
    void databaseFailurePropagates() {
        when(repository.findByEmail("admin@x.io")).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> service.login(new LoginCommand("admin@x.io", "x")))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    private User user(boolean active) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("admin@x.io");
        u.setDisplayName("Admin");
        u.setRole(UserRole.ADMIN);
        u.setActive(active);
        u.setPasswordHash(encoder.encode("correct-password"));
        return u;
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AppException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getMessage()).isEqualTo(message);
        });
    }
}
