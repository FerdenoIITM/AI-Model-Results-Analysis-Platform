package com.amrap.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amrap.application.rag.RagProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

class RagSearchServiceTest {

    private VectorStore vectorStore;
    private RagSearchService service;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        properties.setTopK(4);
        service = new RagSearchService(properties, vectorStore);
    }

    @Test
    void searchUsesTopK() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        service.search(req("Best model?"), "Best model?", null);
        SearchRequest sent = captureSearch();
        assertThat(sent.getQuery()).isEqualTo("Best model?");
        assertThat(sent.getTopK()).isEqualTo(4);
        assertThat(sent.getFilterExpression()).isNull();
    }

    @Test
    void postFiltersByResultIds() {
        UUID keep = UUID.randomUUID();
        UUID drop = UUID.randomUUID();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().text("a").metadata(Map.of("result_id", keep.toString())).build(),
                Document.builder().text("b").metadata(Map.of("result_id", drop.toString())).build()));

        List<Document> hits = service.search(req("q"), "q", List.of(keep));
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getText()).isEqualTo("a");
    }

    private SearchRequest captureSearch() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        org.mockito.Mockito.verify(vectorStore).similaritySearch(captor.capture());
        return captor.getValue();
    }

    private static ChatRequest req(String q) {
        return new ChatRequest(q, null, null, null, null, null, null);
    }
}
