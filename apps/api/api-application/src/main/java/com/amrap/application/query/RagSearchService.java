package com.amrap.application.query;

import com.amrap.application.common.AppException;
import com.amrap.application.rag.RagProperties;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(searchQuery)
                .topK(properties.getTopK());

        Filter.Expression filter = buildFilter(request.question(), searchQuery, restrictToResultIds);
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

    private Filter.Expression buildFilter(String question, String searchQuery, List<UUID> restrictToResultIds) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        List<FilterExpressionBuilder.Op> parts = new ArrayList<>();

        ZoneId zone = properties.zoneId();
        LocalDate day = relativeDay(question, zone);
        if (day == null) {
            day = relativeDay(searchQuery, zone);
        }
        if (day != null) {
            ZonedDateTime start = day.atStartOfDay(zone);
            parts.add(b.gte("inserted_at", start.toInstant().toString()));
            parts.add(b.lte("inserted_at", start.plusDays(1).toInstant().toString()));
        }

        if (restrictToResultIds != null && !restrictToResultIds.isEmpty() && restrictToResultIds.size() <= 20) {
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
}
