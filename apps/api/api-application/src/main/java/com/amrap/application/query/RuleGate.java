package com.amrap.application.query;

import com.amrap.application.rag.RagProperties;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Cheap NONE answers (greetings, clock/date) and refuse destructive intents without an LLM.
 */
@Component
public class RuleGate {

    private static final Pattern GREETING = Pattern.compile(
            "^(hi|hello|hey|good\\s+(morning|afternoon|evening)|thanks|thank\\s+you|bye|goodbye"
                    + "|how\\s+are\\s+you|how'?s\\s+it\\s+going)([!.?]*)?$",
            Pattern.CASE_INSENSITIVE);

    /** Calendar / clock only — not "results for today" (those mention results/model/metrics). */
    private static final Pattern ASK_DATE = Pattern.compile(
            "^(what'?s|whats|what\\s+is)\\s+(the\\s+)?"
                    + "(today'?s\\s+)?date(\\s+for\\s+today)?([!.?]*)?$"
                    + "|^today'?s\\s+date([!.?]*)?$"
                    + "|^what\\s+day\\s+is\\s+it([!.?]*)?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ASK_TIME = Pattern.compile(
            "^(what'?s|whats|what\\s+is)\\s+(the\\s+)?(current\\s+)?time([!.?]*)?$"
                    + "|^current\\s+time([!.?]*)?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ANALYTICS = Pattern.compile(
            "\\b(result|results|model|f1|accuracy|recall|precision|sensitivity|specificity"
                    + "|metric|version|iteration|ingested|submitted|category)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "\\b(delete|drop|truncate|update\\s+all|insert\\s+into|remove\\s+all|wipe|destroy)\\b",
            Pattern.CASE_INSENSITIVE);

    private final RagProperties ragProperties;

    public RuleGate(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public Optional<ChatResponse> tryAnswer(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String q = question.trim();
        if (DESTRUCTIVE.matcher(q).find()) {
            return Optional.of(new ChatResponse(
                    "Read-only access only: I cannot delete, update, or otherwise modify data.",
                    java.util.List.of()));
        }
        if (GREETING.matcher(q).matches()) {
            return Optional.of(new ChatResponse("Hello! Ask me about your model results.", java.util.List.of()));
        }
        // "results for today" must not be answered here — leave to SQL/RAG.
        if (!ANALYTICS.matcher(q).find() && ASK_DATE.matcher(q).matches()) {
            LocalDate day = LocalDate.now(ragProperties.zoneId());
            return Optional.of(new ChatResponse(
                    "Today is " + day.format(DateTimeFormatter.ISO_LOCAL_DATE)
                            + " (" + ragProperties.zoneId() + ").",
                    java.util.List.of()));
        }
        if (!ANALYTICS.matcher(q).find() && ASK_TIME.matcher(q).matches()) {
            LocalTime time = LocalTime.now(ragProperties.zoneId());
            return Optional.of(new ChatResponse(
                    "The current time is "
                            + time.format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT))
                            + " (" + ragProperties.zoneId() + ").",
                    java.util.List.of()));
        }
        return Optional.empty();
    }
}
