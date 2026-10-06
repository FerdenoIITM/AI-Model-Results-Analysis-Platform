package com.amrap.domain.result;

import java.time.Instant;
import java.util.UUID;

/** Metadata row for a single AI model result submission. Raw JSON lives on disk. */
public class Result {

    private UUID id;
    private String modelName;
    private String version;
    private String iterationId;
    private String category;
    private String subCategory;
    private Double confidenceAccuracy;
    private Double sensitivity;
    private Double specificity;
    private Double f1Score;
    private Double recall;
    private Double precision;
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSubCategory() {
        return subCategory;
    }

    public void setSubCategory(String subCategory) {
        this.subCategory = subCategory;
    }

    public Double getConfidenceAccuracy() {
        return confidenceAccuracy;
    }

    public void setConfidenceAccuracy(Double confidenceAccuracy) {
        this.confidenceAccuracy = confidenceAccuracy;
    }

    public Double getSensitivity() {
        return sensitivity;
    }

    public void setSensitivity(Double sensitivity) {
        this.sensitivity = sensitivity;
    }

    public Double getSpecificity() {
        return specificity;
    }

    public void setSpecificity(Double specificity) {
        this.specificity = specificity;
    }

    public Double getF1Score() {
        return f1Score;
    }

    public void setF1Score(Double f1Score) {
        this.f1Score = f1Score;
    }

    public Double getRecall() {
        return recall;
    }

    public void setRecall(Double recall) {
        this.recall = recall;
    }

    public Double getPrecision() {
        return precision;
    }

    public void setPrecision(Double precision) {
        this.precision = precision;
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
