package com.amrap.application.query;

import com.amrap.application.common.AppException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Routed chat pipeline: RuleGate → classify → SQL / RAG / HYBRID → answer LLM.
 */
@Service
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);
    private static final int MAX_TURN_CHARS = 2000;

    private final RuleGate ruleGate;
    private final PromptClassifier classifier;
    private final SqlQueryService sqlQueryService;
    private final RagSearchService ragSearchService;
    private final AnswerService answerService;
    private final ChatScopeService chatScopeService;
    private final OllamaChatClient ollama;
    private final ChatProperties chatProperties;

    public ChatOrchestrator(
            RuleGate ruleGate,
            PromptClassifier classifier,
            SqlQueryService sqlQueryService,
            RagSearchService ragSearchService,
            AnswerService answerService,
            ChatScopeService chatScopeService,
            OllamaChatClient ollama,
            ChatProperties chatProperties) {
        this.ruleGate = ruleGate;
        this.classifier = classifier;
        this.sqlQueryService = sqlQueryService;
        this.ragSearchService = ragSearchService;
        this.answerService = answerService;
        this.chatScopeService = chatScopeService;
        this.ollama = ollama;
        this.chatProperties = chatProperties;
    }

    public ChatResponse ask(ChatRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new AppException(400, "question is required");
        }
        String question = request.question().trim();
        var gated = ruleGate.tryAnswer(question);
        if (gated.isPresent()) {
            return gated.get();
        }

        List<ChatTurn> history = recentHistory(request.history());
        ChatRoute route = classifier.classify(question, history);
        log.info("Chat route={} for question len={}", route, question.length());

        return switch (route) {
            case NONE -> ruleGate.tryAnswer(question)
                    .orElse(new ChatResponse(
                            "I can only help with your model results (metrics, notes, comparisons)—not general questions. "
                                    + "What would you like to know about your results?",
                            List.of()));
            case SQL -> runSql(request, history);
            case RAG -> runRag(request, history, null);
            case HYBRID -> runHybrid(request, history);
        };
    }

    private ChatResponse runSql(ChatRequest request, List<ChatTurn> history) {
        if (!sqlQueryService.available()) {
            log.warn("SQL route requested but chat SQL unavailable; falling back to RAG");
            return runRag(request, history, null);
        }
        try {
            List<Map<String, Object>> rows = sqlQueryService.run(request, history);
            return answerService.answer(request, history, rows, List.of());
        } catch (AppException ex) {
            if (ex.getStatus() >= 500 && ragSearchService.available()) {
                log.warn("SQL failed ({}), falling back to RAG", ex.getMessage());
                ChatResponse rag = runRag(request, history, null);
                return new ChatResponse(
                        "(SQL path failed; answered from documents instead.)\n\n" + rag.answer(),
                        rag.citations());
            }
            throw ex;
        }
    }

    private ChatResponse runRag(ChatRequest request, List<ChatTurn> history, List<UUID> restrictIds) {
        if (!ragSearchService.available()) {
            return new ChatResponse(
                    "RAG is not configured on this server. Make sure the Ollama container is "
                            + "running and models are pulled, then restart the API.",
                    List.of());
        }
        List<UUID> scopeIds = chatScopeService.resolveResultIds(request);
        if (scopeIds != null && scopeIds.isEmpty()) {
            return new ChatResponse(
                    "I don't have any ingested results that match your filters yet.", List.of());
        }
        List<UUID> merged = mergeIds(restrictIds, scopeIds);
        if (merged != null && merged.isEmpty()) {
            return new ChatResponse(
                    "I don't have any ingested results that match your filters yet.", List.of());
        }
        String searchQuery = history.isEmpty()
                ? request.question().trim()
                : rewriteQuery(request.question().trim(), history);
        List<Document> hits = ragSearchService.search(request, searchQuery, merged);
        return answerService.answer(request, history, List.of(), hits);
    }

    private ChatResponse runHybrid(ChatRequest request, List<ChatTurn> history) {
        if (!sqlQueryService.available()) {
            return runRag(request, history, null);
        }
        List<Map<String, Object>> rows;
        try {
            rows = sqlQueryService.run(request, history);
        } catch (AppException ex) {
            log.warn("HYBRID SQL failed, falling back to RAG: {}", ex.getMessage());
            return runRag(request, history, null);
        }
        if (rows.isEmpty()) {
            return new ChatResponse(
                    "I don't have any ingested results that match your filters yet.", List.of());
        }
        List<UUID> ids = ChatSqlExecutor.extractResultIds(rows);
        if (ids.isEmpty() || !ragSearchService.available()) {
            return answerService.answer(request, history, rows, List.of());
        }
        String searchQuery = history.isEmpty()
                ? request.question().trim()
                : rewriteQuery(request.question().trim(), history);
        List<UUID> scopeIds = chatScopeService.resolveResultIds(request);
        List<UUID> merged = mergeIds(ids, scopeIds);
        if (merged != null && merged.isEmpty()) {
            return answerService.answer(request, history, rows, List.of());
        }
        List<Document> hits = ragSearchService.search(request, searchQuery, merged);
        return answerService.answer(request, history, rows, hits);
    }

    /** Intersect two id lists. Null means “no restriction” on that side; empty means no matches. */
    static List<UUID> mergeIds(List<UUID> a, List<UUID> b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        Set<UUID> allowed = new HashSet<>(b);
        List<UUID> out = new ArrayList<>();
        for (UUID id : a) {
            if (allowed.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private String rewriteQuery(String question, List<ChatTurn> history) {
        StringBuilder transcript = new StringBuilder();
        for (ChatTurn turn : history) {
            transcript.append(turn.role()).append(": ").append(turn.text()).append('\n');
        }
        transcript.append("latest: ").append(question);
        try {
            String rewritten = ollama.chat(
                    chatProperties.getClassifyModel(),
                    List.of(
                            new OllamaChatClient.ChatMessage(
                                    "system",
                                    """
                                    Rewrite the latest user question into one standalone search sentence.
                                    Resolve pronouns using the conversation. Keep today/yesterday when they apply.
                                    Return only that search sentence, with no quotes.
                                    """),
                            new OllamaChatClient.ChatMessage("user", transcript.toString())));
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
            log.warn("Query rewrite failed, using original question: {}", ex.getMessage());
            return question;
        }
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
}
