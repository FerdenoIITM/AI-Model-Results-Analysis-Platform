package com.amrap.application.query;

import com.amrap.application.common.AppException;
import com.amrap.application.rag.RagProperties;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/** Phrases SQL rows and/or RAG chunks into a final answer with citations. */
@Service
public class AnswerService {

    private static final String SYSTEM = """
            You are AMRAP's analytics assistant. Answer strictly from the DATA and CONTEXT below.
            If there is not enough information, say so plainly — do not invent numbers.
            When you cite a value, mention the model, version, and iteration when available.
            Earlier turns are only for resolving follow-ups such as "them".
            """;

    private final ChatProperties properties;
    private final RagProperties ragProperties;
    private final OllamaChatClient ollama;

    public AnswerService(ChatProperties properties, RagProperties ragProperties, OllamaChatClient ollama) {
        this.properties = properties;
        this.ragProperties = ragProperties;
        this.ollama = ollama;
    }

    public ChatResponse answer(
            ChatRequest request,
            List<ChatTurn> history,
            List<Map<String, Object>> sqlRows,
            List<Document> chunks) {
        StringBuilder data = new StringBuilder();
        if (sqlRows != null && !sqlRows.isEmpty()) {
            data.append("SQL_ROWS (").append(sqlRows.size()).append("):\n");
            int i = 0;
            for (Map<String, Object> row : sqlRows) {
                data.append(i + 1).append(". ").append(row).append('\n');
                i++;
                if (i >= 50) {
                    data.append("... truncated\n");
                    break;
                }
            }
            data.append('\n');
        }
        if (chunks != null && !chunks.isEmpty()) {
            data.append("CONTEXT_CHUNKS:\n");
            data.append(renderContext(chunks));
        }
        if (data.isEmpty()) {
            return new ChatResponse(
                    "I don't have any ingested results that match your question yet.", List.of());
        }

        List<OllamaChatClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new OllamaChatClient.ChatMessage(
                "system", SYSTEM + "\nToday is " + LocalDate.now(ragProperties.zoneId()) + "."));
        if (history != null) {
            for (ChatTurn turn : history) {
                String role = "assistant".equalsIgnoreCase(turn.role()) ? "assistant" : "user";
                messages.add(new OllamaChatClient.ChatMessage(role, turn.text()));
            }
        }
        messages.add(new OllamaChatClient.ChatMessage(
                "user", data + "\nQUESTION: " + request.question().trim()));

        try {
            String answer = ollama.chat(properties.getAnswerModel(), messages);
            return new ChatResponse(answer, toCitations(chunks));
        } catch (AppException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AppException(502, "LLM call failed: " + ex.getMessage());
        }
    }

    private static String renderContext(List<Document> hits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Document d = hits.get(i);
            Map<String, Object> m = d.getMetadata();
            sb.append("--- Chunk ").append(i + 1).append(" ---\n");
            sb.append("result_id=").append(m.getOrDefault("result_id", "?"));
            sb.append(" model=").append(m.getOrDefault("model_name", "?"));
            sb.append(" version=").append(m.getOrDefault("version", "?"));
            sb.append(" iteration=").append(m.getOrDefault("iteration_id", "?"));
            sb.append(" inserted_at=").append(m.getOrDefault("inserted_at", "?"));
            sb.append('\n');
            sb.append(d.getText()).append("\n\n");
        }
        return sb.toString();
    }

    private static List<ChatCitation> toCitations(List<Document> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<ChatCitation> out = new ArrayList<>(hits.size());
        for (Document d : hits) {
            Map<String, Object> m = d.getMetadata();
            out.add(new ChatCitation(
                    asUuid(m.get("result_id")),
                    asString(m.get("model_name")),
                    asString(m.get("version")),
                    asString(m.get("iteration_id")),
                    asString(m.get("inserted_at")),
                    asInt(m.get("chunk_index")),
                    d.getScore()));
        }
        return out;
    }

    private static UUID asUuid(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return UUID.fromString(v.toString());
        } catch (Exception ex) {
            return null;
        }
    }

    private static Integer asInt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (Exception ex) {
            return null;
        }
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }
}
