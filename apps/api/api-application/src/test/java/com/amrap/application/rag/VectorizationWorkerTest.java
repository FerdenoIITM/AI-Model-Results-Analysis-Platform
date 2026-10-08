package com.amrap.application.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.amrap.application.ingestion.ResultQueryService;
import com.amrap.domain.result.Result;
import com.amrap.persistence.result.ResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

class VectorizationWorkerTest {

    private ResultRepository repository;
    private ResultQueryService queryService;
    private VectorStore vectorStore;
    private RagProperties properties;
    private VectorizationWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(ResultRepository.class);
        queryService = mock(ResultQueryService.class);
        vectorStore = mock(VectorStore.class);
        properties = new RagProperties();
        properties.setWorkerBatchSize(3);
        worker = new VectorizationWorker(repository, queryService, properties, new ObjectMapper(), vectorStore);
    }

    @Test
    void doesNothingWithoutVectorStore() {
        new VectorizationWorker(repository, queryService, properties, new ObjectMapper(), null).tick();

        verifyNoInteractions(repository);
    }

    @Test
    void doesNothingWhenBatchEmpty() {
        when(repository.findUnvectorizedBatch(3)).thenReturn(List.of());

        worker.tick();

        verifyNoInteractions(vectorStore);
    }

    @Test
    @SuppressWarnings("unchecked")
    void embedsChunksWithMetadataAndMarksVectorized() {
        Result r = result();
        when(repository.findUnvectorizedBatch(3)).thenReturn(List.of(r));
        when(queryService.readPayload(r)).thenReturn("{\"metrics\":{\"recall\":91.5},\"tags\":[\"a\",\"b\"]}");

        worker.tick();

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        Document doc = captor.getValue().get(0);
        assertThat(doc.getText()).contains("metrics.recall: 91.5").contains("tags[1]: b");
        assertThat(doc.getMetadata())
                .containsEntry("result_id", r.getId().toString())
                .containsEntry("model_name", "CardioNet")
                .containsEntry("version", "1.0")
                .containsEntry("chunk_index", 0);
        verify(repository).markVectorized(eq(r.getId()), any(Instant.class));
    }

    @Test
    void oneFailureDoesNotStopTheBatchAndLeavesRowForRetry() {
        Result bad = result();
        Result good = result();
        when(repository.findUnvectorizedBatch(3)).thenReturn(List.of(bad, good));
        when(queryService.readPayload(bad)).thenReturn("{not json");
        when(queryService.readPayload(good)).thenReturn("{\"a\":1}");

        worker.tick();

        verify(repository, never()).markVectorized(eq(bad.getId()), any());
        verify(repository).markVectorized(eq(good.getId()), any());
    }

    @Test
    void embeddingFailureLeavesRowUnvectorized() {
        Result r = result();
        when(repository.findUnvectorizedBatch(anyInt())).thenReturn(List.of(r));
        when(queryService.readPayload(r)).thenReturn("{\"a\":1}");
        doThrow(new RuntimeException("ollama unreachable")).when(vectorStore).add(anyList());

        worker.tick();

        verify(repository, never()).markVectorized(any(), any());
    }

    private static Result result() {
        Result r = new Result();
        r.setId(UUID.randomUUID());
        r.setModelName("CardioNet");
        r.setVersion("1.0");
        r.setIterationId("20260101000000");
        r.setInsertedAt(Instant.parse("2026-01-01T00:00:00Z"));
        r.setJsonPath(r.getId() + ".json");
        return r;
    }
}
