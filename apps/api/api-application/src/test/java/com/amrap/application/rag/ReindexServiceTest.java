package com.amrap.application.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amrap.persistence.result.ResultRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class ReindexServiceTest {

    private ResultRepository repository;
    private Statement statement;
    private ReindexService service;

    @BeforeEach
    void setUp() throws SQLException {
        repository = mock(ResultRepository.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        service = new ReindexService(repository, dataSource);
    }

    @Test
    void truncatesChunksThenResetsRows() throws SQLException {
        when(repository.resetAllVectorization()).thenReturn(7);

        assertThat(service.resetAll()).isEqualTo(7);
        verify(statement).execute("TRUNCATE TABLE result_chunks");
    }

    @Test
    void zeroRowsIsFine() {
        when(repository.resetAllVectorization()).thenReturn(0);

        assertThat(service.resetAll()).isZero();
    }

    @Test
    void truncateFailureStopsBeforeResettingRows() throws SQLException {
        when(statement.execute("TRUNCATE TABLE result_chunks")).thenThrow(new SQLException("relation does not exist"));

        assertThatThrownBy(service::resetAll).isInstanceOf(DataAccessException.class);
        verify(repository, never()).resetAllVectorization();
    }
}
