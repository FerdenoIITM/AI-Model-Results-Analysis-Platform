package com.amrap.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.amrap.application.rag.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuleGateTest {

    private RuleGate gate;

    @BeforeEach
    void setUp() {
        RagProperties props = new RagProperties();
        props.setTimezone("Asia/Kolkata");
        gate = new RuleGate(props);
    }

    @Test
    void greetsWithoutLlm() {
        assertThat(gate.tryAnswer("Hello").orElseThrow().answer()).contains("Hello");
    }

    @Test
    void refusesDelete() {
        assertThat(gate.tryAnswer("Please delete all results").orElseThrow().answer())
                .containsIgnoringCase("Read-only");
    }

    @Test
    void answersDate() {
        assertThat(gate.tryAnswer("What is the date?").orElseThrow().answer()).contains("Today is");
    }

    @Test
    void answersDateForTodayPhrasings() {
        assertThat(gate.tryAnswer("Whats the date for today").orElseThrow().answer()).contains("Today is");
        assertThat(gate.tryAnswer("Whats the today's date").orElseThrow().answer()).contains("Today is");
    }

    @Test
    void answersHowAreYou() {
        assertThat(gate.tryAnswer("How are you").orElseThrow().answer()).contains("Hello");
    }

    @Test
    void leavesAnalyticsToPipeline() {
        assertThat(gate.tryAnswer("What is the best F1 score?")).isEmpty();
        assertThat(gate.tryAnswer("results for today")).isEmpty();
    }
}
