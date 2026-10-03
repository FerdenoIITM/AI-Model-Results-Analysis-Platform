package com.amrap.web.llm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequestBody(
        @NotBlank @Size(max = 2000) String question,
        @Size(max = 255) String modelName,
        @Size(max = 100) String version,
        @Size(max = 255) String iterationId,
        String fromIso,
        String toIso) {
}
