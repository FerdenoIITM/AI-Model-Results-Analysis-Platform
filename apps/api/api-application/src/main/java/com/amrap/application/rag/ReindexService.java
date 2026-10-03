package com.amrap.application.rag;

import com.amrap.persistence.result.ResultRepository;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Superadmin-only reset: clears the vector store table and flips every result back to
 * {@code is_vectorized=false} so the worker re-embeds them.
 */
@Service
public class ReindexService {

    private static final Logger log = LoggerFactory.getLogger(ReindexService.class);

    private final ResultRepository resultRepository;
    private final JdbcTemplate jdbcTemplate;

    public ReindexService(ResultRepository resultRepository, DataSource dataSource) {
        this.resultRepository = resultRepository;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Transactional
    public int resetAll() {
        jdbcTemplate.execute("TRUNCATE TABLE result_chunks");
        int updated = resultRepository.resetAllVectorization();
        log.info("Reindex requested: cleared result_chunks and reset {} result rows", updated);
        return updated;
    }
}
