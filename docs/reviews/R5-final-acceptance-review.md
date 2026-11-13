# R5 Final Acceptance Review — feature/r5-end-to-end-acceptance

## Verdict

**R5 PASSED with PARTIAL Windows interactive demo — PR READY after latest Head CI**（不要自动合并）

Offline + live E2E gates green. Windows one-click PASS; V1→V2 operator demo remains **PARTIAL** (PrintOnly only on this workstation; `:18080` not running during closure). P0/P1 = 0.

## PR / CI (distinguish history vs latest)

| Record | SHA | CI | Role |
|---|---|---|---|
| Historical READY | `d28034e` | [43448845107](https://github.com/xiiiiimu/craftPilot/actions/runs/43448845107) success | Prior snapshot |
| User-audited Head | `cea7f2f` | [43458269573](https://github.com/xiiiiimu/craftPilot/actions/runs/43458269573) success | Green before closure commits |
| Latest Head | `38cd1c8` | [43495514359](https://github.com/xiiiiimu/craftPilot/actions/runs/43495514359) success | Closure taxonomy + PARTIAL demo |

PR: [#6](https://github.com/xiiiiimu/craftPilot/pull/6) — open, `mergeable=True` / `mergeable_state=clean`; review comments = 0.

## Scope checked

- Capability matrix vs R0/R5 contracts (`docs/reviews/R5-capability-matrix.md`)
- Evaluation harness: MODEL_AUTONOMOUS vs RUNTIME_CONTROLLED_SEED (`payload_json` provenance asserted)
- 1200-message deterministic replay (seed `20251113`): **1175** DB rows, **25** expected 409 conflicts classified, unexpected=0; outbox/ticket consistency
- Fault injection: lease reclaim, cancel vs late join/approval, concurrent HITL, MCP disconnect, budget, **40-thread seq race**
- Security: SSRF, cross-tenant, unauthorized HITL/decision, memory isolation/revoke, skill revoke, evidence forge
- Live E2E: chat + embedding + vision + MCP + HITL + decision + memory (isolated)
- Windows one-click + V1→V2 demo scripts (no data wipe, no `.env` overwrite, no auto-approve)
- Memory search authorization fix (P0)
- Parallel `runtime_event.seq` lock (P0)

## Findings

1. **P0 fixed:** `GET .../runtime/memory` ignored principal; now requires membership.
2. **Replay:** 25 non-2xx are **expected** `409 IDEMPOTENCY_CONFLICT` (same sender + `clientMsgId`, different payload). Not failures; must not be zeroed in metrics.
3. **R4 P2 closed:** controlled tool seed audited separately from model-autonomous tools.
4. **P0 fixed:** parallel worker `runtime_event` seq allocation raced under fan-out; `CheckpointService.appendEvent` `FOR UPDATE` locks the run row.
5. **Demo honesty:** PrintOnly ≠ full interactive V1→V2; matrix marks **PARTIAL**.

## P0 / P1 / P2

| Severity | Count | Notes |
|---|---|---|
| P0 | 0 open | Memory authz + seq race fixed and tested |
| P1 | 0 open | Windows interactive demo PARTIAL is documented, not a product defect |
| P2 | 0 open | R4 tool-seed P2 addressed by harness |

## Fake-capability checks

| Risk | Assessment |
|---|---|
| Fake Agent | No — live chat tool-calling + ledger |
| Fake MCP | No — Streamable HTTP tools/list + tools/call |
| Fake Vision | No — real Qwen-VL compare with PNGs |
| Fake HITL | No — WAITING_APPROVAL → approve → resume → SUCCEEDED in E2E |
| Fake Memory | No — publish ACTIVE then revoke |
| Fake Windows demo | Avoided — PrintOnly left PARTIAL |
| Cross-tenant leak | Denied in security + replay tests |
| Secret leak | Results redacted; `.env` not committed |

## Review threads

Unresolved review threads / PR review comments = **0** after Head `38cd1c8` CI success.
