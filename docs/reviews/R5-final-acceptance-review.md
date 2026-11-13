# R5 Final Acceptance Review — feature/r5-end-to-end-acceptance

## Verdict

**Pending Head CI** — local gates green. PR [#6](https://github.com/xiiiiimu/craftPilot/pull/6) Head `d4657f6`. Target: **R5 PASSED — PR READY**（不要自动合并）

## Scope checked

- Capability matrix vs R0/R5 contracts (`docs/reviews/R5-capability-matrix.md`)
- Evaluation harness: MODEL_AUTONOMOUS vs RUNTIME_CONTROLLED_SEED audit events
- 1200-message deterministic replay (seed `20251113`, multi-merchant, isolation, tickets)
- Fault injection: lease reclaim, cancel vs late join/approval, concurrent HITL, MCP disconnect, budget
- Security: SSRF, cross-tenant, unauthorized HITL/decision, memory isolation/revoke, skill revoke, evidence forge
- Live E2E: chat + embedding + vision + MCP + HITL + decision + memory
- Windows one-click + V1→V2 demo scripts (no data wipe, no `.env` overwrite, no auto-approve)
- Memory search authorization fix (P0)

## Findings

1. **P0 fixed:** `GET .../runtime/memory` ignored principal; now requires membership.
2. **Replay 403:** sample cards require group ADMIN/OWNER; corpus now attaches cards only for owner (members text-only).
3. **R4 P2 closed:** controlled tool seed audited separately from model-autonomous tools.

## P0 / P1 / P2

| Severity | Count | Notes |
|---|---|---|
| P0 | 0 open | Memory authz fixed and tested |
| P1 | 0 open | — |
| P2 | 0 open | R4 tool-seed P2 addressed by harness |

## Fake-capability checks

| Risk | Assessment |
|---|---|
| Fake Agent | No — live chat tool-calling + ledger |
| Fake MCP | No — Streamable HTTP tools/list + tools/call |
| Fake Vision | No — real Qwen-VL compare with PNGs |
| Fake HITL | No — WAITING_APPROVAL → approve → resume → SUCCEEDED |
| Fake Memory | No — publish ACTIVE then revoke |
| Cross-tenant leak | Denied in security + replay tests |
| Secret leak | Results redacted; `.env` not committed |

## Review threads

Unresolved = 0 at review authoring time (update after PR conversation scan).
