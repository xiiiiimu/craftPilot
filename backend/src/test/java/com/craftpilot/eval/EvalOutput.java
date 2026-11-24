package com.craftpilot.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Output directory, CSV writing and environment metadata shared by the evaluation tests. */
final class EvalOutput {
    private EvalOutput() {
    }

    /**
     * Items of one dataset: {@code eval/<name>-<version>.json} plus, when it exists, the separately generated
     * {@code eval/<name>-<version>-test.json}, filtered by {@code -Deval.split=dev|test} (default all). With
     * {@code eval.split=dev} the test file is not opened at all.
     */
    static List<JsonNode> datasetItems(ObjectMapper mapper, String name, String arrayField) throws IOException {
        String split = split();
        String version = datasetVersion();
        List<JsonNode> out = new ArrayList<>();
        List<String> resources = new ArrayList<>(List.of("eval/" + name + "-" + version + ".json"));
        if (!split.equals("dev")) {
            resources.add("eval/" + name + "-" + version + "-test.json");
        }
        for (int i = 0; i < resources.size(); i++) {
            try (InputStream in = EvalOutput.class.getClassLoader().getResourceAsStream(resources.get(i))) {
                if (in == null) {
                    if (i == 0) {
                        throw new IllegalStateException("missing dataset " + resources.get(i));
                    }
                    continue;
                }
                for (JsonNode item : mapper.readTree(in).path(arrayField)) {
                    if (split.isEmpty() || split.equals(item.path("split").asText())) {
                        out.add(item);
                    }
                }
            }
        }
        return out;
    }

    /** {@code -Deval.split}: empty (all), {@code dev} or {@code test}. */
    static String split() {
        String split = System.getProperty("eval.split", "").trim();
        if (!split.isEmpty() && !split.equals("dev") && !split.equals("test")) {
            throw new IllegalStateException("eval.split must be dev or test, got '" + split + "'");
        }
        return split;
    }

    static Path outDir() throws IOException {
        Path dir = Path.of(System.getProperty("eval.outDir", "target/eval/latest")).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        return dir;
    }

    /** Dataset version from {@code -Deval.dataset} (default {@code v1}); selects {@code eval/*-<version>.json}. */
    static String datasetVersion() {
        String version = System.getProperty("eval.dataset", "v1").trim();
        if (!version.matches("v\\d+")) {
            throw new IllegalStateException("eval.dataset must look like v1, v2, ... got '" + version + "'");
        }
        return version;
    }

    static void writeCsv(Path file, List<Map<String, Object>> rows) throws IOException {
        StringBuilder out = new StringBuilder();
        if (!rows.isEmpty()) {
            List<String> header = new ArrayList<>(rows.get(0).keySet());
            out.append(String.join(",", header)).append('\n');
            for (Map<String, Object> row : rows) {
                List<String> cells = new ArrayList<>();
                for (String column : header) {
                    cells.add(escape(row.get(column)));
                }
                out.append(String.join(",", cells)).append('\n');
            }
        }
        Files.writeString(file, out, StandardCharsets.UTF_8);
    }

    /**
     * The {@code cited_messages} cell: each cited evidence id mapped back to the dataset message key it was extracted
     * from, in citation order and once per message. Evidence that is not a message opinion keeps its source type
     * ({@code otherSources}); anything else (outside the survey, not a UUID) is {@code invalid}. Joined with ';'.
     */
    static String citedMessages(List<UUID> cited, Map<String, List<UUID>> evidenceByMessage,
                                Map<UUID, String> otherSources) {
        Map<UUID, String> messageOf = new LinkedHashMap<>();
        evidenceByMessage.forEach((key, ids) -> ids.forEach(id -> messageOf.putIfAbsent(id, key)));
        Set<String> labels = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (UUID id : cited) {
            String message = messageOf.get(id);
            if (message != null) {
                if (labels.add(message)) {
                    out.add(message);
                }
            } else {
                out.add(otherSources.getOrDefault(id, "invalid"));
            }
        }
        return String.join(";", out);
    }

    /** The {@code final_summary} cell: the answer's summary on one line (CSV quoting handles commas and quotes). */
    static String summaryCell(String summary) {
        return summary == null ? "" : summary.replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
    }

    static final int RAW_FINAL_MAX_CHARS = 4000;

    /**
     * The {@code raw_final_content} cell: the model's last response exactly as returned (line breaks kept, CSV quoting
     * handles them), cut to {@value #RAW_FINAL_MAX_CHARS} characters without splitting a surrogate pair.
     */
    static String rawFinalCell(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= RAW_FINAL_MAX_CHARS) {
            return content;
        }
        int end = RAW_FINAL_MAX_CHARS;
        if (Character.isHighSurrogate(content.charAt(end - 1))) {
            end--;
        }
        return content.substring(0, end);
    }

    /** One compact JSON object per line (eval-contexts.jsonl). */
    static void writeJsonl(Path file, List<? extends Map<String, Object>> records, ObjectMapper mapper)
            throws IOException {
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> record : records) {
            out.append(mapper.writeValueAsString(record)).append('\n');
        }
        Files.writeString(file, out, StandardCharsets.UTF_8);
    }

    static Map<String, Object> gitDescribe() {
        Map<String, Object> git = new LinkedHashMap<>();
        git.put("commit", run("git", "rev-parse", "--short", "HEAD"));
        String status = run("git", "status", "--porcelain", "--untracked-files=no");
        git.put("dirty", !status.isEmpty() && !"unknown".equals(status));
        return git;
    }

    static Map<String, Object> machine() {
        Map<String, Object> machine = new LinkedHashMap<>();
        machine.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        machine.put("arch", System.getProperty("os.arch"));
        machine.put("cpus", Runtime.getRuntime().availableProcessors());
        machine.put("maxHeapMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        machine.put("java", System.getProperty("java.version"));
        return machine;
    }

    private static String escape(Object value) {
        String text = value == null ? "" : value.toString();
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static String run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return "unknown";
            }
            return output;
        } catch (IOException ex) {
            return "unknown";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }
}
