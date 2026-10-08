package com.amrap.application.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonFlattenerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void flattensNestedObjectsAndArrays() throws Exception {
        String flat = JsonFlattener.flatten(mapper.readTree(
                "{\"model\":{\"name\":\"M1\"},\"scores\":[0.9,0.8],\"ok\":true,\"none\":null}"));

        assertThat(flat).isEqualTo("model.name: M1\nscores[0]: 0.9\nscores[1]: 0.8\nok: true\nnone: null\n");
    }

    @Test
    void handlesEmptyContainersAndScalars() throws Exception {
        assertThat(JsonFlattener.flatten(mapper.readTree("{}"))).isEqualTo("{}\n");
        assertThat(JsonFlattener.flatten(mapper.readTree("[]"))).isEqualTo("[]\n");
        assertThat(JsonFlattener.flatten(mapper.readTree("{\"a\":{},\"b\":[]}"))).isEqualTo("a: {}\nb: []\n");
        assertThat(JsonFlattener.flatten(mapper.readTree("42"))).isEqualTo("42\n");
        assertThat(JsonFlattener.flatten(null)).isEqualTo("null\n");
    }

    @Test
    void handlesNestedArraysOfObjects() throws Exception {
        String flat = JsonFlattener.flatten(mapper.readTree("{\"runs\":[{\"epoch\":1},[\"x\"]]}"));

        assertThat(flat).isEqualTo("runs[0].epoch: 1\nruns[1][0]: x\n");
    }

    @Test
    void handlesDeepNesting() throws Exception {
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            json.append("{\"k\":");
        }
        json.append("1");
        json.append("}".repeat(200));

        assertThat(JsonFlattener.flatten(mapper.readTree(json.toString()))).endsWith("k: 1\n").startsWith("k.k.k");
    }
}
