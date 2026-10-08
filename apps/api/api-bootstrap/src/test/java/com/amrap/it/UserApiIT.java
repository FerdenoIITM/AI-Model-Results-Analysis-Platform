package com.amrap.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.amrap.domain.user.UserRole;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class UserApiIT extends AbstractIntegrationTest {

    private TestUser superadmin;
    private TestUser admin;
    private TestUser member;

    @BeforeEach
    void users() {
        superadmin = createUser(UserRole.SUPERADMIN, null);
        admin = createUser(UserRole.ADMIN, superadmin.id());
        member = createUser(UserRole.MEMBER, admin.id());
    }

    private ResultActions create(TestUser as, String body) throws Exception {
        return mvc.perform(post("/api/users").header("Authorization", bearer(as))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions update(TestUser as, UUID id, String body) throws Exception {
        return mvc.perform(patch("/api/users/" + id).header("Authorization", bearer(as))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String newUser(String email, String role) {
        return "{\"email\":\"" + email + "\",\"password\":\"password123\",\"displayName\":\"New User\",\"role\":\"" + role + "\"}";
    }

    @Test
    void listIsScopedByRole() throws Exception {
        createUser(UserRole.MEMBER, superadmin.id());

        mvc.perform(get("/api/users").header("Authorization", bearer(superadmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
        mvc.perform(get("/api/users").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(member.id().toString()));
        mvc.perform(get("/api/users").header("Authorization", bearer(member)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Members cannot access user management"));
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void superadminCreatesAdminAndPasswordWorksForLogin() throws Exception {
        create(superadmin, newUser("Fresh.Admin@Test.io", "admin"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("fresh.admin@test.io"))
                .andExpect(jsonPath("$.role").value("admin"))
                .andExpect(jsonPath("$.createdBy").value(superadmin.id().toString()))
                .andExpect(jsonPath("$.active").value(true));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"fresh.admin@test.io\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email='fresh.admin@test.io'", String.class);
        assertThat(hash).startsWith("$2").doesNotContain("password123");
    }

    @Test
    void roleMatrixForCreate() throws Exception {
        create(admin, newUser("m1@test.io", "member")).andExpect(status().isCreated());
        create(admin, newUser("a1@test.io", "admin")).andExpect(status().isForbidden());
        create(superadmin, newUser("s1@test.io", "superadmin")).andExpect(status().isForbidden());
        create(member, newUser("m2@test.io", "member")).andExpect(status().isForbidden());
    }

    @Test
    void duplicateEmailIsConflictIgnoringCase() throws Exception {
        create(superadmin, newUser("dup@test.io", "member")).andExpect(status().isCreated());
        create(superadmin, newUser("DUP@test.io", "member"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already exists"));
        update(superadmin, member.id(), "{\"email\":\"dup@test.io\"}").andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"email\":\"not-an-email\",\"password\":\"password123\",\"displayName\":\"N\",\"role\":\"member\"}",
            "{\"email\":\"a@test.io\",\"password\":\"short\",\"displayName\":\"N\",\"role\":\"member\"}",
            "{\"email\":\"a@test.io\",\"password\":\"password123\",\"displayName\":\"\",\"role\":\"member\"}",
            "{\"email\":\"a@test.io\",\"password\":\"password123\",\"displayName\":\"N\"}",
            "{\"email\":\"a@test.io\",\"password\":\"password123\",\"displayName\":\"N\",\"role\":\"root\"}",
            "{\"email\":\"a@test.io\",\"password\":\"password123\",\"displayName\":\"N\",\"role\":5}",
            "{\"password\":\"password123\",\"displayName\":\"N\",\"role\":\"member\"}",
            "{\"email\":null,\"password\":null,\"displayName\":null,\"role\":null}",
            "{}",
            "not json"})
    void invalidCreateBodiesAreBadRequest(String body) throws Exception {
        create(superadmin, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(3);
    }

    @Test
    void displayNameAndPasswordLengthBoundaries() throws Exception {
        String name120 = "n".repeat(120);
        create(superadmin, "{\"email\":\"b@test.io\",\"password\":\"12345678\",\"displayName\":\"" + name120 + "\",\"role\":\"member\"}")
                .andExpect(status().isCreated());
        create(superadmin, "{\"email\":\"c@test.io\",\"password\":\"12345678\",\"displayName\":\"" + name120 + "n\",\"role\":\"member\"}")
                .andExpect(status().isBadRequest());
        create(superadmin, "{\"email\":\"d@test.io\",\"password\":\"" + "p".repeat(129) + "\",\"displayName\":\"N\",\"role\":\"member\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRulesAndPersistence() throws Exception {
        update(admin, member.id(), "{\"displayName\":\"Renamed\",\"active\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Renamed"))
                .andExpect(jsonPath("$.active").value(false));
        assertThat(jdbc.queryForObject("SELECT is_active FROM users WHERE id=?", Boolean.class, member.id())).isFalse();

        update(admin, member.id(), "{\"role\":\"admin\"}").andExpect(status().isForbidden());
        update(superadmin, member.id(), "{\"role\":\"admin\"}").andExpect(jsonPath("$.role").value("admin"));
        update(superadmin, superadmin.id(), "{\"role\":\"member\"}").andExpect(status().isForbidden());
        update(member, admin.id(), "{\"displayName\":\"x\"}").andExpect(status().isForbidden());
        update(superadmin, UUID.randomUUID(), "{\"displayName\":\"x\"}").andExpect(status().isNotFound());
        update(superadmin, member.id(), "{\"email\":\"bad\"}").andExpect(status().isBadRequest());
        update(superadmin, member.id(), "{\"password\":\"short\"}").andExpect(status().isBadRequest());
    }

    @Test
    void updatedAtTriggerFires() throws Exception {
        java.sql.Timestamp before = jdbc.queryForObject("SELECT updated_at FROM users WHERE id=?", java.sql.Timestamp.class, member.id());
        Thread.sleep(20);

        update(superadmin, member.id(), "{\"displayName\":\"Touched\"}").andExpect(status().isOk());

        java.sql.Timestamp after = jdbc.queryForObject("SELECT updated_at FROM users WHERE id=?", java.sql.Timestamp.class, member.id());
        assertThat(after).isAfter(before);
    }

    @Test
    void deactivateEndpointBlocksLogin() throws Exception {
        mvc.perform(post("/api/users/" + member.id() + "/deactivate").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + member.email() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Account is deactivated"));
        mvc.perform(post("/api/users/" + superadmin.id() + "/deactivate").header("Authorization", bearer(superadmin)))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletingAdminReassignsTheirMembersToSuperadmin() throws Exception {
        mvc.perform(delete("/api/users/" + admin.id()).header("Authorization", bearer(superadmin)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, admin.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT created_by FROM users WHERE id=?", UUID.class, member.id()))
                .isEqualTo(superadmin.id());
    }

    @Test
    void deletingUserNullsResultCreator() throws Exception {
        TestUser other = createUser(UserRole.MEMBER, superadmin.id());
        UUID resultId = jdbc.queryForObject("""
                INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, created_by)
                VALUES ('M', '1', 'i', 'x.json', 1, 'h', ?) RETURNING id
                """, UUID.class, other.id());

        mvc.perform(delete("/api/users/" + other.id()).header("Authorization", bearer(superadmin)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT created_by FROM results WHERE id=?", UUID.class, resultId)).isNull();
    }

    @Test
    void deleteRules() throws Exception {
        mvc.perform(delete("/api/users/" + member.id()).header("Authorization", bearer(admin))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/users/" + superadmin.id()).header("Authorization", bearer(superadmin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Cannot delete your own account"));
        mvc.perform(delete("/api/users/" + UUID.randomUUID()).header("Authorization", bearer(superadmin)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/users/not-a-uuid").header("Authorization", bearer(superadmin)))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/users/" + member.id())).andExpect(status().isUnauthorized());
    }
}
