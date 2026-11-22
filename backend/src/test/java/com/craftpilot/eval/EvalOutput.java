package com.craftpilot.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Output directory, CSV writing and environment metadata shared by the evaluation tests. */
final class EvalOutput {
    private EvalOutput() {
    }

    static Path outDir() throws IOException {
        Path dir = Path.of(System.getProperty("eval.outDir", "target/eval/latest")).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        return dir;
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
