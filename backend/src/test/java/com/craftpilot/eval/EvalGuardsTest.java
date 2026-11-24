package com.craftpilot.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.craftpilot.runtime.model.ChatPricing;
import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Runs in the default build: the guards that keep a live evaluation from spending without a key or a cap. */
class EvalGuardsTest {
    private static final ChatPricing GPT4O_MINI = new ChatPricing(new ChatPricing.Properties(null, null));

    @Test
    void stubIsTheDefaultAndNeedsNoKeys() {
        EvalSettings settings = EvalSettings.resolve(props(Map.of()), env(Map.of()));

        assertThat(settings.live()).isFalse();
        assertThat(settings.maxCostUsd()).isEqualByComparingTo("0.50");
        assertThat(settings.caseFilter()).isEmpty();
    }

    @Test
    void liveRefusesWithoutChatKey() {
        assertThatThrownBy(() -> EvalSettings.resolve(props(Map.of("eval.mode", "live")), env(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRAFTPILOT_CHAT_API_KEY or CRAFTPILOT_OPENAI_API_KEY");
    }

    @Test
    void liveRefusesWithoutEmbeddingKey() {
        assertThatThrownBy(() -> EvalSettings.resolve(props(Map.of("eval.mode", "live")),
                env(Map.of("CRAFTPILOT_CHAT_API_KEY", "chat-only"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embeddings");
    }

    @Test
    void refusesWithoutPositiveCap() {
        for (String cap : new String[] {"0", "-1", "abc"}) {
            assertThatThrownBy(() -> EvalSettings.resolve(
                    props(Map.of("eval.mode", "live", "eval.maxCostUsd", cap)),
                    env(Map.of("CRAFTPILOT_OPENAI_API_KEY", "k"))))
                    .as(cap).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("eval.maxCostUsd");
        }
    }

    @Test
    void liveUsesOpenAiKeyForChatWhenNoChatKeyAndHidesSecrets() {
        EvalSettings settings = EvalSettings.resolve(props(Map.of("eval.mode", "live", "eval.cases", "C01, A03")),
                env(Map.of("CRAFTPILOT_OPENAI_API_KEY", "sk-secret")));

        assertThat(settings.chatKey()).isEqualTo("sk-secret");
        assertThat(settings.chatBaseUrl()).isEqualTo("https://api.openai.com");
        assertThat(settings.chatModel()).isEqualTo("gpt-4o-mini");
        assertThat(settings.includes("C01")).isTrue();
        assertThat(settings.includes("C02")).isFalse();
        assertThat(settings.describe().toString()).doesNotContain("sk-secret");
    }

    @Test
    void perRunCeilingSpendsWholeBudgetWithMaximumOutput() {
        // 24000 budget, 8 turns x 800 output: 17600 in + 6400 out at $0.15 / $0.60 per 1M = 0.00264 + 0.00384
        assertThat(EvalBudget.perRunCeiling(GPT4O_MINI, "gpt-4o-mini", 24000, 8, 800))
                .isEqualByComparingTo("0.006480");
    }

    @Test
    void preflightRejectsPlansWhoseWorstCaseExceedsCap() {
        EvalBudget budget = new EvalBudget(new BigDecimal("0.10"), new BigDecimal("0.006480"));

        assertThatThrownBy(() -> budget.preflight(45))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("15 runs would fit");
        budget.preflight(15);
    }

    @Test
    void stopsBeforeARunThatCouldCrossTheCap() {
        EvalBudget budget = new EvalBudget(new BigDecimal("0.50"), new BigDecimal("0.006480"));

        assertThat(budget.mustStop(new BigDecimal("0.49"))).isFalse();
        assertThat(budget.mustStop(new BigDecimal("0.4936"))).isTrue();
    }

    @Test
    void dotEnvParserHandlesQuotesCommentsAndExport() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("eval", ".env");
        java.nio.file.Files.writeString(file, "# comment\nexport A=\"x y\"\nB='z'\nC=plain\n\nNOEQUALS\n");

        assertThat(EvalSettings.loadDotEnv(file)).containsExactly(
                Map.entry("A", "x y"), Map.entry("B", "z"), Map.entry("C", "plain"));
        java.nio.file.Files.delete(file);
    }

    @Test
    void repeatsForOverridesTheRepeatCountOfSelectedCases() {
        EvalSettings settings = EvalSettings.resolve(
                props(Map.of("eval.repeats", "3", "eval.repeatsFor", "C04:5, C32:5 ,C35:5")), env(Map.of()));

        assertThat(settings.repeatsFor("C04")).isEqualTo(5);
        assertThat(settings.repeatsFor("C35")).isEqualTo(5);
        assertThat(settings.repeatsFor("C01")).isEqualTo(3);
        assertThat(settings.describe()).containsEntry("repeatsFor", "C04:5,C32:5,C35:5");
        assertThat(EvalSettings.resolve(props(Map.of()), env(Map.of())).repeatsFor("C04")).isEqualTo(1);
    }

    @Test
    void repeatsForRejectsMalformedEntries() {
        for (String bad : new String[] {"C04", "C04:x", "C04:0", ":5"}) {
            assertThatThrownBy(() -> EvalSettings.resolve(props(Map.of("eval.repeatsFor", bad)), env(Map.of())))
                    .as(bad).isInstanceOf(IllegalStateException.class).hasMessageContaining("eval.repeatsFor");
        }
    }

    private static Function<String, String> props(Map<String, String> values) {
        return values::get;
    }

    private static Function<String, String> env(Map<String, String> values) {
        return values::get;
    }
}
