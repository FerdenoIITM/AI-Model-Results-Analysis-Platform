package com.amrap.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.amrap.domain.user.UserRole;
import org.junit.jupiter.api.Test;

/** Cross-cutting HTTP behaviour: health, CORS, unknown routes, wrong methods, error body shape. */
class PlatformIT extends AbstractIntegrationTest {

    @Test
    void healthIsPublicAndUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void corsPreflightFromAnotherOriginIsAllowed() throws Exception {
        mvc.perform(options("/api/auth/login")
                        .header("Origin", "http://172.16.100.74:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://172.16.100.74:5173"));
    }

    @Test
    void unknownRouteIs404ForAuthenticatedUser() throws Exception {
        TestUser member = createUser(UserRole.MEMBER, null);

        mvc.perform(get("/api/does-not-exist").header("Authorization", bearer(member)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void wrongMethodIs405() throws Exception {
        TestUser member = createUser(UserRole.MEMBER, null);

        mvc.perform(delete("/api/results").header("Authorization", bearer(member)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
        mvc.perform(put("/api/auth/me").header("Authorization", bearer(member)))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void protectedEndpointsReturnJson401WithoutToken() throws Exception {
        for (String path : new String[] {"/api/results", "/api/results/filter-options", "/api/users", "/api/auth/me"}) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
    }
}
