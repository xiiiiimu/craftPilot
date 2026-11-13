# R5 End-to-End Acceptance Report

Branch: `feature/r5-end-to-end-acceptance`  
Base: `fcb23ad` (R4 Agent Extensions merged)

## Scope

Validate R0–R4 end-to-end: capability matrix, evaluation harness (controlled tool seed), 1200-message deterministic replay, fault injection, security, live Provider/MCP/Vision E2E, Windows one-click + V1→V2 demo, docs, PR, Head CI.

## Code changes

| Area | Change |
|---|---|
| Tool provenance | `SupervisorLoop` emits `TOOL_GUIDANCE_CONTROLLED` / `TOOL_MODEL_AUTONOMOUS` with provenance fields |
| Memory authz (P0) | `MemoryService.searchActive(actor, …)` requires merchant membership |
| Seq race (P0) | `CheckpointService.appendEvent` locks `agent_run` before allocating `seq` |
| Offline suite | `R5EvaluationHarnessTest`, `R5FaultInjectionTest`, `R5SecurityAcceptanceTest`, `R5MessageReplayTest` |
| Live suite | `R5ProviderE2ETest` (Surefire-excluded) |
| Windows | `scripts/r5-one-click.ps1`, `scripts/r5-demo-v1-v2.ps1` |

## Offline gates

| Gate | Result | Evidence |
|---|---|---|
| Evaluation harness | **PASS** (incl. `RUNTIME_CONTROLLED_SEED` in `payload_json`) | `R5-evaluation-results.md` |
| Fault injection | **PASS** (incl. 40-thread `runtime_event.seq` stress) | `R5-fault-injection-results.md` |
| Security acceptance | **PASS** (memory cross-tenant 4xx + revoke) | `R5-security-acceptance-results.md` |
| 1200-message replay | **PASS** — 1200 posts, **1175** accepted 2xx, **25** expected `409 IDEMPOTENCY_CONFLICT`, unexpected=0; outbox MESSAGE_PERSISTED=1175; tickets/runs=3 | `R5-1200-message-replay-results.md` |
| R1–R4 regression | **PASS** (included in CI / local verify) | surefire |

### Replay rejection audit (not zeroed)

| Class | Count | HTTP | Meaning |
|---|---|---|---|
| Accepted | 1175 | 201 | New rows + same-payload idempotent retries |
| Expected reject | 25 | 409 | Corpus `duplicateOfPrior` reused `clientMsgId` for same sender with different payload → `IDEMPOTENCY_CONFLICT` |
| Unexpected | 0 | — | Must stay zero |
| DB / Outbox | 1175 / 1175 | — | `group_message` rows == MESSAGE_PERSISTED outbox events; unique (merchant,group,sender,clientMsgId) |
| Tenant | denied | 4xx | Cross-merchant message list rejected |

## Live / demo gates

| Gate | Result | Evidence |
|---|---|---|
| Provider + MCP + Vision E2E | **PASS** (isolated Testcontainers; authorized HITL/decision) | `R5-provider-mcp-e2e-results.md` |
| Windows one-click | **PASS** (infra TCP + jar; no data wipe / no `.env` rewrite) | `R5-windows-startup-demo.md` |
| V1→V2 Windows interactive demo | **PARTIAL** — PrintOnly verified; `:18080` down this closure; no forged full interactive PASS | same |

## Live E2E chain (executed earlier on branch)

Campaign → Style V1 → Survey → message/vote → Feedback/Evidence → V2 → Qwen-VL compare → Hybrid Retrieval → Ticket `launch-supervise-r4` → Supervisor/Handoff/Join/MCP → HITL approve/resume → Merchant Decision → Memory publish/revoke → Run `SUCCEEDED`.

Redacted: chat `gpt-4o-mini`, embedding `text-embedding-3-small`, vision `qwen3-vl-plus`, MCP `lookup_material` succeeded.

## Security note

R5 found unauthenticated cross-tenant memory search (principal ignored). Fixed via `tenant.requireMember` in `searchActive`. Covered by `R5SecurityAcceptanceTest`.

## Mode legend

- **Automated offline:** Testcontainers + stubbed chat/embedding; real local MCP HTTP.
- **Live:** Real OpenAI-compatible chat, OpenAI embedding, Qwen-VL; local MCP.
- Results never embed secrets.

## PR / CI verification timeline

| Kind | SHA | CI run | Notes |
|---|---|---|---|
| Historical (seq-race docs) | `d28034e` | [43448845107](https://github.com/xiiiiimu/craftPilot/actions/runs/43448845107) success | Prior READY snapshot |
| Prior Head (docs align) | `cea7f2f` | [43458269573](https://github.com/xiiiiimu/craftPilot/actions/runs/43458269573) success | User-verified green Head before closure commits |
| Latest Head | *(this commit)* | *(await CI after push)* | Closure: replay taxonomy, seq stress, provenance assert, PARTIAL demo honesty |

## Final verdict

**R5 PASSED with one PARTIAL (Windows interactive V1→V2 demo) — PR READY to merge after latest Head CI green**（不要自动合并）

- PR: https://github.com/xiiiiimu/craftPilot/pull/6
- P0/P1 open: **0**
- Capability matrix: 17 PASS / 1 PARTIAL / 0 FAIL
- Do not treat PrintOnly as full interactive demo PASS
