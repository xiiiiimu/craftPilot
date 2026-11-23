package com.craftpilot.runtime.workflow;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.evidence.EvidenceDtos.EvidenceVerifyRequest;
import com.craftpilot.evidence.EvidenceService;
import com.craftpilot.runtime.checkpoint.CheckpointService;
import com.craftpilot.runtime.context.ContextBuilder;
import com.craftpilot.runtime.context.TokenEstimator;
import com.craftpilot.runtime.lease.LeaseHandle;
import com.craftpilot.runtime.ledger.BudgetService;
import com.craftpilot.runtime.metrics.RuntimeMetrics;
import com.craftpilot.runtime.release.AgentSpecResolver;
import com.craftpilot.runtime.model.ChatModelGateway;
import com.craftpilot.runtime.model.ChatModelGateway.InferResult;
import com.craftpilot.runtime.model.ChatModelGateway.ToolCall;
import com.craftpilot.runtime.tool.ToolBroker;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AgentLoop {
    private static final Logger LOG = LoggerFactory.getLogger(AgentLoop.class);
    private static final Pattern UUID_RE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final int MAX_OUTPUT_TOKENS = 800;
    /** The only recommendations a final answer may carry; anything else fails the run (INVALID_RECOMMENDATION). */
    static final List<String> RECOMMENDATIONS = List.of("PRODUCE", "REVISE", "TRIAL", "HOLD");
    /**
     * P1-3: strict JSON Schema for the final answer instead of scraping the first '{' .. last '}'. Off by default
     * ({@code craftpilot.runtime.structured-final-output}): with gpt-4o-mini, sending it together with tools made the
     * model keep calling tools until a budget ran out (evaluation, dataset v2 dev split).
     */
    static final Map<String, Object> FINAL_FORMAT = Map.of("type", "json_schema", "json_schema", Map.of(
            "name", "research_final", "strict", true, "schema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "recommendation", Map.of("type", "string",
                                    "enum", RECOMMENDATIONS),
                            "summary", Map.of("type", "string"),
                            "evidenceIds", Map.of("type", "array", "items", Map.of("type", "string"))),
                    "required", List.of("recommendation", "summary", "evidenceIds"),
                    "additionalProperties", false)));
    /** Budget refusals that end the run with their own code as terminal_reason. */
    static final Set<String> BUDGET_CODES = Set.of(
            "MAX_TURNS_EXCEEDED", "TOKEN_BUDGET_EXCEEDED", "COST_BUDGET_EXCEEDED");

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final ChatModelGateway chat;
    private final ContextBuilder contexts;
    private final ToolBroker tools;
    private final BudgetService budget;
    private final CheckpointService checkpoints;
    private final EvidenceService evidence;
    private final RuntimeMetrics metrics;
    private final AgentSpecResolver specs;
    private final boolean structuredFinalOutput;
    /** Round 3: check pollReadings / concerns against the tool results, one corrective turn (default off). */
    private final boolean finalConsistencyCheck;

    public AgentLoop(JdbcClient jdbc, ObjectMapper mapper, TransactionTemplate tx, ChatModelGateway chat,
                     ContextBuilder contexts, ToolBroker tools, BudgetService budget,
                     CheckpointService checkpoints, EvidenceService evidence, RuntimeMetrics metrics,
                     AgentSpecResolver specs,
                     @Value("${craftpilot.runtime.structured-final-output:false}") boolean structuredFinalOutput,
                     @Value("${craftpilot.runtime.final-consistency-check:false}") boolean finalConsistencyCheck) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tx = tx;
        this.chat = chat;
        this.contexts = contexts;
        this.tools = tools;
        this.budget = budget;
        this.checkpoints = checkpoints;
        this.evidence = evidence;
        this.metrics = metrics;
        this.specs = specs;
        this.structuredFinalOutput = structuredFinalOutput;
        this.finalConsistencyCheck = finalConsistencyCheck;
    }

    public void executeResearch(UUID actor, UUID merchant, UUID surveyId, UUID runId, UUID taskId,
                                long leaseEpoch, LeaseHandle lease) {
        String instructions = specs.research().instructions();
        var ticket = jdbc.sql("""
                SELECT r.ticket_id, t.objective
                FROM agent_run r JOIN ticket t ON t.merchant_id = r.merchant_id AND t.id = r.ticket_id
                WHERE r.merchant_id = :m AND r.id = :run
                """).param("m", merchant).param("run", runId)
                .query((rs, i) -> Map.entry(rs.getObject("ticket_id", UUID.class), rs.getString("objective")))
                .single();
        UUID ticketId = ticket.getKey();
        String objective = ticket.getValue();
        List<Map<String, Object>> toolSchemas = tools.toolSchemas();

        while (true) {
            if (!ownsLease(merchant, runId, leaseEpoch, lease, "turn-start")) {
                return;
            }
            if (deadlinePassed(merchant, runId)) {
                fail(merchant, surveyId, runId, ticketId, "DEADLINE");
                return;
            }
            if (isCancelRequested(merchant, runId)) {
                cancel(merchant, surveyId, runId, ticketId);
                return;
            }

            int turnNo = jdbc.sql("""
                    SELECT COALESCE(MAX(turn_no), 0) + 1 FROM agent_turn
                    WHERE merchant_id = :m AND run_id = :run
                    """).param("m", merchant).param("run", runId).query(Integer.class).single();

            long contextStart = System.nanoTime();
            List<Map<String, Object>> messages = contexts.buildMessages(
                    merchant, surveyId, runId, ticketId, taskId, instructions, objective);
            int inputEstimate = TokenEstimator.estimate(mapper, messages, toolSchemas);
            UUID contextId = contexts.persist(merchant, surveyId, runId, taskId, instructions, messages,
                    toolSchemas, List.of(), inputEstimate, null);
            long contextNanos = System.nanoTime() - contextStart;
            metrics.contextBuild("research", contextNanos);

            // Reserve what this call can actually spend: the full prompt (re-sent every turn) plus max_tokens.
            int reserveTokens = inputEstimate + MAX_OUTPUT_TOKENS;
            BigDecimal reserveCost = chat.estimateCost(inputEstimate, MAX_OUTPUT_TOKENS);
            UUID reservationId;
            try {
                reservationId = tx.execute(status ->
                        budget.reserveTurn(merchant, surveyId, runId, reserveTokens, reserveCost));
            } catch (ApiFailure ex) {
                if (!BUDGET_CODES.contains(ex.code())) {
                    throw ex;
                }
                if (isCancelRequested(merchant, runId)) {
                    cancel(merchant, surveyId, runId, ticketId);
                } else {
                    fail(merchant, surveyId, runId, ticketId, ex.code());
                }
                return;
            }

            UUID turnId = UUID.randomUUID();
            InferResult infer;
            long modelNanos = 0;
            boolean settled = false;
            try {
                jdbc.sql("""
                        INSERT INTO agent_turn
                        (merchant_id, id, survey_id, run_id, task_id, turn_no, status, context_id, request_summary,
                         context_build_ms)
                        VALUES (:m, :id, :survey, :run, :task, :n, 'REQUESTED', :ctx, :summary, :ctxMs)
                        """).param("m", merchant).param("id", turnId).param("survey", surveyId).param("run", runId)
                        .param("task", taskId).param("n", turnNo).param("ctx", contextId)
                        .param("summary", "turn-" + turnNo).param("ctxMs", RuntimeMetrics.millis(contextNanos))
                        .update();
                checkpoints.append(merchant, surveyId, runId, "turn-" + turnNo + "-requested", "research",
                        "REQUESTED", Map.of("turnId", turnId, "turnNo", turnNo));
                if (!ownsLease(merchant, runId, leaseEpoch, lease, "before-model-call")
                        || isCancelRequested(merchant, runId)) {
                    return; // the finally block releases the reservation
                }

                long modelStart = System.nanoTime();
                try {
                    infer = chat.infer(merchant, messages, toolSchemas, MAX_OUTPUT_TOKENS,
                            structuredFinalOutput ? FINAL_FORMAT : null);
                    modelNanos = System.nanoTime() - modelStart;
                    metrics.modelCall("research", true, modelNanos);
                } catch (RuntimeException ex) {
                    long failedNanos = System.nanoTime() - modelStart;
                    metrics.modelCall("research", false, failedNanos);
                    jdbc.sql("""
                            UPDATE agent_turn SET status = 'FAILED', model_latency_ms = :modelMs,
                                   completed_at = clock_timestamp()
                            WHERE merchant_id = :m AND id = :id
                            """).param("m", merchant).param("id", turnId)
                            .param("modelMs", RuntimeMetrics.millis(failedNanos)).update();
                    fail(merchant, surveyId, runId, ticketId, "MODEL_" + ex.getClass().getSimpleName());
                    return;
                }

                tx.executeWithoutResult(status -> budget.settleModel(
                        merchant, reservationId, runId, infer.modelCallId(), infer.inputTokens(),
                        infer.outputTokens(), infer.estimatedCostUsd(), infer.usageAccuracy(),
                        infer.providerRequestId()));
                settled = true;
            } finally {
                if (!settled) {
                    // release only acts on HELD reservations, so this is safe on every exit path
                    tx.executeWithoutResult(status -> budget.release(merchant, reservationId, runId));
                }
            }

            if (!ownsLease(merchant, runId, leaseEpoch, lease, "after-model-call")
                    || isCancelRequested(merchant, runId)) {
                return;
            }

            List<Map<String, Object>> toolBatch = new ArrayList<>();
            for (ToolCall call : infer.toolCalls()) {
                toolBatch.add(Map.of(
                        "id", call.providerCallId(),
                        "name", call.toolName(),
                        "arguments", call.argumentsJson(),
                        "ordinal", call.ordinal()));
            }
            try {
                jdbc.sql("""
                        UPDATE agent_turn SET status = 'RESPONSE_COMMITTED', model_call_id = :call,
                               response_summary = :summary, tool_batch_json = CAST(:tools AS jsonb),
                               input_tokens = :tin, output_tokens = :tout, model_latency_ms = :modelMs,
                               completed_at = clock_timestamp()
                        WHERE merchant_id = :m AND id = :id
                        """).param("m", merchant).param("id", turnId).param("call", infer.modelCallId())
                        .param("summary", truncate(infer.content(), 900))
                        .param("tools", mapper.writeValueAsString(toolBatch))
                        .param("tin", infer.inputTokens()).param("tout", infer.outputTokens())
                        .param("modelMs", RuntimeMetrics.millis(modelNanos)).update();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            checkpoints.append(merchant, surveyId, runId, "turn-" + turnNo + "-committed", "research",
                    "RESPONSE_COMMITTED", Map.of("turnId", turnId, "toolCalls", toolBatch.size()));

            if (!infer.toolCalls().isEmpty()) {
                for (ToolCall call : infer.toolCalls()) {
                    if (!ownsLease(merchant, runId, leaseEpoch, lease, "before-tool")
                            || isCancelRequested(merchant, runId)) {
                        return;
                    }
                    Map<String, Object> result = tools.dispatch(actor, merchant, surveyId, runId, taskId, turnId,
                            leaseEpoch, call.ordinal(), call.toolName(), call.argumentsJson(),
                            call.providerCallId());
                    checkpoints.appendEvent(merchant, surveyId, runId, "TOOL_RESULT", Map.of(
                            "tool", call.toolName(), "ordinal", call.ordinal(),
                            "keys", result.keySet()));
                }
                continue;
            }

            // final answer path
            Map<String, Object> finalOutput = parseFinal(infer.content());
            if (finalOutput == null) {
                fail(merchant, surveyId, runId, ticketId, "FINAL_PARSE_FAILED");
                return;
            }
            if (!RECOMMENDATIONS.contains(finalOutput.get("recommendation"))) {
                fail(merchant, surveyId, runId, ticketId, "INVALID_RECOMMENDATION");
                return;
            }
            if (finalConsistencyCheck
                    && !consistent(merchant, surveyId, runId, ticketId, turnId, finalOutput)) {
                if (isTerminal(merchant, runId)) {
                    return; // second inconsistent answer: the run already failed
                }
                continue; // one corrective turn
            }
            List<UUID> claimed = extractEvidenceIds(finalOutput, infer.content());
            boolean malformedIds = Boolean.TRUE.equals(finalOutput.remove("malformedEvidenceIds"));
            var verified = evidence.verify(actor, merchant, surveyId, new EvidenceVerifyRequest(claimed));
            if (malformedIds || !verified.invalidIds().isEmpty() || claimed.isEmpty()) {
                fail(merchant, surveyId, runId, ticketId, "EVIDENCE_VALIDATION_FAILED");
                return;
            }
            finalOutput.put("evidenceIds", verified.validIds());
            succeed(merchant, surveyId, runId, ticketId, taskId, finalOutput);
            return;
        }
    }

    /**
     * Parses the final answer, or returns {@code null} when there is no JSON object or it has no textual
     * recommendation; the caller fails the run with FINAL_PARSE_FAILED instead of assuming HOLD. Evidence ids that
     * are not UUIDs are flagged ({@code malformedEvidenceIds}) so they fail evidence validation, not parsing.
     */
    private Map<String, Object> parseFinal(String content) {
        JsonNode node = null;
        if (content != null) {
            // Structured output makes the whole content one JSON object; the brace scan stays as a fallback for
            // providers that ignore response_format.
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    node = mapper.readTree(content.substring(start, end + 1));
                } catch (Exception ignored) {
                    node = null;
                }
            }
        }
        if (node == null || !node.isObject() || !node.path("recommendation").isString()) {
            metrics.finalParse("research", false);
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recommendation", node.path("recommendation").asText());
        out.put("summary", node.path("summary").asText(content));
        List<UUID> ids = new ArrayList<>();
        boolean malformed = false;
        if (node.path("evidenceIds").isArray()) {
            for (JsonNode n : node.path("evidenceIds")) {
                try {
                    ids.add(UUID.fromString(n.asText()));
                } catch (IllegalArgumentException ex) {
                    malformed = true;
                }
            }
        }
        out.put("evidenceIds", ids);
        // round 3 structured fields, kept as given (checked only when the consistency check is on)
        for (String key : List.of("pollReadings", "concerns")) {
            if (node.path(key).isArray()) {
                out.put(key, mapper.convertValue(node.path(key), List.class));
            }
        }
        if (malformed) {
            out.put("malformedEvidenceIds", true);
        }
        metrics.finalParse("research", true);
        return out;
    }

    /**
     * Round 3 consistency check. Returns true when the answer may proceed to evidence validation. On the first
     * inconsistent answer it stores the correction on this turn (shown to the model by the next context build) and
     * returns false; on a second one it fails the run with INCONSISTENT_FINAL. The outcome is recorded as a
     * FINAL_CONSISTENCY runtime event: PASSED, CORRECTED_SAME_RECOMMENDATION (only the numbers changed),
     * CORRECTED_CHANGED_RECOMMENDATION (the conclusion changed too) or FAILED.
     */
    private boolean consistent(UUID merchant, UUID surveyId, UUID runId, UUID ticketId, UUID turnId,
                               Map<String, Object> finalOutput) {
        JsonNode answer = mapper.valueToTree(finalOutput);
        List<String> problems = FinalConsistencyCheck.check(answer, seenPolls(merchant, runId),
                concernAuthors(merchant, surveyId, FinalConsistencyCheck.concernEvidenceIds(answer)));
        String recommendation = String.valueOf(finalOutput.get("recommendation"));
        String firstRecommendation = jdbc.sql("""
                SELECT consistency_feedback ->> 'firstRecommendation' FROM agent_turn
                WHERE merchant_id = :m AND run_id = :run AND consistency_feedback IS NOT NULL
                ORDER BY turn_no LIMIT 1
                """).param("m", merchant).param("run", runId).query(String.class).optional().orElse(null);
        boolean retried = firstRecommendation != null;

        if (!problems.isEmpty() && !retried) {
            Map<String, Object> feedback = new LinkedHashMap<>();
            feedback.put("message", correction(problems));
            feedback.put("firstRecommendation", recommendation);
            feedback.put("problems", problems);
            jdbc.sql("""
                    UPDATE agent_turn SET consistency_feedback = CAST(:feedback AS jsonb)
                    WHERE merchant_id = :m AND id = :id
                    """).param("m", merchant).param("id", turnId).param("feedback", write(feedback)).update();
            checkpoints.appendEvent(merchant, surveyId, runId, "FINAL_CONSISTENCY_RETRY", Map.of(
                    "firstRecommendation", recommendation, "problems", problems));
            metrics.finalConsistency("research", "RETRY");
            return false;
        }
        String outcome = !problems.isEmpty() ? "FAILED"
                : !retried ? "PASSED"
                : firstRecommendation.equals(recommendation) ? "CORRECTED_SAME_RECOMMENDATION"
                : "CORRECTED_CHANGED_RECOMMENDATION";
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("outcome", outcome);
        event.put("recommendation", recommendation);
        if (retried) {
            event.put("firstRecommendation", firstRecommendation);
        }
        event.put("problems", problems);
        checkpoints.appendEvent(merchant, surveyId, runId, "FINAL_CONSISTENCY", event);
        metrics.finalConsistency("research", outcome);
        if (!problems.isEmpty()) {
            fail(merchant, surveyId, runId, ticketId, "INCONSISTENT_FINAL");
            return false;
        }
        return true;
    }

    private static String correction(List<String> problems) {
        StringBuilder text = new StringBuilder(
                "Your final answer failed the consistency check and was not accepted:");
        for (String problem : problems) {
            text.append("\n- ").append(problem);
        }
        return text.append("\nRe-read the survey_get_stats result and the evidence, fix pollReadings and concerns, ")
                .append("make sure the recommendation and summary follow from the corrected numbers, and return the ")
                .append("complete final JSON object again.").toString();
    }

    /** Polls returned by this run's successful survey_get_stats calls; a later result for the same poll wins. */
    private List<JsonNode> seenPolls(UUID merchant, UUID runId) {
        Map<String, JsonNode> polls = new LinkedHashMap<>();
        for (String body : jdbc.sql("""
                SELECT result_json::text FROM tool_invocation
                WHERE merchant_id = :m AND run_id = :run AND tool_name = 'survey_get_stats'
                  AND status = 'SUCCEEDED' AND result_json IS NOT NULL
                ORDER BY created_at, ordinal
                """).param("m", merchant).param("run", runId).query(String.class).list()) {
            for (JsonNode poll : mapper.readTree(body).path("polls")) {
                polls.put(poll.path("pollId").asText(), poll);
            }
        }
        return new ArrayList<>(polls.values());
    }

    /** Author of each cited opinion evidence of this survey (other evidence has no author and is left out). */
    private Map<UUID, UUID> concernAuthors(UUID merchant, UUID surveyId, List<UUID> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> authors = new HashMap<>();
        jdbc.sql("""
                SELECT e.id, o.author_id FROM evidence_ref e
                JOIN feedback_opinion o ON o.merchant_id = e.merchant_id AND o.id = e.source_id
                WHERE e.merchant_id = :m AND e.survey_id = :survey AND e.source_type = 'FEEDBACK_OPINION'
                  AND e.visibility = 'ACTIVE' AND e.id IN (:ids)
                """).param("m", merchant).param("survey", surveyId).param("ids", List.copyOf(Set.copyOf(evidenceIds)))
                .query((rs, i) -> Map.entry(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class)))
                .list().forEach(e -> authors.put(e.getKey(), e.getValue()));
        return authors;
    }

    private boolean isTerminal(UUID merchant, UUID runId) {
        return jdbc.sql("SELECT status FROM agent_run WHERE merchant_id = :m AND id = :run")
                .param("m", merchant).param("run", runId).query(String.class).optional()
                .map(status -> List.of("FAILED", "SUCCEEDED", "CANCELLED").contains(status)).orElse(true);
    }

    private List<UUID> extractEvidenceIds(Map<String, Object> finalOutput, String content) {
        Object raw = finalOutput.get("evidenceIds");
        List<UUID> ids = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof UUID u) {
                    ids.add(u);
                } else if (o != null) {
                    try {
                        ids.add(UUID.fromString(o.toString()));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (ids.isEmpty() && content != null) {
            Matcher m = UUID_RE.matcher(content);
            while (m.find()) {
                ids.add(UUID.fromString(m.group()));
            }
        }
        return ids.stream().distinct().toList();
    }

    private void succeed(UUID merchant, UUID surveyId, UUID runId, UUID ticketId, UUID taskId,
                         Map<String, Object> output) {
        try {
            jdbc.sql("""
                    UPDATE agent_run SET status = 'SUCCEEDED', final_output_json = CAST(:out AS jsonb),
                           current_node = 'publish', version = version + 1, updated_at = clock_timestamp()
                    WHERE merchant_id = :m AND id = :run AND status IN ('RUNNING','RECOVERING')
                    """).param("m", merchant).param("run", runId).param("out", mapper.writeValueAsString(output))
                    .update();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        jdbc.sql("""
                UPDATE agent_task SET status = 'SUCCEEDED', result_json = CAST(:out AS jsonb),
                       finished_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", taskId).param("out", write(output)).update();
        jdbc.sql("""
                UPDATE agent_run_attempt SET status = 'SUCCEEDED', updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = (SELECT attempt_id FROM agent_run WHERE merchant_id = :m AND id = :run)
                """).param("m", merchant).param("run", runId).update();
        jdbc.sql("""
                UPDATE assignment SET status = 'DONE', finished_at = clock_timestamp()
                WHERE merchant_id = :m AND id = (
                    SELECT assignment_id FROM agent_run_attempt a
                    JOIN agent_run r ON r.merchant_id = a.merchant_id AND r.attempt_id = a.id
                    WHERE r.merchant_id = :m AND r.id = :run)
                """).param("m", merchant).param("run", runId).update();
        jdbc.sql("""
                UPDATE ticket SET status = 'COMPLETED', version = version + 1, updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :t
                """).param("m", merchant).param("t", ticketId).update();
        checkpoints.append(merchant, surveyId, runId, "run-succeeded", "publish", "SUCCEEDED", output);
    }

    private void fail(UUID merchant, UUID surveyId, UUID runId, UUID ticketId, String reason) {
        jdbc.sql("""
                UPDATE agent_run SET status = 'FAILED', terminal_reason = :reason, version = version + 1,
                       updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :run
                  AND status IN ('QUEUED','RUNNING','RECOVERING','CANCEL_REQUESTED')
                """).param("m", merchant).param("run", runId).param("reason", reason).update();
        jdbc.sql("""
                UPDATE ticket SET status = 'FAILED', terminal_reason = :reason, version = version + 1,
                       updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :t AND status NOT IN ('COMPLETED','CANCELLED','EXPIRED')
                """).param("m", merchant).param("t", ticketId).param("reason", reason).update();
        checkpoints.appendEvent(merchant, surveyId, runId, "RUN_FAILED", Map.of("reason", reason));
    }

    private void cancel(UUID merchant, UUID surveyId, UUID runId, UUID ticketId) {
        jdbc.sql("""
                UPDATE agent_run SET status = 'CANCELLED', version = version + 1, updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :run AND status = 'CANCEL_REQUESTED'
                """).param("m", merchant).param("run", runId).update();
        jdbc.sql("""
                UPDATE ticket SET status = 'CANCELLED', version = version + 1, updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :t AND status <> 'COMPLETED'
                """).param("m", merchant).param("t", ticketId).update();
        checkpoints.appendEvent(merchant, surveyId, runId, "RUN_CANCELLED", Map.of());
    }

    /**
     * Lease check at a loop checkpoint: the in-memory heartbeat state first (no database round trip), then the
     * fenced database check. A stale worker only logs and stops; it writes nothing for a run it no longer owns.
     */
    private boolean ownsLease(UUID merchant, UUID runId, long epoch, LeaseHandle lease, String checkpoint) {
        if (lease.isLost()) {
            LOG.info("runtime_lease_lost run={} epoch={} at={} source=heartbeat state={}", runId, epoch, checkpoint,
                    lease.state());
            return false;
        }
        if (!stillOwns(merchant, runId, epoch)) {
            LOG.info("runtime_lease_lost run={} epoch={} at={} source=database", runId, epoch, checkpoint);
            return false;
        }
        return true;
    }

    private boolean stillOwns(UUID merchant, UUID runId, long epoch) {
        return jdbc.sql("""
                SELECT 1 FROM agent_run
                WHERE merchant_id = :m AND id = :run AND lease_epoch = :epoch
                  AND lease_until > clock_timestamp()
                  AND status IN ('RUNNING','RECOVERING','CANCEL_REQUESTED')
                """).param("m", merchant).param("run", runId).param("epoch", epoch)
                .query(Integer.class).optional().isPresent();
    }

    private boolean isCancelRequested(UUID merchant, UUID runId) {
        return jdbc.sql("""
                SELECT 1 FROM agent_run WHERE merchant_id = :m AND id = :run AND status = 'CANCEL_REQUESTED'
                """).param("m", merchant).param("run", runId).query(Integer.class).optional().isPresent();
    }

    private boolean deadlinePassed(UUID merchant, UUID runId) {
        return jdbc.sql("""
                SELECT 1 FROM agent_run
                WHERE merchant_id = :m AND id = :run AND execution_deadline <= clock_timestamp()
                """).param("m", merchant).param("run", runId).query(Integer.class).optional().isPresent();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
