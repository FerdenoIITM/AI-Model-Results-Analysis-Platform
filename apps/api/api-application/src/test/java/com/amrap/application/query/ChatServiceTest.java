package com.amrap.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amrap.application.common.AppException;
import com.amrap.application.rag.RagProperties;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

class ChatServiceTest {

    private VectorStore vectorStore;
    private FakeChatModel chatModel;
    private ChatService service;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        chatModel = new FakeChatModel(prompt -> "The answer");
        RagProperties properties = new RagProperties();
        properties.setTopK(4);
        service = new ChatService(properties, vectorStore, chatModel);
    }

    @Test
    void answersWithCitationsFromRetrievedChunks() {
        UUID resultId = UUID.randomUUID();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(Document.builder()
                .text("sensitivity: 94.2")
                .metadata(Map.of("result_id", resultId.toString(), "model_name", "CardioNet", "version", "1.2.0",
                        "iteration_id", "20260101120000", "inserted_at", "2026-01-01T12:00:00Z", "chunk_index", 3))
                .score(0.91)
                .build()));

        ChatResponse response = service.ask(request("What is the sensitivity?", null, null, null, null));

        assertThat(response.answer()).isEqualTo("The answer");
        assertThat(response.citations()).singleElement().satisfies(c -> {
            assertThat(c.resultId()).isEqualTo(resultId);
            assertThat(c.modelName()).isEqualTo("CardioNet");
            assertThat(c.version()).isEqualTo("1.2.0");
            assertThat(c.chunkIndex()).isEqualTo(3);
            assertThat(c.score()).isEqualTo(0.91);
        });
        String sentToLlm = chatModel.prompts.get(0).getInstructions().stream().map(Message::getText).reduce("", String::concat);
        assertThat(sentToLlm).contains("sensitivity: 94.2").contains("model=CardioNet").contains("QUESTION: What is the sensitivity?");
    }

    @Test
    void searchUsesTopKAndNoFilterByDefault() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.ask(request("Best model?", null, null, null, null));

        SearchRequest sent = captureSearch();
        assertThat(sent.getQuery()).isEqualTo("Best model?");
        assertThat(sent.getTopK()).isEqualTo(4);
        assertThat(sent.getFilterExpression()).isNull();
    }

    @Test
    void buildsAndFilterFromModelVersionAndDates() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.ask(request("q", " CardioNet ", "1.2.0", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"));

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression expected = b.and(b.and(b.and(
                b.eq("model_name", "CardioNet"), b.eq("version", "1.2.0")),
                b.gte("inserted_at", "2026-01-01T00:00:00Z")),
                b.lte("inserted_at", "2026-01-02T00:00:00Z")).build();
        assertThat(captureSearch().getFilterExpression()).isEqualTo(expected);
    }

    @Test
    void todayInQuestionLimitsToLocalDay() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.ask(request("What ran TODAY?", null, null, null, null));

        String start = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toString();
        assertThat(captureSearch().getFilterExpression().toString()).contains(start);
    }

    @Test
    void yesterdayInQuestionLimitsToPreviousDay() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.ask(request("results from yesterday", null, null, null, null));

        ZoneId zone = ZoneId.systemDefault();
        String start = LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant().toString();
        assertThat(captureSearch().getFilterExpression().toString()).contains(start);
    }

    @Test
    void todayUsesConfiguredTimezone() {
        RagProperties properties = new RagProperties();
        properties.setTimezone("Pacific/Kiritimati");
        ChatService zoned = new ChatService(properties, vectorStore, chatModel);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        zoned.ask(request("What ran today?", null, null, null, null));

        ZoneId zone = ZoneId.of("Pacific/Kiritimati");
        String start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toString();
        assertThat(captureSearch().getFilterExpression().toString()).contains(start);
    }

    @Test
    void explicitDatesWinOverRelativeWords() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.ask(request("today", null, null, "2020-01-01T00:00:00Z", null));

        assertThat(captureSearch().getFilterExpression().toString())
                .contains("2020-01-01T00:00:00Z")
                .doesNotContain(LocalDate.now().toString());
    }

    @Test
    void invalidIsoTimestampIsBadRequest() {
        assertStatus(() -> service.ask(request("q", null, null, "yesterday-ish", null)), 400, "Invalid ISO timestamp: yesterday-ish");
    }

    @Test
    void noHitsGivesFriendlyAnswerWithoutCallingLlm() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        ChatResponse response = service.ask(request("q", null, null, null, null));

        assertThat(response.answer()).contains("don't have any ingested results");
        assertThat(response.citations()).isEmpty();
        assertThat(chatModel.prompts).isEmpty();
    }

    @Test
    void nullHitsTreatedAsEmpty() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(null);

        assertThat(service.ask(request("q", null, null, null, null)).citations()).isEmpty();
    }

    @Test
    void vectorStoreFailureIs502() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("connection refused"));

        assertStatus(() -> service.ask(request("q", null, null, null, null)), 502, "Vector search failed: connection refused");
    }

    @Test
    void llmFailureIs502() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("x", Map.of())));
        ChatService failing = new ChatService(new RagProperties(), vectorStore, new FakeChatModel(p -> {
            throw new IllegalStateException("ollama down");
        }));

        assertStatus(() -> failing.ask(request("q", null, null, null, null)), 502, "LLM call failed: ollama down");
    }

    @Test
    void blankQuestionIsBadRequest() {
        assertStatus(() -> service.ask(null), 400, "question is required");
        assertStatus(() -> service.ask(request(null, null, null, null, null)), 400, "question is required");
        assertStatus(() -> service.ask(request("   ", null, null, null, null)), 400, "question is required");
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void missingBeansGiveNotConfiguredMessage() {
        ChatService unconfigured = new ChatService(new RagProperties(), null, null);

        assertThat(unconfigured.ask(request("q", null, null, null, null)).answer()).contains("RAG is not configured");
    }

    @Test
    void followUpRewritesQueryAndKeepsOnlyValidHistory() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("x", Map.of())));
        List<String> calls = new ArrayList<>();
        ChatService withHistory = new ChatService(new RagProperties(), vectorStore, new FakeChatModel(p -> {
            calls.add(p.getInstructions().get(p.getInstructions().size() - 1).getText());
            return calls.size() == 1 ? "\"CardioNet sensitivity\"\nextra" : "final";
        }));

        ChatResponse response = withHistory.ask(new ChatRequest("and them?", null, null, null, null, null, List.of(
                new ChatTurn("user", "Tell me about CardioNet"),
                new ChatTurn("assistant", "CardioNet is..."),
                new ChatTurn("system", "ignored"),
                new ChatTurn("user", "  "),
                new ChatTurn(null, "ignored too"))));

        assertThat(response.answer()).isEqualTo("final");
        assertThat(captureSearch().getQuery()).isEqualTo("CardioNet sensitivity");
        assertThat(calls.get(0)).contains("user: Tell me about CardioNet").doesNotContain("ignored");
    }

    @Test
    void failedRewriteFallsBackToOriginalQuestion() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        int[] count = {0};
        ChatService flaky = new ChatService(new RagProperties(), vectorStore, new FakeChatModel(p -> {
            count[0]++;
            throw new IllegalStateException("rewrite failed");
        }));

        flaky.ask(new ChatRequest("original?", null, null, null, null, null, List.of(new ChatTurn("user", "hi"))));

        assertThat(captureSearch().getQuery()).isEqualTo("original?");
        assertThat(count[0]).isEqualTo(1);
    }

    @Test
    void malformedCitationMetadataBecomesNull() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("x", Map.of("result_id", "not-a-uuid", "chunk_index", "NaN"))));

        ChatCitation c = service.ask(request("q", null, null, null, null)).citations().get(0);

        assertThat(c.resultId()).isNull();
        assertThat(c.chunkIndex()).isNull();
        assertThat(c.modelName()).isNull();
    }

    private SearchRequest captureSearch() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        return captor.getValue();
    }

    private static ChatRequest request(String q, String model, String version, String from, String to) {
        return new ChatRequest(q, model, version, null, from, to, null);
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AppException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getMessage()).isEqualTo(message);
        });
    }

    static final class FakeChatModel implements ChatModel {
        final List<Prompt> prompts = new ArrayList<>();
        private final Function<Prompt, String> reply;

        FakeChatModel(Function<Prompt, String> reply) {
            this.reply = reply;
        }

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new Generation(new AssistantMessage(reply.apply(prompt)))));
        }
    }
}
