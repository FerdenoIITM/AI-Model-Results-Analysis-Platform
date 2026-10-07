package com.amrap.application.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amrap.application.common.AppException;
import com.amrap.domain.result.CompareOp;
import com.amrap.domain.result.MetricBound;
import com.amrap.domain.result.ResultFilter;
import com.amrap.domain.result.ResultMetric;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResultFilterParserTest {

    @Test
    void defaultsWhenNoParameters() {
        ResultFilter filter = ResultFilterParser.parseList(Map.of());

        assertThat(filter.page()).isEqualTo(1);
        assertThat(filter.pageSize()).isEqualTo(50);
        assertThat(filter.sortProperty()).isEqualTo("insertedAt");
        assertThat(filter.sortDescending()).isTrue();
        assertThat(filter.categories()).isEmpty();
        assertThat(filter.subCategories()).isEmpty();
        assertThat(filter.metrics()).isEmpty();
        assertThat(filter.modelNames()).isEmpty();
        assertThat(filter.vectorized()).isNull();
    }

    @Test
    void parsesCombinedFiltersAndSort() {
        Map<String, String[]> params = new LinkedHashMap<>();
        params.put("category", new String[] {" Cardiology ", "Neurology"});
        params.put("subCategory", new String[] {"Aortic Valve"});
        params.put("model", new String[] {" CardioNet ", "NeuroSlice", "CardioNet"});
        params.put("vectorized", new String[] {"true"});
        params.put("confidenceAccuracyGte", new String[] {"95"});
        params.put("sensitivityGt", new String[] {"90"});
        params.put("specificityGt", new String[] {"95"});
        params.put("f1ScoreGte", new String[] {"90"});
        params.put("f1ScoreLte", new String[] {"98"});
        params.put("sort", new String[] {"-confidenceAccuracy"});
        params.put("page", new String[] {"2"});
        params.put("pageSize", new String[] {"25"});

        ResultFilter filter = ResultFilterParser.parseList(params);

        assertThat(filter.categories()).containsExactly("Cardiology", "Neurology");
        assertThat(filter.subCategories()).containsExactly("Aortic Valve");
        assertThat(filter.modelNames()).containsExactly("CardioNet", "NeuroSlice");
        assertThat(filter.vectorized()).isTrue();
        assertThat(filter.metrics().get(ResultMetric.CONFIDENCE_ACCURACY))
                .containsExactly(new MetricBound(CompareOp.GTE, 95));
        assertThat(filter.metrics().get(ResultMetric.SENSITIVITY))
                .containsExactly(new MetricBound(CompareOp.GT, 90));
        assertThat(filter.metrics().get(ResultMetric.SPECIFICITY))
                .containsExactly(new MetricBound(CompareOp.GT, 95));
        assertThat(filter.metrics().get(ResultMetric.F1_SCORE))
                .containsExactly(new MetricBound(CompareOp.GTE, 90), new MetricBound(CompareOp.LTE, 98));
        assertThat(filter.sortProperty()).isEqualTo("confidenceAccuracy");
        assertThat(filter.sortDescending()).isTrue();
        assertThat(filter.page()).isEqualTo(2);
        assertThat(filter.pageSize()).isEqualTo(25);
    }

    @Test
    void acceptsEveryMetricEqualityFilter() {
        for (ResultMetric metric : ResultMetric.values()) {
            ResultFilter filter = ResultFilterParser.parseList(
                    Map.of(metric.property() + "Eq", new String[] {"50"}));
            assertThat(filter.metrics().get(metric)).containsExactly(new MetricBound(CompareOp.EQ, 50));
        }
    }

    @Test
    void acceptsInclusiveRangeWithEqualBounds() {
        ResultFilter filter = ResultFilterParser.parseList(Map.of(
                "recallGte", new String[] {"90"},
                "recallLte", new String[] {"90"}));

        assertThat(filter.metrics().get(ResultMetric.RECALL)).hasSize(2);
    }

    @Test
    void rejectsUnknownField() {
        assertBad(Map.of("limit", new String[] {"10"}), "Unsupported filter field: limit");
        assertBad(Map.of("confidenceAccuracyGe", new String[] {"1"}), "Unsupported filter field");
        assertBad(Map.of("version", new String[] {"1.0"}), "Unsupported filter field: version");
        assertBad(Map.of("modelName", new String[] {"M1"}), "Unsupported filter field: modelName");
    }

    @Test
    void rejectsUnsupportedSort() {
        assertBad(Map.of("sort", new String[] {"dropTable"}), "Unsupported sort field");
        assertBad(Map.of("sort", new String[] {"-nope"}), "Unsupported sort field");
    }

    @Test
    void rejectsInvalidRangesAndOperators() {
        assertBad(Map.of(
                "f1ScoreGte", new String[] {"98"},
                "f1ScoreLte", new String[] {"90"}), "f1Score range is invalid");
        assertBad(Map.of(
                "f1ScoreGt", new String[] {"90"},
                "f1ScoreLt", new String[] {"90"}), "f1Score range is invalid");
        assertBad(Map.of(
                "precisionEq", new String[] {"90"},
                "precisionGte", new String[] {"80"}), "cannot combine = with another operator");
        assertBad(Map.of(
                "precisionGt", new String[] {"1"},
                "precisionGte", new String[] {"2"}), "cannot combine");
    }

    @Test
    void rejectsMetricValuesOutsideZeroToOneHundred() {
        assertBad(Map.of("sensitivityGte", new String[] {"101"}), "must be between 0 and 100");
        assertBad(Map.of("sensitivityGte", new String[] {"-0.1"}), "must be between 0 and 100");
        assertBad(Map.of("sensitivityGte", new String[] {"abc"}), "must be a number");
        assertBad(Map.of("sensitivityGte", new String[] {"NaN"}), "must be between 0 and 100");
        assertBad(Map.of("sensitivityGte", new String[] {" "}), "requires a value");
    }

    @Test
    void rejectsBlankOrLongLabels() {
        assertBad(Map.of("category", new String[] {" "}), "category must not be blank");
        assertBad(Map.of("subCategory", new String[] {"x".repeat(121)}), "at most 120");
    }

    @Test
    void rejectsPagingAndVectorizedMistakes() {
        assertBad(Map.of("page", new String[] {"0"}), "page must be an integer from 1 to 1000000");
        assertBad(Map.of("pageSize", new String[] {"101"}), "pageSize must be an integer from 1 to 100");
        assertBad(Map.of("pageSize", new String[] {"0"}), "pageSize must be an integer from 1 to 100");
        assertBad(Map.of("page", new String[] {"1.5"}), "page must be an integer");
        assertBad(Map.of("vectorized", new String[] {"yes"}), "vectorized must be true or false");
        assertBad(Map.of("model", new String[] {" "}), "model must not be blank");
        assertBad(Map.of("model", new String[] {"x".repeat(256)}), "model must be at most 255 characters");
    }

    @Test
    void parsesAscendingSortAndFalseVectorized() {
        ResultFilter filter = ResultFilterParser.parseList(Map.of(
                "sort", new String[] {"modelName"},
                "vectorized", new String[] {"false"}));

        assertThat(filter.sortProperty()).isEqualTo("modelName");
        assertThat(filter.sortDescending()).isFalse();
        assertThat(filter.vectorized()).isFalse();
    }

    @Test
    void filterOptionsAllowOnlyCategory() {
        assertThat(ResultFilterParser.parseCategories(Map.of("category", new String[] {"Cardiology", "Cardiology"})))
                .containsExactly("Cardiology");
        assertThatThrownBy(() -> ResultFilterParser.parseCategories(Map.of("page", new String[] {"1"})))
                .isInstanceOfSatisfying(AppException.class, ex -> assertThat(ex.getStatus()).isEqualTo(400));
    }

    private static void assertBad(Map<String, String[]> params, String messagePart) {
        assertThatThrownBy(() -> ResultFilterParser.parseList(params))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains(messagePart);
                });
    }
}
