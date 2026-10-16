package com.amrap.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PromptClassifierTest {

    private OllamaChatClient ollama;
    private PromptClassifier classifier;

    @BeforeEach
    void setUp() {
        ollama = mock(OllamaChatClient.class);
        ChatProperties props = new ChatProperties();
        props.setClassifyModel("qwen2.5:3b");
        classifier = new PromptClassifier(props, ollama, new ObjectMapper());
    }

    @Test
    void parsesJsonRoute() {
        when(ollama.chat(anyString(), any())).thenReturn("{\"route\":\"HYBRID\"}");
        assertThat(classifier.classify("notes of F1>90", List.of())).isEqualTo(ChatRoute.HYBRID);
    }

    @Test
    void defaultsToRagOnGarbage() {
        when(ollama.chat(anyString(), any())).thenReturn("not sure");
        assertThat(classifier.classify("q", List.of())).isEqualTo(ChatRoute.RAG);
    }

    @Test
    void parseRouteExtractsEmbeddedJson() {
        assertThat(classifier.parseRoute("Sure.\n{\"route\":\"SQL\"}\n")).isEqualTo(ChatRoute.SQL);
    }
}
