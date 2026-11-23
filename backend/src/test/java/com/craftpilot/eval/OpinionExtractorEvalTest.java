package com.craftpilot.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.craftpilot.feedback.ModelOpinionExtractor;
import com.craftpilot.feedback.OpinionExtractor;
import com.craftpilot.feedback.OpinionExtractor.ExtractedOpinion;
import com.craftpilot.runtime.model.ChatPricing;
import java.io.InputStream;
import java.math.BigDecimal;
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
 * Component evaluation of opinion extraction against {@code eval/opinions-<eval.dataset>.json} (default v1). Writes
 * one row per message to {@code eval-opinions.csv}; macro-F1 and the confusion matrix are computed by
 * {@code eval_report.py}. No application context is started.
 *
 * <ul>
 *   <li>{@code -Deval.opinionExtractor=v0} (default): rule-based {@link OpinionExtractor}.</li>
 *   <li>{@code -Deval.opinionExtractor=v1}: {@link ModelOpinionExtractor}; needs {@code -Deval.mode=live} (keys from
 *       the environment or {@code .env}) and stops once {@code eval.maxCostUsd} is spent.</li>
 *   <li>{@code -Deval.split=dev|test} limits the items (default all).</li>
 * </ul>
 */
@Tag("eval")
class OpinionExtractorEvalTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void extractAnnotatedOpinions() throws Exception {
        JsonNode dataset;
        String resource = "eval/opinions-" + EvalOutput.datasetVersion() + ".json";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).isNotNull();
            dataset = mapper.readTree(in);
        }
        String version = System.getProperty("eval.opinionExtractor", "v0").trim();
        assertThat(version).as("eval.opinionExtractor").isIn("v0", "v1");
        String split = System.getProperty("eval.split", "").trim();

        Extractor extractor;
        EvalSettings settings = EvalSettings.fromEnvironment();
        ChatPricing pricing = new ChatPricing(new ChatPricing.Properties(null, null));
        if (version.equals("v1")) {
            assertThat(settings.live()).as("eval.opinionExtractor=v1 calls the model: needs -Deval.mode=live").isTrue();
            ModelOpinionExtractor model = ModelOpinionExtractor.openAiCompatible(
                    mapper, settings.chatBaseUrl(), settings.chatKey(), settings.chatModel());
            extractor = model::extract;
        } else {
            OpinionExtractor rules = new OpinionExtractor();
            extractor = text -> new ModelOpinionExtractor.Extraction(rules.extract(text), 0, 0, false);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal spent = BigDecimal.ZERO;
        int planned = 0;
        for (JsonNode item : dataset.path("items")) {
            if (!split.isEmpty() && !split.equals(item.path("split").asText())) {
                continue;
            }
            planned++;
            if (version.equals("v1") && spent.compareTo(settings.maxCostUsd()) >= 0) {
                continue;
            }
            Set<String> gold = new LinkedHashSet<>();
            for (JsonNode label : item.path("labels")) {
                gold.add(label.path("polarity").asText() + ":" + label.path("attribute").asText());
            }
            ModelOpinionExtractor.Extraction extraction = extractor.extract(item.path("text").asText());
            Set<String> predicted = new LinkedHashSet<>();
            for (ExtractedOpinion opinion : extraction.opinions()) {
                predicted.add(opinion.polarity() + ":" + opinion.attributeKey());
            }
            BigDecimal cost = extraction.inputTokens() + extraction.outputTokens() == 0 ? BigDecimal.ZERO
                    : pricing.cost(settings.chatModel(), extraction.inputTokens(), extraction.outputTokens());
            spent = spent.add(cost);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.path("id").asText());
            row.put("split", item.path("split").asText());
            row.put("text", item.path("text").asText());
            row.put("gold", String.join(";", gold));
            row.put("predicted", String.join(";", predicted));
            row.put("extractor", version.equals("v1") ? ModelOpinionExtractor.VERSION : "r2-rule-1");
            row.put("tokens_in", extraction.inputTokens());
            row.put("tokens_out", extraction.outputTokens());
            row.put("cost_usd", cost.toPlainString());
            row.put("fallback", extraction.fallback() ? 1 : 0);
            rows.add(row);
        }
        EvalOutput.writeCsv(EvalOutput.outDir().resolve("eval-opinions.csv"), rows);
        System.out.printf("opinions: extractor=%s split=%s items=%d/%d spent=$%s%n", version,
                split.isEmpty() ? "all" : split, rows.size(), planned, spent.toPlainString());
        assertThat(rows).as("items skipped by eval.maxCostUsd").hasSize(planned);
    }

    @FunctionalInterface
    private interface Extractor {
        ModelOpinionExtractor.Extraction extract(String text);
    }
}
