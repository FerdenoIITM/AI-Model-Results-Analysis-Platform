package com.amrap.application.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Natural-language query input.
 *
 * <p>Optional multi-select filters narrow SQL and RAG to matching {@code results} rows.
 *
 * @param question user text
 * @param modelNames optional exact-match filters on {@code model_name}
 * @param categories optional exact-match filters on {@code category}
 * @param subCategories optional exact-match filters on {@code sub_category}
 * @param history optional earlier turns, oldest first, not including {@code question}
 */
public record ChatRequest(
        String question,
        List<String> modelNames,
        List<String> categories,
        List<String> subCategories,
        List<ChatTurn> history) {

    public ChatRequest {
        modelNames = clean(modelNames);
        categories = clean(categories);
        subCategories = clean(subCategories);
    }

    public boolean hasUiFilters() {
        return !modelNames.isEmpty() || !categories.isEmpty() || !subCategories.isEmpty();
    }

    private static List<String> clean(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty() && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }
}
