package com.amrap.application.query;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

/** Thin wrapper so the chat pool is not registered as a {@link JdbcTemplate} bean. */
public final class ChatJdbc {

    private final JdbcTemplate jdbc;

    public ChatJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
        return jdbc.query(sql, rowMapper);
    }
}
