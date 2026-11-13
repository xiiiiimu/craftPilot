package com.craftpilot.runtime.model;

import com.craftpilot.common.ApiFailure;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ChatModelGateway {
    public record ToolCall(String providerCallId, String toolName, String argumentsJson, int ordinal) {}
    public record InferResult(UUID modelCallId, String finishReason, String content, List<ToolCall> toolCalls,
                              Integer inputTokens, Integer outputTokens, String providerRequestId,
                              String usageAccuracy, BigDecimal estimatedCostUsd) {}

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final JdbcClient jdbc;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxRetries;

    public ChatModelGateway(ObjectMapper mapper, JdbcClient jdbc,
                            @Value("${craftpilot.chat.base-url:https://api.openai.com}") String baseUrl,
                            @Value("${craftpilot.chat.api-key:}") String apiKey,
                            @Value("${craftpilot.chat.model:gpt-4o-mini}") String model,
                            @Value("${craftpilot.chat.max-retries:2}") int maxRetries) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxRetries = Math.max(0, maxRetries);
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(60))
                .build();
    }

    public String modelName() {
        return model;
    }

    public InferResult infer(UUID merchant, List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                             int maxTokens) {
        String requestHash = sha(model + "|" + safeJson(messages) + "|" + safeJson(tools));
        UUID callId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO model_call_attempt
                (merchant_id, id, provider, model_name, purpose, request_hash, status)
                VALUES (:m, :id, 'openai-compatible', :model, 'agent_chat', :hash, 'REQUESTED')
                """).param("m", merchant).param("id", callId).param("model", model).param("hash", requestHash)
                .update();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("tools", tools);
        body.put("tool_choice", "auto");
        body.put("temperature", 0.1);
        body.put("max_tokens", maxTokens);

        RuntimeException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return executeOnce(merchant, callId, body);
            } catch (ApiFailure ex) {
                if ("CHAT_RATE_LIMITED".equals(ex.code()) && attempt < maxRetries) {
                    sleep(100L << attempt);
                    last = ex;
                    continue;
                }
                throw ex;
            } catch (RuntimeException ex) {
                last = ex;
                if (attempt >= maxRetries) {
                    String detail = ex.getClass().getSimpleName();
                    if (detail.length() > 60) {
                        detail = detail.substring(0, 60);
                    }
                    try {
                        mark(merchant, callId, "FAILED", null, null, null, null, detail);
                    } catch (RuntimeException ignored) {
                        // keep original failure
                    }
                    throw ApiFailure.unavailable("CHAT_UNAVAILABLE", "Chat provider failed");
                }
                sleep(100L << attempt);
            }
        }
        throw last == null ? ApiFailure.unavailable("CHAT_UNAVAILABLE", "Chat provider failed") : last;
    }

    private InferResult executeOnce(UUID merchant, UUID callId, Map<String, Object> body) {
        try {
            String json = mapper.writeValueAsString(body);
            Request.Builder builder = new Request.Builder()
                    .url(chatUrl())
                    .post(RequestBody.create(json, MediaType.get("application/json")))
                    .header("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            try (var response = http.newCall(builder.build()).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                String providerId = response.header("x-request-id");
                if (response.code() == 429) {
                    mark(merchant, callId, "RATE_LIMITED", 429, providerId, null, null, "RATE_LIMITED");
                    throw ApiFailure.unavailable("CHAT_RATE_LIMITED", "Chat provider rate limited");
                }
                if (!response.isSuccessful()) {
                    String err = "HTTP_" + response.code();
                    if (response.code() == 400 && responseBody != null && !responseBody.isBlank()) {
                        String snippet = responseBody.replaceAll("\\s+", " ").trim();
                        if (snippet.length() > 40) {
                            snippet = snippet.substring(0, 40);
                        }
                        err = "HTTP_400:" + snippet;
                        if (err.length() > 60) {
                            err = err.substring(0, 60);
                        }
                    }
                    mark(merchant, callId, "FAILED", response.code(), providerId, null, null, err);
                    throw ApiFailure.unavailable("CHAT_UNAVAILABLE", "Chat provider failed");
                }
                JsonNode root = mapper.readTree(responseBody);
                if (providerId == null || providerId.isBlank()) {
                    providerId = text(root, "id");
                }
                JsonNode choice = root.path("choices").path(0);
                JsonNode message = choice.path("message");
                String content = text(message, "content");
                String finish = text(choice, "finish_reason");
                List<ToolCall> toolCalls = new ArrayList<>();
                JsonNode toolNodes = message.path("tool_calls");
                if (toolNodes.isArray()) {
                    int ordinal = 0;
                    for (JsonNode n : toolNodes) {
                        String id = text(n, "id");
                        String name = text(n.path("function"), "name");
                        String args = text(n.path("function"), "arguments");
                        if (id == null || name == null) {
                            mark(merchant, callId, "FAILED", 200, providerId, null, null, "BAD_TOOL_CALL");
                            throw ApiFailure.unavailable("CHAT_PROTOCOL_ERROR", "Malformed tool call");
                        }
                        toolCalls.add(new ToolCall(id, name, args == null ? "{}" : args, ordinal++));
                    }
                }
                Integer inTok = intOrNull(root.path("usage"), "prompt_tokens");
                Integer outTok = intOrNull(root.path("usage"), "completion_tokens");
                String accuracy = (inTok == null && outTok == null) ? "UNKNOWN" : "EXACT";
                BigDecimal cost = estimateCost(inTok, outTok);
                mark(merchant, callId, "SUCCEEDED", 200, providerId, inTok, outTok, null);
                return new InferResult(callId, finish, content, List.copyOf(toolCalls), inTok, outTok,
                        providerId, accuracy, cost);
            }
        } catch (ApiFailure ex) {
            throw ex;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String chatUrl() {
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + "/chat/completions";
        }
        return baseUrl + "/v1/chat/completions";
    }

    private void mark(UUID merchant, UUID id, String status, Integer http, String providerId,
                      Integer inTok, Integer outTok, String error) {
        jdbc.sql("""
                UPDATE model_call_attempt
                SET status = :status, http_status = :http, provider_request_id = :provider,
                    input_tokens = :tin, output_tokens = :tout, error_code = :error,
                    completed_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", id).param("status", status).param("http", http)
                .param("provider", providerId).param("tin", inTok).param("tout", outTok)
                .param("error", error).update();
    }

    private static BigDecimal estimateCost(Integer inTok, Integer outTok) {
        if (inTok == null && outTok == null) {
            return BigDecimal.valueOf(0.001);
        }
        double dollars = ((inTok == null ? 0 : inTok) * 0.00000015)
                + ((outTok == null ? 0 : outTok) * 0.0000006);
        return BigDecimal.valueOf(Math.max(dollars, 0.000001));
    }

    private String safeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isNumber() ? null : v.asInt();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
