# R3 Java Agent Runtime — Implementation Report

## Exit status

**R3 PASSED — PR READY**（不自动合并 main）

Offline `.\mvnw.cmd -B -ntp clean verify` **BUILD SUCCESS** (31 tests). Live `R3ProviderSmokeTest` **PASSED** (see `R3-provider-smoke-results.md`). Head CI `ce456e4` / Actions run [`43354713153`](https://github.com/xiiiiimu/craftPilot/actions/runs/43354713153) success.

## Scope delivered

R3 delivers a self-built, durable Java Agent Runtime on top of merged R1/R2:

- Flyway `V4__r3_agent_runtime.sql` (Trigger/Ticket/Assignment/Attempt, Workflow/Run/Task, Checkpoint, Tool ledger, Budget/Usage, runtime_event; seed `launch-research-r3` + read-only tools)
- Trigger → Ticket → Assignment → Attempt with Outbox `RUNTIME_RUN_QUEUED` + `consumer_receipt` idempotency
- `RunWorker` with `FOR UPDATE SKIP LOCKED`, lease epoch fencing, renew, cancel/recover
- `ChatModelGateway` (OpenAI-compatible `/chat/completions` + tools; independent `CRAFTPILOT_CHAT_*`)
- `ContextBuilder`, `ToolBroker` (read-only R1/R2 handlers), `AgentLoop`, `BudgetService`, checkpoints
- Runtime APIs under `/api/v1/merchants/{m}/…` for tickets, runs, model/tool calls, budget, cancel

## R3 boundary (not in scope)

Supervisor multi-agent Join, real MCP, HITL approval, Memory publish — extension placeholders only.

## Schema (V4)

Core tables: `source_event`, `trigger_evaluation`, `ticket`, `ticket_revision`, `assignment`, `agent_run_attempt`, `workflow_release`, `agent_spec`, `tool_release`, `model_profile`, `agent_run`, `agent_task`, `agent_turn`, `context_snapshot`, `checkpoint`, `tool_invocation`, `tool_dispatch_attempt`, `effect_receipt`, `budget_reservation`, `usage_ledger`, `runtime_event` (+ approval/resume stubs).

Reuses V2: `api_command`, `outbox`, `consumer_receipt`, `subscription`. Reuses V3: `model_call_attempt`, Evidence/Retrieval/Vision.

## APIs

| Endpoint | Purpose |
|---|---|
| `POST /merchants/{m}/surveys/{s}/tickets` | Create ticket + allocate run (Idempotency-Key) |
| `GET /merchants/{m}/tickets/{id}` | Ticket view |
| `POST /merchants/{m}/tickets/{id}/cancel` | Cancel ticket/run |
| `GET /merchants/{m}/runtime/runs/{id}` | Run status / final output |
| `GET …/tasks` / `events` / `model-calls` / `tool-calls` / `budget` | Audit surfaces |
| `POST …/runtime/runs/{id}/cancel` | Cancel with expectedVersion |

## Design notes

- Worker claim increments `lease_epoch`; renew/tool dispatch require matching epoch (fencing).
- Agent loop commits full model frames before tool dispatch; final path runs `EvidenceService.verify` and rejects fabricated IDs.
- Tool broker re-checks merchant/survey/ticket revision whitelist + JSON schema; no SQL/HTTP/FS/arbitrary writes.
- Budget reserve/settle under run row lock; uncertain usage marked ESTIMATED/UNKNOWN where needed.
- Chat config is independent of Embedding (`CRAFTPILOT_CHAT_BASE_URL` / `API_KEY` / `MODEL`).

## Tests

| Suite | Role |
|---|---|
| `FlywayR1MigrationTest` | empty → V4; V3 → V4 |
| `FoundationTest` / `R1BusinessTest` / `R2FeedbackRetrievalVisionTest` | regression |
| `R3RuntimeTest` | stubbed chat E2E: idempotency, multi-turn tools, lease fence, cancel, fabricated evidence, budget |
| `R3ProviderSmokeTest` | live chat (Surefire-excluded); results in `R3-provider-smoke-results.md` |

## Real provider gate

| 项 | 结果 |
|---|---|
| Chat key | CONFIGURED（`CRAFTPILOT_CHAT_API_KEY` 或 fallback `CRAFTPILOT_OPENAI_API_KEY`） |
| Model | `gpt-4o-mini` SUCCEEDED，多轮 tool-calling |
| Tools | `evidence_search` 等 underscore 名（OpenAI `^[a-zA-Z0-9_-]+$`） |
| Run | SUCCEEDED；turns≥2；tool invocations>0；request id 已脱敏 |

## Config

See `.env.example`: `CRAFTPILOT_CHAT_*`. Keys never logged or committed.
