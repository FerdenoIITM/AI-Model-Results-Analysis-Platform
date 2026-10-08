package com.amrap.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.amrap.domain.user.UserRole;
import com.amrap.security.JwtService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class AuthApiIT extends AbstractIntegrationTest {

    private TestUser admin;

    @BeforeEach
    void users() {
        admin = createUser(UserRole.ADMIN, null, "admin@test.io", true);
    }

    private ResultActions login(String body) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void loginReturnsTokenUsableOnMe() throws Exception {
        String body = login("{\"email\":\"ADMIN@Test.IO\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("admin@test.io"))
                .andExpect(jsonPath("$.user.role").value("admin"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).get("accessToken").asText();

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(admin.id().toString()))
                .andExpect(jsonPath("$.lastLoginAt").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT last_login_at FROM users WHERE id=?", java.sql.Timestamp.class, admin.id()))
                .isNotNull();
    }

    @Test
    void wrongPasswordAndUnknownEmailAreIndistinguishable401() throws Exception {
        login("{\"email\":\"admin@test.io\",\"password\":\"nope\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
        login("{\"email\":\"ghost@test.io\",\"password\":\"nope\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void deactivatedAccountIs403() throws Exception {
        createUser(UserRole.MEMBER, admin.id(), "gone@test.io", false);

        login("{\"email\":\"gone@test.io\",\"password\":\"" + PASSWORD + "\"}").andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"email\":\"admin@test.io\"}", "{\"password\":\"x\"}",
            "{\"email\":\"not-email\",\"password\":\"x\"}", "{\"email\":\"\",\"password\":\"\"}",
            "{\"email\":123,\"password\":[]}", "", "garbage"})
    void invalidLoginBodiesAre400(String body) throws Exception {
        login(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void meRequiresValidToken() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", admin.token())).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredAndForeignTokensAreRejected() throws Exception {
        String expired = new JwtService("integration-test-secret-at-least-32-characters-long", -1000)
                .generateToken(admin.id(), admin.email(), UserRole.ADMIN, "A");
        String foreign = new JwtService("some-other-secret-that-is-at-least-32-chars", 60000)
                .generateToken(admin.id(), admin.email(), UserRole.SUPERADMIN, "A");

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + foreign)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenForDeletedUserGives404OnMe() throws Exception {
        String token = jwtService.generateToken(UUID.randomUUID(), "ghost@test.io", UserRole.MEMBER, "Ghost");

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }
}
