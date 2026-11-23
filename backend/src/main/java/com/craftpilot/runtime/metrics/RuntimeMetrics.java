package com.craftpilot.runtime.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Micrometer timers for the agent runtime. The same durations are persisted on {@code agent_turn} and
 * {@code tool_invocation} so evaluation can compute per-run figures from the database.
 *
 * <ul>
 *   <li>{@code craftpilot.agent.model.latency} (loop, outcome): one chat inference, including gateway retries.</li>
 *   <li>{@code craftpilot.agent.tool.latency} (tool, outcome): one tool execution inside the broker.</li>
 *   <li>{@code craftpilot.agent.context.build} (loop): building, estimating and persisting a turn's context.</li>
 *   <li>{@code craftpilot.agent.final.parse} (loop, outcome): final answers that parsed as JSON vs. fell back.</li>
 * </ul>
 */
@Component
public class RuntimeMetrics {
    private final MeterRegistry registry;

    public RuntimeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void modelCall(String loop, boolean success, long nanos) {
        Timer.builder("craftpilot.agent.model.latency")
                .tag("loop", loop).tag("outcome", success ? "success" : "error")
                .register(registry).record(Duration.ofNanos(nanos));
    }

    public void toolCall(String tool, boolean success, long nanos) {
        Timer.builder("craftpilot.agent.tool.latency")
                .tag("tool", tool).tag("outcome", success ? "success" : "error")
                .register(registry).record(Duration.ofNanos(nanos));
    }

    public void contextBuild(String loop, long nanos) {
        Timer.builder("craftpilot.agent.context.build")
                .tag("loop", loop)
                .register(registry).record(Duration.ofNanos(nanos));
    }

    public void finalParse(String loop, boolean parsed) {
        registry.counter("craftpilot.agent.final.parse", "loop", loop, "outcome", parsed ? "parsed" : "fallback")
                .increment();
    }

    public static int millis(long nanos) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, nanos / 1_000_000));
    }
}
