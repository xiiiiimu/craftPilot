# R5 End-to-End Acceptance Report

Branch: `feature/r5-end-to-end-acceptance`  
Base: `fcb23ad` (R4 Agent Extensions merged)

## Scope

Validate R0–R4 end-to-end: capability matrix, evaluation harness (controlled tool seed), 1200-message deterministic replay, fault injection, security, live Provider/MCP/Vision E2E, Windows one-click + V1→V2 demo, docs, PR, Head CI.

## Code changes

| Area | Change |
|---|---|
| Tool provenance | `SupervisorLoop` emits `TOOL_GUIDANCE_CONTROLLED` / `TOOL_MODEL_AUTONOMOUS` |
| Memory authz (P0) | `MemoryService.searchActive(actor, …)` requires merchant membership |
| Offline suite | `R5EvaluationHarnessTest`, `R5FaultInjectionTest`, `R5SecurityAcceptanceTest`, `R5MessageReplayTest` |
| Live suite | `R5ProviderE2ETest` (Surefire-excluded) |
| Windows | `scripts/r5-one-click.ps1`, `scripts/r5-demo-v1-v2.ps1` |

## Offline gates

| Gate | Result | Evidence |
|---|---|---|
| Evaluation harness | **PASS** | `R5-evaluation-results.md` |
| Fault injection | **PASS** | `R5-fault-injection-results.md` |
| Security acceptance | **PASS** | `R5-security-acceptance-results.md` |
| 1200-message replay | **PASS** (1200 posts, 1175 accepted, seed `20251113`) | `R5-1200-message-replay-results.md` |
| `mvnw clean verify` | **PASS** — **53 tests, 0 failures** | local `r5-clean-verify2.txt` |
| R1–R4 regression | **PASS** (included in verify) | surefire |

## Live / demo gates

| Gate | Result | Evidence |
|---|---|---|
| Provider + MCP + Vision E2E | **PASS** | `R5-provider-mcp-e2e-results.md` |
| Windows one-click | **PASS** (infra TCP + jar; no data wipe) | `R5-windows-startup-demo.md` |
| V1→V2 demo script | **PASS** PrintOnly; live API demo needs `backend-up` + manual HITL | same |

## Live E2E chain (executed)

Campaign → Style V1 → Survey → message/vote → Feedback/Evidence → V2 → Qwen-VL compare → Hybrid Retrieval → Ticket `launch-supervise-r4` → Supervisor/Handoff/Join/MCP → HITL approve/resume → Merchant Decision → Memory publish/revoke → Run `SUCCEEDED`.

Redacted: chat `gpt-4o-mini`, embedding `text-embedding-3-small`, vision `qwen3-vl-plus`, MCP `lookup_material` succeeded, handoffs=2, join=1, tokens_used=3703.

## Security note

R5 found unauthenticated cross-tenant memory search (principal ignored). Fixed via `tenant.requireMember` in `searchActive`. Covered by `R5SecurityAcceptanceTest`.

## Mode legend

- **Automated offline:** Testcontainers + stubbed chat/embedding; real local MCP HTTP.
- **Live:** Real OpenAI-compatible chat, OpenAI embedding, Qwen-VL; local MCP.
- Results never embed secrets.

## Final verdict

**R5 PASSED — PR READY**（不要自动合并）

- PR: https://github.com/xiiiiimu/craftPilot/pull/6
- Head: `d28034e`
- Head CI: https://github.com/xiiiiimu/craftPilot/actions/runs/43448845107 success
- Maven offline: 53 tests, 0 failures
- Live E2E: PASSED
- Windows one-click: PASSED
- Capability matrix: 18 PASS / 0 FAIL / 0 BLOCKED critical
