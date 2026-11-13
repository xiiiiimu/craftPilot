package com.craftpilot.model;

import com.craftpilot.common.ApiFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
public class EmbeddingClient {
    public static final int DIMENSION = 1536;

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final JdbcClient jdbc;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public EmbeddingClient(ObjectMapper mapper, JdbcClient jdbc,
                           @Value("${craftpilot.openai.base-url:https://api.openai.com}") String baseUrl,
                           @Value("${craftpilot.openai.api-key:}") String apiKey,
                           @Value("${craftpilot.openai.embedding-model:text-embedding-3-small}") String model) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }

    public float[] embed(UUID merchant, String text) {
        String normalized = text == null ? "" : text.trim();
        String requestHash = sha(model + "|" + normalized);
        UUID callId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO model_call_attempt
                (merchant_id, id, provider, model_name, purpose, request_hash, status)
                VALUES (:m, :id, 'openai', :model, 'embedding', :hash, 'REQUESTED')
                """).param("m", merchant).param("id", callId).param("model", model)
                .param("hash", requestHash).update();
        try {
            String body = mapper.writeValueAsString(java.util.Map.of(
                    "model", model,
                    "input", normalized));
            Request.Builder builder = new Request.Builder()
                    .url(baseUrl + "/v1/embeddings")
                    .post(RequestBody.create(body, MediaType.get("application/json")))
                    .header("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            try (var response = http.newCall(builder.build()).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                if (response.code() == 429) {
                    mark(merchant, callId, "RATE_LIMITED", 429, null, null, null, "RATE_LIMITED");
                    throw ApiFailure.unavailable("EMBEDDING_RATE_LIMITED", "Embedding provider rate limited");
                }
                if (!response.isSuccessful()) {
                    mark(merchant, callId, "FAILED", response.code(), null, null, null, "HTTP_" + response.code());
                    throw ApiFailure.unavailable("EMBEDDING_UNAVAILABLE", "Embedding provider failed");
                }
                JsonNode root = mapper.readTree(responseBody);
                JsonNode vectorNode = root.path("data").path(0).path("embedding");
                if (!vectorNode.isArray() || vectorNode.size() != DIMENSION) {
                    mark(merchant, callId, "FAILED", response.code(), null, null, null, "DIMENSION_MISMATCH");
                    throw ApiFailure.conflict("EMBEDDING_DIMENSION_MISMATCH",
                            "Embedding dimension must be " + DIMENSION);
                }
                float[] vector = new float[DIMENSION];
                for (int i = 0; i < DIMENSION; i++) {
                    vector[i] = (float) vectorNode.get(i).asDouble();
                }
                Integer tokens = root.path("usage").path("total_tokens").isMissingNode()
                        ? null : root.path("usage").path("total_tokens").asInt();
                String requestId = root.path("id").asText(null);
                if (requestId == null || requestId.isBlank()) {
                    requestId = response.header("x-request-id");
                }
                mark(merchant, callId, "SUCCEEDED", response.code(), tokens, null, requestId, null);
                return vector;
            }
        } catch (ApiFailure failure) {
            throw failure;
        } catch (IOException ex) {
            mark(merchant, callId, "TIMEOUT", null, null, null, null, "IO_ERROR");
            throw ApiFailure.unavailable("EMBEDDING_UNAVAILABLE", "Embedding provider unavailable");
        } catch (Exception ex) {
            mark(merchant, callId, "FAILED", null, null, null, null, "PROTOCOL_ERROR");
            throw ApiFailure.unavailable("EMBEDDING_UNAVAILABLE", "Embedding provider protocol error");
        }
    }

    public String vectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    public String embeddingHash(float[] vector) {
        StringBuilder sb = new StringBuilder();
        for (float value : vector) {
            sb.append(Float.floatToIntBits(value)).append(',');
        }
        return sha(sb.toString());
    }

    private void mark(UUID merchant, UUID callId, String status, Integer httpStatus, Integer tokens,
                      Double cost, String providerRequestId, String error) {
        jdbc.sql("""
                UPDATE model_call_attempt
                SET status = :status, http_status = :http, input_tokens = :tokens,
                    estimated_cost_usd = :cost, provider_request_id = :req,
                    error_code = :error, completed_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", callId).param("status", status)
                .param("http", httpStatus).param("tokens", tokens).param("cost", cost)
                .param("req", providerRequestId).param("error", error)
                .update();
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
