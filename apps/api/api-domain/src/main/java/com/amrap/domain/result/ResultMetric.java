package com.amrap.domain.result;

/** Whitelisted numeric columns on a result. Names match the JSON properties. */
public enum ResultMetric {
    CONFIDENCE_ACCURACY("confidenceAccuracy"),
    SENSITIVITY("sensitivity"),
    SPECIFICITY("specificity"),
    F1_SCORE("f1Score"),
    RECALL("recall"),
    PRECISION("precision");

    private final String property;

    ResultMetric(String property) {
        this.property = property;
    }

    public String property() {
        return property;
    }

    public static ResultMetric fromProperty(String property) {
        for (ResultMetric metric : values()) {
            if (metric.property.equals(property)) {
                return metric;
            }
        }
        return null;
    }
}
