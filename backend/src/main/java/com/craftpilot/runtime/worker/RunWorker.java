package com.craftpilot.runtime.worker;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.runtime.checkpoint.CheckpointService;
import com.craftpilot.runtime.lease.LeaseHandle;
import com.craftpilot.runtime.lease.LeaseHeartbeat;
import com.craftpilot.runtime.lease.RenewOutcome;
import com.craftpilot.runtime.workflow.AgentLoop;
import com.craftpilot.runtime.workflow.SupervisorLoop;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RunWorker {
    private static final Logger LOG = LoggerFactory.getLogger(RunWorker.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AgentLoop loop;
    private final SupervisorLoop supervisor;
    private final CheckpointService checkpoints;
    private final LeaseHeartbeat heartbeat;
    private final String workerId;
    private final Duration leaseTtl;
    private final boolean enabled;

    public RunWorker(JdbcClient jdbc, TransactionTemplate tx, AgentLoop loop, SupervisorLoop supervisor,
                     CheckpointService checkpoints, LeaseHeartbeat heartbeat,
                     @Value("${craftpilot.runtime.worker-id:}") String configuredWorkerId,
                     @Value("${craftpilot.runtime.lease-seconds:30}") long leaseSeconds,
                     @Value("${craftpilot.runtime.worker-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.loop = loop;
        this.supervisor = supervisor;
        this.checkpoints = checkpoints;
        this.heartbeat = heartbeat;
        this.workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
                ? "runtime-" + UUID.randomUUID() : configuredWorkerId;
        this.leaseTtl = Duration.ofSeconds(leaseSeconds);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${craftpilot.runtime.poll-interval-ms:700}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            claimAndRun(1);
        } catch (Exception ex) {
            LOG.debug("runtime_worker_poll_skipped type={}", ex.getClass().getSimpleName());
        }
    }

    public int claimAndRun(int limit) {
        int done = 0;
        for (int i = 0; i < limit; i++) {
            Claim claim = tx.execute(status -> claimOne());
            if (claim == null) {
                break;
            }
            // The heartbeat keeps the lease alive for the whole execution (model and tool calls can outlast the
            // TTL); closing the handle stops it on every exit path: success, failure, cancel, HITL pause, exception.
            try (LeaseHandle lease = heartbeat.start(claim.runId().toString(), claim.leaseEpoch(),
                    () -> renewOutcome(claim))) {
                renew(claim);
                String workflowId = jdbc.sql("""
                        SELECT workflow_id FROM agent_run WHERE merchant_id = :m AND id = :id
                        """).param("m", claim.merchantId()).param("id", claim.runId()).query(String.class).single();
                UUID actor = resolveActor(claim.merchantId(), claim.ticketId());
                if ("launch-supervise-r4".equals(workflowId)) {
                    UUID taskId = ensureSupervisorTask(claim);
                    supervisor.execute(actor, claim.merchantId(), claim.surveyId(), claim.runId(), taskId,
                            claim.leaseEpoch(), lease);
                } else {
                    UUID taskId = ensureResearchTask(claim);
                    loop.executeResearch(actor, claim.merchantId(), claim.surveyId(), claim.runId(), taskId,
                            claim.leaseEpoch(), lease);
                }
            } catch (Exception ex) {
                LOG.warn("runtime_run_failed run={} type={} msg={}", claim.runId(), ex.getClass().getSimpleName(),
                        ex.getMessage());
                tx.executeWithoutResult(status -> markFailed(claim, failureReason(ex)));
            }
            done++;
        }
        return done;
    }

    public Claim claimOnePublic() {
        return tx.execute(status -> claimOne());
    }

    private Claim claimOne() {
        return jdbc.sql("""
                WITH candidate AS (
                    SELECT merchant_id, id
                    FROM agent_run
                    WHERE (
                        status = 'QUEUED'
                        OR (status IN ('RUNNING','RECOVERING') AND lease_until < clock_timestamp())
                        OR status = 'CANCEL_REQUESTED'
                    )
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE agent_run r
                SET status = CASE
                        WHEN r.status = 'QUEUED' THEN 'RUNNING'
                        WHEN r.status = 'CANCEL_REQUESTED' THEN 'CANCEL_REQUESTED'
                        ELSE 'RECOVERING'
                    END,
                    lease_owner = :owner,
                    lease_epoch = r.lease_epoch + 1,
                    lease_until = clock_timestamp() + make_interval(secs => :leaseSeconds),
                    started_at = COALESCE(r.started_at, clock_timestamp()),
                    updated_at = clock_timestamp(),
                    version = r.version + 1
                FROM candidate c
                WHERE r.merchant_id = c.merchant_id AND r.id = c.id
                RETURNING r.merchant_id, r.id, r.survey_id, r.ticket_id, r.lease_epoch, r.status
                """)
                .param("owner", workerId)
                .param("leaseSeconds", leaseTtl.toSeconds())
                .query((rs, i) -> new Claim(
                        rs.getObject("merchant_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        rs.getObject("survey_id", UUID.class),
                        rs.getObject("ticket_id", UUID.class),
                        rs.getLong("lease_epoch"),
                        rs.getString("status")))
                .optional().orElse(null);
    }

    public boolean renew(Claim claim) {
        return renewOutcome(claim) == RenewOutcome.RENEWED;
    }

    /**
     * Extends the lease while this worker still owns an active run whose deadline has not passed. Terminal and
     * paused (WAITING_APPROVAL) runs are never renewed, so a late heartbeat cannot resurrect them.
     */
    public RenewOutcome renewOutcome(Claim claim) {
        int updated = jdbc.sql("""
                UPDATE agent_run
                SET lease_until = clock_timestamp() + make_interval(secs => :leaseSeconds),
                    updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id AND lease_owner = :owner AND lease_epoch = :epoch
                  AND lease_until > clock_timestamp()
                  AND status IN ('RUNNING','RECOVERING','CANCEL_REQUESTED')
                  AND execution_deadline > clock_timestamp()
                """).param("m", claim.merchantId()).param("id", claim.runId()).param("owner", workerId)
                .param("epoch", claim.leaseEpoch()).param("leaseSeconds", leaseTtl.toSeconds()).update();
        if (updated == 1) {
            return RenewOutcome.RENEWED;
        }
        boolean ownedButPastDeadline = jdbc.sql("""
                SELECT 1 FROM agent_run
                WHERE merchant_id = :m AND id = :id AND lease_owner = :owner AND lease_epoch = :epoch
                  AND lease_until > clock_timestamp()
                  AND status IN ('RUNNING','RECOVERING','CANCEL_REQUESTED')
                  AND execution_deadline <= clock_timestamp()
                """).param("m", claim.merchantId()).param("id", claim.runId()).param("owner", workerId)
                .param("epoch", claim.leaseEpoch()).query(Integer.class).optional().isPresent();
        return ownedButPastDeadline ? RenewOutcome.DEADLINE : RenewOutcome.LOST;
    }

    private UUID ensureResearchTask(Claim claim) {
        return jdbc.sql("""
                SELECT id FROM agent_task
                WHERE merchant_id = :m AND run_id = :run AND logical_key = 'research-main'
                """).param("m", claim.merchantId()).param("run", claim.runId()).query(UUID.class)
                .optional().orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    jdbc.sql("""
                            INSERT INTO agent_task
                            (merchant_id, id, survey_id, run_id, logical_key, node_id, agent_spec_id, status, started_at)
                            VALUES (:m, :id, :survey, :run, 'research-main', 'research', 'research-agent-r3',
                                    'RUNNING', clock_timestamp())
                            """).param("m", claim.merchantId()).param("id", id).param("survey", claim.surveyId())
                            .param("run", claim.runId()).update();
                    return id;
                });
    }

    private UUID ensureSupervisorTask(Claim claim) {
        return jdbc.sql("""
                SELECT id FROM agent_task
                WHERE merchant_id = :m AND run_id = :run AND logical_key = 'supervisor-main'
                """).param("m", claim.merchantId()).param("run", claim.runId()).query(UUID.class)
                .optional().orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    jdbc.sql("""
                            INSERT INTO agent_task
                            (merchant_id, id, survey_id, run_id, logical_key, node_id, agent_spec_id, status, started_at)
                            VALUES (:m, :id, :survey, :run, 'supervisor-main', 'plan', 'supervisor-agent-r4',
                                    'RUNNING', clock_timestamp())
                            """).param("m", claim.merchantId()).param("id", id).param("survey", claim.surveyId())
                            .param("run", claim.runId()).update();
                    return id;
                });
    }

    private UUID resolveActor(UUID merchant, UUID ticketId) {
        return jdbc.sql("SELECT created_by FROM ticket WHERE merchant_id = :m AND id = :id")
                .param("m", merchant).param("id", ticketId).query(UUID.class).single();
    }

    /** Business error code for ApiFailure (e.g. MAX_TURNS_EXCEEDED); exception class name otherwise. */
    static String failureReason(Throwable ex) {
        return ex instanceof ApiFailure af && af.code() != null ? af.code() : ex.getClass().getSimpleName();
    }

    private void markFailed(Claim claim, String reason) {
        jdbc.sql("""
                UPDATE agent_run SET status = 'FAILED', terminal_reason = :reason, updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id AND lease_epoch = :epoch
                  AND status IN ('RUNNING','RECOVERING')
                """).param("m", claim.merchantId()).param("id", claim.runId()).param("epoch", claim.leaseEpoch())
                .param("reason", reason).update();
        checkpoints.appendEvent(claim.merchantId(), claim.surveyId(), claim.runId(), "WORKER_FAILED",
                Map.of("reason", reason));
    }

    public String workerId() {
        return workerId;
    }

    public record Claim(UUID merchantId, UUID runId, UUID surveyId, UUID ticketId, long leaseEpoch, String status) {}
}
