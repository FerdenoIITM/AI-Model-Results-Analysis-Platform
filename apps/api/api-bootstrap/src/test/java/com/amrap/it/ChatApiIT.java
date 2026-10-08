package com.amrap.it;

import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class ChatApiIT extends AbstractIntegrationTest {

    private TestUser member;

    @BeforeEach
    void setUp() {
        member = createUser(UserRole.MEMBER, null);
        when(chatModel.getDefaultOptions()).thenReturn(ChatOptions.builder().build());
    }

    private ResultActions ask(String body, String auth) throws Exception {
        var req = post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body);
        if (auth != null) {
            req.header("Authorization", auth);
        }
        return mvc.perform(req);
    }

    @Test
    void answersWithCitations() throws Exception {
        UUID resultId = UUID.randomUUID();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document(
                "recall: 94.2", Map.of("result_id", resultId.toString(), "model_name", "CardioNet", "chunk_index", 0))));
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("CardioNet recall is 94.2")))));

        ask("{\"question\":\"What is the recall?\",\"modelName\":\"CardioNet\"}", bearer(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("CardioNet recall is 94.2"))
                .andExpect(jsonPath("$.citations[0].resultId").value(resultId.toString()))
                .andExpect(jsonPath("$.citations[0].modelName").value("CardioNet"));
    }

    @Test
    void noMatchingChunksGivesFriendlyAnswer() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        ask("{\"question\":\"anything\"}", bearer(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations").isEmpty());
    }

    @Test
    void vectorStoreOrLlmFailureIs502() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("pgvector down"));
        ask("{\"question\":\"q\"}", bearer(member))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502));

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("x", Map.of())));
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Connection refused"));
        ask("{\"question\":\"q\"}", bearer(member))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("LLM call failed")));
    }

    @Test
    void validationErrors() throws Exception {
        ask("{\"question\":\"\"}", bearer(member)).andExpect(status().isBadRequest());
        ask("{}", bearer(member)).andExpect(status().isBadRequest());
        ask("{\"question\":\"" + "q".repeat(2001) + "\"}", bearer(member)).andExpect(status().isBadRequest());
        ask("{\"question\":\"q\",\"fromIso\":\"last tuesday\"}", bearer(member))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid ISO timestamp: last tuesday"));
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
}
