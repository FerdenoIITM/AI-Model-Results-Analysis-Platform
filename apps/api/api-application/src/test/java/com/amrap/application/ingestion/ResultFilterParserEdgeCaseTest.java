package com.amrap.application.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amrap.application.common.AppException;
import com.amrap.domain.result.CompareOp;
import com.amrap.domain.result.MetricBound;
import com.amrap.domain.result.ResultFilter;
import com.amrap.domain.result.ResultMetric;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ResultFilterParserEdgeCaseTest {

    static Stream<Arguments> everyMetricAndOperator() {
        Map<String, CompareOp> ops = Map.of(
                "Eq", CompareOp.EQ, "Gt", CompareOp.GT, "Gte", CompareOp.GTE, "Lt", CompareOp.LT, "Lte", CompareOp.LTE);
        return Stream.of(ResultMetric.values())
                .flatMap(metric -> ops.entrySet().stream().map(op -> Arguments.of(metric, op.getKey(), op.getValue())));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyMetricAndOperator")
    void acceptsAllThirtyMetricOperatorKeys(ResultMetric metric, String suffix, CompareOp op) {
        ResultFilter filter = ResultFilterParser.parseList(Map.of(metric.property() + suffix, new String[] {"42.5"}));

        assertThat(filter.metrics()).containsOnlyKeys(metric);
        assertThat(filter.metrics().get(metric)).containsExactly(new MetricBound(op, 42.5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "100", "0.0", "100.0", "99.9999", " 50 ", "1e1"})
    void acceptsBoundaryAndFormattedNumbers(String raw) {
        ResultFilter filter = ResultFilterParser.parseList(Map.of("recallGte", new String[] {raw}));

        assertThat(filter.metrics().get(ResultMetric.RECALL).get(0).value())
                .isBetween(0.0, 100.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.0001", "100.0001", "1e3", "Infinity", "-Infinity", "NaN"})
    void rejectsValuesOutsideRange(String raw) {
        assertBad(Map.of("precisionLte", new String[] {raw}), "precision must be between 0 and 100");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "12abc", "1,5", "true", "0x10", "--5"})
    void rejectsNonNumericValues(String raw) {
        assertBad(Map.of("f1ScoreEq", new String[] {raw}), "f1Score must be a number");
    }

    @Test
    void rejectsEmptyAndRepeatedMetricValues() {
        assertBad(Map.of("f1ScoreEq", new String[] {""}), "f1ScoreEq requires a value");
        assertBad(Map.of("f1ScoreEq", new String[] {}), "f1ScoreEq must be specified once");
        assertBad(Map.of("f1ScoreEq", new String[] {"1", "2"}), "f1ScoreEq must be specified once");
        assertBad(Map.of("f1ScoreEq", new String[] {null}), "f1ScoreEq requires a value");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ResultMetric.class)
    void rejectsInvalidRangesForEveryMetric(ResultMetric metric) {
        String p = metric.property();
        assertBad(Map.of(p + "Gte", new String[] {"60"}, p + "Lte", new String[] {"40"}), p + " range is invalid");
        assertBad(Map.of(p + "Gt", new String[] {"50"}, p + "Lt", new String[] {"50"}), p + " range is invalid");
        assertBad(Map.of(p + "Gt", new String[] {"50.01"}, p + "Lte", new String[] {"50"}), p + " range is invalid");
    }

    @Test
    void acceptsHalfOpenEqualRanges() {
        assertThat(ResultFilterParser.parseList(Map.of("recallGt", new String[] {"50"}, "recallLte", new String[] {"50"}))
                .metrics().get(ResultMetric.RECALL)).hasSize(2);
        assertThat(ResultFilterParser.parseList(Map.of("recallGte", new String[] {"0"}, "recallLte", new String[] {"100"}))
                .metrics().get(ResultMetric.RECALL)).hasSize(2);
    }

    @Test
    void rejectsConflictingOperatorPairs() {
        assertBad(Map.of("recallLt", new String[] {"5"}, "recallLte", new String[] {"6"}), "recall cannot combine < with <=");
        assertBad(Map.of("recallGt", new String[] {"5"}, "recallGte", new String[] {"6"}), "recall cannot combine > with >=");
        assertBad(Map.of("recallEq", new String[] {"5"}, "recallLt", new String[] {"6"}), "recall cannot combine = with another operator");
    }

    @Test
    void combinesFiltersAcrossAllMetricsWithAnd() {
        Map<String, String[]> params = new HashMap<>();
        for (ResultMetric metric : ResultMetric.values()) {
            params.put(metric.property() + "Gte", new String[] {"10"});
            params.put(metric.property() + "Lt", new String[] {"90"});
        }
        ResultFilter filter = ResultFilterParser.parseList(params);

        assertThat(filter.metrics()).hasSize(ResultMetric.values().length);
        filter.metrics().values().forEach(bounds -> assertThat(bounds)
                .containsExactly(new MetricBound(CompareOp.GTE, 10), new MetricBound(CompareOp.LT, 90)));
    }

    @Test
    void deduplicatesAndTrimsLabelsAndModels() {
        Map<String, String[]> params = new LinkedHashMap<>();
        params.put("category", new String[] {"Neuro", " Neuro ", "Cardio"});
        params.put("subCategory", new String[] {"A", "A"});
        params.put("model", new String[] {"M", " M", "M "});

        ResultFilter filter = ResultFilterParser.parseList(params);

        assertThat(filter.categories()).containsExactly("Neuro", "Cardio");
        assertThat(filter.subCategories()).containsExactly("A");
        assertThat(filter.modelNames()).containsExactly("M");
    }

    @Test
    void acceptsLabelsAtMaximumLength() {
        ResultFilter filter = ResultFilterParser.parseList(Map.of(
                "category", new String[] {"c".repeat(120)},
                "model", new String[] {"m".repeat(255)}));

        assertThat(filter.categories().get(0)).hasSize(120);
        assertThat(filter.modelNames().get(0)).hasSize(255);
    }

    @Test
    void rejectsNullOrEmptyLabelValues() {
        assertBad(Map.of("category", new String[] {null}), "category must not be blank");
        assertBad(Map.of("subCategory", new String[] {""}), "subCategory must not be blank");
        assertBad(Map.of("model", new String[] {null}), "model must not be blank");
        assertBad(Map.of("category", new String[] {"ok", " "}), "category must not be blank");
    }

    @Test
    void pagingBoundaries() {
        assertThat(ResultFilterParser.parseList(Map.of("page", new String[] {"1"})).page()).isEqualTo(1);
        assertThat(ResultFilterParser.parseList(Map.of("page", new String[] {"1000000"})).page()).isEqualTo(1_000_000);
        assertThat(ResultFilterParser.parseList(Map.of("pageSize", new String[] {"1"})).pageSize()).isEqualTo(1);
        assertThat(ResultFilterParser.parseList(Map.of("pageSize", new String[] {"100"})).pageSize()).isEqualTo(100);
        assertBad(Map.of("page", new String[] {"1000001"}), "page must be an integer from 1 to 1000000");
        assertBad(Map.of("page", new String[] {"-1"}), "page must be an integer from 1 to 1000000");
        assertBad(Map.of("page", new String[] {"99999999999"}), "page must be an integer");
        assertBad(Map.of("pageSize", new String[] {"ten"}), "pageSize must be an integer");
        assertBad(Map.of("pageSize", new String[] {"10", "20"}), "pageSize must be specified once");
    }

    @ParameterizedTest
    @ValueSource(strings = {"insertedAt", "modelName", "version", "iterationId", "category", "subCategory",
            "confidenceAccuracy", "sensitivity", "specificity", "f1Score", "recall", "precision"})
    void acceptsEveryWhitelistedSortInBothDirections(String field) {
        ResultFilter asc = ResultFilterParser.parseList(Map.of("sort", new String[] {field}));
        ResultFilter desc = ResultFilterParser.parseList(Map.of("sort", new String[] {"-" + field}));

        assertThat(asc.sortProperty()).isEqualTo(field);
        assertThat(asc.sortDescending()).isFalse();
        assertThat(desc.sortProperty()).isEqualTo(field);
        assertThat(desc.sortDescending()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"--insertedAt", "-", "contentHash", "jsonPath", "createdBy", "model_name", "MODELNAME",
            "insertedAt;drop table results"})
    void rejectsNonWhitelistedSorts(String sort) {
        assertBad(Map.of("sort", new String[] {sort}), "Unsupported sort field");
    }

    @Test
    void rejectsVectorizedVariants() {
        assertBad(Map.of("vectorized", new String[] {"TRUE"}), "vectorized must be true or false");
        assertBad(Map.of("vectorized", new String[] {"1"}), "vectorized must be true or false");
        assertBad(Map.of("vectorized", new String[] {"true", "false"}), "vectorized must be specified once");
        assertThat(ResultFilterParser.parseList(Map.of("vectorized", new String[] {" true "})).vectorized()).isTrue();
    }

    @Test
    void removedVersionAndModelNameParamsAreRejected() {
        assertBad(Map.of("version", new String[] {"1.0.0"}), "Unsupported filter field: version");
        assertBad(Map.of("modelName", new String[] {"CardioNet"}), "Unsupported filter field: modelName");
        assertBad(Map.of("versionEq", new String[] {"1"}), "Unsupported filter field: versionEq");
    }

    @Test
    void filterOptionsRejectsAnythingButCategory() {
        assertThatThrownBy(() -> ResultFilterParser.parseCategories(Map.of("model", new String[] {"M"})))
                .isInstanceOf(AppException.class)
                .hasMessage("Unsupported filter field: model");
        assertThat(ResultFilterParser.parseCategories(Map.of())).isEmpty();
    }

    private static void assertBad(Map<String, String[]> params, String messagePart) {
        assertThatThrownBy(() -> ResultFilterParser.parseList(params))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains(messagePart);
                });
    }
}
