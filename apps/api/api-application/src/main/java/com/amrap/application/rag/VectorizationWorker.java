package com.amrap.application.rag;

import com.amrap.application.ingestion.ResultQueryService;
import com.amrap.domain.result.Result;
import com.amrap.persistence.result.ResultRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls {@code results.is_vectorized=false} rows, chunks each JSON file, calls the embedding
 * model via Spring AI, and writes chunks into the pgvector-backed {@code result_chunks} table.
 * Marks the row {@code is_vectorized=true} on success.
 *
 * <p>Only runs when a {@link VectorStore} bean is present (i.e. when Ollama is reachable at
 * startup). Otherwise the worker stays dormant so the rest of the app still boots.
 */
@Component
public class VectorizationWorker {

    private static final Logger log = LoggerFactory.getLogger(VectorizationWorker.class);

    private final ResultRepository resultRepository;
    private final ResultQueryService resultQueryService;
    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final VectorStore vectorStore;

    public VectorizationWorker(
            ResultRepository resultRepository,
            ResultQueryService resultQueryService,
            RagProperties properties,
            ObjectMapper objectMapper,
            @Autowired(required = false) VectorStore vectorStore) {
        this.resultRepository = resultRepository;
        this.resultQueryService = resultQueryService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.vectorStore = vectorStore;
    }

    @Scheduled(
            fixedDelayString = "${amrap.rag.worker-delay-ms:10000}",
            initialDelayString = "${amrap.rag.worker-delay-ms:10000}")
    public void tick() {
        if (vectorStore == null) {
            return;
        }
        List<Result> batch = resultRepository.findUnvectorizedBatch(properties.getWorkerBatchSize());
        if (batch.isEmpty()) {
            return;
        }
        log.info("Vectorizing {} result(s)", batch.size());
        for (Result r : batch) {
            try {
                vectorizeOne(r);
            } catch (Exception ex) {
                log.error("Vectorization failed for result {}: {}", r.getId(), ex.getMessage(), ex);
                // Leave is_vectorized=false; next tick will retry.
            }
        }
    }

    private void vectorizeOne(Result r) throws Exception {
        String rawJson = resultQueryService.readPayload(r);
        JsonNode node = objectMapper.readTree(rawJson);
        String flat = JsonFlattener.flatten(node);
        List<String> chunks = TextChunker.chunk(flat, properties.getChunkSize(), properties.getChunkOverlap());
        if (chunks.isEmpty()) {
            log.warn("Result {} produced zero chunks; marking vectorized to avoid retry loop", r.getId());
            resultRepository.markVectorized(r.getId(), Instant.now());
            return;
        }

        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("result_id", r.getId().toString());
            meta.put("chunk_index", i);
            meta.put("model_name", r.getModelName());
            meta.put("version", r.getVersion());
            meta.put("iteration_id", r.getIterationId());
            meta.put("category", r.getCategory());
            meta.put("sub_category", r.getSubCategory());
            meta.put("inserted_at", r.getInsertedAt() != null ? r.getInsertedAt().toString() : null);
            docs.add(new Document(chunks.get(i), meta));
        }

        vectorStore.add(docs);
        resultRepository.markVectorized(r.getId(), Instant.now());
        log.info("Vectorized result {} ({} chunks)", r.getId(), docs.size());
    }
}
