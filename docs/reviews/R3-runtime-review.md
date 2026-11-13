# R3 Runtime Review — feature/r3-java-agent-runtime

## Verdict

**R3 PASSED — PR READY**（不要自动合并）

Offline `clean verify` green; live OpenAI-compatible chat tool-calling smoke green. Latest Head CI `81be716` / Actions run `43356050784` **success**. P0/P1 = 0；未解决 Review Threads = 0.

## Scope checked

- Flyway V4 additive migration + empty→V4 / V3→V4 tests
- Trigger/Ticket/Assignment/Attempt + Idempotency-Key + consumer_receipt dedup
- RunWorker SKIP LOCKED lease fencing / renew / cancel / recover
- ChatModelGateway independent of Embedding; ContextBuilder DB rebuild; ToolBroker whitelist; AgentLoop; Budget/Checkpoint
- Evidence verify on Final; fabricated IDs rejected
- Cross-tenant ticket access → 404
- Live smoke: multi-turn tool calling + SUCCEEDED (see `docs/development/R3-provider-smoke-results.md`)

## Findings addressed during implementation

1. **OpenAI tool name pattern** — dotted names (`evidence.search`) rejected; renamed to `evidence_search` etc. (`^[a-zA-Z0-9_-]+$`).
2. **Tool schema `format:uuid`** — removed; use string + description for provider compatibility.
3. **Soft tool failures** — unauthorized/invalid tool args return error payload + `FAILED_FINAL` with `result_json` so subsequent chat turns still have `role=tool` frames (avoids provider 400 on orphaned `tool_calls`).
4. **Chat stub JSON escaping** — text-block `\\"` collapsed; builders now escape safely.
5. **Foundation/R1 Flyway expectations** — include version `4`.

## Residual notes

- `R3ProviderSmokeTest` excluded from default Surefire (quota); run with `-Dtest=R3ProviderSmokeTest -Dsurefire.excludes=`.
- Do not commit secrets; do not auto-merge main.
- Architecture docs may still show dotted conceptual tool names; runtime registry uses underscore names for provider compliance.
