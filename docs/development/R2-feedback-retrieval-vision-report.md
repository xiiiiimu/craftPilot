# R2 Feedback, Retrieval, and Vision — Implementation Report

## Scope delivered

R2 adds message attribution, opinion extraction / issue merge, Evidence, OpenAI Embedding, hybrid retrieval (lexical + pgvector RRF), and Qwen-VL revision image compare on top of the merged R1 business domain.

## Schema

- Flyway `V3__r2_feedback_retrieval_vision.sql`
- Tables: `message_attribution`, `feedback_opinion`, `feedback_issue`, `issue_opinion`, `feedback_attribution_event`, `evidence_ref`, `evidence_chunk` (`public.vector(1536)`), `model_call_attempt`, `vision_comparison`, `retrieval_execution`
- `CREATE EXTENSION vector WITH SCHEMA public` for multi-schema safety

## APIs

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/merchants/{m}/messages/{id}/attribution` | Idempotent attribution |
| `POST /api/v1/merchants/{m}/surveys/{s}/feedback/{id}/attribution` | Manual override in intake window |
| `GET /api/v1/merchants/{m}/surveys/{s}/issues` | Issues + opinions |
| `GET /api/v1/merchants/{m}/surveys/{s}/feedback-stats` | Counts |
| `GET /api/v1/merchants/{m}/evidence/{id}` | Evidence fetch |
| `POST /api/v1/merchants/{m}/surveys/{s}/evidence/verify` | Deterministic Evidence ID check |
| `POST /api/v1/merchants/{m}/surveys/{s}/retrieval/search` | Hybrid retrieval |
| `POST /api/v1/merchants/{m}/vision/compare` | Qwen-VL V1/V2 compare |
| `GET /api/v1/merchants/{m}/vision/comparisons/{id}` | Compare result |

## Design notes

- Attribution uses server `received_at` and survey intake window; card / reply-chain / ambiguous / out-of-window states are explicit. No forced revision without an anchor.
- Opinions use a versioned rule extractor (`r2-rule-1`) with structured polarity/attribute/canonical/quote/confidence; issues are revision-scoped with independent author counts.
- Evidence IDs are server-allocated with checksum + seq; model-fabricated Evidence IDs are rejected.
- Embedding/Qwen adapters are real HTTP clients; CI uses local stubs. Keys come from env only.
- Retrieval authorizes merchant/survey/revision/visibility/watermark first, then lexical + vector with RRF k=60; vector failures degrade to lexical.
- Vision reads MinIO assets only after revision binding checks; missing assets / model outage return `GAP`.

## Tests

- `FlywayR1MigrationTest` — empty DB V1→V3 and V1→V2/V3 upgrade
- `FoundationTest` / `R1BusinessTest` — R0/R1 regression including Flyway `1,2,3`
- `R2FeedbackRetrievalVisionTest` — attribution, merge, evidence verify, hybrid/degrade, vision success/gap/fabricated ID

## Exit status

**R2 status: `BLOCKED_EXTERNAL_CREDENTIALS` — not PASSED.**

Offline implementation, protocol stubs, PR, and CI may be green. Formal R2 completion still requires at least one live OpenAI Embedding call and one live Qwen-VL compare with recorded model/usage fields.

## Real provider gate

Offline from GitHub CI. Requires `CRAFTPILOT_OPENAI_API_KEY` and `CRAFTPILOT_QWEN_API_KEY` (and optional base URL / model overrides). See `R2-provider-smoke.md`. Do not enable paid calls in Actions.

## NULL parameter binding (watermark / material)

`survey_session.evidence_watermark` is `bigint` (NULL while ACTIVE). `evidence_chunk.material_code` is `varchar(80)`. Binding untyped JDBC NULLs into `(:watermark IS NULL OR …)` / `(:material IS NOT NULL AND …)` fails with `could not determine data type of parameter`. Production retrieval branches omit unbound NULL filters; when present, parameters are cast to `bigint` / `varchar(80)`. Regression: `nullWatermarkAndNullMaterialBindingIsTypedAndReproducible`.
