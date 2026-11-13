# R5 Capability Matrix

Branch: `feature/r5-end-to-end-acceptance`  
Base: `origin/main` @ `fcb23ad` (R4 squash-merge)

| Capability | Contract | Code | Test | Method | Result | Evidence | Sev |
|---|---|---|---|---|---|---|---|
| R1 Campaign/Style/Revision/BOM | roadmap R1 | `catalog/*` | R1 + R5 replay/E2E | auto+live | **PASS** | surefire / e2e | P0 |
| R1 Group Chat + idempotency | contracts | `groupchat/*` | R5MessageReplayTest | 1200 HTTP | **PASS** | replay results | P0 |
| R1 Survey / Vote / Purchase | contracts | `survey/*` | replay + E2E | auto+live | **PASS** | replay / e2e | P0 |
| R1 Outbox / tenant FK | security | `events/*` | R1 + security | auto | **PASS** | surefire | P0 |
| R2 Feedback / Evidence | evaluation | `feedback/*`, `evidence/*` | R2 + E2E | auto+live | **PASS** | e2e | P0 |
| R2 Embedding / Hybrid Retrieval | contracts | `retrieval/*` | R5ProviderE2ETest | live | **PASS** | e2e results | P0 |
| R2 Qwen-VL compare | contracts | `vision/*` | R5ProviderE2ETest | live PNGs | **PASS** | e2e results | P0 |
| R3 Ticket / Run / Worker | runtime | ticket/worker | R3–R5 | auto | **PASS** | surefire | P0 |
| R3 ToolBroker / Budget / Checkpoint | runtime | tool/ledger/checkpoint | R5FaultInjectionTest | auto | **PASS** | fault results | P0 |
| R4 Skill Snapshot | V5 | `SkillRegistry` | R4 + security | auto | **PASS** | surefire | P0 |
| R4 Supervisor / Handoff / Join | V5 | SupervisorLoop et al. | R4 + eval + E2E | auto+live | **PASS** | eval / e2e | P0 |
| R4 MCP Streamable HTTP | security | `McpClient` | eval + E2E | stub+live | **PASS** | eval / e2e | P0 |
| R4 HITL / Decision / Memory | contracts | hitl/decision/memory | R4 + security + E2E | auto+live | **PASS** | security / e2e | P0 |
| Tool provenance (R4 P2) | evaluation | TOOL_* events | R5EvaluationHarnessTest | auto | **PASS** | eval results | P1 |
| Memory search authz | security | `MemoryService` | R5SecurityAcceptanceTest | auto | **PASS** | security results | P0 |
| MCP SSRF / injection | security | `validateEndpoint` | eval + security | auto | **PASS** | eval / security | P0 |
| Fault suite (+ seq race) | T03–T05 | runtime | R5FaultInjectionTest | auto | **PASS** | fault results | P0 |
| Windows one-click | roadmap R5 | `r5-one-click.ps1` | manual script | executed | **PASS** | windows demo doc | P1 |
| V1→V2 Windows interactive demo | roadmap R5 | `r5-demo-v1-v2.ps1` | PrintOnly only | partial | **PARTIAL** | windows demo doc | P1 |

## Notes on PARTIAL

- **PrintOnly** proves script generation/preview of API steps only — not a full interactive demo.
- Full product path V1→V2 + HITL + decision + memory is covered by isolated `R5ProviderE2ETest` (authorized test principals under Testcontainers), not by the Windows operator demo against `:18080`.
- Real merchant HITL / decision on a live `backend-up` process remains an operator action; scripts never auto-approve.

## Counts

| Status | Count |
|---|---|
| PASS | 17 |
| PARTIAL | 1 |
| FAIL | 0 |
| BLOCKED | 0 |
| NOT TESTED | 0 critical |
