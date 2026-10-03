package com.amrap.web.results;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateResultRequest(
        @NotBlank @Size(max = 255) String modelName,
        @NotBlank @Size(max = 100) String version,
        @NotBlank @Size(max = 255) String iterationId,
        @NotNull JsonNode payload) {
}
