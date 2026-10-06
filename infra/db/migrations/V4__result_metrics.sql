-- V4__result_metrics.sql
-- Filterable category labels and percentage metrics on results.
-- Existing rows stay NULL. No backfill: older payloads do not use these keys.
-- Apply manually (Flyway is disabled), same as V1 and V3.

ALTER TABLE results
    ADD COLUMN category              VARCHAR(120) NULL,
    ADD COLUMN sub_category          VARCHAR(120) NULL,
    ADD COLUMN confidence_accuracy   DOUBLE PRECISION NULL,
    ADD COLUMN sensitivity           DOUBLE PRECISION NULL,
    ADD COLUMN specificity           DOUBLE PRECISION NULL,
    ADD COLUMN f1_score              DOUBLE PRECISION NULL,
    ADD COLUMN recall                DOUBLE PRECISION NULL,
    ADD COLUMN precision             DOUBLE PRECISION NULL;

ALTER TABLE results
    ADD CONSTRAINT ck_results_category_trimmed CHECK (
        category IS NULL
        OR (char_length(btrim(category)) > 0 AND category = btrim(category))
    ),
    ADD CONSTRAINT ck_results_sub_category_trimmed CHECK (
        sub_category IS NULL
        OR (char_length(btrim(sub_category)) > 0 AND sub_category = btrim(sub_category))
    ),
    ADD CONSTRAINT ck_results_confidence_accuracy CHECK (
        confidence_accuracy IS NULL
        OR (confidence_accuracy >= 0 AND confidence_accuracy <= 100)
    ),
    ADD CONSTRAINT ck_results_sensitivity CHECK (
        sensitivity IS NULL OR (sensitivity >= 0 AND sensitivity <= 100)
    ),
    ADD CONSTRAINT ck_results_specificity CHECK (
        specificity IS NULL OR (specificity >= 0 AND specificity <= 100)
    ),
    ADD CONSTRAINT ck_results_f1_score CHECK (
        f1_score IS NULL OR (f1_score >= 0 AND f1_score <= 100)
    ),
    ADD CONSTRAINT ck_results_recall CHECK (
        recall IS NULL OR (recall >= 0 AND recall <= 100)
    ),
    ADD CONSTRAINT ck_results_precision CHECK (
        precision IS NULL OR (precision >= 0 AND precision <= 100)
    );

-- Category equality, and category + subcategory. Leftmost prefix covers category alone.
CREATE INDEX idx_results_category_sub_category
    ON results (category, sub_category)
    WHERE category IS NOT NULL;

-- Subcategory without a category. The composite index cannot serve that lookup.
CREATE INDEX idx_results_sub_category
    ON results (sub_category)
    WHERE sub_category IS NOT NULL;

-- Each metric is an independent range predicate. Bitmap AND combines whichever subset is used.
CREATE INDEX idx_results_confidence_accuracy
    ON results (confidence_accuracy)
    WHERE confidence_accuracy IS NOT NULL;

CREATE INDEX idx_results_sensitivity
    ON results (sensitivity)
    WHERE sensitivity IS NOT NULL;

CREATE INDEX idx_results_specificity
    ON results (specificity)
    WHERE specificity IS NOT NULL;

CREATE INDEX idx_results_f1_score
    ON results (f1_score)
    WHERE f1_score IS NOT NULL;

CREATE INDEX idx_results_recall
    ON results (recall)
    WHERE recall IS NOT NULL;

CREATE INDEX idx_results_precision
    ON results (precision)
    WHERE precision IS NOT NULL;

COMMENT ON COLUMN results.category IS 'Submission category. NULL on rows ingested before V4.';
COMMENT ON COLUMN results.sub_category IS 'Submission subcategory. NULL on rows ingested before V4.';
COMMENT ON COLUMN results.confidence_accuracy IS 'Percentage points in [0, 100], not a 0-1 fraction.';
COMMENT ON COLUMN results.sensitivity IS 'Percentage points in [0, 100].';
COMMENT ON COLUMN results.specificity IS 'Percentage points in [0, 100].';
COMMENT ON COLUMN results.f1_score IS 'Percentage points in [0, 100].';
COMMENT ON COLUMN results.recall IS 'Percentage points in [0, 100].';
COMMENT ON COLUMN results.precision IS 'Percentage points in [0, 100].';
