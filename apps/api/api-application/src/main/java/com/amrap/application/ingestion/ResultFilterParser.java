package com.amrap.application.ingestion;

import com.amrap.application.common.AppException;
import com.amrap.domain.result.CompareOp;
import com.amrap.domain.result.MetricBound;
import com.amrap.domain.result.ResultFilter;
import com.amrap.domain.result.ResultMetric;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns an allowlisted query map into a {@link ResultFilter}. Unknown keys are rejected. */
public final class ResultFilterParser {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 100;
    public static final String DEFAULT_SORT = "insertedAt";
    private static final int MAX_MODEL_NAME_LENGTH = 255;

    private static final List<String> SUFFIX_ORDER = List.of("Eq", "Gt", "Gte", "Lt", "Lte");
    private static final Map<String, CompareOp> SUFFIXES = Map.of(
            "Eq", CompareOp.EQ,
            "Gt", CompareOp.GT,
            "Gte", CompareOp.GTE,
            "Lt", CompareOp.LT,
            "Lte", CompareOp.LTE);

    private static final Set<String> SORT_FIELDS = Set.of(
            "insertedAt",
            "modelName",
            "version",
            "iterationId",
            "category",
            "subCategory",
            "confidenceAccuracy",
            "sensitivity",
            "specificity",
            "f1Score",
            "recall",
            "precision");

    private static final Set<String> LIST_KEYS;

    static {
        Set<String> keys = new LinkedHashSet<>();
        keys.add("category");
        keys.add("subCategory");
        keys.add("model");
        keys.add("vectorized");
        keys.add("page");
        keys.add("pageSize");
        keys.add("sort");
        for (ResultMetric metric : ResultMetric.values()) {
            for (String suffix : SUFFIXES.keySet()) {
                keys.add(metric.property() + suffix);
            }
        }
        LIST_KEYS = Set.copyOf(keys);
    }

    private ResultFilterParser() {
    }

    public static ResultFilter parseList(Map<String, String[]> params) {
        rejectUnknown(params, LIST_KEYS);
        boolean sortDescending = true;
        String sortProperty = DEFAULT_SORT;
        if (params.containsKey("sort")) {
            String raw = single("sort", params.get("sort"));
            sortDescending = raw.startsWith("-");
            sortProperty = sortDescending ? raw.substring(1) : raw;
            if (!SORT_FIELDS.contains(sortProperty)) {
                throw new AppException(400, "Unsupported sort field");
            }
        }
        Map<ResultMetric, List<MetricBound>> metrics = new EnumMap<>(ResultMetric.class);
        for (ResultMetric metric : ResultMetric.values()) {
            List<MetricBound> bounds = readBounds(metric, params);
            if (!bounds.isEmpty()) {
                metrics.put(metric, List.copyOf(bounds));
            }
        }
        return new ResultFilter(
                labels("category", params.get("category")),
                labels("subCategory", params.get("subCategory")),
                modelNames(params.get("model")),
                vectorized(params.get("vectorized")),
                metrics,
                sortProperty,
                sortDescending,
                integer("page", params.get("page"), DEFAULT_PAGE, 1, 1_000_000),
                integer("pageSize", params.get("pageSize"), DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE));
    }

    /** Categories for the filter-options endpoint. Any other key is rejected. */
    public static List<String> parseCategories(Map<String, String[]> params) {
        rejectUnknown(params, Set.of("category"));
        return labels("category", params.get("category"));
    }

    private static void rejectUnknown(Map<String, String[]> params, Set<String> allowed) {
        for (String key : params.keySet()) {
            if (!allowed.contains(key)) {
                throw new AppException(400, "Unsupported filter field: " + key);
            }
        }
    }

    private static List<MetricBound> readBounds(ResultMetric metric, Map<String, String[]> params) {
        List<MetricBound> bounds = new ArrayList<>();
        MetricBound lower = null;
        MetricBound upper = null;
        MetricBound exact = null;
        for (String suffix : SUFFIX_ORDER) {
            String key = metric.property() + suffix;
            if (!params.containsKey(key)) {
                continue;
            }
            CompareOp op = SUFFIXES.get(suffix);
            double value = ResultConstraints.requirePercentageString(metric.property(), single(key, params.get(key)));
            MetricBound bound = new MetricBound(op, value);
            switch (bound.op()) {
                case EQ -> {
                    if (exact != null) {
                        throw new AppException(400, metric.property() + " must be specified once");
                    }
                    exact = bound;
                }
                case GT, GTE -> {
                    if (lower != null) {
                        throw new AppException(400, metric.property() + " cannot combine "
                                + symbol(lower.op()) + " with " + symbol(bound.op()));
                    }
                    lower = bound;
                }
                case LT, LTE -> {
                    if (upper != null) {
                        throw new AppException(400, metric.property() + " cannot combine "
                                + symbol(upper.op()) + " with " + symbol(bound.op()));
                    }
                    upper = bound;
                }
            }
            bounds.add(bound);
        }
        if (exact != null && bounds.size() > 1) {
            throw new AppException(400, metric.property() + " cannot combine = with another operator");
        }
        if (lower != null && upper != null) {
            boolean bothStrict = lower.op() == CompareOp.GT && upper.op() == CompareOp.LT;
            if (Double.compare(lower.value(), upper.value()) > 0
                    || (Double.compare(lower.value(), upper.value()) == 0 && bothStrict)) {
                throw new AppException(400, metric.property() + " range is invalid");
            }
        }
        return bounds;
    }

    private static List<String> labels(String field, String[] values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String label = ResultConstraints.optionalLabel(field, value == null ? "" : value);
            if (label != null) {
                unique.add(label);
            }
        }
        return List.copyOf(unique);
    }

    private static List<String> modelNames(String[] values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String trimmed = value == null ? "" : value.trim();
            if (trimmed.isEmpty()) {
                throw new AppException(400, "model must not be blank");
            }
            if (trimmed.length() > MAX_MODEL_NAME_LENGTH) {
                throw new AppException(400, "model must be at most " + MAX_MODEL_NAME_LENGTH + " characters");
            }
            unique.add(trimmed);
        }
        return List.copyOf(unique);
    }

    private static Boolean vectorized(String[] values) {
        if (values == null) {
            return null;
        }
        String raw = single("vectorized", values).trim();
        if (raw.equals("true")) {
            return Boolean.TRUE;
        }
        if (raw.equals("false")) {
            return Boolean.FALSE;
        }
        throw new AppException(400, "vectorized must be true or false");
    }

    private static int integer(String field, String[] values, int fallback, int min, int max) {
        if (values == null) {
            return fallback;
        }
        String raw = single(field, values).trim();
        final int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw new AppException(400, field + " must be an integer");
        }
        if (parsed < min || parsed > max) {
            throw new AppException(400, field + " must be an integer from " + min + " to " + max);
        }
        return parsed;
    }

    private static String single(String key, String[] values) {
        if (values == null || values.length != 1) {
            throw new AppException(400, key + " must be specified once");
        }
        if (values[0] == null || values[0].isBlank()) {
            throw new AppException(400, key + " requires a value");
        }
        return values[0];
    }

    private static String symbol(CompareOp op) {
        return switch (op) {
            case EQ -> "=";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
        };
    }
}
