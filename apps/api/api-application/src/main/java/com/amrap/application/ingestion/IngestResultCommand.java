package com.amrap.application.ingestion;

/** Payload fields the ingest endpoint accepts. Metric fields are optional percentage points. */
public record IngestResultCommand(
        String modelName,
        String version,
        String rawJson,
        String category,
        String subCategory,
        Double confidenceAccuracy,
        Double sensitivity,
        Double specificity,
        Double f1Score,
        Double recall,
        Double precision) {
}
