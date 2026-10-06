package com.amrap.web.results;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateResultRequest(
        @NotBlank @Size(max = 255) String modelName,
        @NotBlank @Size(max = 100) String version,
        String category,
        String subCategory,
        Double confidenceAccuracy,
        Double sensitivity,
        Double specificity,
        Double f1Score,
        Double recall,
        Double precision,
        @NotNull JsonNode payload) {
}
