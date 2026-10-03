package com.amrap.domain.result;

import java.time.Instant;
import java.util.UUID;

/** Metadata row for a single AI model result submission. Raw JSON lives on disk. */
public class Result {

    private UUID id;
    private String modelName;
    private String version;
    private String iterationId;
    private String jsonPath;
    private long sizeBytes;
    private String contentHash;
    private Instant insertedAt;
    private boolean vectorized;
    private Instant vectorizedAt;
    private UUID createdBy;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getIterationId() {
        return iterationId;
    }

    public void setIterationId(String iterationId) {
        this.iterationId = iterationId;
    }

    public String getJsonPath() {
        return jsonPath;
    }

    public void setJsonPath(String jsonPath) {
        this.jsonPath = jsonPath;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Instant getInsertedAt() {
        return insertedAt;
    }

    public void setInsertedAt(Instant insertedAt) {
        this.insertedAt = insertedAt;
    }

    public boolean isVectorized() {
        return vectorized;
    }

    public void setVectorized(boolean vectorized) {
        this.vectorized = vectorized;
    }

    public Instant getVectorizedAt() {
        return vectorizedAt;
    }

    public void setVectorizedAt(Instant vectorizedAt) {
        this.vectorizedAt = vectorizedAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }
}
