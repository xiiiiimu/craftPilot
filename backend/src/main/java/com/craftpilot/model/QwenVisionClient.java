package com.craftpilot.model;

import com.craftpilot.common.ApiFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
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
public class QwenVisionClient {
    public record VisionModelResult(String rawContent, Integer inputTokens, Integer outputTokens,
                                    String providerRequestId) {}

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final JdbcClient jdbc;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public QwenVisionClient(ObjectMapper mapper, JdbcClient jdbc,
                            @Value("${craftpilot.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
                            String baseUrl,
                            @Value("${craftpilot.qwen.api-key:}") String apiKey,
                            @Value("${craftpilot.qwen.vision-model:qwen3-vl-plus}") String model) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(60))
                .build();
    }

    public String modelName() {
        return model;
    }

    public VisionModelResult compareImages(UUID merchant, String leftContentType, byte[] leftBytes,
                                           String rightContentType, byte[] rightBytes, String prompt)
            throws ApiFailure {
        UUID callId = UUID.randomUUID();
        String requestHash = sha(model + "|" + shaBytes(leftBytes) + "|" + shaBytes(rightBytes) + "|" + prompt);
        jdbc.sql("""
                INSERT INTO model_call_attempt
                (merchant_id, id, provider, model_name, purpose, request_hash, status)
                VALUES (:m, :id, 'qwen', :model, 'vision_compare', :hash, 'REQUESTED')
                """).param("m", merchant).param("id", callId).param("model", model)
                .param("hash", requestHash).update();
        try {
            String leftUri = "data:" + leftContentType + ";base64," + Base64.getEncoder().encodeToString(leftBytes);
            String rightUri = "data:" + rightContentType + ";base64," + Base64.getEncoder().encodeToString(rightBytes);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", 0.1);
            body.put("messages", List.of(Map.of(
                    "role", "user",
                    "content", List.of(
                            Map.of("type", "text", "text", prompt),
                            Map.of("type", "image_url", "image_url", Map.of("url", leftUri)),
                            Map.of("type", "image_url", "image_url", Map.of("url", rightUri))))));
            Request.Builder builder = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .post(RequestBody.create(mapper.writeValueAsString(body), MediaType.get("application/json")))
                    .header("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            try (var response = http.newCall(builder.build()).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                if (response.code() == 429) {
                    mark(merchant, callId, "RATE_LIMITED", 429, null, null, null, "RATE_LIMITED");
                    throw ApiFailure.unavailable("VISION_RATE_LIMITED", "Vision provider rate limited");
                }
                if (!response.isSuccessful()) {
                    mark(merchant, callId, "FAILED", response.code(), null, null, null, "HTTP_" + response.code());
                    throw ApiFailure.unavailable("VISION_UNAVAILABLE", "Vision provider failed");
                }
                JsonNode root = mapper.readTree(responseBody);
                String content = root.path("choices").path(0).path("message").path("content").asText(null);
                if (content == null || content.isBlank()) {
                    mark(merchant, callId, "FAILED", response.code(), null, null, null, "EMPTY_CONTENT");
                    throw ApiFailure.unavailable("VISION_UNAVAILABLE", "Vision provider returned empty content");
                }
                Integer inTokens = intOrNull(root.path("usage").path("prompt_tokens"));
                Integer outTokens = intOrNull(root.path("usage").path("completion_tokens"));
                String requestId = root.path("id").asText(null);
                mark(merchant, callId, "SUCCEEDED", response.code(), inTokens, outTokens, requestId, null);
                return new VisionModelResult(content, inTokens, outTokens, requestId);
            }
        } catch (ApiFailure failure) {
            throw failure;
        } catch (IOException ex) {
            mark(merchant, callId, "TIMEOUT", null, null, null, null, "IO_ERROR");
            throw ApiFailure.unavailable("VISION_UNAVAILABLE", "Vision provider unavailable");
        } catch (Exception ex) {
            mark(merchant, callId, "FAILED", null, null, null, null, "PROTOCOL_ERROR");
            throw ApiFailure.unavailable("VISION_UNAVAILABLE", "Vision provider protocol error");
        }
    }

    public UUID lastCallId(UUID merchant, String requestHashPrefixIgnored) {
        return jdbc.sql("""
                SELECT id FROM model_call_attempt
                WHERE merchant_id = :m AND purpose = 'vision_compare'
                ORDER BY created_at DESC LIMIT 1
                """).param("m", merchant).query(UUID.class).optional().orElse(null);
    }

    private void mark(UUID merchant, UUID callId, String status, Integer httpStatus, Integer inTokens,
                      Integer outTokens, String providerRequestId, String error) {
        jdbc.sql("""
                UPDATE model_call_attempt
                SET status = :status, http_status = :http, input_tokens = :inTok, output_tokens = :outTok,
                    provider_request_id = :req, error_code = :error, completed_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", callId).param("status", status).param("http", httpStatus)
                .param("inTok", inTokens).param("outTok", outTokens).param("req", providerRequestId)
                .param("error", error).update();
    }

    private static Integer intOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String shaBytes(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
