package com.amrap.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amrap.application.common.AppException;
import org.junit.jupiter.api.Test;

class SqlValidatorTest {

    private final SqlValidator validator = new SqlValidator();

    @Test
    void acceptsSimpleSelectOnView() {
        String sql = validator.validate("SELECT id, model_name FROM v_chat_results WHERE f1_score > 90 LIMIT 10");
        assertThat(sql).containsIgnoringCase("v_chat_results");
    }

    @Test
    void stripsMarkdownFence() {
        String sql = validator.validate("```sql\nSELECT id FROM v_chat_results\n```");
        assertThat(sql).isEqualToIgnoringCase("SELECT id FROM v_chat_results");
    }

    @Test
    void rejectsDelete() {
        assertThatThrownBy(() -> validator.validate("DELETE FROM v_chat_results"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void rejectsOtherTables() {
        assertThatThrownBy(() -> validator.validate("SELECT * FROM users"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("v_chat_results");
    }

    @Test
    void rejectsMultiStatement() {
        assertThatThrownBy(() -> validator.validate("SELECT id FROM v_chat_results; DROP TABLE users"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("single");
    }

    @Test
    void rejectsBlockedFunction() {
        assertThatThrownBy(() -> validator.validate("SELECT pg_sleep(10) FROM v_chat_results"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("pg_sleep");
    }
}
