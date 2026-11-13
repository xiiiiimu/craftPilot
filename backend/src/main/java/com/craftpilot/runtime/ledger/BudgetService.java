package com.craftpilot.runtime.ledger;

import com.craftpilot.common.ApiFailure;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BudgetService {
    private final JdbcClient jdbc;

    public BudgetService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID reserveModel(UUID merchant, UUID surveyId, UUID runId, int tokens, BigDecimal costUsd) {
        var row = jdbc.sql("""
                SELECT token_budget, tokens_used, cost_budget_usd, cost_used_usd, status,
                       max_turns, turns_used, max_tool_calls, tool_calls_used
                FROM agent_run WHERE merchant_id = :m AND id = :id FOR UPDATE
                """).param("m", merchant).param("id", runId).query((rs, i) -> MapRow.from(rs))
                .optional().orElseThrow(ApiFailure::notFound);
        if (List.of("CANCELLED", "CANCEL_REQUESTED", "SUCCEEDED", "FAILED", "EXPIRED").contains(row.status())) {
            throw ApiFailure.conflict("RUN_TERMINAL", "Run is not accepting new spend");
        }
        if (row.tokensUsed() + tokens > row.tokenBudget()) {
            throw ApiFailure.conflict("TOKEN_BUDGET_EXCEEDED", "Token budget exceeded");
        }
        if (row.costUsed().add(costUsd).compareTo(row.costBudget()) > 0) {
            throw ApiFailure.conflict("COST_BUDGET_EXCEEDED", "Cost budget exceeded");
        }
        if (row.turnsUsed() >= row.maxTurns()) {
            throw ApiFailure.conflict("MAX_TURNS_EXCEEDED", "Max turns exceeded");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO budget_reservation
                (merchant_id, id, survey_id, run_id, purpose, tokens_reserved, cost_reserved_usd, status)
                VALUES (:m, :id, :survey, :run, 'MODEL', :tokens, :cost, 'HELD')
                """).param("m", merchant).param("id", id).param("survey", surveyId).param("run", runId)
                .param("tokens", tokens).param("cost", costUsd).update();
        jdbc.sql("""
                UPDATE agent_run SET tokens_used = tokens_used + :tokens,
                       cost_used_usd = cost_used_usd + :cost, updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :run
                """).param("m", merchant).param("run", runId).param("tokens", tokens).param("cost", costUsd)
                .update();
        return id;
    }

    @Transactional
    public void settleModel(UUID merchant, UUID reservationId, UUID runId, UUID modelCallId,
                            Integer tokensIn, Integer tokensOut, BigDecimal actualCost, String accuracy,
                            String providerRequestId) {
        var held = jdbc.sql("""
                SELECT tokens_reserved, cost_reserved_usd, survey_id
                FROM budget_reservation
                WHERE merchant_id = :m AND id = :id AND status = 'HELD' FOR UPDATE
                """).param("m", merchant).param("id", reservationId)
                .query((rs, i) -> new Object[]{
                        rs.getInt("tokens_reserved"),
                        rs.getBigDecimal("cost_reserved_usd"),
                        rs.getObject("survey_id", UUID.class)
                }).optional().orElse(null);
        if (held == null) {
            return;
        }
        int reservedTokens = (Integer) held[0];
        BigDecimal reservedCost = (BigDecimal) held[1];
        UUID surveyId = (UUID) held[2];
        int actualTokens = (tokensIn == null ? 0 : tokensIn) + (tokensOut == null ? 0 : tokensOut);
        int deltaTokens = actualTokens - reservedTokens;
        BigDecimal actual = actualCost == null ? reservedCost : actualCost;
        BigDecimal deltaCost = actual.subtract(reservedCost);
        jdbc.sql("SELECT id FROM agent_run WHERE merchant_id = :m AND id = :id FOR UPDATE")
                .param("m", merchant).param("id", runId).query(UUID.class).optional()
                .orElseThrow(ApiFailure::notFound);
        if (deltaTokens != 0 || deltaCost.signum() != 0) {
            jdbc.sql("""
                    UPDATE agent_run SET tokens_used = GREATEST(0, tokens_used + :dt),
                           cost_used_usd = GREATEST(0, cost_used_usd + :dc), updated_at = clock_timestamp()
                    WHERE merchant_id = :m AND id = :run
                    """).param("m", merchant).param("run", runId).param("dt", deltaTokens).param("dc", deltaCost)
                    .update();
        }
        jdbc.sql("""
                UPDATE budget_reservation SET status = 'SETTLED', settled_at = clock_timestamp(),
                       model_call_id = :call
                WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", reservationId).param("call", modelCallId).update();
        try {
            jdbc.sql("""
                    INSERT INTO usage_ledger
                    (merchant_id, id, survey_id, run_id, kind, provider_request_id, tokens_in, tokens_out,
                     cost_usd, accuracy, model_call_id)
                    VALUES (:m, :id, :survey, :run, 'MODEL', :provider, :tin, :tout, :cost, :acc, :call)
                    """).param("m", merchant).param("id", UUID.randomUUID()).param("survey", surveyId)
                    .param("run", runId).param("provider", providerRequestId).param("tin", tokensIn)
                    .param("tout", tokensOut).param("cost", actual).param("acc", accuracy)
                    .param("call", modelCallId).update();
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // provider request already ledgered
        }
    }

    @Transactional
    public void release(UUID merchant, UUID reservationId, UUID runId) {
        var held = jdbc.sql("""
                SELECT tokens_reserved, cost_reserved_usd
                FROM budget_reservation
                WHERE merchant_id = :m AND id = :id AND status = 'HELD' FOR UPDATE
                """).param("m", merchant).param("id", reservationId)
                .query((rs, i) -> new Object[]{rs.getInt("tokens_reserved"), rs.getBigDecimal("cost_reserved_usd")})
                .optional().orElse(null);
        if (held == null) {
            return;
        }
        jdbc.sql("SELECT id FROM agent_run WHERE merchant_id = :m AND id = :id FOR UPDATE")
                .param("m", merchant).param("id", runId).query(UUID.class).optional()
                .orElseThrow(ApiFailure::notFound);
        jdbc.sql("""
                UPDATE agent_run SET tokens_used = GREATEST(0, tokens_used - :t),
                       cost_used_usd = GREATEST(0, cost_used_usd - :c), updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :run
                """).param("m", merchant).param("run", runId)
                .param("t", (Integer) held[0]).param("c", (BigDecimal) held[1]).update();
        jdbc.sql("""
                UPDATE budget_reservation SET status = 'RELEASED', settled_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :id
                """).param("m", merchant).param("id", reservationId).update();
    }

    @Transactional
    public void consumeToolSlot(UUID merchant, UUID runId) {
        int updated = jdbc.sql("""
                UPDATE agent_run SET tool_calls_used = tool_calls_used + 1, updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :run AND tool_calls_used < max_tool_calls
                  AND status IN ('RUNNING','RECOVERING')
                """).param("m", merchant).param("run", runId).update();
        if (updated == 0) {
            throw ApiFailure.conflict("MAX_TOOL_CALLS_EXCEEDED", "Tool call budget exceeded");
        }
    }

    @Transactional
    public void consumeTurn(UUID merchant, UUID runId) {
        int updated = jdbc.sql("""
                UPDATE agent_run SET turns_used = turns_used + 1, updated_at = clock_timestamp()
                WHERE merchant_id = :m AND id = :run AND turns_used < max_turns
                """).param("m", merchant).param("run", runId).update();
        if (updated == 0) {
            throw ApiFailure.conflict("MAX_TURNS_EXCEEDED", "Max turns exceeded");
        }
    }

    private record MapRow(String status, int tokenBudget, int tokensUsed, BigDecimal costBudget,
                          BigDecimal costUsed, int maxTurns, int turnsUsed, int maxToolCalls, int toolCallsUsed) {
        static MapRow from(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new MapRow(rs.getString("status"), rs.getInt("token_budget"), rs.getInt("tokens_used"),
                    rs.getBigDecimal("cost_budget_usd"), rs.getBigDecimal("cost_used_usd"),
                    rs.getInt("max_turns"), rs.getInt("turns_used"),
                    rs.getInt("max_tool_calls"), rs.getInt("tool_calls_used"));
        }
    }
}
