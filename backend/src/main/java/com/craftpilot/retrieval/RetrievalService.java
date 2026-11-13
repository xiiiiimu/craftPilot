package com.craftpilot.retrieval;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.evidence.EvidenceService;
import com.craftpilot.model.EmbeddingClient;
import com.craftpilot.tenant.TenantAccess;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class RetrievalService {
    public record Hit(UUID evidenceId, UUID chunkId, int chunkOrdinal, double score, String mode, String summary) {}
    public record SearchResult(String retrievalMode, List<Hit> hits) {}

    /**
     * Unsafe lexical filter used only to document the PostgreSQL null-parameter type-inference failure.
     * Production search must never call this; use {@link #search} instead.
     */
    public static final String UNSAFE_NULL_PARAM_LEXICAL_SQL = """
            SELECT c.id
            FROM evidence_chunk c
            JOIN evidence_ref e ON e.merchant_id = c.merchant_id AND e.id = c.evidence_id
            WHERE c.merchant_id = :m AND c.survey_id = :survey AND c.revision_id = :revision
              AND c.visibility = 'ACTIVE' AND e.visibility = 'ACTIVE'
              AND (:watermark IS NULL OR e.seq <= :watermark)
              AND (
                (:material IS NOT NULL AND c.material_code = :material)
                OR position(:norm in c.content_norm) > 0
              )
            LIMIT :limit
            """;

    private final JdbcClient jdbc;
    private final TenantAccess tenant;
    private final EmbeddingClient embedding;
    private final ObjectMapper mapper;

    public RetrievalService(JdbcClient jdbc, TenantAccess tenant, EmbeddingClient embedding, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.embedding = embedding;
        this.mapper = mapper;
    }

    public SearchResult search(UUID actor, UUID merchant, UUID surveyId, UUID revisionId, String query, int topK) {
        UUID groupId = jdbc.sql("SELECT group_id FROM survey_session WHERE merchant_id = :m AND id = :id")
                .param("m", merchant).param("id", surveyId).query(UUID.class)
                .optional().orElseThrow(ApiFailure::notFound);
        tenant.requireGroupMember(actor, merchant, groupId);
        jdbc.sql("""
                SELECT 1 FROM survey_revision
                WHERE merchant_id = :m AND survey_id = :survey AND revision_id = :revision
                """).param("m", merchant).param("survey", surveyId).param("revision", revisionId)
                .query(Integer.class).optional().orElseThrow(ApiFailure::notFound);
        if (query == null || query.isBlank()) {
            throw ApiFailure.invalid("query is required");
        }
        int k = Math.min(Math.max(topK, 1), 50);
        String norm = EvidenceService.normalize(query);
        String material = EvidenceService.extractMaterial(query);

        Long watermark = jdbc.sql("""
                SELECT evidence_watermark FROM survey_session WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", surveyId).query(Long.class).optional().orElse(null);

        List<Hit> lexical = lexicalHits(merchant, surveyId, revisionId, norm, material, watermark, k);

        List<Hit> vectorHits = List.of();
        String mode = lexical.isEmpty() ? "empty" : "lexical";
        try {
            ensureAuthorizedEmbeddings(merchant, surveyId, revisionId, watermark);
            float[] vector = embedding.embed(merchant, norm);
            String literal = embedding.vectorLiteral(vector);
            vectorHits = vectorHits(merchant, surveyId, revisionId, literal, watermark, k);
            if (!vectorHits.isEmpty() && !lexical.isEmpty()) {
                mode = "hybrid";
            } else if (!vectorHits.isEmpty()) {
                mode = "vector";
            } else if (!lexical.isEmpty()) {
                mode = "lexical";
            }
        } catch (RuntimeException ex) {
            mode = lexical.isEmpty() ? "empty_degraded" : "lexical_degraded";
            vectorHits = List.of();
        }

        List<Hit> fused;
        if ("hybrid".equals(mode)) {
            fused = rrf(lexical, vectorHits, k);
        } else if ("vector".equals(mode)) {
            fused = vectorHits.stream().limit(k).toList();
        } else {
            fused = lexical.stream().limit(k).toList();
        }
        try {
            String json = mapper.writeValueAsString(fused);
            UUID executionId = UUID.randomUUID();
            if (watermark == null) {
                jdbc.sql("""
                        INSERT INTO retrieval_execution
                        (merchant_id, id, survey_id, revision_id, query_text, retrieval_mode, profile_version,
                         top_k, result_json, evidence_watermark)
                        VALUES (:m, :id, :survey, :revision, :query, :mode, 'r2-rrf-60', :topK,
                                CAST(:json AS jsonb), NULL)
                        """).param("m", merchant).param("id", executionId).param("survey", surveyId)
                        .param("revision", revisionId).param("query", query).param("mode", mode)
                        .param("topK", k).param("json", json).update();
            } else {
                jdbc.sql("""
                        INSERT INTO retrieval_execution
                        (merchant_id, id, survey_id, revision_id, query_text, retrieval_mode, profile_version,
                         top_k, result_json, evidence_watermark)
                        VALUES (:m, :id, :survey, :revision, :query, :mode, 'r2-rrf-60', :topK,
                                CAST(:json AS jsonb), CAST(:watermark AS bigint))
                        """).param("m", merchant).param("id", executionId).param("survey", surveyId)
                        .param("revision", revisionId).param("query", query).param("mode", mode)
                        .param("topK", k).param("json", json).param("watermark", watermark).update();
            }
        } catch (Exception ignored) {
            // audit best-effort
        }
        return new SearchResult(mode, fused);
    }

    private List<Hit> lexicalHits(UUID merchant, UUID surveyId, UUID revisionId, String norm, String material,
                                  Long watermark, int limit) {
        // Branch on nullability so JDBC never sends untyped NULL for watermark (bigint) or material (varchar(80)).
        String watermarkClause = watermark == null
                ? ""
                : " AND e.seq <= CAST(:watermark AS bigint)";
        String materialScore = material == null
                ? """
                       CASE
                         WHEN position(CAST(:norm AS varchar) in c.content_norm) > 0 THEN 0.8
                         ELSE 0.2
                       END AS score
                  """
                : """
                       CASE
                         WHEN c.material_code = CAST(:material AS varchar(80)) THEN 1.0
                         WHEN position(CAST(:norm AS varchar) in c.content_norm) > 0 THEN 0.8
                         ELSE 0.2
                       END AS score
                  """;
        String matchClause = material == null
                ? " AND position(CAST(:norm AS varchar) in c.content_norm) > 0"
                : """
                   AND (
                     c.material_code = CAST(:material AS varchar(80))
                     OR position(CAST(:norm AS varchar) in c.content_norm) > 0
                   )
                  """;
        // Explicit "\\n" separators: text-block openers drop the first newline after """, which would
        // otherwise glue fragments like "> 0" + "ORDER" into invalid SQL ("> 0ORDER").
        String sql = """
                SELECT c.id AS chunk_id, c.evidence_id, c.chunk_ordinal, e.summary, e.seq,
                """ + materialScore + "\n" + """
                FROM evidence_chunk c
                JOIN evidence_ref e ON e.merchant_id = c.merchant_id AND e.id = c.evidence_id
                WHERE c.merchant_id = :m AND c.survey_id = :survey AND c.revision_id = :revision
                  AND c.visibility = 'ACTIVE' AND e.visibility = 'ACTIVE'
                """ + watermarkClause + matchClause + "\n" + """
                ORDER BY score DESC, e.id, c.chunk_ordinal
                LIMIT :limit
                """;
        StatementSpec spec = jdbc.sql(sql)
                .param("m", merchant).param("survey", surveyId).param("revision", revisionId)
                .param("norm", norm).param("limit", limit);
        if (watermark != null) {
            spec = spec.param("watermark", watermark);
        }
        if (material != null) {
            spec = spec.param("material", material);
        }
        return spec.query((rs, row) -> new Hit(
                rs.getObject("evidence_id", UUID.class),
                rs.getObject("chunk_id", UUID.class),
                rs.getInt("chunk_ordinal"),
                rs.getDouble("score"),
                "lexical",
                rs.getString("summary")))
                .list();
    }

    private List<Hit> vectorHits(UUID merchant, UUID surveyId, UUID revisionId, String literal, Long watermark,
                                 int limit) {
        String watermarkClause = watermark == null
                ? ""
                : " AND e.seq <= CAST(:watermark AS bigint)";
        String sql = """
                SELECT c.id AS chunk_id, c.evidence_id, c.chunk_ordinal, e.summary,
                       (1 - (c.embedding <=> CAST(CAST(:vec AS varchar) AS public.vector))) AS score
                FROM evidence_chunk c
                JOIN evidence_ref e ON e.merchant_id = c.merchant_id AND e.id = c.evidence_id
                WHERE c.merchant_id = :m AND c.survey_id = :survey AND c.revision_id = :revision
                  AND c.visibility = 'ACTIVE' AND e.visibility = 'ACTIVE'
                  AND c.embedding IS NOT NULL AND c.embedding_dim = 1536
                """ + watermarkClause + "\n" + """
                ORDER BY c.embedding <=> CAST(CAST(:vec AS varchar) AS public.vector), e.id, c.chunk_ordinal
                LIMIT :limit
                """;
        StatementSpec spec = jdbc.sql(sql)
                .param("m", merchant).param("survey", surveyId).param("revision", revisionId)
                .param("vec", literal).param("limit", limit);
        if (watermark != null) {
            spec = spec.param("watermark", watermark);
        }
        return spec.query((rs, row) -> new Hit(
                rs.getObject("evidence_id", UUID.class),
                rs.getObject("chunk_id", UUID.class),
                rs.getInt("chunk_ordinal"),
                rs.getDouble("score"),
                "vector",
                rs.getString("summary")))
                .list();
    }

    private void ensureAuthorizedEmbeddings(UUID merchant, UUID surveyId, UUID revisionId, Long watermark) {
        String watermarkClause = watermark == null
                ? ""
                : " AND e.seq <= CAST(:watermark AS bigint)";
        String sql = """
                SELECT c.id, c.content_norm
                FROM evidence_chunk c
                JOIN evidence_ref e ON e.merchant_id = c.merchant_id AND e.id = c.evidence_id
                WHERE c.merchant_id = :m AND c.survey_id = :survey AND c.revision_id = :revision
                  AND c.visibility = 'ACTIVE' AND e.visibility = 'ACTIVE'
                  AND c.embedding IS NULL
                """ + watermarkClause + "\n" + """
                ORDER BY e.seq, c.chunk_ordinal
                LIMIT 32
                """;
        StatementSpec spec = jdbc.sql(sql)
                .param("m", merchant).param("survey", surveyId).param("revision", revisionId);
        if (watermark != null) {
            spec = spec.param("watermark", watermark);
        }
        var pending = spec.query((rs, row) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("content_norm")))
                .list();
        for (var row : pending) {
            float[] vector = embedding.embed(merchant, row.getValue());
            jdbc.sql("""
                    UPDATE evidence_chunk
                    SET embedding = CAST(CAST(:vec AS varchar) AS public.vector), embedding_model = :model,
                        embedding_dim = 1536, embedding_hash = :hash
                    WHERE merchant_id = :m AND id = :id AND embedding IS NULL AND visibility = 'ACTIVE'
                    """).param("m", merchant).param("id", row.getKey())
                    .param("vec", embedding.vectorLiteral(vector))
                    .param("model", "text-embedding-3-small")
                    .param("hash", embedding.embeddingHash(vector)).update();
        }
    }

    private static List<Hit> rrf(List<Hit> lexical, List<Hit> vector, int topK) {
        Map<UUID, Double> scores = new HashMap<>();
        Map<UUID, Hit> sample = new LinkedHashMap<>();
        int k = 60;
        for (int i = 0; i < lexical.size(); i++) {
            Hit hit = lexical.get(i);
            scores.merge(hit.chunkId(), 1.0 / (k + i + 1), Double::sum);
            sample.putIfAbsent(hit.chunkId(), hit);
        }
        for (int i = 0; i < vector.size(); i++) {
            Hit hit = vector.get(i);
            scores.merge(hit.chunkId(), 1.0 / (k + i + 1), Double::sum);
            sample.putIfAbsent(hit.chunkId(), hit);
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(e -> sample.get(e.getKey()).evidenceId())
                        .thenComparing(e -> sample.get(e.getKey()).chunkOrdinal()))
                .limit(topK)
                .map(e -> {
                    Hit base = sample.get(e.getKey());
                    return new Hit(base.evidenceId(), base.chunkId(), base.chunkOrdinal(), e.getValue(),
                            "hybrid", base.summary());
                })
                .toList();
    }
}
