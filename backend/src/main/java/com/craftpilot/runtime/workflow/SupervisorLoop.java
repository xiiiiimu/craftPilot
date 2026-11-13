package com.craftpilot.runtime.workflow;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.evidence.EvidenceDtos.EvidenceVerifyRequest;
import com.craftpilot.evidence.EvidenceService;
import com.craftpilot.runtime.checkpoint.CheckpointService;
import com.craftpilot.runtime.context.ContextBuilder;
import com.craftpilot.runtime.decision.MerchantDecisionService;
import com.craftpilot.runtime.hitl.ApprovalService;
import com.craftpilot.runtime.ledger.BudgetService;
import com.craftpilot.runtime.memory.MemoryService;
import com.craftpilot.runtime.model.ChatModelGateway;
import com.craftpilot.runtime.model.ChatModelGateway.InferResult;
import com.craftpilot.runtime.model.ChatModelGateway.ToolCall;
import com.craftpilot.runtime.skill.SkillRegistry;
import com.craftpilot.runtime.supervisor.HandoffService;
import com.craftpilot.runtime.supervisor.JoinService;
import com.craftpilot.runtime.tool.ToolBroker;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class SupervisorLoop {
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
    private final SkillRegistry skills;
    private final HandoffService handoffs;
    private final JoinService joins;
    private final ApprovalService approvals;
    private final MerchantDecisionService decisions;
    private final MemoryService memory;

    public SupervisorLoop(JdbcClient jdbc, ObjectMapper mapper, TransactionTemplate tx, ChatModelGateway chat,
                          ContextBuilder contexts, ToolBroker tools, BudgetService budget,
                          CheckpointService checkpoints, EvidenceService evidence, SkillRegistry skills,
                          HandoffService handoffs, JoinService joins, ApprovalService approvals,
                          MerchantDecisionService decisions, MemoryService memory) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tx = tx;
        this.chat = chat;
        this.contexts = contexts;
        this.tools = tools;
        this.budget = budget;
        this.checkpoints = checkpoints;
        this.evidence = evidence;
        this.skills = skills;
        this.handoffs = handoffs;
        this.joins = joins;
        this.approvals = approvals;
        this.decisions = decisions;
        this.memory = memory;
    }

    public void execute(UUID actor, UUID merchant, UUID surveyId, UUID runId, UUID taskId, long leaseEpoch) {
        if (isCancelRequested(merchant, runId)) {
            cancel(merchant, surveyId, runId);
            return;
        }
        String node = currentNode(merchant, runId);
        if (node == null || node.isBlank() || "plan".equals(node) || "fanout".equals(node) || "join".equals(node)) {
            if (!planAndFanout(actor, merchant, surveyId, runId, taskId, leaseEpoch)) {
                return;
            }
            node = "hitl";
        }
        if ("hitl".equals(node)) {
            if (hasAppliedResume(merchant, runId)) {
                setNode(merchant, runId, "memory");
                node = "memory";
            } else if (!isWaitingApproval(merchant, runId)) {
                requestHitl(actor, merchant, surveyId, runId, taskId);
                return;
            } else {
                return;
            }
        }
        if ("memory".equals(node) || "publish".equals(node)) {
            finishAfterApproval(actor, merchant, surveyId, runId, taskId, leaseEpoch);
        }
    }

    private boolean planAndFanout(UUID actor, UUID merchant, UUID surveyId, UUID runId, UUID taskId,
                                  long leaseEpoch) {
        if (!stillOwns(merchant, runId, leaseEpoch) || isCancelRequested(merchant, runId)) {
            if (isCancelRequested(merchant, runId)) {
                cancel(merchant, surveyId, runId);
            }
            return false;
        }
        Map<String, Object> supervisorSkill = skills.frozenSkill(merchant, runId, "SUPERVISOR");
        setNode(merchant, runId, "plan");
        String objective = jdbc.sql("""
                SELECT t.objective FROM ticket t
                JOIN agent_run r ON r.merchant_id = t.merchant_id AND r.ticket_id = t.id
                WHERE r.merchant_id = :m AND r.id = :run
                """).param("m", merchant).param("run", runId).query(String.class).single();
        UUID revisionId = jdbc.sql("""
                SELECT revision_id FROM ticket_revision tr
                JOIN agent_run r ON r.merchant_id = tr.merchant_id AND r.ticket_id = tr.ticket_id
                WHERE r.merchant_id = :m AND r.id = :run ORDER BY revision_id LIMIT 1
                """).param("m", merchant).param("run", runId).query(UUID.class).single();

        OffsetDateTime deadline = OffsetDateTime.now().plusMinutes(15);
        var evidenceHandoff = handoffs.submit(merchant, surveyId, runId, taskId, "evidence-worker",
                "EVIDENCE_WORKER", "evidence-worker-skill-r4", "1",
                "Collect and verify evidence for: " + objective, List.of(), 2000, 6, deadline);
        var materialHandoff = handoffs.submit(merchant, surveyId, runId, taskId, "material-worker",
                "MATERIAL_WORKER", "material-worker-skill-r4", "1",
                "Inspect materials/BOM and MCP lookup for: " + objective, List.of(), 2000, 6, deadline);
        UUID joinId = joins.create(merchant, surveyId, runId, taskId, "workers-join",
                List.of(evidenceHandoff.childTaskId(), materialHandoff.childTaskId()),
                List.of(evidenceHandoff.id(), materialHandoff.id()), deadline);
        setNode(merchant, runId, "fanout");
        checkpoints.appendEvent(merchant, surveyId, runId, "SUPERVISOR_PLANNED", Map.of(
                "joinId", joinId,
                "skillHash", supervisorSkill.get("contentHash"),
                "workers", List.of("EVIDENCE_WORKER", "MATERIAL_WORKER")));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> evidenceFuture = pool.submit(() -> runWorker(
                    actor, merchant, surveyId, runId, evidenceHandoff.id(), leaseEpoch, revisionId));
            Future<Map<String, Object>> materialFuture = pool.submit(() -> runWorker(
                    actor, merchant, surveyId, runId, materialHandoff.id(), leaseEpoch, revisionId));
            Map<String, Object> evidenceResult = evidenceFuture.get(120, TimeUnit.SECONDS);
            Map<String, Object> materialResult = materialFuture.get(120, TimeUnit.SECONDS);
            boolean evidenceOk = Boolean.TRUE.equals(evidenceResult.get("ok"));
            boolean materialOk = Boolean.TRUE.equals(materialResult.get("ok"));
            handoffs.complete(merchant, surveyId, runId, evidenceHandoff.id(), evidenceOk, evidenceResult);
            handoffs.complete(merchant, surveyId, runId, materialHandoff.id(), materialOk, materialResult);
            joins.recordMemberResult(merchant, surveyId, runId, evidenceHandoff.childTaskId(), evidenceOk,
                    evidenceResult, leaseEpoch);
            joins.recordMemberResult(merchant, surveyId, runId, materialHandoff.childTaskId(), materialOk,
                    materialResult, leaseEpoch);
            // idempotent duplicate completion must not regress join
            joins.recordMemberResult(merchant, surveyId, runId, evidenceHandoff.childTaskId(), evidenceOk,
                    evidenceResult, leaseEpoch);
            var join = joins.evaluate(merchant, surveyId, runId, joinId);
            setNode(merchant, runId, "join");
            if (!"COMPLETED".equals(join.status())) {
                fail(merchant, surveyId, runId, "JOIN_" + join.status());
                return false;
            }
            setNode(merchant, runId, "hitl");
            return stillOwns(merchant, runId, leaseEpoch) && !isCancelRequested(merchant, runId);
        } catch (Exception ex) {
            fail(merchant, surveyId, runId, "SUPERVISOR_" + ex.getClass().getSimpleName());
            return false;
        } finally {
            pool.shutdownNow();
        }
    }

    private Map<String, Object> runWorker(UUID actor, UUID merchant, UUID surveyId, UUID runId, UUID handoffId,
                                          long leaseEpoch, UUID revisionId) {
        Map<String, Object> handoff = handoffs.load(merchant, handoffId);
        handoffs.markRunning(merchant, handoffId);
        String role = String.valueOf(handoff.get("targetRole"));
        Map<String, Object> skill = skills.frozenSkill(merchant, runId, role);
        @SuppressWarnings("unchecked")
        List<String> whitelist = (List<String>) skill.get("toolWhitelist");
        Set<String> allowed = whitelist == null ? Set.of() : Set.copyOf(whitelist);
        String instructions = String.valueOf(skill.get("instructions"));
        String objective = String.valueOf(handoff.get("objective"));
        UUID taskId = (UUID) handoff.get("childTaskId");
        List<Map<String, Object>> toolSchemas = tools.toolSchemas(allowed);
        UUID ticketId = jdbc.sql("SELECT ticket_id FROM agent_run WHERE merchant_id = :m AND id = :id")
                .param("m", merchant).param("id", runId).query(UUID.class).single();

        for (int turn = 0; turn < 4; turn++) {
            if (!stillOwns(merchant, runId, leaseEpoch) || isCancelRequested(merchant, runId)) {
                return Map.of("ok", false, "error", "CANCELLED_OR_LEASE");
            }
            List<Map<String, Object>> messages = contexts.buildMessages(
                    merchant, surveyId, runId, ticketId, taskId, instructions,
                    "[role=" + role + "] " + objective + " revisionId=" + revisionId);
            UUID contextId = contexts.persist(merchant, surveyId, runId, taskId, instructions, messages,
                    toolSchemas, List.of(), Math.max(1, messages.toString().length() / 4), null);
            UUID reservationId = tx.execute(status ->
                    budget.reserveModel(merchant, surveyId, runId, 400, BigDecimal.valueOf(0.008)));
            tx.executeWithoutResult(status -> budget.consumeTurn(merchant, runId));
            int turnNo = jdbc.sql("""
                    SELECT COALESCE(MAX(turn_no), 0) + 1 FROM agent_turn
                    WHERE merchant_id = :m AND run_id = :run AND task_id = :task
                    """).param("m", merchant).param("run", runId).param("task", taskId)
                    .query(Integer.class).single();
            UUID turnId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO agent_turn
                    (merchant_id, id, survey_id, run_id, task_id, turn_no, status, context_id, request_summary)
                    VALUES (:m, :id, :survey, :run, :task, :n, 'REQUESTED', :ctx, :summary)
                    """).param("m", merchant).param("id", turnId).param("survey", surveyId).param("run", runId)
                    .param("task", taskId).param("n", turnNo).param("ctx", contextId)
                    .param("summary", role + "-turn-" + turnNo).update();
            InferResult infer;
            try {
                infer = chat.infer(merchant, messages, toolSchemas, 700);
            } catch (RuntimeException ex) {
                tx.executeWithoutResult(status -> budget.release(merchant, reservationId, runId));
                return Map.of("ok", false, "error", "MODEL_" + ex.getClass().getSimpleName());
            }
            tx.executeWithoutResult(status -> budget.settleModel(
                    merchant, reservationId, runId, infer.modelCallId(), infer.inputTokens(),
                    infer.outputTokens(), infer.estimatedCostUsd(), infer.usageAccuracy(),
                    infer.providerRequestId()));
            List<Map<String, Object>> toolBatch = new ArrayList<>();
            for (ToolCall call : infer.toolCalls()) {
                toolBatch.add(Map.of("id", call.providerCallId(), "name", call.toolName(),
                        "arguments", call.argumentsJson(), "ordinal", call.ordinal()));
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
            if (infer.toolCalls().isEmpty() && turn == 0) {
                Map<String, String> seed = seedToolRequest(role, revisionId, allowed);
                if (seed != null) {
                    try {
                        jdbc.sql("""
                                UPDATE agent_turn SET tool_batch_json = CAST(:tools AS jsonb)
                                WHERE merchant_id = :m AND id = :id
                                """).param("m", merchant).param("id", turnId)
                                .param("tools", mapper.writeValueAsString(List.of(Map.of(
                                        "id", seed.get("callId"),
                                        "name", seed.get("name"),
                                        "arguments", seed.get("args"),
                                        "ordinal", 0)))).update();
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                    tools.dispatch(actor, merchant, surveyId, runId, taskId, turnId, leaseEpoch, 0,
                            seed.get("name"), seed.get("args"), seed.get("callId"), allowed);
                    continue;
                }
            }
            if (!infer.toolCalls().isEmpty()) {
                for (ToolCall call : infer.toolCalls()) {
                    tools.dispatch(actor, merchant, surveyId, runId, taskId, turnId, leaseEpoch,
                            call.ordinal(), call.toolName(), call.argumentsJson(), call.providerCallId(),
                            allowed);
                }
                continue;
            }
            Map<String, Object> parsed = parseWorkerFinal(infer.content());
            List<UUID> claimed = extractEvidenceIds(parsed, infer.content());
            if (!claimed.isEmpty()) {
                var verified = evidence.verify(actor, merchant, surveyId, new EvidenceVerifyRequest(claimed));
                if ("EVIDENCE_WORKER".equals(role)
                        && (verified.validIds().isEmpty() || !verified.invalidIds().isEmpty())) {
                    return Map.of("ok", false, "error", "EVIDENCE_VALIDATION_FAILED", "role", role);
                }
                parsed.put("evidenceIds", verified.validIds());
            } else if ("MATERIAL_WORKER".equals(role)) {
                // Material worker may conclude from MCP/BOM without evidence UUIDs.
                parsed.put("evidenceIds", List.of());
            } else {
                return Map.of("ok", false, "error", "NO_EVIDENCE", "role", role);
            }
            parsed.put("ok", true);
            parsed.put("role", role);
            parsed.put("skillId", skill.get("skillId"));
            parsed.put("skillVersion", skill.get("version"));
            return parsed;
        }
        return Map.of("ok", false, "error", "MAX_WORKER_TURNS", "role", role);
    }

    private Map<String, String> seedToolRequest(String role, UUID revisionId, Set<String> allowed) {
        if ("MATERIAL_WORKER".equals(role) && allowed.contains("mcp_material_lookup")) {
            return Map.of(
                    "callId", "seed-mcp",
                    "name", "mcp_material_lookup",
                    "args", "{\"materialCode\":\"FAB-9\",\"connectorId\":\"local-materials\"}");
        }
        if (allowed.contains("evidence_search")) {
            return Map.of(
                    "callId", "seed-evidence",
                    "name", "evidence_search",
                    "args", "{\"revisionId\":\"" + revisionId + "\",\"query\":\"fabric FAB-9\",\"topK\":5}");
        }
        if (allowed.contains("sample_get_snapshot")) {
            return Map.of(
                    "callId", "seed-sample",
                    "name", "sample_get_snapshot",
                    "args", "{\"revisionId\":\"" + revisionId + "\"}");
        }
        return null;
    }

    private void requestHitl(UUID actor, UUID merchant, UUID surveyId, UUID runId, UUID taskId) {
        var join = jdbc.sql("""
                SELECT result_json::text AS body FROM task_join
                WHERE merchant_id = :m AND run_id = :run AND join_key = 'workers-join'
                """).param("m", merchant).param("run", runId).query(String.class).optional().orElse("{}");
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("action", "MERCHANT_DECISION");
        operation.put("joinResult", join);
        operation.put("runId", runId.toString());
        approvals.request(actor, merchant, surveyId, runId, taskId, "MERCHANT_DECISION", operation,
                OffsetDateTime.now().plusHours(2));
        setNode(merchant, runId, "hitl");
    }

    private void finishAfterApproval(UUID actor, UUID merchant, UUID surveyId, UUID runId, UUID taskId,
                                     long leaseEpoch) {
        if (!stillOwns(merchant, runId, leaseEpoch) || isCancelRequested(merchant, runId)) {
            if (isCancelRequested(merchant, runId)) {
                cancel(merchant, surveyId, runId);
            }
            return;
        }
        String joinBody = jdbc.sql("""
                SELECT result_json::text AS body FROM task_join
                WHERE merchant_id = :m AND run_id = :run AND join_key = 'workers-join'
                """).param("m", merchant).param("run", runId).query(String.class).optional().orElse("{}");
        List<UUID> claimed = extractEvidenceIds(Map.of(), joinBody);
        List<UUID> evidenceIds = List.of();
        if (!claimed.isEmpty()) {
            var verified = evidence.verify(actor, merchant, surveyId, new EvidenceVerifyRequest(claimed));
            evidenceIds = verified.validIds();
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", "Supervisor joined worker results");
        report.put("recommendation", "REVISE");
        report.put("join", joinBody);
        report.put("evidenceIds", evidenceIds);
        UUID reportId = decisions.publishReport(merchant, surveyId, runId, report, evidenceIds);
        UUID revisionId = jdbc.sql("""
                SELECT revision_id FROM ticket_revision tr
                JOIN agent_run r ON r.merchant_id = tr.merchant_id AND r.ticket_id = tr.ticket_id
                WHERE r.merchant_id = :m AND r.id = :run ORDER BY revision_id LIMIT 1
                """).param("m", merchant).param("run", runId).query(UUID.class).single();
        decisions.requestDecision(actor, merchant, surveyId, runId, reportId, revisionId, "REVISE");
        if (!evidenceIds.isEmpty()) {
            memory.propose(actor, merchant, surveyId, runId, "SURVEY", surveyId.toString(),
                    "Survey memory from supervised research", evidenceIds);
        }
        setNode(merchant, runId, "publish");
        succeed(merchant, surveyId, runId, taskId, report);
    }

    private Map<String, Object> parseWorkerFinal(String content) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (content == null) {
            out.put("summary", "");
            out.put("evidenceIds", List.of());
            return out;
        }
        try {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode node = mapper.readTree(content.substring(start, end + 1));
                out.put("summary", node.path("summary").asText(content));
                List<UUID> ids = new ArrayList<>();
                if (node.path("evidenceIds").isArray()) {
                    for (JsonNode n : node.path("evidenceIds")) {
                        ids.add(UUID.fromString(n.asText()));
                    }
                }
                out.put("evidenceIds", ids);
                if (node.has("materialNotes")) {
                    out.put("materialNotes", node.path("materialNotes").asText(""));
                }
                return out;
            }
        } catch (Exception ignored) {
            // fall through
        }
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
        if (content != null) {
            Matcher m = UUID_RE.matcher(content);
            while (m.find()) {
                try {
                    ids.add(UUID.fromString(m.group()));
                } catch (Exception ignored) {
                }
            }
        }
        return ids.stream().distinct().toList();
    }

    private void succeed(UUID merchant, UUID surveyId, UUID runId, UUID taskId, Map<String, Object> output) {
        UUID ticketId = jdbc.sql("SELECT ticket_id FROM agent_run WHERE merchant_id = :m AND id = :id")
                .param("m", merchant).param("id", runId).query(UUID.class).single();
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

    private void fail(UUID merchant, UUID surveyId, UUID runId, String reason) {
        UUID ticketId = jdbc.sql("SELECT ticket_id FROM agent_run WHERE merchant_id = :m AND id = :id")
                .param("m", merchant).param("id", runId).query(UUID.class).single();
        jdbc.sql("""
                UPDATE agent_run SET status = 'FAILED', terminal_reason = :reason, version = version + 1,
                       updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :run
                  AND status IN ('QUEUED','RUNNING','RECOVERING','CANCEL_REQUESTED','WAITING_APPROVAL','PAUSED')
                """).param("m", merchant).param("run", runId).param("reason", reason).update();
        jdbc.sql("""
                UPDATE ticket SET status = 'FAILED', terminal_reason = :reason, version = version + 1,
                       updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :t AND status NOT IN ('COMPLETED','CANCELLED','EXPIRED')
                """).param("m", merchant).param("t", ticketId).param("reason", reason).update();
        checkpoints.appendEvent(merchant, surveyId, runId, "RUN_FAILED", Map.of("reason", reason));
    }

    private void cancel(UUID merchant, UUID surveyId, UUID runId) {
        UUID ticketId = jdbc.sql("SELECT ticket_id FROM agent_run WHERE merchant_id = :m AND id = :id")
                .param("m", merchant).param("id", runId).query(UUID.class).single();
        jdbc.sql("""
                UPDATE handoff SET status = 'CANCELLED', finished_at = clock_timestamp()
                WHERE merchant_id = :m AND run_id = :run AND status IN ('SUBMITTED','RUNNING')
                """).param("m", merchant).param("run", runId).update();
        jdbc.sql("""
                UPDATE task_join SET status = 'CANCELLED', completed_at = clock_timestamp()
                WHERE merchant_id = :m AND run_id = :run AND status = 'WAITING'
                """).param("m", merchant).param("run", runId).update();
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

    private boolean isWaitingApproval(UUID merchant, UUID runId) {
        return jdbc.sql("""
                SELECT 1 FROM agent_run WHERE merchant_id = :m AND id = :run AND status = 'WAITING_APPROVAL'
                """).param("m", merchant).param("run", runId).query(Integer.class).optional().isPresent();
    }

    private boolean hasAppliedResume(UUID merchant, UUID runId) {
        return jdbc.sql("""
                SELECT 1 FROM resume_command
                WHERE merchant_id = :m AND run_id = :run AND status = 'APPLIED'
                """).param("m", merchant).param("run", runId).query(Integer.class).optional().isPresent();
    }

    private String currentNode(UUID merchant, UUID runId) {
        return jdbc.sql("SELECT current_node FROM agent_run WHERE merchant_id = :m AND id = :id")
                .param("m", merchant).param("id", runId).query(String.class).optional().orElse("plan");
    }

    private void setNode(UUID merchant, UUID runId, String node) {
        jdbc.sql("""
                UPDATE agent_run SET current_node = :node, updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", runId).param("node", node).update();
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
