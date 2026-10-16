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
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Vector similarity search over {@code result_chunks}, with optional result_id restriction. */
@Service
public class RagSearchService {

    private static final Logger log = LoggerFactory.getLogger(RagSearchService.class);
    private static final Pattern TODAY = Pattern.compile("\\btoday\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern YESTERDAY = Pattern.compile("\\byesterday\\b", Pattern.CASE_INSENSITIVE);

    private final RagProperties properties;
    private final VectorStore vectorStore;

    public RagSearchService(
            RagProperties properties,
            @Autowired(required = false) VectorStore vectorStore) {
        this.properties = properties;
        this.vectorStore = vectorStore;
    }

    public boolean available() {
        return vectorStore != null;
    }

    public List<Document> search(ChatRequest request, String searchQuery, List<UUID> restrictToResultIds) {
        if (!available()) {
            throw new AppException(503, "Vector store is not configured. Start Ollama and restart the API.");
        }
        ChatRequest scoped = withRelativeDay(request, searchQuery);
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(searchQuery)
                .topK(properties.getTopK());

        Filter.Expression filter = buildFilter(scoped, restrictToResultIds);
        if (filter != null) {
            builder.filterExpression(filter);
        }

        try {
            List<Document> hits = vectorStore.similaritySearch(builder.build());
            if (hits == null) {
                return List.of();
            }
            if (restrictToResultIds != null && !restrictToResultIds.isEmpty()) {
                return postFilterByIds(hits, restrictToResultIds);
            }
            return hits;
        } catch (Exception ex) {
            log.error("Vector search failed", ex);
            throw new AppException(502, "Vector search failed: " + ex.getMessage());
        }
    }

    private Filter.Expression buildFilter(ChatRequest r, List<UUID> restrictToResultIds) {
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
        if (restrictToResultIds != null && !restrictToResultIds.isEmpty() && restrictToResultIds.size() <= 20) {
            // Spring AI in() with string ids; post-filter still applied for safety.
            List<Object> values = new ArrayList<>(restrictToResultIds.size());
            for (UUID id : restrictToResultIds) {
                values.add(id.toString());
            }
            parts.add(b.in("result_id", values.toArray()));
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

    private static List<Document> postFilterByIds(List<Document> hits, List<UUID> allowed) {
        List<Document> out = new ArrayList<>();
        for (Document d : hits) {
            Object raw = d.getMetadata().get("result_id");
            if (raw == null) {
                continue;
            }
            try {
                UUID id = UUID.fromString(raw.toString());
                if (allowed.contains(id)) {
                    out.add(d);
                }
            } catch (IllegalArgumentException ignored) {
                // skip
            }
        }
        return out;
    }

    ChatRequest withRelativeDay(ChatRequest request, String searchQuery) {
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

    private static String parseIso(String iso) {
        try {
            return Instant.parse(iso.trim()).toString();
        } catch (DateTimeParseException ex) {
            throw new AppException(400, "Invalid ISO timestamp: " + iso);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
