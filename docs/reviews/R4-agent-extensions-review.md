# R4 Agent Extensions Review — feature/r4-agent-extensions-hitl

## Verdict

**R4 PASSED — PR READY**（不要自动合并）

Offline `clean verify` green (38 tests). Live multi-agent + local MCP + HITL smoke green. Latest Head `8855db6` / Actions run `43367167589` **success**. P0/P1 = 0. Review threads unresolved = 0.

## Scope checked

- Flyway V5 additive tables/constraints; V1→V5 and V4→V5 paths
- Skill snapshot immutability and revoke gating for new runs
- Supervisor handoff idempotency, worker skill whitelist isolation, parallel join + cancel propagation
- MCP protocol discovery/call, connector whitelist, SSRF rejection, untrusted result envelope
- HITL WAITING_APPROVAL persistence, approve/reject/expire, unauthorized deny, resume reclaim, late approval after cancel
- Merchant decision separate from agent recommendation
- Memory propose/publish/revoke with evidence verify and scope search
- Budget shared across supervisor/workers; pause does not model-spend
- R1/R2/R3 regression via full Surefire suite
- Live smoke evidence in `docs/development/R4-provider-mcp-smoke-results.md`

## Findings during implementation (addressed)

1. **Parallel `agent_turn` uniqueness** — V4 unique `(run_id, turn_no)` broke multi-agent; V5 scopes by task.
2. **Context history bleed** — `ContextBuilder` now filters turns by `task_id`.
3. **HITL resume reclaim** — pause/resume expire lease and re-queue to `QUEUED`.
4. **Approval expire rollback** — marking EXPIRED then throwing rolled back status; now returns EXPIRED view in-transaction.
5. **Cancel SQL param** — `:run` vs `:id` mismatch fixed in `SupervisorLoop.cancel`.
6. **Join body UUID noise** — memory propose uses evidence.verify validIds only.
7. **Live material worker** — fabricated evidence IDs no longer fail material handoff; MCP seed path ensures protocol call.

## P0 / P1 / P2

| Severity | Count | Notes |
|---|---|---|
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 1 | Live workers may still need a first-turn authorized tool seed when the model skips tools; offline stub always tools first. Acceptable for R4; tighten in R5 evaluation harness. |

## R5 admission

R4 gates for Skill/Supervisor/Join/MCP/HITL/Decision/Memory are met. R5 may start only after this PR merges (manual) and product prioritizes evaluation/observability hardening.
