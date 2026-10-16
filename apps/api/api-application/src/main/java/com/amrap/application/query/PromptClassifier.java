package com.amrap.application.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Routes a question (+ history) to NONE / SQL / RAG / HYBRID using a dedicated local model. */
@Component
public class PromptClassifier {

    private static final Logger log = LoggerFactory.getLogger(PromptClassifier.class);

    private static final String SYSTEM = """
            You classify questions for AMRAP (AI model results analytics).
            Reply with ONLY a JSON object: {"route":"NONE|SQL|RAG|HYBRID"}
            Rules:
            - NONE: greetings, date/time, or help that needs no database.
            - SQL: counts, averages, best/worst metrics, lists filtered by model/version/date/category/metrics columns.
            - RAG: meaning/notes/datasets/free-text explanations from result JSON chunks.
            - HYBRID: needs both column filters AND text/notes (e.g. "notes of models with F1 > 90").
            Use conversation history to resolve pronouns such as them/those.
            """;

    private final ChatProperties properties;
    private final OllamaChatClient ollama;
    private final ObjectMapper mapper;

    public PromptClassifier(ChatProperties properties, OllamaChatClient ollama, ObjectMapper mapper) {
        this.properties = properties;
        this.ollama = ollama;
        this.mapper = mapper;
    }

    public ChatRoute classify(String question, List<ChatTurn> history) {
        List<OllamaChatClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new OllamaChatClient.ChatMessage("system", SYSTEM));
        if (history != null) {
            for (ChatTurn turn : history) {
                String role = "assistant".equalsIgnoreCase(turn.role()) ? "assistant" : "user";
                messages.add(new OllamaChatClient.ChatMessage(role, turn.text()));
            }
        }
        messages.add(new OllamaChatClient.ChatMessage("user", "Classify this question:\n" + question));
        try {
            String raw = ollama.chat(properties.getClassifyModel(), messages);
            return parseRoute(raw);
        } catch (Exception ex) {
            log.warn("Classification failed, defaulting to RAG: {}", ex.getMessage());
            return ChatRoute.RAG;
        }
    }

    ChatRoute parseRoute(String raw) {
        if (raw == null || raw.isBlank()) {
            return ChatRoute.RAG;
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
            try {
                JsonNode node = mapper.readTree(text);
                return ChatRoute.parse(node.path("route").asText(null));
            } catch (Exception ignored) {
                // fall through
            }
        }
        String upper = raw.toUpperCase();
        for (ChatRoute route : ChatRoute.values()) {
            if (upper.contains(route.name())) {
                return route;
            }
        }
        return ChatRoute.RAG;
    }
}
