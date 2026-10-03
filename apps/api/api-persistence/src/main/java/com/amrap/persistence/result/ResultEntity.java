package com.amrap.persistence.result;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "results")
public class ResultEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "model_name", nullable = false, length = 255)
    private String modelName;

    @Column(name = "version", nullable = false, length = 100)
    private String version;

    @Column(name = "iteration_id", nullable = false, length = 255)
    private String iterationId;

    @Column(name = "json_path", nullable = false)
    private String jsonPath;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content_hash", nullable = false, length = 128)
    private String contentHash;

    @Column(name = "inserted_at", nullable = false)
    private Instant insertedAt;

    @Column(name = "is_vectorized", nullable = false)
    private boolean vectorized = false;

    @Column(name = "vectorized_at")
    private Instant vectorizedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (insertedAt == null) {
            insertedAt = Instant.now();
        }
    }

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
