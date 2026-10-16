package com.amrap.it;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.amrap.domain.user.UserRole;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class ChatApiIT extends AbstractIntegrationTest {

    private TestUser member;

    @BeforeEach
    void setUp() {
        member = createUser(UserRole.MEMBER, null);
        when(ollamaChatClient.chat(anyString(), anyList())).thenAnswer(invocation -> {
            String model = invocation.getArgument(0);
            if (model != null && model.contains("coder")) {
                return "SELECT id, model_name, f1_score FROM v_chat_results LIMIT 5";
            }
            // classify model → RAG; answer model → fixed text
            List<?> messages = invocation.getArgument(1);
            String blob = messages == null ? "" : messages.toString();
            if (blob.contains("Classify") || blob.contains("classify")) {
                return "{\"route\":\"RAG\"}";
            }
            return "CardioNet recall is 94.2";
        });
    }

    private ResultActions ask(String body, String auth) throws Exception {
        var req = post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body);
        if (auth != null) {
            req.header("Authorization", auth);
        }
        return mvc.perform(req);
    }

    @Test
    void unknownUiModelFilterReturnsNoMatchWithoutSearch() throws Exception {
        ask("{\"question\":\"What is the recall?\",\"modelNames\":[\"CardioNet\"]}", bearer(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("match your filters")));
    }

    @Test
    void answersWithCitations() throws Exception {
        UUID resultId = UUID.randomUUID();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document(
                "recall: 94.2", Map.of("result_id", resultId.toString(), "model_name", "CardioNet", "chunk_index", 0))));

        ask("{\"question\":\"What is the recall?\"}", bearer(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("CardioNet recall is 94.2"))
                .andExpect(jsonPath("$.citations[0].resultId").value(resultId.toString()))
                .andExpect(jsonPath("$.citations[0].modelName").value("CardioNet"));
    }

    @Test
    void greetingUsesNoneRouteWithoutVectorSearch() throws Exception {
        ask("{\"question\":\"Hello\"}", bearer(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("Hello")));
    }

    @Test
    void noMatchingChunksGivesFriendlyAnswer() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        ask("{\"question\":\"anything\"}", bearer(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations").isEmpty());
    }

    @Test
    void vectorStoreFailureIs502() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("pgvector down"));
        ask("{\"question\":\"q\"}", bearer(member))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502));
    }

    @Test
    void validationErrors() throws Exception {
        ask("{\"question\":\"\"}", bearer(member)).andExpect(status().isBadRequest());
        ask("{}", bearer(member)).andExpect(status().isBadRequest());
        ask("{\"question\":\"" + "q".repeat(2001) + "\"}", bearer(member)).andExpect(status().isBadRequest());
        ask("{\"question\":42,\"history\":\"nope\"}", bearer(member)).andExpect(status().isBadRequest());
    }

    @Test
    void questionAtMaximumLengthIsAccepted() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        ask("{\"question\":\"" + "q".repeat(2000) + "\"}", bearer(member)).andExpect(status().isOk());
    }

    @Test
    void requiresAuthentication() throws Exception {
        ask("{\"question\":\"q\"}", null).andExpect(status().isUnauthorized());
    }

    @Test
    void chatRoRoleCannotSelectUsers() {
        Boolean canUsers = jdbc.queryForObject(
                "SELECT has_table_privilege('amrap_chat_ro', 'users', 'SELECT')", Boolean.class);
        Boolean canView = jdbc.queryForObject(
                "SELECT has_table_privilege('amrap_chat_ro', 'v_chat_results', 'SELECT')", Boolean.class);
        org.assertj.core.api.Assertions.assertThat(canUsers).isFalse();
        org.assertj.core.api.Assertions.assertThat(canView).isTrue();
    }
}

