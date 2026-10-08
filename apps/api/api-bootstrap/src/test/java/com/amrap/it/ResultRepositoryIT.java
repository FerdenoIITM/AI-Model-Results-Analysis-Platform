package com.amrap.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.amrap.application.ingestion.ResultFilterParser;
import com.amrap.domain.result.Result;
import com.amrap.domain.result.ResultPage;
import com.amrap.persistence.result.ResultRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ResultRepositoryIT extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ResultRepository repository;

    private ResultPage search(String... keyValues) {
        Map<String, List<String>> grouped = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            grouped.computeIfAbsent(keyValues[i], k -> new ArrayList<>()).add(keyValues[i + 1]);
        }
        Map<String, String[]> params = new HashMap<>();
        grouped.forEach((k, v) -> params.put(k, v.toArray(String[]::new)));
        return repository.search(ResultFilterParser.parseList(params));
    }

    private List<Double> confidences(ResultPage page) {
        return page.results().stream().map(Result::getConfidenceAccuracy).toList();
    }

    private void seedFour() {
        insertResult(ResultRow.of("CardioNet", "Cardiology", "Valve", 97.5, T0.plusSeconds(1))
                .withMetrics(94.0, 98.0, 95.0, 94.0, 97.0).vectorized(true));
        insertResult(ResultRow.of("CardioNet", "Cardiology", "Rhythm", 88.0, T0.plusSeconds(2))
                .withMetrics(80.0, 85.0, 82.0, 80.0, 84.0));
        insertResult(ResultRow.of("ChestScan", "Radiology", "Lung", 95.0, T0.plusSeconds(3))
                .withMetrics(96.0, 91.0, 93.0, 96.0, 90.0).vectorized(true));
        insertResult(ResultRow.of("NeuroSlice", null, null, null, T0.plusSeconds(4)));
    }

    @Test
    void migrationsCreatedTheSchemaHibernateValidates() {
        assertThat(jdbc.queryForList("SELECT extname FROM pg_extension", String.class)).contains("vector", "pgcrypto");
        assertThat(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema='public'", String.class))
                .contains("users", "results", "result_chunks");
    }

    @Test
    void noFilterReturnsEverythingNewestFirst() {
        seedFour();

        ResultPage page = search();

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.results()).extracting(Result::getModelName)
                .containsExactly("NeuroSlice", "ChestScan", "CardioNet", "CardioNet");
    }

    @Test
    void filtersByModelCategoryAndSubCategory() {
        seedFour();

        assertThat(search("model", "CardioNet").total()).isEqualTo(2);
        assertThat(search("model", "CardioNet", "model", "ChestScan").total()).isEqualTo(3);
        assertThat(search("model", "cardionet").total()).isZero();
        assertThat(search("category", "Radiology").results()).extracting(Result::getModelName).containsExactly("ChestScan");
        assertThat(search("subCategory", "Valve", "subCategory", "Lung").total()).isEqualTo(2);
        assertThat(search("category", "Cardiology", "subCategory", "Lung").total()).isZero();
        assertThat(search("model", "Unknown").results()).isEmpty();
    }

    @Test
    void filtersByVectorizedFlag() {
        seedFour();

        assertThat(search("vectorized", "true").total()).isEqualTo(2);
        assertThat(search("vectorized", "false").total()).isEqualTo(2);
    }

    @Test
    void everyComparisonOperatorOnMetrics() {
        seedFour();

        assertThat(confidences(search("confidenceAccuracyEq", "95", "sort", "confidenceAccuracy"))).containsExactly(95.0);
        assertThat(confidences(search("confidenceAccuracyGt", "95", "sort", "confidenceAccuracy"))).containsExactly(97.5);
        assertThat(confidences(search("confidenceAccuracyGte", "95", "sort", "confidenceAccuracy"))).containsExactly(95.0, 97.5);
        assertThat(confidences(search("confidenceAccuracyLt", "95", "sort", "confidenceAccuracy"))).containsExactly(88.0);
        assertThat(confidences(search("confidenceAccuracyLte", "95", "sort", "confidenceAccuracy"))).containsExactly(88.0, 95.0);
    }

    @Test
    void nullMetricsNeverMatchComparisons() {
        seedFour();

        assertThat(search("confidenceAccuracyGte", "0").total()).isEqualTo(3);
        assertThat(search("confidenceAccuracyLte", "100").total()).isEqualTo(3);
    }

    @Test
    void numericRangesInclusiveAndExclusive() {
        seedFour();

        assertThat(confidences(search("confidenceAccuracyGte", "88", "confidenceAccuracyLte", "95", "sort", "confidenceAccuracy")))
                .containsExactly(88.0, 95.0);
        assertThat(confidences(search("confidenceAccuracyGt", "88", "confidenceAccuracyLt", "97.5", "sort", "confidenceAccuracy")))
                .containsExactly(95.0);
        assertThat(search("confidenceAccuracyGte", "95", "confidenceAccuracyLte", "95").total()).isEqualTo(1);
        assertThat(search("confidenceAccuracyGt", "97.5", "confidenceAccuracyLte", "100").total()).isZero();
    }

    @Test
    void multipleFiltersCombineWithAnd() {
        seedFour();

        ResultPage page = search("model", "CardioNet", "model", "ChestScan", "category", "Cardiology",
                "confidenceAccuracyGte", "90", "sensitivityGt", "90", "precisionLte", "97");

        assertThat(page.results()).extracting(Result::getSubCategory).containsExactly("Valve");
    }

    @Test
    void sortingIsNumericNotLexicographicWithNullsLast() {
        for (double v : new double[] {9.5, 10.0, 100.0, 2.25}) {
            insertResult(ResultRow.of("M", null, null, v, T0.plusSeconds((long) v)));
        }
        insertResult(ResultRow.of("M", null, null, null, T0));

        assertThat(confidences(search("sort", "confidenceAccuracy"))).containsExactly(2.25, 9.5, 10.0, 100.0, null);
        assertThat(confidences(search("sort", "-confidenceAccuracy"))).containsExactly(100.0, 10.0, 9.5, 2.25, null);
    }

    @Test
    void sortsEveryMetricColumnNumerically() {
        insertResult(ResultRow.of("A", null, null, 1.0, T0).withMetrics(9.0, 9.0, 9.0, 9.0, 9.0));
        insertResult(ResultRow.of("B", null, null, 1.0, T0.plusSeconds(1)).withMetrics(10.0, 10.0, 10.0, 10.0, 10.0));
        insertResult(ResultRow.of("C", null, null, 1.0, T0.plusSeconds(2)).withMetrics(100.0, 100.0, 100.0, 100.0, 100.0));

        for (String metric : List.of("sensitivity", "specificity", "f1Score", "recall", "precision")) {
            assertThat(search("sort", metric).results()).as(metric).extracting(Result::getModelName).containsExactly("A", "B", "C");
            assertThat(search("sort", "-" + metric).results()).as(metric).extracting(Result::getModelName).containsExactly("C", "B", "A");
        }
    }

    @Test
    void sortsTextColumns() {
        insertResult(ResultRow.of("beta", "Z", "z", null, T0).withVersion("2.0"));
        insertResult(ResultRow.of("Alpha", "A", "a", null, T0.plusSeconds(1)).withVersion("10.0"));
        insertResult(ResultRow.of("gamma", null, null, null, T0.plusSeconds(2)).withVersion("1.0"));

        assertThat(search("sort", "category").results()).extracting(Result::getCategory).containsExactly("A", "Z", null);
        assertThat(search("sort", "-category").results()).extracting(Result::getCategory).containsExactly("Z", "A", null);
        assertThat(search("sort", "insertedAt").results()).extracting(Result::getModelName).containsExactly("beta", "Alpha", "gamma");
        assertThat(search("sort", "version").results()).extracting(Result::getVersion).hasSize(3);
    }

    @Test
    void paginatesLargeDatasetWithoutOverlap() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            rows.add(new Object[] {"Model" + (i % 7), java.sql.Timestamp.from(T0.plusSeconds(i)), (double) (i % 101)});
        }
        jdbc.batchUpdate("""
                INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash,
                                     inserted_at, confidence_accuracy)
                VALUES (?, '1', 'it', 'x.json', 1, 'h', ?, ?)
                """, rows);

        Set<UUID> seen = new HashSet<>();
        for (int p = 1; p <= 10; p++) {
            ResultPage page = search("page", String.valueOf(p), "pageSize", "100");
            assertThat(page.total()).isEqualTo(1000);
            assertThat(page.results()).hasSize(100);
            page.results().forEach(r -> seen.add(r.getId()));
        }
        assertThat(seen).hasSize(1000);
        assertThat(search("page", "11", "pageSize", "100").results()).isEmpty();
        assertThat(search("page", "11", "pageSize", "100").total()).isEqualTo(1000);
    }

    @Test
    void pagingByMetricWithManyTiesReturnsEachRowExactlyOnce() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            rows.add(new Object[] {java.sql.Timestamp.from(T0), i % 3 == 0 ? null : (double) (i % 3)});
        }
        jdbc.batchUpdate("""
                INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash,
                                     inserted_at, confidence_accuracy)
                VALUES ('Tie', '1', 'it', 'x.json', 1, 'h', ?, ?)
                """, rows);

        for (String sort : List.of("confidenceAccuracy", "-confidenceAccuracy", "insertedAt", "-modelName")) {
            Set<UUID> seen = new HashSet<>();
            List<Double> ordered = new ArrayList<>();
            for (int p = 1; p <= 6; p++) {
                ResultPage page = search("sort", sort, "page", String.valueOf(p), "pageSize", "50");
                page.results().forEach(r -> {
                    seen.add(r.getId());
                    ordered.add(r.getConfidenceAccuracy());
                });
            }
            assertThat(seen).as(sort).hasSize(300);
            if (sort.endsWith("confidenceAccuracy")) {
                assertThat(ordered.subList(200, 300)).as(sort + " nulls last").containsOnlyNulls();
            }
        }
    }

    @Test
    void filteringPlusSortingPlusPaging() {
        for (int i = 0; i < 30; i++) {
            insertResult(ResultRow.of(i % 2 == 0 ? "Even" : "Odd", null, null, (double) i, T0.plusSeconds(i)));
        }

        ResultPage page2 = search("model", "Even", "confidenceAccuracyGte", "4", "sort", "-confidenceAccuracy",
                "page", "2", "pageSize", "5");

        assertThat(page2.total()).isEqualTo(13);
        assertThat(confidences(page2)).containsExactly(18.0, 16.0, 14.0, 12.0, 10.0);
    }

    @Test
    void distinctLookupsComeFromTheDatabase() {
        seedFour();

        assertThat(repository.findDistinctModelNames()).containsExactly("CardioNet", "ChestScan", "NeuroSlice");
        assertThat(repository.findDistinctCategories()).containsExactly("Cardiology", "Radiology");
        assertThat(repository.findDistinctSubCategories()).containsExactly("Lung", "Rhythm", "Valve");
        assertThat(repository.findDistinctSubCategoriesByCategories(List.of("Cardiology"))).containsExactly("Rhythm", "Valve");
        assertThat(repository.findDistinctSubCategoriesByCategories(List.of("Nope"))).isEmpty();
    }

    @Test
    void distinctLookupsAreEmptyWithoutData() {
        assertThat(repository.findDistinctModelNames()).isEmpty();
        assertThat(repository.findDistinctCategories()).isEmpty();
        assertThat(search().results()).isEmpty();
        assertThat(search().total()).isZero();
    }

    @Test
    void vectorizationFlagsRoundTrip() {
        UUID id = insertResult(ResultRow.of("M", null, null, null, T0));

        assertThat(repository.findUnvectorizedBatch(10)).extracting(Result::getId).containsExactly(id);
        repository.markVectorized(id, Instant.now());
        assertThat(repository.findUnvectorizedBatch(10)).isEmpty();
        assertThat(repository.findById(id).orElseThrow().getVectorizedAt()).isNotNull();
        assertThat(repository.resetAllVectorization()).isEqualTo(1);
        assertThat(repository.findUnvectorizedBatch(10)).hasSize(1);
    }
}
