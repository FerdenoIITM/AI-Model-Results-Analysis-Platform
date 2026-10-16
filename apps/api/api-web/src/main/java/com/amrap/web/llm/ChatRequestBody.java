package com.amrap.web.llm;

import com.amrap.application.query.ChatTurn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ChatRequestBody(
        @NotBlank @Size(max = 2000) String question,
        List<@Size(max = 255) String> modelNames,
        List<@Size(max = 120) String> categories,
        List<@Size(max = 120) String> subCategories,
        @Valid List<ChatTurn> history) {
}
