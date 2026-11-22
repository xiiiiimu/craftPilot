package com.craftpilot.runtime.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Per-model chat pricing (USD per 1M tokens) from {@code craftpilot.chat.pricing}. Settle and reservation both price
 * through {@link #cost(String, int, int)}, so they always agree. A model without an entry falls back to
 * {@code craftpilot.chat.pricing.fallback} and logs a warning once at startup for the configured model.
 */
@Component
public class ChatPricing {
    private static final Logger LOG = LoggerFactory.getLogger(ChatPricing.class);
    private static final BigDecimal MIN_COST = new BigDecimal("0.000001");

    public record Price(BigDecimal inputUsdPerMtok, BigDecimal outputUsdPerMtok) {}

    public record Properties(Price fallback, Map<String, Price> models) {}

    private final Price fallback;
    private final Map<String, Price> models;

    @Autowired
    public ChatPricing(Environment env) {
        this(Binder.get(env).bind("craftpilot.chat.pricing", Bindable.of(Properties.class))
                .orElse(new Properties(null, Map.of())));
        String configured = env.getProperty("craftpilot.chat.model", "gpt-4o-mini");
        Price price = priceFor(configured);
        if (!models.containsKey(configured)) {
            LOG.warn("chat_pricing_fallback model={} inputUsdPerMtok={} outputUsdPerMtok={}",
                    configured, price.inputUsdPerMtok(), price.outputUsdPerMtok());
        } else {
            LOG.info("chat_pricing model={} inputUsdPerMtok={} outputUsdPerMtok={}",
                    configured, price.inputUsdPerMtok(), price.outputUsdPerMtok());
        }
    }

    public ChatPricing(Properties properties) {
        // Built-in fallback is the gpt-4o-mini list price that was previously hard-coded.
        this.fallback = properties.fallback() != null ? properties.fallback()
                : new Price(new BigDecimal("0.15"), new BigDecimal("0.60"));
        this.models = properties.models() == null ? Map.of() : Map.copyOf(properties.models());
    }

    public Price priceFor(String model) {
        return models.getOrDefault(model, fallback);
    }

    public BigDecimal cost(String model, int inputTokens, int outputTokens) {
        Price price = priceFor(model);
        BigDecimal dollars = price.inputUsdPerMtok().multiply(BigDecimal.valueOf(Math.max(0, inputTokens)))
                .add(price.outputUsdPerMtok().multiply(BigDecimal.valueOf(Math.max(0, outputTokens))))
                .movePointLeft(6)
                .setScale(6, RoundingMode.HALF_UP);
        return dollars.max(MIN_COST);
    }
}
