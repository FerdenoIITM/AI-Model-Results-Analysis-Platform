package com.amrap.application.rag;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for result ingestion + vectorization + RAG chat. */
@ConfigurationProperties(prefix = "amrap.rag")
public class RagProperties {

    /** Directory on disk where ingested result JSON files are stored. */
    private String storageDir = "./data/results";

    /** Shared secret required on the POST /api/results header `X-Ingest-Key`. Set via INGEST_API_KEY. */
    private String ingestApiKey;

    /** Milliseconds between vectorization worker polls. */
    private long workerDelayMs = 10_000L;

    /** Max unvectorized results processed per worker tick. */
    private int workerBatchSize = 5;

    /** Approximate character length of each chunk fed to the embedder. */
    private int chunkSize = 800;

    /** Character overlap between adjacent chunks. */
    private int chunkOverlap = 100;

    /** Top-K chunks fetched from the vector store per chat question. */
    private int topK = 6;

    /** IANA time zone for "today"/"yesterday" and iteration IDs; blank means the JVM default. */
    private String timezone;

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public ZoneId zoneId() {
        return timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone.trim());
    }

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public String getIngestApiKey() {
        return ingestApiKey;
    }

    public void setIngestApiKey(String ingestApiKey) {
        this.ingestApiKey = ingestApiKey;
    }

    public long getWorkerDelayMs() {
        return workerDelayMs;
    }

    public void setWorkerDelayMs(long workerDelayMs) {
        this.workerDelayMs = workerDelayMs;
    }

    public int getWorkerBatchSize() {
        return workerBatchSize;
    }

    public void setWorkerBatchSize(int workerBatchSize) {
        this.workerBatchSize = workerBatchSize;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }
}
