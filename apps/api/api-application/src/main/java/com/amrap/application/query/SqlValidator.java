package com.amrap.application.query;

import com.amrap.application.common.AppException;
import java.util.Locale;
import java.util.Set;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

/**
 * Accepts a single {@code SELECT} against {@code v_chat_results} only.
 * Rejects multi-statements, DML/DDL, and dangerous functions.
 */
@Component
public class SqlValidator {

    public static final String ALLOWED_TABLE = "v_chat_results";

    private static final Set<String> BLOCKED_FUNCTIONS = Set.of(
            "pg_read_file",
            "pg_read_binary_file",
            "pg_ls_dir",
            "pg_sleep",
            "lo_import",
            "lo_export",
            "dblink",
            "dblink_exec");

    public String validate(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new AppException(400, "Generated SQL is empty");
        }
        String sql = stripMarkdownFence(rawSql.trim());
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.contains(";")) {
            throw new AppException(400, "Only a single SQL statement is allowed");
        }
        String padded = " " + sql.toLowerCase(Locale.ROOT) + " ";
        for (String banned : new String[] {
            " insert ", " update ", " delete ", " drop ", " alter ", " truncate ",
            " create ", " grant ", " revoke ", " copy ", " call ", " execute ",
            " into outfile", " into dumpfile"
        }) {
            if (padded.contains(banned)) {
                throw new AppException(400, "Only read-only SELECT queries are allowed");
            }
        }
        for (String fn : BLOCKED_FUNCTIONS) {
            if (padded.contains(fn + "(") || padded.contains(fn + " (")) {
                throw new AppException(400, "Function not allowed: " + fn);
            }
        }

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException ex) {
            throw new AppException(400, "Invalid SQL: " + ex.getMessage());
        }
        if (!(statement instanceof Select select)) {
            throw new AppException(400, "Only SELECT statements are allowed");
        }
        if (!(select.getSelectBody() instanceof PlainSelect)) {
            throw new AppException(400, "Only simple SELECT statements are allowed");
        }

        @SuppressWarnings("unchecked")
        Set<String> tables = (Set<String>) (Set<?>) new TablesNamesFinder().getTables(statement);
        if (tables == null || tables.isEmpty()) {
            throw new AppException(400, "SQL must query " + ALLOWED_TABLE);
        }
        for (String table : tables) {
            String normalized = table.replace("\"", "").toLowerCase(Locale.ROOT);
            int dot = normalized.lastIndexOf('.');
            if (dot >= 0) {
                normalized = normalized.substring(dot + 1);
            }
            if (!ALLOWED_TABLE.equals(normalized)) {
                throw new AppException(400, "SQL may only reference " + ALLOWED_TABLE);
            }
        }
        return sql;
    }

    static String stripMarkdownFence(String sql) {
        String s = sql.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) {
                s = s.substring(firstNl + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
        }
        return s.trim();
    }
}
