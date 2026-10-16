package com.amrap.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amrap.application.rag.RagProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class ChatOrchestratorTest {

    private RuleGate ruleGate;
    private PromptClassifier classifier;
    private SqlQueryService sqlQueryService;
    private RagSearchService ragSearchService;
    private AnswerService answerService;
    private ChatOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        ruleGate = new RuleGate(new RagProperties());
        classifier = mock(PromptClassifier.class);
        sqlQueryService = mock(SqlQueryService.class);
        ragSearchService = mock(RagSearchService.class);
        answerService = mock(AnswerService.class);
        OllamaChatClient ollama = mock(OllamaChatClient.class);
        ChatProperties props = new ChatProperties();
        orchestrator = new ChatOrchestrator(
                ruleGate, classifier, sqlQueryService, ragSearchService, answerService, ollama, props);
    }

    @Test
    void noneRouteFromRuleGateSkipsClassifier() {
        ChatResponse response = orchestrator.ask(new ChatRequest("hi", null, null, null, null, null, null));
        assertThat(response.answer()).containsIgnoringCase("Hello");
        verify(classifier, never()).classify(any(), any());
    }

    @Test
    void sqlRouteUsesSqlThenAnswer() {
        when(classifier.classify(any(), any())).thenReturn(ChatRoute.SQL);
        when(sqlQueryService.available()).thenReturn(true);
        List<Map<String, Object>> rows = List.of(Map.of("model_name", "X", "f1_score", 99.0));
        when(sqlQueryService.run(any(), any())).thenReturn(rows);
        when(answerService.answer(any(), any(), eq(rows), eq(List.of())))
                .thenReturn(new ChatResponse("best is X", List.of()));

        ChatResponse response = orchestrator.ask(req("best F1?"));
        assertThat(response.answer()).isEqualTo("best is X");
        verify(ragSearchService, never()).search(any(), any(), any());
    }

    @Test
    void hybridRestrictsRagToSqlIds() {
        UUID id = UUID.randomUUID();
        when(classifier.classify(any(), any())).thenReturn(ChatRoute.HYBRID);
        when(sqlQueryService.available()).thenReturn(true);
        when(ragSearchService.available()).thenReturn(true);
        List<Map<String, Object>> rows = List.of(Map.of("id", id, "model_name", "M"));
        when(sqlQueryService.run(any(), any())).thenReturn(rows);
        Document doc = Document.builder().text("notes").metadata(Map.of("result_id", id.toString())).build();
        when(ragSearchService.search(any(), any(), eq(List.of(id)))).thenReturn(List.of(doc));
        when(answerService.answer(any(), any(), eq(rows), eq(List.of(doc))))
                .thenReturn(new ChatResponse("ok", List.of()));

        assertThat(orchestrator.ask(req("notes for top F1")).answer()).isEqualTo("ok");
        verify(ragSearchService).search(any(), any(), eq(List.of(id)));
    }

    @Test
    void ragRouteSearchesWithoutSql() {
        when(classifier.classify(any(), any())).thenReturn(ChatRoute.RAG);
        when(ragSearchService.available()).thenReturn(true);
        when(ragSearchService.search(any(), any(), isNull())).thenReturn(List.of());
        when(answerService.answer(any(), any(), eq(List.of()), eq(List.of())))
                .thenReturn(new ChatResponse("no data", List.of()));

        assertThat(orchestrator.ask(req("weak on tumors?")).answer()).isEqualTo("no data");
        verify(sqlQueryService, never()).run(any(), any());
    }

    private static ChatRequest req(String q) {
        return new ChatRequest(q, null, null, null, null, null, null);
    }
}
