package com.amrap.domain.result;

import java.util.List;
import java.util.Map;

/**
 * Server-side list query. Page is 1-based. Absent list filters are empty.
 * Metric bounds are AND-ed. The repository applies this before sort and offset.
 */
public record ResultFilter(
        List<String> categories,
        List<String> subCategories,
        List<String> modelNames,
        Boolean vectorized,
        Map<ResultMetric, List<MetricBound>> metrics,
        String sortProperty,
        boolean sortDescending,
        int page,
        int pageSize) {

    public ResultFilter {
        categories = List.copyOf(categories);
        subCategories = List.copyOf(subCategories);
        modelNames = List.copyOf(modelNames);
        metrics = Map.copyOf(metrics);
        if (sortProperty == null || sortProperty.isBlank()) {
            throw new IllegalArgumentException("sortProperty is required");
        }
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("page is out of range");
        }
    }
}
