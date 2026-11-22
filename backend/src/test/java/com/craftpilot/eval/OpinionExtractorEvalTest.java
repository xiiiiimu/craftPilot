package com.craftpilot.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.craftpilot.feedback.OpinionExtractor;
import com.craftpilot.feedback.OpinionExtractor.ExtractedOpinion;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Component evaluation of the opinion extractor against {@code eval/opinions-v1.json}. Writes one row per message
 * to {@code eval-opinions.csv}; macro-F1 and the confusion matrix are computed by {@code eval_report.py}. The
 * extractor is the rule-based v0 (P1-1 baseline); it has no Spring dependencies, so no application context is
 * started.
 */
@Tag("eval")
class OpinionExtractorEvalTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void extractAnnotatedOpinions() throws Exception {
        JsonNode dataset;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("eval/opinions-v1.json")) {
            assertThat(in).isNotNull();
            dataset = mapper.readTree(in);
        }
        OpinionExtractor extractor = new OpinionExtractor();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : dataset.path("items")) {
            Set<String> gold = new LinkedHashSet<>();
            for (JsonNode label : item.path("labels")) {
                gold.add(label.path("polarity").asText() + ":" + label.path("attribute").asText());
            }
            Set<String> predicted = new LinkedHashSet<>();
            for (ExtractedOpinion opinion : extractor.extract(item.path("text").asText())) {
                predicted.add(opinion.polarity() + ":" + opinion.attributeKey());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.path("id").asText());
            row.put("split", item.path("split").asText());
            row.put("text", item.path("text").asText());
            row.put("gold", String.join(";", gold));
            row.put("predicted", String.join(";", predicted));
            rows.add(row);
        }
        EvalOutput.writeCsv(EvalOutput.outDir().resolve("eval-opinions.csv"), rows);
        assertThat(rows).hasSize(dataset.path("items").size());
    }
}
