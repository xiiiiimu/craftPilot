-- P0-5: per-turn and per-tool latency for evaluation (milliseconds, wall clock measured by the worker).
-- Nullable: rows written before this migration, and turns that fail before the model call, have no value.
ALTER TABLE agent_turn
    ADD COLUMN context_build_ms int CHECK (context_build_ms >= 0),
    ADD COLUMN model_latency_ms int CHECK (model_latency_ms >= 0);

ALTER TABLE tool_invocation
    ADD COLUMN latency_ms int CHECK (latency_ms >= 0);
