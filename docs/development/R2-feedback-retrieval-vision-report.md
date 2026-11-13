# R2 Feedback, Retrieval, and Vision — Implementation Report

## Exit status

**R2 PASSED — PR READY**（不自动合并 main）

离线实现、协议 Stub 测试、真实供应商最小联调、P0/P1 复查与最新 Head CI（`059ad65` / Actions run `43334930301`）均已通过。

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
- Evidence IDs are server-allocated with checksum + seq; model-fabricated Evidence IDs are rejected; manual override reactivates revoked opinion evidence.
- Embedding/Qwen adapters are real HTTP clients; CI uses local stubs. Keys come from env only.
- Retrieval authorizes merchant/survey/revision/visibility/watermark first, then lexical + vector with RRF k=60; vector failures degrade to lexical.
- Vision reads MinIO assets only after revision binding checks; PNG width/height ≤10 → `IMAGE_DIMENSION_TOO_SMALL`; missing assets / model outage return `GAP`.

## NULL parameter binding (watermark / material)

`survey_session.evidence_watermark` is `bigint` (NULL while ACTIVE). `evidence_chunk.material_code` is `varchar(80)`. Untyped JDBC NULLs fail PostgreSQL inference. Production branches omit unbound filters; present values cast to `bigint` / `varchar(80)`. Regression: `nullWatermarkAndNullMaterialBindingIsTypedAndReproducible`.

## Tests

- `FlywayR1MigrationTest` / `FoundationTest` / `R1BusinessTest` / `R2FeedbackRetrievalVisionTest`（CI 默认）
- `R2ProviderSmokeTest`（本地 live，已从 Surefire 默认排除；结果见 `R2-provider-smoke-results.md`）

## Real provider gate（已完成）

| 项 | 结果 |
|---|---|
| OpenAI Embedding | `text-embedding-3-small` SUCCEEDED，tokens=4，request id 已脱敏 |
| Hybrid retrieval | `hybrid`，hits=2，pgvector embedded chunks=2 |
| Qwen-VL | `qwen3-vl-plus` SUCCEEDED，in=223 out=132，request id 已脱敏 |
| 视觉差异 | status=SUCCEEDED，diff_count=2，evidence 已关联 |

联调中曾因 1×1 PNG 触发供应商 `InvalidParameter`（宽高须 >10）；已改为 ≥64×64 样例，并增加 PNG 尺寸前置 GAP。密钥未写入日志、提交或 PR。
