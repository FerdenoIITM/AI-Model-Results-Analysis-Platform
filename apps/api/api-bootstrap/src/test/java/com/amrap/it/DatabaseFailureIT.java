package com.amrap.it;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.amrap.domain.user.UserRole;
import com.amrap.persistence.result.ResultRepository;
import com.amrap.persistence.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;

/**
 * The database layer fails (connection lost, pool exhausted). The API must answer with a
 * generic 500 body and never leak driver messages or stack traces.
 */
class DatabaseFailureIT extends AbstractIntegrationTest {

    @MockBean
    private ResultRepository resultRepository;

    @MockBean
    private UserRepository userRepository;

    private static final DataAccessResourceFailureException DOWN =
            new DataAccessResourceFailureException("Connection to db:5432 refused. password=secret");

    @Test
    void listFailsWithGeneric500() throws Exception {
        TestUser member = createUser(UserRole.MEMBER, null);
        when(resultRepository.search(any())).thenThrow(DOWN);

        mvc.perform(get("/api/results").header("Authorization", bearer(member)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("Unexpected server error"))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("5432"))));
    }

    @Test
    void filterOptionsAndIngestFailWithGeneric500() throws Exception {
        TestUser member = createUser(UserRole.MEMBER, null);
        when(resultRepository.findDistinctModelNames()).thenThrow(DOWN);
        when(resultRepository.save(any())).thenThrow(DOWN);

        mvc.perform(get("/api/results/filter-options").header("Authorization", bearer(member)))
                .andExpect(status().isInternalServerError());
        mvc.perform(post("/api/results").header("X-Ingest-Key", INGEST_KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelName\":\"M\",\"version\":\"1\",\"payload\":{}}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Unexpected server error"));
    }

    @Test
    void loginFailsWithGeneric500() throws Exception {
        when(userRepository.findByEmail(any())).thenThrow(DOWN);

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@test.io\",\"password\":\"x\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("refused"))));
    }
}
