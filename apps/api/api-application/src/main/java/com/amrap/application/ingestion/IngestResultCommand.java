package com.amrap.application.ingestion;

/** Payload fields the ingest endpoint accepts. */
public record IngestResultCommand(
        String modelName,
        String version,
        String iterationId,
        String rawJson) {
}
