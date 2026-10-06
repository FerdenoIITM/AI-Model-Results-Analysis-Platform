package com.amrap.application.ingestion;

import com.amrap.application.common.AppException;

/** Shared checks for result labels and percentage metrics (0 to 100). */
public final class ResultConstraints {

    public static final int MAX_LABEL_LENGTH = 120;

    private ResultConstraints() {
    }

    /** Null stays null. Blank or over-long values are rejected. Stored value is trimmed. */
    public static String optionalLabel(String field, String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new AppException(400, field + " must not be blank");
        }
        if (trimmed.length() > MAX_LABEL_LENGTH) {
            throw new AppException(400, field + " must be at most " + MAX_LABEL_LENGTH + " characters");
        }
        return trimmed;
    }

    public static void optionalPercentage(String field, Double value) {
        if (value == null) {
            return;
        }
        if (!Double.isFinite(value) || value < 0 || value > 100) {
            throw new AppException(400, field + " must be between 0 and 100");
        }
    }

    public static double requirePercentageString(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AppException(400, field + " requires a value");
        }
        final double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            throw new AppException(400, field + " must be a number");
        }
        optionalPercentage(field, value);
        return value;
    }
}
