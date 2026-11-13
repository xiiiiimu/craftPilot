# R4 Agent Extensions + HITL — Implementation Report

Branch: `feature/r4-agent-extensions-hitl`  
Base: `origin/main` @ `29ae64c` (R3 squash-merge)

## Architecture contract mapping

| R0/R4 capability | Implementation |
|---|---|
| Skill Snapshot / version pin | `skill_release`, `skill_snapshot`, `agent_run_skill`, `SkillRegistry` |
| Supervisor / Handoff | `HandoffService`, `SupervisorLoop`, workflow `launch-supervise-r4` |
| Durable Join | `task_join` / `task_join_member`, `JoinService` (ALL_REQUIRED, epoch fencing) |
| MCP via Tool Broker | `McpClient` + `mcp_material_lookup` in `ToolBroker` |
| HITL Pause/Approve/Resume | `ApprovalService`, run `WAITING_APPROVAL` → resume `QUEUED` |
| Merchant Decision | `research_report`, `merchant_decision`, `MerchantDecisionService` |
| Memory Publish/Revoke | `memory_proposal`, `memory_entry`, `MemoryService` |
| Budget / Lease reuse | Shared run budget; MCP counted as tool slot; pause does not spend model |

DeerFlow was used only as a design reference; no source/API was copied.

## Flyway V5

- File: `backend/src/main/resources/db/migration/V5__r4_agent_extensions.sql`
- Empty DB → V1…V5; V4→V5 upgrade covered in `FlywayR1MigrationTest`
- `agent_turn` uniqueness scoped to `(run_id, task_id, turn_no)` for parallel workers
- Seeds: supervisor/evidence/material skills, `launch-supervise-r4`, `mcp_material_lookup`

## Module results

### Skill Snapshot
- Run create for `launch-supervise-r4` freezes three skills into immutable snapshot hash
- Revoked skill cannot pin new runs; in-flight frozen skill remains readable

### Supervisor / Handoff
- Distinct SUPERVISOR + EVIDENCE_WORKER + MATERIAL_WORKER identities
- Handoff key idempotent; child tasks do not inherit parent tool whitelist beyond skill pin

### Durable Join
- Parallel fan-out via thread pool; join waits for required members
- Duplicate member completion is epoch-guarded; cancel rejects late join results

### MCP
- Real Streamable HTTP client (`tools/list`, `tools/call`), allowlist connector, SSRF host checks
- Results wrapped as untrusted external content; tool descriptions never become system instructions

### HITL
- Protected summarize path pauses before merchant decision continuation
- Approve / Reject / Expire / Cancel / late-approval denial covered in `R4RuntimeTest`
- Resume consumes approval idempotently and re-queues run from checkpoint node

### Merchant Decision + Memory
- Agent publishes report recommendation only; owner confirms via API
- Memory requires evidence verify; publish needs approval; revoke removes from active search

## Tests

- Offline: `mvnw clean verify` → **38 tests, 0 failures** (R1/R2/R3 regression + R4RuntimeTest + Flyway)
- Live: `R4ProviderSmokeTest` → PASSED (see `R4-provider-mcp-smoke-results.md`)

## Security notes

- MCP endpoint cannot be model-supplied (`connectorId` only; URL from merchant connector_config)
- Loopback MCP allowed only when `allow_loopback=true`
- Cross-tenant approval/memory denied via `TenantAccess`
- No API keys logged or stored in runtime tables

## Residual / non-goals

- R5 not started
- Default CI does not call paid models (`R4ProviderSmokeTest` Surefire-excluded)
- No auto-merge to main
