package com.amrap.application.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amrap.application.common.AppException;
import com.amrap.application.rag.RagProperties;
import com.amrap.domain.result.Result;
import com.amrap.domain.result.ResultFilter;
import com.amrap.domain.result.ResultPage;
import com.amrap.persistence.result.ResultRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResultQueryServiceTest {

    @TempDir
    Path storage;

    private ResultRepository repository;
    private ResultQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(ResultRepository.class);
        RagProperties properties = new RagProperties();
        properties.setStorageDir(storage.toString());
        service = new ResultQueryService(repository, properties);
    }

    @Test
    void searchDelegatesToRepository() {
        ResultFilter filter = ResultFilterParser.parseList(Map.of());
        ResultPage page = new ResultPage(List.of(), 0, 1, 50);
        when(repository.search(filter)).thenReturn(page);

        assertThat(service.search(filter)).isSameAs(page);
    }

    @Test
    void filterOptionsUseAllSubCategoriesWhenNoCategorySelected() {
        when(repository.findDistinctModelNames()).thenReturn(List.of("CardioNet", "ChestScan"));
        when(repository.findDistinctCategories()).thenReturn(List.of("Cardiology", "Radiology"));
        when(repository.findDistinctSubCategories()).thenReturn(List.of("Valve", "Lung"));

        FilterOptions options = service.filterOptions(List.of());

        assertThat(options.models()).containsExactly("CardioNet", "ChestScan");
        assertThat(options.categories()).containsExactly("Cardiology", "Radiology");
        assertThat(options.subCategories()).containsExactly("Valve", "Lung");
        verify(repository, never()).findDistinctSubCategoriesByCategories(List.of());
    }

    @Test
    void filterOptionsNarrowSubCategoriesBySelectedCategories() {
        when(repository.findDistinctModelNames()).thenReturn(List.of());
        when(repository.findDistinctCategories()).thenReturn(List.of("Cardiology", "Radiology"));
        when(repository.findDistinctSubCategoriesByCategories(List.of("Cardiology"))).thenReturn(List.of("Valve"));

        FilterOptions options = service.filterOptions(List.of("Cardiology"));

        assertThat(options.categories()).containsExactly("Cardiology", "Radiology");
        assertThat(options.subCategories()).containsExactly("Valve");
        verify(repository, never()).findDistinctSubCategories();
    }

    @Test
    void requireByIdThrows404WhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireById(id))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(404);
                    assertThat(ex.getMessage()).isEqualTo("Result not found");
                });
    }

    @Test
    void readPayloadReturnsFileContent() throws Exception {
        Files.writeString(storage.resolve("abc.json"), "{\"ok\":true}");

        assertThat(service.readPayload(result("abc.json"))).isEqualTo("{\"ok\":true}");
    }

    @Test
    void readPayloadRefusesPathTraversal() {
        assertThatThrownBy(() -> service.readPayload(result("../../etc/passwd")))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(500);
                    assertThat(ex.getMessage()).contains("outside storage dir");
                });
    }

    @Test
    void readPayloadFailsWhenFileMissing() {
        assertThatThrownBy(() -> service.readPayload(result("missing.json")))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(500);
                    assertThat(ex.getMessage()).isEqualTo("Failed to read result payload");
                });
    }

    private static Result result(String jsonPath) {
        Result r = new Result();
        r.setId(UUID.randomUUID());
        r.setJsonPath(jsonPath);
        return r;
    }
}
