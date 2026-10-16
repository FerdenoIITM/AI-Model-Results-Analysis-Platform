package com.amrap.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatSqlExecutorTest {

    @Test
    void ensureLimitAppendsWhenMissing() {
        assertThat(ChatSqlExecutor.ensureLimit("SELECT id FROM v_chat_results", 50))
                .endsWith("LIMIT 50");
    }

    @Test
    void ensureLimitCapsExisting() {
        assertThat(ChatSqlExecutor.ensureLimit("SELECT id FROM v_chat_results LIMIT 999", 200))
                .contains("LIMIT 200");
    }

    @Test
    void extractResultIdsFromIdColumn() {
        UUID id = UUID.randomUUID();
        assertThat(ChatSqlExecutor.extractResultIds(List.of(Map.of("id", id)))).containsExactly(id);
    }
}
