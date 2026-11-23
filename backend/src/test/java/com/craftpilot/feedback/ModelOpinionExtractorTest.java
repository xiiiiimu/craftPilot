package com.craftpilot.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.craftpilot.feedback.OpinionExtractor.ExtractedOpinion;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ModelOpinionExtractorTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void sendsStrictJsonSchemaAndParsesOpinionsWithUsage() {
        AtomicReference<String> sent = new AtomicReference<>();
        ModelOpinionExtractor extractor = new ModelOpinionExtractor(mapper, body -> {
            sent.set(body);
            return response("{\\\"opinions\\\":[{\\\"polarity\\\":\\\"NEGATIVE\\\",\\\"attribute\\\":\\\"size\\\","
                    + "\\\"quote\\\":\\\"尺码偏小\\\"},{\\\"polarity\\\":\\\"POSITIVE\\\",\\\"attribute\\\":\\\"color\\\","
                    + "\\\"quote\\\":\\\"颜色好看\\\"}]}", 120, 30);
        }, "gpt-4o-mini");

        ModelOpinionExtractor.Extraction result = extractor.extract("尺码偏小，颜色倒是好看");

        assertThat(result.fallback()).isFalse();
        assertThat(result.inputTokens()).isEqualTo(120);
        assertThat(result.outputTokens()).isEqualTo(30);
        assertThat(labels(result.opinions())).containsExactly("NEGATIVE:size", "POSITIVE:color");
        JsonNode request = mapper.readTree(sent.get());
        assertThat(request.path("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(request.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(request.path("messages").get(1).path("content").asText()).isEqualTo("尺码偏小，颜色倒是好看");
    }

    @Test
    void dropsUnknownLabelsAndDuplicatesAndDefaultsToNeutralOverall() {
        ModelOpinionExtractor extractor = new ModelOpinionExtractor(mapper, body -> response(
                "{\\\"opinions\\\":[{\\\"polarity\\\":\\\"POSITIVE\\\",\\\"attribute\\\":\\\"smell\\\",\\\"quote\\\":\\\"x\\\"},"
                        + "{\\\"polarity\\\":\\\"NEUTRAL\\\",\\\"attribute\\\":\\\"price\\\",\\\"quote\\\":\\\"\\\"},"
                        + "{\\\"polarity\\\":\\\"NEUTRAL\\\",\\\"attribute\\\":\\\"price\\\",\\\"quote\\\":\\\"y\\\"}]}", 10, 5),
                "m");
        assertThat(labels(extractor.extract("多少钱").opinions())).containsExactly("NEUTRAL:price");

        ModelOpinionExtractor empty = new ModelOpinionExtractor(mapper,
                body -> response("{\\\"opinions\\\":[]}", 10, 5), "m");
        assertThat(labels(empty.extract("嗯").opinions())).containsExactly("NEUTRAL:overall");
    }

    @Test
    void fallsBackToRulesWhenTheModelFailsOrReturnsGarbage() {
        ModelOpinionExtractor failing = new ModelOpinionExtractor(mapper, body -> {
            throw new IOException("HTTP 500");
        }, "m");
        ModelOpinionExtractor garbage = new ModelOpinionExtractor(mapper, body -> response("not json", 10, 5), "m");

        for (ModelOpinionExtractor extractor : List.of(failing, garbage)) {
            ModelOpinionExtractor.Extraction result = extractor.extract("不喜欢");
            assertThat(result.fallback()).isTrue();
            assertThat(labels(result.opinions())).isEqualTo(labels(new OpinionExtractor().extract("不喜欢")));
        }
        assertThat(failing.extract("  ").opinions()).isEmpty();
    }

    private static String response(String escapedContent, int in, int out) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + escapedContent + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":" + in + ",\"completion_tokens\":" + out + "}}";
    }

    private static List<String> labels(List<ExtractedOpinion> opinions) {
        return opinions.stream().map(o -> o.polarity() + ":" + o.attributeKey()).toList();
    }
}
