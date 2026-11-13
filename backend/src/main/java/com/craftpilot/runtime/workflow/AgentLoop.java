package com.craftpilot.runtime.workflow;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.evidence.EvidenceDtos.EvidenceVerifyRequest;
import com.craftpilot.evidence.EvidenceService;
import com.craftpilot.runtime.checkpoint.CheckpointService;
import com.craftpilot.runtime.context.ContextBuilder;
import com.craftpilot.runtime.ledger.BudgetService;
import com.craftpilot.runtime.model.ChatModelGateway;
import com.craftpilot.runtime.model.ChatModelGateway.InferResult;
import com.craftpilot.runtime.model.ChatModelGateway.ToolCall;
import com.craftpilot.runtime.tool.ToolBroker;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AgentLoop {
    private static final Pattern UUID_RE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final ChatModelGateway chat;
    private final ContextBuilder contexts;
    private final ToolBroker tools;
    private final BudgetService budget;
    private final CheckpointService checkpoints;
    private final EvidenceService evidence;

    public AgentLoop(JdbcClient jdbc, ObjectMapper mapper, TransactionTemplate tx, ChatModelGateway chat,
                     ContextBuilder contexts, ToolBroker tools, BudgetService budget,
                     CheckpointService checkpoints, EvidenceService evidence) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tx = tx;
        this.chat = chat;
        this.contexts = contexts;
        this.tools = tools;
        this.budget = budget;
        this.checkpoints = checkpoints;
        this.evidence = evidence;
    }

    public void executeResearch(UUID actor, UUID merchant, UUID surveyId, UUID runId, UUID taskId,
                                long leaseEpoch) {
        String instructions = jdbc.sql("SELECT instructions FROM agent_spec WHERE id = 'research-agent-r3'")
                .query(String.class).single();
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
            if (!stillOwns(merchant, runId, leaseEpoch)) {
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

            List<Map<String, Object>> messages = contexts.buildMessages(
                    merchant, surveyId, runId, ticketId, instructions, objective);
            UUID contextId = contexts.persist(merchant, surveyId, runId, taskId, instructions, messages,
                    toolSchemas, List.of(), estimateTokens(messages), null);

            UUID reservationId = tx.execute(status ->
                    budget.reserveModel(merchant, surveyId, runId, 500, BigDecimal.valueOf(0.01)));
            tx.executeWithoutResult(status -> budget.consumeTurn(merchant, runId));

            UUID turnId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO agent_turn
                    (merchant_id, id, survey_id, run_id, task_id, turn_no, status, context_id, request_summary)
                    VALUES (:m, :id, :survey, :run, :task, :n, 'REQUESTED', :ctx, :summary)
                    """).param("m", merchant).param("id", turnId).param("survey", surveyId).param("run", runId)
                    .param("task", taskId).param("n", turnNo).param("ctx", contextId)
                    .param("summary", "turn-" + turnNo).update();
            checkpoints.append(merchant, surveyId, runId, "turn-" + turnNo + "-requested", "research",
                    "REQUESTED", Map.of("turnId", turnId, "turnNo", turnNo));

            InferResult infer;
            try {
                infer = chat.infer(merchant, messages, toolSchemas, 800);
            } catch (RuntimeException ex) {
                tx.executeWithoutResult(status -> budget.release(merchant, reservationId, runId));
                jdbc.sql("""
                        UPDATE agent_turn SET status = 'FAILED', completed_at = clock_timestamp()
                        WHERE merchant_id = :m AND id = :id
                        """).param("m", merchant).param("id", turnId).update();
                fail(merchant, surveyId, runId, ticketId, "MODEL_" + ex.getClass().getSimpleName());
                return;
            }

            tx.executeWithoutResult(status -> budget.settleModel(
                    merchant, reservationId, runId, infer.modelCallId(), infer.inputTokens(),
                    infer.outputTokens(), infer.estimatedCostUsd(), infer.usageAccuracy(),
                    infer.providerRequestId()));

            if (!stillOwns(merchant, runId, leaseEpoch) || isCancelRequested(merchant, runId)) {
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
                               input_tokens = :tin, output_tokens = :tout, completed_at = clock_timestamp()
                        WHERE merchant_id = :m AND id = :id
                        """).param("m", merchant).param("id", turnId).param("call", infer.modelCallId())
                        .param("summary", truncate(infer.content(), 900))
                        .param("tools", mapper.writeValueAsString(toolBatch))
                        .param("tin", infer.inputTokens()).param("tout", infer.outputTokens()).update();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            checkpoints.append(merchant, surveyId, runId, "turn-" + turnNo + "-committed", "research",
                    "RESPONSE_COMMITTED", Map.of("turnId", turnId, "toolCalls", toolBatch.size()));

            if (!infer.toolCalls().isEmpty()) {
                for (ToolCall call : infer.toolCalls()) {
                    if (!stillOwns(merchant, runId, leaseEpoch) || isCancelRequested(merchant, runId)) {
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
            List<UUID> claimed = extractEvidenceIds(finalOutput, infer.content());
            var verified = evidence.verify(actor, merchant, surveyId, new EvidenceVerifyRequest(claimed));
            if (!verified.invalidIds().isEmpty() || claimed.isEmpty()) {
                fail(merchant, surveyId, runId, ticketId, "EVIDENCE_VALIDATION_FAILED");
                return;
            }
            finalOutput.put("evidenceIds", verified.validIds());
            succeed(merchant, surveyId, runId, ticketId, taskId, finalOutput);
            return;
        }
    }

    private Map<String, Object> parseFinal(String content) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (content == null) {
            out.put("recommendation", "HOLD");
            out.put("summary", "");
            return out;
        }
        try {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode node = mapper.readTree(content.substring(start, end + 1));
                out.put("recommendation", node.path("recommendation").asText("HOLD"));
                out.put("summary", node.path("summary").asText(content));
                List<UUID> ids = new ArrayList<>();
                if (node.path("evidenceIds").isArray()) {
                    for (JsonNode n : node.path("evidenceIds")) {
                        ids.add(UUID.fromString(n.asText()));
                    }
                }
                out.put("evidenceIds", ids);
                return out;
            }
        } catch (Exception ignored) {
            // fall through
        }
        out.put("recommendation", "HOLD");
        out.put("summary", content);
        out.put("evidenceIds", List.of());
        return out;
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

    private static int estimateTokens(List<Map<String, Object>> messages) {
        int chars = messages.stream().mapToInt(m -> String.valueOf(m.get("content")).length()).sum();
        return Math.max(1, chars / 4);
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
