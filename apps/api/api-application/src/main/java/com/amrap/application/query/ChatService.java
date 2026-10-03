package com.amrap.application.query;

import com.amrap.application.common.AppException;
import com.amrap.application.rag.RagProperties;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * RAG orchestrator:
 *
 * <ol>
 *   <li>Build an optional metadata filter from {@link ChatRequest} (model/version/date).</li>
 *   <li>Similarity-search the top-K chunks via Spring AI's {@link VectorStore}.</li>
 *   <li>Prompt the local Ollama chat model with the question + retrieved context.</li>
 *   <li>Return the answer + lightweight citations (which result each chunk came from).</li>
 * </ol>
 *
 * <p>If no {@link VectorStore} or {@link ChatModel} bean is present (Ollama unreachable at
 * boot), the service still answers with a clear 503-style message so the UI can show it.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String SYSTEM_PROMPT = """
            You are AMRAP's analytics assistant. Answer the user's question strictly from
            the CONTEXT chunks below. Each chunk comes from a stored AI model result and is
            labelled with its result id, model name, version, and iteration. If the context
            does not contain enough information to answer, say so plainly — do not invent
            numbers. When you cite a value, mention the model, version, and iteration it
            came from.
            """;

    private final RagProperties properties;
    private final VectorStore vectorStore;
    private final ChatModel chatModel;

    public ChatService(
            RagProperties properties,
            @Autowired(required = false) VectorStore vectorStore,
            @Autowired(required = false) ChatModel chatModel) {
        this.properties = properties;
        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
    }

    public ChatResponse ask(ChatRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new AppException(400, "question is required");
        }
        if (vectorStore == null || chatModel == null) {
            return new ChatResponse(
                    "RAG is not configured on this server. Make sure the Ollama container is "
                            + "running and models are pulled, then restart the API.",
                    List.of());
        }

        SearchRequest.Builder builder = SearchRequest.builder()
                .query(request.question())
                .topK(properties.getTopK());

        Filter.Expression filter = buildFilter(request);
        if (filter != null) {
            builder.filterExpression(filter);
        }

        List<Document> hits;
        try {
            hits = vectorStore.similaritySearch(builder.build());
        } catch (Exception ex) {
            log.error("Vector search failed", ex);
            throw new AppException(502, "Vector search failed: " + ex.getMessage());
        }
        if (hits == null) {
            hits = List.of();
        }

        if (hits.isEmpty()) {
            return new ChatResponse(
                    "I don't have any ingested results that match your question yet.", List.of());
        }

        String context = renderContext(hits);
        List<Message> messages = List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage("CONTEXT:\n" + context + "\n\nQUESTION: " + request.question()));

        String answer;
        try {
            answer = ChatClient.builder(chatModel).build()
                    .prompt(new Prompt(messages))
                    .call()
                    .content();
        } catch (Exception ex) {
            log.error("Chat model call failed", ex);
            throw new AppException(502, "LLM call failed: " + ex.getMessage());
        }

        return new ChatResponse(answer, toCitations(hits));
    }

    private Filter.Expression buildFilter(ChatRequest r) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        List<FilterExpressionBuilder.Op> parts = new ArrayList<>();

        if (notBlank(r.modelName())) {
            parts.add(b.eq("model_name", r.modelName().trim()));
        }
        if (notBlank(r.version())) {
            parts.add(b.eq("version", r.version().trim()));
        }
        if (notBlank(r.iterationId())) {
            parts.add(b.eq("iteration_id", r.iterationId().trim()));
        }
        if (notBlank(r.fromIso())) {
            parts.add(b.gte("inserted_at", parseIso(r.fromIso())));
        }
        if (notBlank(r.toIso())) {
            parts.add(b.lte("inserted_at", parseIso(r.toIso())));
        }

        if (parts.isEmpty()) {
            return null;
        }
        FilterExpressionBuilder.Op combined = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            combined = b.and(combined, parts.get(i));
        }
        return combined.build();
    }

    private static String parseIso(String iso) {
        try {
            // Keep as ISO string — pgvector metadata is JSON; string comparison on ISO-8601
            // timestamps sorts correctly.
            return Instant.parse(iso.trim()).toString();
        } catch (DateTimeParseException ex) {
            throw new AppException(400, "Invalid ISO timestamp: " + iso);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
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
        List<ChatCitation> out = new ArrayList<>(hits.size());
        for (Document d : hits) {
            Map<String, Object> m = d.getMetadata();
            UUID resultId = asUuid(m.get("result_id"));
            Integer chunkIndex = asInt(m.get("chunk_index"));
            Double score = d.getScore();
            out.add(new ChatCitation(
                    resultId,
                    asString(m.get("model_name")),
                    asString(m.get("version")),
                    asString(m.get("iteration_id")),
                    asString(m.get("inserted_at")),
                    chunkIndex,
                    score));
        }
        return out;
    }

    private static UUID asUuid(Object v) {
        if (v == null) return null;
        try {
            return UUID.fromString(v.toString());
        } catch (Exception ex) {
            return null;
        }
    }

    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
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
