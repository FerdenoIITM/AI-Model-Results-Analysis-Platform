package com.amrap.application.query;

import com.amrap.application.common.AppException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Runs validated SELECT SQL against the read-only chat datasource. */
@Component
public class ChatSqlExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChatSqlExecutor.class);
    private static final Pattern LIMIT = Pattern.compile("\\blimit\\s+\\d+\\b", Pattern.CASE_INSENSITIVE);

    private final ChatProperties properties;
    private final ChatJdbc chatJdbc;

    public ChatSqlExecutor(
            ChatProperties properties,
            @Autowired(required = false) ChatJdbc chatJdbc) {
        this.properties = properties;
        this.chatJdbc = chatJdbc;
    }

    public boolean available() {
        return chatJdbc != null && properties.chatSqlConfigured();
    }

    public List<Map<String, Object>> execute(String validatedSql) {
        if (!available()) {
            throw new AppException(503, "Chat SQL is not configured. Set CHAT_DB_PASSWORD and apply V5 migrations.");
        }
        String sql = ensureLimit(validatedSql, properties.getSqlMaxRows());
        try {
            return chatJdbc.query(sql, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                int cols = rs.getMetaData().getColumnCount();
                for (int i = 1; i <= cols; i++) {
                    String label = rs.getMetaData().getColumnLabel(i);
                    row.put(label, rs.getObject(i));
                }
                return row;
            });
        } catch (AppException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Chat SQL failed: {}", ex.getMessage());
            throw new AppException(502, "Chat SQL failed: " + ex.getMessage());
        }
    }

    /** Collects UUID values from columns named id or result_id. */
    public static List<UUID> extractResultIds(List<Map<String, Object>> rows) {
        List<UUID> ids = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object raw = first(row, "id", "result_id");
            if (raw == null) {
                continue;
            }
            try {
                UUID id = raw instanceof UUID u ? u : UUID.fromString(raw.toString());
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            } catch (IllegalArgumentException ignored) {
                // skip non-uuid
            }
        }
        return ids;
    }

    private static Object first(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    static String ensureLimit(String sql, int maxRows) {
        int max = Math.max(1, maxRows);
        Matcher m = LIMIT.matcher(sql);
        if (m.find()) {
            String number = m.group().replaceAll("(?i)limit\\s+", "").trim();
            try {
                int existing = Integer.parseInt(number);
                if (existing > max) {
                    return m.replaceFirst("LIMIT " + max);
                }
                return sql;
            } catch (NumberFormatException ex) {
                return sql + " LIMIT " + max;
            }
        }
        String trimmed = sql.trim();
        if (trimmed.toLowerCase(Locale.ROOT).contains(" limit ")) {
            return sql;
        }
        return sql + " LIMIT " + max;
    }
}
