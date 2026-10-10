package com.amrap.application.query;

import com.amrap.application.common.AppException;
import com.amrap.application.rag.RagProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
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

    private static final int MAX_TURN_CHARS = 2000;
    private static final Pattern TODAY = Pattern.compile("\\btoday\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern YESTERDAY = Pattern.compile("\\byesterday\\b", Pattern.CASE_INSENSITIVE);

    private static final String SYSTEM_PROMPT = """
            You are AMRAP's analytics assistant. Answer the user's question strictly from
            the CONTEXT chunks below. Each chunk comes from a stored AI model result and is
            labelled with its result id, model name, version, and iteration. If the context
            does not contain enough information to answer, say so plainly — do not invent
            numbers. When you cite a value, mention the model, version, and iteration it
            came from. Earlier turns are only for resolving follow-ups such as "them".
            """;

    private static final String REWRITE_PROMPT = """
            Rewrite the latest user question into one standalone search sentence.
            Resolve pronouns such as them, those, and it using the conversation.
            Keep words such as today or yesterday when they still apply.
            Return only that search sentence, with no quotes.
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

        List<ChatTurn> history = recentHistory(request.history());
        String searchQuery = history.isEmpty()
                ? request.question().trim()
                : rewriteQuery(request.question().trim(), history);
        ChatRequest scoped = withRelativeDay(request, searchQuery);

        SearchRequest.Builder builder = SearchRequest.builder()
                .query(searchQuery)
                .topK(properties.getTopK());

        Filter.Expression filter = buildFilter(scoped);
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
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT + "\nToday is " + LocalDate.now(properties.zoneId()) + "."));
        for (ChatTurn turn : history) {
            if ("assistant".equalsIgnoreCase(turn.role())) {
                messages.add(new AssistantMessage(turn.text()));
            } else {
                messages.add(new UserMessage(turn.text()));
            }
        }
        messages.add(new UserMessage("CONTEXT:\n" + context + "\n\nQUESTION: " + request.question()));

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

    private List<ChatTurn> recentHistory(List<ChatTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<ChatTurn> kept = new ArrayList<>();
        for (ChatTurn turn : history) {
            if (turn == null || turn.text() == null || turn.text().isBlank()) {
                continue;
            }
            String role = turn.role() == null ? "" : turn.role().trim().toLowerCase();
            if (!role.equals("user") && !role.equals("assistant")) {
                continue;
            }
            String text = turn.text().trim();
            if (text.length() > MAX_TURN_CHARS) {
                text = text.substring(0, MAX_TURN_CHARS);
            }
            kept.add(new ChatTurn(role, text));
        }
        return List.copyOf(kept);
    }

    private String rewriteQuery(String question, List<ChatTurn> history) {
        StringBuilder transcript = new StringBuilder();
        for (ChatTurn turn : history) {
            transcript.append(turn.role()).append(": ").append(turn.text()).append('\n');
        }
        transcript.append("latest: ").append(question);
        try {
            String rewritten = ChatClient.builder(chatModel).build()
                    .prompt(new Prompt(List.of(
                            new SystemMessage(REWRITE_PROMPT),
                            new UserMessage(transcript.toString()))))
                    .call()
                    .content();
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String line = rewritten.trim();
            int newline = line.indexOf('\n');
            if (newline > 0) {
                line = line.substring(0, newline).trim();
            }
            if (line.length() > 2 && line.startsWith("\"") && line.endsWith("\"")) {
                line = line.substring(1, line.length() - 1).trim();
            }
            return line.isBlank() ? question : line;
        } catch (Exception ex) {
            log.warn("Query rewrite failed, searching the original question: {}", ex.getMessage());
            return question;
        }
    }

    private ChatRequest withRelativeDay(ChatRequest request, String searchQuery) {
        if (notBlank(request.fromIso()) || notBlank(request.toIso())) {
            return request;
        }
        ZoneId zone = properties.zoneId();
        LocalDate day = relativeDay(request.question(), zone);
        if (day == null) {
            day = relativeDay(searchQuery, zone);
        }
        if (day == null) {
            return request;
        }
        ZonedDateTime start = day.atStartOfDay(zone);
        return new ChatRequest(
                request.question(),
                request.modelName(),
                request.version(),
                request.iterationId(),
                start.toInstant().toString(),
                start.plusDays(1).toInstant().toString(),
                request.history());
    }

    private static LocalDate relativeDay(String text, ZoneId zone) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (YESTERDAY.matcher(text).find()) {
            return LocalDate.now(zone).minusDays(1);
        }
        if (TODAY.matcher(text).find()) {
            return LocalDate.now(zone);
        }
        return null;
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
