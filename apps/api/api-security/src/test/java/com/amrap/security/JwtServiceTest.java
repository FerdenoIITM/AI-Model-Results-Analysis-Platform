package com.amrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amrap.domain.user.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JwtServiceTest {

    static final String SECRET = "unit-test-secret-that-is-at-least-32-characters";

    private final JwtService service = new JwtService(SECRET, 60_000);

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void roundTripsClaimsForEveryRole(UserRole role) {
        UUID id = UUID.randomUUID();

        Claims claims = service.parseClaims(service.generateToken(id, "a@x.io", role, "Alice"));

        assertThat(service.getUserId(claims)).isEqualTo(id);
        assertThat(service.getRole(claims)).isEqualTo(role);
        assertThat(claims.get("email", String.class)).isEqualTo("a@x.io");
        assertThat(claims.get("displayName", String.class)).isEqualTo("Alice");
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void rejectsExpiredToken() {
        String token = new JwtService(SECRET, -1_000).generateToken(UUID.randomUUID(), "a@x.io", UserRole.ADMIN, "A");

        assertThatThrownBy(() -> service.parseClaims(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        String token = new JwtService("another-secret-that-is-also-32-characters-long", 60_000)
                .generateToken(UUID.randomUUID(), "a@x.io", UserRole.SUPERADMIN, "A");

        assertThatThrownBy(() -> service.parseClaims(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTamperedPayload() {
        String token = service.generateToken(UUID.randomUUID(), "a@x.io", UserRole.MEMBER, "A");
        String[] parts = token.split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + UUID.randomUUID() + "\",\"role\":\"superadmin\"}").getBytes());

        assertThatThrownBy(() -> service.parseClaims(parts[0] + "." + forgedPayload + "." + parts[2]))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsGarbageAndEmptyTokens() {
        assertThatThrownBy(() -> service.parseClaims("not.a.jwt")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service.parseClaims("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsignedToken() {
        String header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + UUID.randomUUID() + "\",\"role\":\"superadmin\"}").getBytes());

        assertThatThrownBy(() -> service.parseClaims(header + "." + payload + ".")).isInstanceOf(JwtException.class);
    }

    @Test
    void refusesShortOrMissingSecret() {
        assertThatThrownBy(() -> new JwtService("too-short", 1000)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService(null, 1000)).isInstanceOf(IllegalStateException.class);
        assertThat(new JwtService("x".repeat(32), 1000)).isNotNull();
    }

    @Test
    void unknownRoleClaimFails() {
        assertThatThrownBy(() -> UserRole.fromDbValue("root")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserRole.fromDbValue(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(UserRole.fromDbValue(" Admin ")).isEqualTo(UserRole.ADMIN);
    }
}
