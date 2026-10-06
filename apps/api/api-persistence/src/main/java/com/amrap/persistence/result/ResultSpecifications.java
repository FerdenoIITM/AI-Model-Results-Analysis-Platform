package com.amrap.persistence.result;

import com.amrap.domain.result.MetricBound;
import com.amrap.domain.result.ResultFilter;
import com.amrap.domain.result.ResultMetric;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.jpa.domain.Specification;

/** Predicates built only from {@link ResultFilter}. Sort and offset are applied by the caller. */
public final class ResultSpecifications {

    private ResultSpecifications() {
    }

    public static Specification<ResultEntity> from(ResultFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (!filter.categories().isEmpty()) {
                predicates.add(root.get("category").in(filter.categories()));
            }
            if (!filter.subCategories().isEmpty()) {
                predicates.add(root.get("subCategory").in(filter.subCategories()));
            }
            if (filter.modelNameContains() != null) {
                predicates.add(containsIgnoreCase(cb, root.get("modelName"), filter.modelNameContains()));
            }
            if (filter.versionContains() != null) {
                predicates.add(containsIgnoreCase(cb, root.get("version"), filter.versionContains()));
            }
            if (filter.vectorized() != null) {
                predicates.add(cb.equal(root.get("vectorized"), filter.vectorized()));
            }
            for (Map.Entry<ResultMetric, List<MetricBound>> entry : filter.metrics().entrySet()) {
                Path<Double> path = root.get(entry.getKey().property());
                for (MetricBound bound : entry.getValue()) {
                    predicates.add(compare(cb, path, bound));
                }
            }
            if (predicates.isEmpty()) {
                return cb.conjunction();
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Predicate compare(CriteriaBuilder cb, Path<Double> path, MetricBound bound) {
        return switch (bound.op()) {
            case EQ -> cb.equal(path, bound.value());
            case GT -> cb.greaterThan(path, bound.value());
            case GTE -> cb.greaterThanOrEqualTo(path, bound.value());
            case LT -> cb.lessThan(path, bound.value());
            case LTE -> cb.lessThanOrEqualTo(path, bound.value());
        };
    }

    private static Predicate containsIgnoreCase(CriteriaBuilder cb, Path<String> path, String raw) {
        String escaped = raw.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return cb.like(cb.lower(path), "%" + escaped + "%", '\\');
    }
}
