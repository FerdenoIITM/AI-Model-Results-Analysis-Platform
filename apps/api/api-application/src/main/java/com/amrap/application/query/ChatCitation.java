package com.amrap.application.query;

import java.util.UUID;

public record ChatCitation(
        UUID resultId,
        String modelName,
        String version,
        String iterationId,
        String insertedAt,
        Integer chunkIndex,
        Double score) {
}
