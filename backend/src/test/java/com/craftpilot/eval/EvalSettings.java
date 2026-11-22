package com.craftpilot.eval;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Evaluation run settings from system properties ({@code -Deval.*}) plus provider settings from environment variables
 * or the repository's {@code .env} (environment variables win). Live mode refuses to start without a chat key, an
 * embedding key, or a positive cost cap. Secrets are never included in {@link #describe()}.
 */
record EvalSettings(String mode, BigDecimal maxCostUsd, int repeats, int researchTokenBudget, long stubLatencyMs,
                    Set<String> caseFilter, String chatBaseUrl, String chatKey, String chatModel,
                    String embeddingsBaseUrl, String embeddingsKey) {

    static final String STUB = "stub";
    static final String LIVE = "live";

    boolean live() {
        return LIVE.equals(mode);
    }

    boolean includes(String caseId) {
        return caseFilter.isEmpty() || caseFilter.contains(caseId);
    }

    static EvalSettings fromEnvironment() {
        Map<String, String> dotEnv = loadDotEnv(Path.of("..").toAbsolutePath().normalize().resolve(".env"));
        return resolve(System::getProperty, name -> {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? dotEnv.get(name) : value;
        });
    }

    static EvalSettings resolve(Function<String, String> props, Function<String, String> env) {
        String mode = value(props.apply("eval.mode"), STUB).trim().toLowerCase();
        if (!mode.equals(STUB) && !mode.equals(LIVE)) {
            throw new IllegalStateException("eval.mode must be 'stub' or 'live', got '" + mode + "'");
        }
        BigDecimal cap;
        try {
            cap = new BigDecimal(value(props.apply("eval.maxCostUsd"), "0.50").trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("eval.maxCostUsd is not a number", ex);
        }
        if (cap.signum() <= 0) {
            throw new IllegalStateException("eval.maxCostUsd must be a positive USD amount (refusing to run without a cap)");
        }
        int repeats = Integer.parseInt(value(props.apply("eval.repeats"), "1").trim());
        int budget = Integer.parseInt(value(props.apply("eval.researchTokenBudget"), "24000").trim());
        long latency = Long.parseLong(value(props.apply("eval.stubLatencyMs"), "50").trim());
        Set<String> cases = new LinkedHashSet<>();
        Arrays.stream(value(props.apply("eval.cases"), "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).forEach(cases::add);

        if (!mode.equals(LIVE)) {
            return new EvalSettings(mode, cap, repeats, budget, latency, cases, null, null, "gpt-4o-mini", null, null);
        }
        String openaiKey = env.apply("CRAFTPILOT_OPENAI_API_KEY");
        String chatKey = first(env.apply("CRAFTPILOT_CHAT_API_KEY"), openaiKey);
        if (chatKey == null) {
            throw new IllegalStateException(
                    "eval.mode=live needs CRAFTPILOT_CHAT_API_KEY or CRAFTPILOT_OPENAI_API_KEY (env or .env)");
        }
        if (openaiKey == null) {
            throw new IllegalStateException("eval.mode=live needs CRAFTPILOT_OPENAI_API_KEY for embeddings (env or .env)");
        }
        String openaiBase = first(env.apply("CRAFTPILOT_OPENAI_BASE_URL"), "https://api.openai.com");
        String chatBase = first(env.apply("CRAFTPILOT_CHAT_BASE_URL"), openaiBase);
        String chatModel = first(env.apply("CRAFTPILOT_CHAT_MODEL"), "gpt-4o-mini");
        return new EvalSettings(mode, cap, repeats, budget, latency, cases, chatBase, chatKey, chatModel,
                openaiBase, openaiKey);
    }

    /** Safe to log and to write into eval-meta.json: no keys. */
    Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode);
        out.put("maxCostUsd", maxCostUsd);
        out.put("cases", caseFilter.isEmpty() ? "all" : String.join(",", caseFilter));
        if (live()) {
            out.put("chatBaseUrl", chatBaseUrl);
            out.put("chatKey", "CONFIGURED");
            out.put("embeddingsBaseUrl", embeddingsBaseUrl);
            out.put("embeddingsKey", "CONFIGURED");
        }
        return out;
    }

    static Map<String, String> loadDotEnv(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring(7).strip();
                }
                int eq = line.indexOf('=');
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                out.put(key, value);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read " + file, ex);
        }
        return out;
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String first(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.strip();
        }
        return b == null || b.isBlank() ? null : b.strip();
    }
}
