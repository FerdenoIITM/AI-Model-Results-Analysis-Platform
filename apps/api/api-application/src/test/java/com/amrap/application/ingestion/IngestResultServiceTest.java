package com.amrap.application.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amrap.application.common.AppException;
import com.amrap.application.rag.RagProperties;
import com.amrap.domain.result.Result;
import com.amrap.persistence.result.ResultRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

class IngestResultServiceTest {

    @TempDir
    Path storage;

    private ResultRepository repository;
    private IngestResultService service;

    @BeforeEach
    void setUp() {
        repository = mock(ResultRepository.class);
        when(repository.save(any(Result.class))).thenAnswer(inv -> inv.getArgument(0));
        RagProperties properties = new RagProperties();
        properties.setStorageDir(storage.toString());
        service = new IngestResultService(repository, properties);
    }

    @Test
    void writesPayloadToFlatUuidFileAndStoresMetadata() throws Exception {
        UUID submitter = UUID.randomUUID();
        String json = "{\"accuracy\":0.97,\"note\":\"ünïcödé\"}";

        Result saved = service.ingest(command(" CardioNet ", " 1.2.0 ", json, " Cardiology ", "Valve", 97.5), submitter);

        assertThat(saved.getJsonPath()).isEqualTo(saved.getId() + ".json");
        Path file = storage.resolve(saved.getJsonPath());
        assertThat(file).exists();
        assertThat(Files.readString(file)).isEqualTo(json);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        assertThat(saved.getSizeBytes()).isEqualTo(bytes.length);
        assertThat(saved.getContentHash())
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(saved.getModelName()).isEqualTo("CardioNet");
        assertThat(saved.getVersion()).isEqualTo("1.2.0");
        assertThat(saved.getCategory()).isEqualTo("Cardiology");
        assertThat(saved.getSubCategory()).isEqualTo("Valve");
        assertThat(saved.getConfidenceAccuracy()).isEqualTo(97.5);
        assertThat(saved.getIterationId()).matches("\\d{14}");
        assertThat(saved.getInsertedAt()).isNotNull();
        assertThat(saved.isVectorized()).isFalse();
        assertThat(saved.getCreatedBy()).isEqualTo(submitter);
    }

    @Test
    void createsMissingStorageDirectory() {
        RagProperties properties = new RagProperties();
        properties.setStorageDir(storage.resolve("nested/dir").toString());
        Result saved = new IngestResultService(repository, properties)
                .ingest(command("M", "1", "{}", null, null, null), null);

        assertThat(storage.resolve("nested/dir").resolve(saved.getJsonPath())).exists();
    }

    @Test
    void optionalFieldsMayBeNull() {
        Result saved = service.ingest(command("M", "1", "[]", null, null, null), null);

        assertThat(saved.getCategory()).isNull();
        assertThat(saved.getSubCategory()).isNull();
        assertThat(saved.getConfidenceAccuracy()).isNull();
        assertThat(saved.getPrecision()).isNull();
        assertThat(saved.getCreatedBy()).isNull();
    }

    @Test
    void identicalPayloadsAreStoredAsSeparateResults() {
        Result first = service.ingest(command("M", "1", "{\"a\":1}", null, null, null), null);
        Result second = service.ingest(command("M", "1", "{\"a\":1}", null, null, null), null);

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getContentHash()).isEqualTo(second.getContentHash());
        assertThat(storage.resolve(first.getJsonPath())).exists();
        assertThat(storage.resolve(second.getJsonPath())).exists();
    }

    @Test
    void acceptsBoundaryMetricValues() {
        Result saved = service.ingest(new IngestResultCommand("M", "1", "{}", null, null, 0.0, 100.0, 0.0, 100.0, 50.0, 0.0001), null);

        assertThat(saved.getConfidenceAccuracy()).isZero();
        assertThat(saved.getSensitivity()).isEqualTo(100.0);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.01, 100.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsOutOfRangeMetrics(double value) {
        assertBad(new IngestResultCommand("M", "1", "{}", null, null, null, null, null, value, null, null),
                400, "f1Score must be between 0 and 100");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertBad(null, 400, "Payload is required");
        assertBad(command(null, "1", "{}", null, null, null), 400, "modelName is required");
        assertBad(command("  ", "1", "{}", null, null, null), 400, "modelName is required");
        assertBad(command("M", null, "{}", null, null, null), 400, "version is required");
        assertBad(command("M", "", "{}", null, null, null), 400, "version is required");
        assertBad(command("M", "1", null, null, null, null), 400, "payload JSON is required");
        assertBad(command("M", "1", " ", null, null, null), 400, "payload JSON is required");
    }

    @Test
    void rejectsBlankOrTooLongLabels() {
        assertBad(command("M", "1", "{}", " ", null, null), 400, "category must not be blank");
        assertBad(command("M", "1", "{}", null, "x".repeat(121), null), 400, "subCategory must be at most 120 characters");
    }

    @Test
    void failsWith500WhenFileCannotBeWritten() throws Exception {
        Path blocker = storage.resolve("not-a-dir");
        Files.writeString(blocker, "file");
        RagProperties properties = new RagProperties();
        properties.setStorageDir(blocker.toString());

        assertThatThrownBy(() -> new IngestResultService(repository, properties)
                .ingest(command("M", "1", "{}", null, null, null), null))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(500);
                    assertThat(ex.getMessage()).isEqualTo("Failed to persist result payload");
                });
        verify(repository, never()).save(any());
    }

    @Test
    void propagatesDatabaseFailure() {
        when(repository.save(any(Result.class))).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.ingest(command("M", "1", "{}", null, null, null), null))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void storesLargePayload() throws Exception {
        String big = "{\"data\":\"" + "x".repeat(5_000_000) + "\"}";

        Result saved = service.ingest(command("M", "1", big, null, null, null), null);

        assertThat(saved.getSizeBytes()).isEqualTo(big.length());
        assertThat(Files.size(storage.resolve(saved.getJsonPath()))).isEqualTo(big.length());
    }

    private void assertBad(IngestResultCommand cmd, int status, String message) {
        assertThatThrownBy(() -> service.ingest(cmd, null))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(status);
                    assertThat(ex.getMessage()).isEqualTo(message);
                });
    }

    private static IngestResultCommand command(
            String model, String version, String json, String category, String subCategory, Double confidence) {
        return new IngestResultCommand(model, version, json, category, subCategory, confidence, null, null, null, null, null);
    }
}
