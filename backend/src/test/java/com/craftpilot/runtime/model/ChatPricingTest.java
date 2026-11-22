package com.craftpilot.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.craftpilot.runtime.model.ChatPricing.Price;
import com.craftpilot.runtime.model.ChatPricing.Properties;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatPricingTest {
    @Test
    void listedModelUsesItsOwnPrice() {
        ChatPricing pricing = new ChatPricing(new Properties(null,
                Map.of("qwen-plus", new Price(new BigDecimal("0.8"), new BigDecimal("2.0")))));

        assertThat(pricing.cost("qwen-plus", 1_000_000, 500_000)).isEqualByComparingTo("1.800000");
    }

    @Test
    void unlistedModelFallsBackToConfiguredFallback() {
        ChatPricing pricing = new ChatPricing(new Properties(
                new Price(new BigDecimal("1"), new BigDecimal("4")), Map.of()));

        assertThat(pricing.cost("unknown-model", 1000, 1000)).isEqualByComparingTo("0.005000");
    }

    @Test
    void withoutAnyConfigurationKeepsPreviousGpt4oMiniPrice() {
        ChatPricing pricing = new ChatPricing(new Properties(null, null));

        // 20 * 0.15/1M + 15 * 0.60/1M = 0.000012
        assertThat(pricing.cost("gpt-4o-mini", 20, 15)).isEqualByComparingTo("0.000012");
    }

    @Test
    void tinyCallsKeepTheOneMicroDollarFloor() {
        ChatPricing pricing = new ChatPricing(new Properties(null, null));

        assertThat(pricing.cost("gpt-4o-mini", 1, 0)).isEqualByComparingTo("0.000001");
    }
}
