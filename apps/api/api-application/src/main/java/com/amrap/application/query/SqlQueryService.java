package com.amrap.application.query;

import com.amrap.application.common.AppException;
import com.amrap.application.rag.RagProperties;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Text-to-SQL against {@code v_chat_results} using a dedicated local coder model. */
@Service
public class SqlQueryService {

    private static final Logger log = LoggerFactory.getLogger(SqlQueryService.class);

    private static final String SCHEMA = """
            You write PostgreSQL for AMRAP. Output ONLY one SELECT statement, no markdown.
            Query only this view:

            CREATE VIEW v_chat_results (
              id UUID,
              model_name VARCHAR,
              version VARCHAR,
              iteration_id VARCHAR,
              inserted_at TIMESTAMPTZ,
              category VARCHAR NULL,
              sub_category VARCHAR NULL,
              confidence_accuracy DOUBLE PRECISION NULL,  -- percentage 0..100
              sensitivity DOUBLE PRECISION NULL,           -- percentage 0..100
              specificity DOUBLE PRECISION NULL,           -- percentage 0..100
              f1_score DOUBLE PRECISION NULL,              -- percentage 0..100
              recall DOUBLE PRECISION NULL,                -- percentage 0..100
              precision DOUBLE PRECISION NULL              -- percentage 0..100
            );

            Rules:
            - SELECT only from v_chat_results.
            - Metrics are percentage points in [0, 100], not 0..1 fractions.
            - Prefer including id in the SELECT list when returning rows (needed for follow-up RAG).
            - Use LIMIT at most 200.
            - Do not use other tables.
            """;

    private final ChatProperties properties;
    private final RagProperties ragProperties;
    private final OllamaChatClient ollama;
    private final SqlValidator validator;
    private final ChatSqlExecutor executor;

    public SqlQueryService(
            ChatProperties properties,
            RagProperties ragProperties,
            OllamaChatClient ollama,
            SqlValidator validator,
            ChatSqlExecutor executor) {
        this.properties = properties;
        this.ragProperties = ragProperties;
        this.ollama = ollama;
        this.validator = validator;
        this.executor = executor;
    }

    public boolean available() {
        return executor.available();
    }

    public List<Map<String, Object>> run(ChatRequest request, List<ChatTurn> history) {
        if (!available()) {
            throw new AppException(503, "Chat SQL is not configured. Set CHAT_DB_PASSWORD and apply V5 migrations.");
        }
        String question = request.question().trim();
        String constraints = uiConstraints(request);
        String system = SCHEMA + "\nToday is " + LocalDate.now(ragProperties.zoneId())
                + " in timezone " + ragProperties.zoneId() + ".\n"
                + constraints;

        Exception last = null;
        String previousSql = null;
        String previousError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            List<OllamaChatClient.ChatMessage> messages = new ArrayList<>();
            messages.add(new OllamaChatClient.ChatMessage("system", system));
            if (history != null) {
                for (ChatTurn turn : history) {
                    String role = "assistant".equalsIgnoreCase(turn.role()) ? "assistant" : "user";
                    messages.add(new OllamaChatClient.ChatMessage(role, turn.text()));
                }
            }
            String userPrompt = "Write SQL for: " + question;
            if (previousSql != null) {
                userPrompt += "\n\nPrevious SQL failed:\n" + previousSql + "\nError: " + previousError
                        + "\nFix it. Output only SQL.";
            }
            messages.add(new OllamaChatClient.ChatMessage("user", userPrompt));
            try {
                String raw = ollama.chat(properties.getSqlModel(), messages);
                String sql = validator.validate(raw);
                previousSql = sql;
                return executor.execute(sql);
            } catch (AppException ex) {
                last = ex;
                previousError = ex.getMessage();
                log.warn("SQL attempt {} failed: {}", attempt + 1, ex.getMessage());
            } catch (Exception ex) {
                last = ex;
                previousError = ex.getMessage();
                log.warn("SQL attempt {} failed: {}", attempt + 1, ex.getMessage());
            }
        }
        if (last instanceof AppException app) {
            throw app;
        }
        throw new AppException(502, "SQL generation failed: " + (last == null ? "unknown" : last.getMessage()));
    }

    private static String uiConstraints(ChatRequest request) {
        StringBuilder sb = new StringBuilder("Hard filters from the UI (must apply in WHERE if relevant):\n");
        boolean any = false;
        if (notBlank(request.modelName())) {
            sb.append("- model_name = '").append(escape(request.modelName().trim())).append("'\n");
            any = true;
        }
        if (notBlank(request.version())) {
            sb.append("- version = '").append(escape(request.version().trim())).append("'\n");
            any = true;
        }
        if (notBlank(request.iterationId())) {
            sb.append("- iteration_id = '").append(escape(request.iterationId().trim())).append("'\n");
            any = true;
        }
        if (notBlank(request.fromIso())) {
            sb.append("- inserted_at >= '").append(escape(request.fromIso().trim())).append("'\n");
            any = true;
        }
        if (notBlank(request.toIso())) {
            sb.append("- inserted_at <= '").append(escape(request.toIso().trim())).append("'\n");
            any = true;
        }
        if (!any) {
            return "No extra UI filters.\n";
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String escape(String s) {
        return s.replace("'", "''");
    }
}
