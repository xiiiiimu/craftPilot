package com.craftpilot.eval;

import com.craftpilot.runtime.model.ChatPricing;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Per-round spend guard for the evaluation runner.
 *
 * <p>The per-run ceiling is the most a single research run can cost under its token budget: at most
 * {@code maxTurns} turns of {@code maxOutputTokens} output, the rest of the budget spent on input, priced with the
 * configured model price. Before starting, the worst case (runs x ceiling) must fit under the cap. While running, a
 * new run starts only if spent + ceiling still fits, so the cap is never exceeded by more than the runtime's own
 * per-run estimation error.
 */
final class EvalBudget {
    private final BigDecimal cap;
    private final BigDecimal perRunCeiling;

    EvalBudget(BigDecimal cap, BigDecimal perRunCeiling) {
        this.cap = cap;
        this.perRunCeiling = perRunCeiling;
    }

    static BigDecimal perRunCeiling(ChatPricing pricing, String model, int tokenBudget, int maxTurns,
                                    int maxOutputTokens) {
        int output = Math.min(tokenBudget, maxTurns * maxOutputTokens);
        int input = Math.max(0, tokenBudget - output);
        return pricing.cost(model, input, output);
    }

    BigDecimal cap() {
        return cap;
    }

    BigDecimal perRunCeiling() {
        return perRunCeiling;
    }

    BigDecimal worstCase(int runs) {
        return perRunCeiling.multiply(BigDecimal.valueOf(runs));
    }

    /** Throws when the planned runs could exceed the cap, saying how many would fit. */
    void preflight(int plannedRuns) {
        BigDecimal worst = worstCase(plannedRuns);
        if (worst.compareTo(cap) > 0) {
            int fits = perRunCeiling.signum() == 0 ? plannedRuns
                    : cap.divide(perRunCeiling, 0, RoundingMode.FLOOR).intValue();
            throw new IllegalStateException("worst-case cost $" + worst.toPlainString() + " for " + plannedRuns
                    + " runs exceeds eval.maxCostUsd=$" + cap.toPlainString() + " (per-run ceiling $"
                    + perRunCeiling.toPlainString() + "; " + fits + " runs would fit). Raise the cap, or narrow "
                    + "eval.cases / eval.repeats.");
        }
    }

    /** True when another run could push total spend past the cap. */
    boolean mustStop(BigDecimal spent) {
        return spent.add(perRunCeiling).compareTo(cap) > 0;
    }
}
