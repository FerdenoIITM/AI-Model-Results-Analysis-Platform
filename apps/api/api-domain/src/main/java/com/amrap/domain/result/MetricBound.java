package com.amrap.domain.result;

/** One comparison against a percentage metric. Value is percentage points in [0, 100]. */
public record MetricBound(CompareOp op, double value) {
}
