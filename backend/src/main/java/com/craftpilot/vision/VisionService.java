package com.craftpilot.vision;

import static com.craftpilot.vision.VisionDtos.*;

import com.craftpilot.catalog.AssetService;
import com.craftpilot.common.ApiFailure;
import com.craftpilot.evidence.EvidenceService;
import com.craftpilot.model.QwenVisionClient;
import com.craftpilot.tenant.TenantAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class VisionService {
    private static final Pattern EVIDENCE_CLAIM = Pattern.compile(
            "(?i)evidence[_\\s-]?id\\s*[:=]\\s*([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    private final JdbcClient jdbc;
    private final TenantAccess tenant;
    private final AssetService assets;
    private final QwenVisionClient vision;
    private final EvidenceService evidence;
    private final ObjectMapper mapper;

    public VisionService(JdbcClient jdbc, TenantAccess tenant, AssetService assets, QwenVisionClient vision,
                         EvidenceService evidence, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.assets = assets;
        this.vision = vision;
        this.evidence = evidence;
        this.mapper = mapper;
    }

    @Transactional
    public VisionCompareView compare(UUID actor, UUID merchant, CompareRequest request) {
        tenant.requireOwner(actor, merchant);
        if (request.leftRevisionId().equals(request.rightRevisionId())) {
            throw ApiFailure.invalid("leftRevisionId and rightRevisionId must differ");
        }
        UUID leftAsset = primaryImageAsset(merchant, request.leftRevisionId());
        UUID rightAsset = primaryImageAsset(merchant, request.rightRevisionId());
        if (leftAsset == null && rightAsset == null) {
            return gap(merchant, request, null, null, "BOTH_ASSETS_MISSING");
        }
        if (leftAsset == null || rightAsset == null) {
            return gap(merchant, request, leftAsset, rightAsset, "SINGLE_ASSET_MISSING");
        }

        byte[] leftBytes = assets.content(actor, merchant, leftAsset);
        byte[] rightBytes = assets.content(actor, merchant, rightAsset);
        String leftType = assets.get(actor, merchant, leftAsset).contentType();
        String rightType = assets.get(actor, merchant, rightAsset).contentType();
        // Qwen-VL rejects images with width/height <= 10; surface as GAP rather than opaque model failure.
        if (looksTooSmallForVision(leftBytes) || looksTooSmallForVision(rightBytes)) {
            return gap(merchant, request, leftAsset, rightAsset, "IMAGE_DIMENSION_TOO_SMALL");
        }

        String prompt = """
                Compare the first image (left/V1) with the second image (right/V2).
                Return ONLY compact JSON with keys:
                visibleDifferences: array of {aspect, observation, certainty} where certainty is HIGH|MEDIUM|LOW,
                uncertainty: string describing what cannot be confirmed from the images.
                Do not invent asset IDs, evidence IDs, materials, or production facts.
                Treat observations as visual hypotheses only.
                """;
        try {
            var modelResult = vision.compareImages(merchant, leftType, leftBytes, rightType, rightBytes, prompt);
            rejectFabricatedEvidenceIds(modelResult.rawContent());
            ParsedVision parsed = parse(modelResult.rawContent());
            UUID comparisonId = UUID.randomUUID();
            UUID evidenceId = null;
            if (request.surveyId() != null) {
                requireSurveyRevision(merchant, request.surveyId(), request.rightRevisionId());
                requireSurveyRevision(merchant, request.surveyId(), request.leftRevisionId());
                evidenceId = createVisionEvidence(merchant, request.surveyId(), request.rightRevisionId(),
                        comparisonId, parsed);
            }
            UUID callId = jdbc.sql("""
                    SELECT id FROM model_call_attempt
                    WHERE merchant_id = :m AND purpose = 'vision_compare' AND status = 'SUCCEEDED'
                    ORDER BY completed_at DESC NULLS LAST, created_at DESC LIMIT 1
                    """).param("m", merchant).query(UUID.class).optional().orElse(null);
            jdbc.sql("""
                    INSERT INTO vision_comparison
                    (merchant_id, id, survey_id, left_revision_id, right_revision_id, left_asset_id, right_asset_id,
                     model_name, status, visible_differences, uncertainty, evidence_id, model_call_id)
                    VALUES (:m, :id, :survey, :leftRev, :rightRev, :leftAsset, :rightAsset, :model, 'SUCCEEDED',
                            CAST(:diff AS jsonb), :uncertainty, :evidence, :call)
                    """).param("m", merchant).param("id", comparisonId).param("survey", request.surveyId())
                    .param("leftRev", request.leftRevisionId()).param("rightRev", request.rightRevisionId())
                    .param("leftAsset", leftAsset).param("rightAsset", rightAsset)
                    .param("model", vision.modelName()).param("diff", mapper.writeValueAsString(parsed.differences()))
                    .param("uncertainty", parsed.uncertainty()).param("evidence", evidenceId).param("call", callId)
                    .update();
            return get(actor, merchant, comparisonId);
        } catch (ApiFailure failure) {
            if ("VISION_UNAVAILABLE".equals(failure.code()) || "VISION_RATE_LIMITED".equals(failure.code())) {
                return gap(merchant, request, leftAsset, rightAsset, "MODEL_UNAVAILABLE");
            }
            throw failure;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public VisionCompareView get(UUID actor, UUID merchant, UUID comparisonId) {
        tenant.requireOwner(actor, merchant);
        return jdbc.sql("""
                SELECT id, left_revision_id, right_revision_id, left_asset_id, right_asset_id, model_name, status,
                       visible_differences::text AS diffs, uncertainty, evidence_id, gap_code
                FROM vision_comparison WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", comparisonId)
                .query((rs, row) -> {
                    List<VisibleDifference> diffs = List.of();
                    String raw = rs.getString("diffs");
                    if (raw != null && !raw.isBlank()) {
                        try {
                            JsonNode arr = mapper.readTree(raw);
                            List<VisibleDifference> parsed = new ArrayList<>();
                            for (JsonNode node : arr) {
                                parsed.add(new VisibleDifference(
                                        node.path("aspect").asText(""),
                                        node.path("observation").asText(""),
                                        node.path("certainty").asText("LOW")));
                            }
                            diffs = List.copyOf(parsed);
                        } catch (Exception ignored) {
                            diffs = List.of();
                        }
                    }
                    return new VisionCompareView(
                            rs.getObject("id", UUID.class),
                            rs.getObject("left_revision_id", UUID.class),
                            rs.getObject("right_revision_id", UUID.class),
                            rs.getObject("left_asset_id", UUID.class),
                            rs.getObject("right_asset_id", UUID.class),
                            rs.getString("model_name"),
                            rs.getString("status"),
                            diffs,
                            rs.getString("uncertainty"),
                            rs.getObject("evidence_id", UUID.class),
                            rs.getString("gap_code"));
                }).optional().orElseThrow(ApiFailure::notFound);
    }

    private VisionCompareView gap(UUID merchant, CompareRequest request, UUID leftAsset, UUID rightAsset,
                                  String gapCode) {
        UUID id = UUID.randomUUID();
        String uncertainty = "Vision comparison unavailable: " + gapCode;
        jdbc.sql("""
                INSERT INTO vision_comparison
                (merchant_id, id, survey_id, left_revision_id, right_revision_id, left_asset_id, right_asset_id,
                 model_name, status, visible_differences, uncertainty, gap_code)
                VALUES (:m, :id, :survey, :leftRev, :rightRev, :leftAsset, :rightAsset, :model, 'GAP',
                        CAST('[]' AS jsonb), :uncertainty, :gap)
                """).param("m", merchant).param("id", id).param("survey", request.surveyId())
                .param("leftRev", request.leftRevisionId()).param("rightRev", request.rightRevisionId())
                .param("leftAsset", leftAsset).param("rightAsset", rightAsset)
                .param("model", vision.modelName()).param("uncertainty", uncertainty).param("gap", gapCode)
                .update();
        return new VisionCompareView(id, request.leftRevisionId(), request.rightRevisionId(),
                leftAsset, rightAsset, vision.modelName(), "GAP", List.of(), uncertainty, null, gapCode);
    }

    private static boolean looksTooSmallForVision(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return true;
        }
        // Only inspect PNG IHDR; other formats are left to the provider.
        if (bytes.length >= 24
                && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
            int width = ((bytes[16] & 0xff) << 24) | ((bytes[17] & 0xff) << 16)
                    | ((bytes[18] & 0xff) << 8) | (bytes[19] & 0xff);
            int height = ((bytes[20] & 0xff) << 24) | ((bytes[21] & 0xff) << 16)
                    | ((bytes[22] & 0xff) << 8) | (bytes[23] & 0xff);
            return width <= 10 || height <= 10;
        }
        return false;
    }

    private void requireSurveyRevision(UUID merchant, UUID surveyId, UUID revisionId) {
        jdbc.sql("""
                SELECT 1 FROM survey_revision
                WHERE merchant_id = :m AND survey_id = :survey AND revision_id = :revision
                """).param("m", merchant).param("survey", surveyId).param("revision", revisionId)
                .query(Integer.class).optional().orElseThrow(ApiFailure::notFound);
    }

    private UUID primaryImageAsset(UUID merchant, UUID revisionId) {
        jdbc.sql("SELECT id FROM sample_revision WHERE merchant_id = :m AND id = :id")
                .param("m", merchant).param("id", revisionId).query(UUID.class)
                .optional().orElseThrow(ApiFailure::notFound);
        return jdbc.sql("""
                SELECT a.id
                FROM revision_asset ra
                JOIN asset a ON a.merchant_id = ra.merchant_id AND a.id = ra.asset_id
                WHERE ra.merchant_id = :m AND ra.revision_id = :revision
                  AND a.status = 'AVAILABLE' AND lower(a.kind) IN ('image', 'photo', 'sample_image')
                ORDER BY a.id
                LIMIT 1
                """).param("m", merchant).param("revision", revisionId).query(UUID.class).optional()
                .orElseGet(() -> jdbc.sql("""
                        SELECT a.id
                        FROM revision_asset ra
                        JOIN asset a ON a.merchant_id = ra.merchant_id AND a.id = ra.asset_id
                        WHERE ra.merchant_id = :m AND ra.revision_id = :revision AND a.status = 'AVAILABLE'
                        ORDER BY a.id LIMIT 1
                        """).param("m", merchant).param("revision", revisionId).query(UUID.class).optional()
                        .orElse(null));
    }

    private void rejectFabricatedEvidenceIds(String content) {
        Matcher matcher = EVIDENCE_CLAIM.matcher(content == null ? "" : content);
        if (matcher.find()) {
            throw ApiFailure.conflict("VISION_FABRICATED_EVIDENCE",
                    "Model output claimed an evidence id which is not allowed");
        }
    }

    private ParsedVision parse(String content) {
        String json = extractJson(content);
        try {
            JsonNode root = mapper.readTree(json);
            List<VisibleDifference> diffs = new ArrayList<>();
            for (JsonNode node : root.path("visibleDifferences")) {
                diffs.add(new VisibleDifference(
                        node.path("aspect").asText("appearance"),
                        node.path("observation").asText(""),
                        normalizeCertainty(node.path("certainty").asText("LOW"))));
            }
            String uncertainty = root.path("uncertainty").asText("Unconfirmed visual details remain.");
            return new ParsedVision(List.copyOf(diffs), uncertainty);
        } catch (Exception ex) {
            return new ParsedVision(List.of(new VisibleDifference("raw", clip(content), "LOW")),
                    "Model output was not valid structured JSON");
        }
    }

    private UUID createVisionEvidence(UUID merchant, UUID surveyId, UUID revisionId, UUID comparisonId,
                                      ParsedVision parsed) {
        String summary = "Vision compare " + comparisonId + ": " + parsed.differences().stream()
                .map(VisibleDifference::observation).reduce((a, b) -> a + "; " + b).orElse("no differences");
        long seq = jdbc.sql("""
                UPDATE survey_session SET evidence_seq = evidence_seq + 1, updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :survey
                RETURNING evidence_seq
                """).param("m", merchant).param("survey", surveyId).query(Long.class).single();
        UUID evidenceId = UUID.randomUUID();
        String checksum = EvidenceService.sha(summary);
        jdbc.sql("""
                INSERT INTO evidence_ref
                (merchant_id, id, survey_id, revision_id, source_type, source_id, captured_at, grade,
                 checksum, visibility, seq, summary)
                VALUES (:m, :id, :survey, :revision, 'VISION_COMPARISON', :source, clock_timestamp(), 'B',
                        :checksum, 'ACTIVE', :seq, :summary)
                """).param("m", merchant).param("id", evidenceId).param("survey", surveyId)
                .param("revision", revisionId).param("source", comparisonId).param("checksum", checksum)
                .param("seq", seq).param("summary", clip(summary)).update();
        jdbc.sql("""
                INSERT INTO evidence_chunk
                (merchant_id, id, evidence_id, survey_id, revision_id, chunk_ordinal, doc_version,
                 content_text, content_norm, material_code, visibility)
                VALUES (:m, :id, :evidence, :survey, :revision, 0, 'r2-vision', :text, :norm, NULL, 'ACTIVE')
                """).param("m", merchant).param("id", UUID.randomUUID()).param("evidence", evidenceId)
                .param("survey", surveyId).param("revision", revisionId).param("text", clip(summary))
                .param("norm", EvidenceService.normalize(summary)).update();
        return evidenceId;
    }

    private static String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }

    private static String normalizeCertainty(String value) {
        String upper = value == null ? "LOW" : value.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "HIGH", "MEDIUM", "LOW" -> upper;
            default -> "LOW";
        };
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 1800 ? text : text.substring(0, 1800);
    }

    private record ParsedVision(List<VisibleDifference> differences, String uncertainty) {}
}
