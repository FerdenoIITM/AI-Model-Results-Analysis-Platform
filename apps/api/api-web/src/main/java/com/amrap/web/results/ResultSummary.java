package com.amrap.web.results;

import com.amrap.domain.result.Result;
import java.time.Instant;
import java.util.UUID;

public record ResultSummary(
        UUID id,
        String modelName,
        String version,
        String iterationId,
        String jsonPath,
        long sizeBytes,
        Instant insertedAt,
        boolean vectorized,
        Instant vectorizedAt,
        UUID createdBy) {

    public static ResultSummary from(Result r) {
        return new ResultSummary(
                r.getId(),
                r.getModelName(),
                r.getVersion(),
                r.getIterationId(),
                r.getJsonPath(),
                r.getSizeBytes(),
                r.getInsertedAt(),
                r.isVectorized(),
                r.getVectorizedAt(),
                r.getCreatedBy());
    }
}
