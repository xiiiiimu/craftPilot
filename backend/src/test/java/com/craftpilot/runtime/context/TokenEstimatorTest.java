package com.craftpilot.runtime.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class TokenEstimatorTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void cjkCharactersCountAsOneTokenEach() {
        String chinese = "面料".repeat(500); // 1000 CJK characters
        assertThat(TokenEstimator.estimateText(chinese)).isGreaterThanOrEqualTo(1000);
    }

    @Test
    void asciiCountsFourCharactersPerToken() {
        assertThat(TokenEstimator.estimateText("a".repeat(4000))).isEqualTo(1000);
    }

    @Test
    void toolCallArgumentsAndSchemasAreCounted() {
        List<Map<String, Object>> plain = List.of(Map.of("role", "user", "content", "hi"));
        String args = "{\"revisionId\":\"" + "x".repeat(400) + "\"}";
        List<Map<String, Object>> withToolCall = List.of(
                Map.of("role", "user", "content", "hi"),
                Map.of("role", "assistant", "tool_calls", List.of(Map.of(
                        "id", "call_1", "type", "function",
                        "function", Map.of("name", "evidence_search", "arguments", args)))));
        int base = TokenEstimator.estimate(mapper, plain, List.of());
        int withCall = TokenEstimator.estimate(mapper, withToolCall, List.of());
        assertThat(withCall - base).as("tool_call arguments are part of the prompt").isGreaterThan(100);

        List<Map<String, Object>> schemas = List.of(Map.of("type", "function", "function",
                Map.of("name", "evidence_search", "description", "d".repeat(400))));
        assertThat(TokenEstimator.estimate(mapper, plain, schemas) - base)
                .as("tool schemas are sent with every call").isGreaterThan(100);
    }
}
