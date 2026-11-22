package com.craftpilot.eval;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Offline stand-ins for the embedding, chat and MCP endpoints used by {@link EvalRunnerTest}.
 *
 * <p>The chat stub is a deliberately naive, deterministic "model" so the whole pipeline can run without spending
 * money. Turn 1 calls {@code evidence_search} on the authorized revision (and {@code mcp_material_lookup} when the case
 * has an MCP connector). Turn 2 counts positive / negative keywords in the returned evidence summaries, picks
 * PRODUCE / REVISE / HOLD with a fixed rule, and cites the returned evidence IDs plus any UUID that appears inside a
 * summary (so injected or forged IDs reach the evidence validator). Its recommendations carry no information about
 * real model quality. Usage is reported as serialized characters / 4.
 */
final class EvalStubServers {
    record McpSpec(String connectorId, String materialCode, String responseText) {}

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern AUTHORIZED = Pattern.compile("Authorized revisions: \\[([0-9a-f-]{36})");
    private static final String[] POSITIVE = {"喜欢", "好看", "舒服", "想买", "高级", "扎实", "精致", "软", "合适",
            "值", "下单", "整齐", "温柔", "显瘦", "显腿长"};
    private static final String[] NEGATIVE = {"硬", "紧", "偏小", "起球", "掉毛", "皱", "歪", "线头", "粗糙", "难看",
            "不喜欢", "不会买", "太贵", "偏暗", "土", "不显白", "断", "毛边", "扎人"};

    private final long latencyMs;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final AtomicReference<McpSpec> caseMcp = new AtomicReference<>();
    private HttpServer embeddings;
    private HttpServer chat;
    private HttpServer mcp;

    EvalStubServers(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    void start() throws IOException {
        embeddings = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        embeddings.createContext("/v1/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, "{\"data\":[{\"embedding\":" + vector() + "}],\"usage\":{\"total_tokens\":4}}");
        });
        embeddings.start();

        chat = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        chat.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            sleep(latencyMs);
            String response;
            try {
                response = chatResponse(request);
            } catch (RuntimeException ex) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("x-request-id", "req_eval_stub_" + UUID.randomUUID());
            respond(exchange, response);
        });
        chat.start();

        mcp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mcp.createContext("/mcp", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> result;
            if (request.contains("tools/list")) {
                result = Map.of("tools", List.of(Map.of(
                        "name", "lookup_material",
                        "description", "Look up a material by code",
                        "inputSchema", Map.of("type", "object", "required", List.of("materialCode"),
                                "properties", Map.of("materialCode", Map.of("type", "string")),
                                "additionalProperties", false))));
            } else {
                McpSpec spec = caseMcp.get();
                String text = spec == null ? "unknown material" : spec.responseText();
                result = Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", false);
            }
            respond(exchange, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "1", "result", result)));
        });
        mcp.start();
    }

    void stop() {
        for (HttpServer server : new HttpServer[] {embeddings, chat, mcp}) {
            if (server != null) {
                server.stop(0);
            }
        }
    }

    void setCaseMcp(McpSpec spec) {
        caseMcp.set(spec);
    }

    String embeddingsBaseUrl() {
        return "http://127.0.0.1:" + embeddings.getAddress().getPort();
    }

    String chatBaseUrl() {
        return "http://127.0.0.1:" + chat.getAddress().getPort();
    }

    String mcpEndpoint() {
        return "http://127.0.0.1:" + mcp.getAddress().getPort() + "/mcp";
    }

    private String chatResponse(String request) {
        JsonNode body = mapper.readTree(request);
        List<String> toolContents = new ArrayList<>();
        StringBuilder prompt = new StringBuilder();
        for (JsonNode message : body.path("messages")) {
            if ("tool".equals(message.path("role").asText())) {
                toolContents.add(message.path("content").asText(""));
            } else {
                prompt.append(message.path("content").asText("")).append('\n');
            }
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        String finish;
        if (toolContents.isEmpty()) {
            Matcher revision = AUTHORIZED.matcher(prompt);
            String revisionId = revision.find() ? revision.group(1) : "00000000-0000-0000-0000-000000000000";
            List<Map<String, Object>> calls = new ArrayList<>();
            calls.add(toolCall("call_search", "evidence_search", Map.of(
                    "revisionId", revisionId, "query", "反馈", "topK", 10)));
            McpSpec spec = caseMcp.get();
            if (spec != null) {
                calls.add(toolCall("call_material", "mcp_material_lookup", Map.of(
                        "materialCode", spec.materialCode(), "connectorId", spec.connectorId())));
            }
            message.put("content", null);
            message.put("tool_calls", calls);
            finish = "tool_calls";
        } else {
            message.put("content", mapper.writeValueAsString(decide(toolContents)));
            finish = "stop";
        }
        String completion = mapper.writeValueAsString(message);
        Map<String, Object> response = Map.of(
                "id", "chatcmpl-eval-" + UUID.randomUUID(),
                "choices", List.of(Map.of("finish_reason", finish, "message", message)),
                "usage", Map.of("prompt_tokens", Math.max(1, request.length() / 4),
                        "completion_tokens", Math.max(1, completion.length() / 4)));
        return mapper.writeValueAsString(response);
    }

    private Map<String, Object> decide(List<String> toolContents) {
        Set<String> cited = new LinkedHashSet<>();
        int positive = 0;
        int negative = 0;
        int hits = 0;
        for (String content : toolContents) {
            JsonNode result;
            try {
                result = mapper.readTree(content);
            } catch (RuntimeException ex) {
                continue;
            }
            for (JsonNode hit : result.path("hits")) {
                hits++;
                String summary = hit.path("summary").asText("");
                if (cited.size() < 5) {
                    cited.add(hit.path("evidenceId").asText());
                }
                Matcher embedded = UUID_PATTERN.matcher(summary);
                while (embedded.find()) {
                    cited.add(embedded.group());
                }
                if (containsAny(summary, NEGATIVE)) {
                    negative++;
                } else if (containsAny(summary, POSITIVE)) {
                    positive++;
                }
            }
        }
        String recommendation;
        if (hits < 3) {
            recommendation = "HOLD";
        } else if (negative >= 2 && positive >= 1) {
            recommendation = "REVISE";
        } else if (positive >= 3 && negative == 0) {
            recommendation = "PRODUCE";
        } else {
            recommendation = "HOLD";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recommendation", recommendation);
        out.put("summary", "stub: positive=" + positive + " negative=" + negative + " hits=" + hits);
        out.put("evidenceIds", List.copyOf(cited));
        return out;
    }

    private Map<String, Object> toolCall(String id, String name, Map<String, Object> args) {
        return Map.of("id", id, "type", "function",
                "function", Map.of("name", name, "arguments", mapper.writeValueAsString(args)));
    }

    private static boolean containsAny(String text, String[] tokens) {
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String vector() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 1536; i++) {
            sb.append(i == 0 ? "" : ",").append(String.format(java.util.Locale.ROOT, "%.4f", 0.3f + (i % 7) * 0.0001f));
        }
        return sb.append(']').toString();
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
