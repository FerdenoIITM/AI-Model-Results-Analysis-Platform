package com.amrap.application.query;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfig {

    /**
     * Read-only pool for chat SQL. Wrapped in {@link ChatJdbc} so Spring Boot still
     * auto-configures the primary app {@link JdbcTemplate} / datasource.
     * Uses Boot's ObjectMapper (with JavaTimeModule) — do not register a bare ObjectMapper here.
     */
    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${amrap.chat.datasource.password:}')")
    ChatJdbc chatJdbc(ChatProperties properties) {
        ChatProperties.Datasource ds = properties.getDatasource();
        HikariDataSource hikari = new HikariDataSource();
        hikari.setPoolName("amrap-chat-ro");
        hikari.setJdbcUrl(ds.getUrl());
        hikari.setUsername(ds.getUsername());
        hikari.setPassword(ds.getPassword());
        hikari.setMaximumPoolSize(3);
        hikari.setReadOnly(true);
        hikari.setConnectionInitSql(
                "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY; SET statement_timeout = '"
                        + Math.max(1, properties.getSqlTimeoutMs())
                        + "ms'");
        return new ChatJdbc(new JdbcTemplate(hikari));
    }
}
