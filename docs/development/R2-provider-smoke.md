# R2 real provider smoke (offline from CI)

## Status

**COMPLETED** — see `R2-provider-smoke-results.md`（脱敏结果）。默认 CI 不跑 live smoke。

## Purpose

Prove at least one live OpenAI Embedding call and one live Qwen-VL compare without spending budget on every GitHub Actions run.

## Prerequisites

Set in local `.env` (never commit):

- `CRAFTPILOT_OPENAI_API_KEY`
- `CRAFTPILOT_QWEN_API_KEY` (DashScope / Model Studio key)
- Optional: `CRAFTPILOT_OPENAI_BASE_URL`, `CRAFTPILOT_QWEN_BASE_URL`, model overrides

Qwen-VL uses the OpenAI-compatible chat completions protocol documented by Alibaba Cloud Model Studio (`/compatible-mode/v1/chat/completions`, multimodal `image_url` content, default model `qwen3-vl-plus`).

## Procedure

推荐一键（Testcontainers + 真实供应商，不走 Stub）：

```bash
cd backend
# requires CRAFTPILOT_OPENAI_API_KEY and CRAFTPILOT_QWEN_API_KEY in ../.env
mvn -Dtest=R2ProviderSmokeTest test
```

手工路径：

1. Start local stack (`scripts/dev-up.ps1`) with keys loaded.
2. Create two revisions with sample images（宽高须 >10）and an active survey.
3. `POST .../retrieval/search` — expect hybrid/vector and `model_call_attempt` embedding SUCCEEDED.
4. `POST .../vision/compare` — expect SUCCEEDED（或明确 GAP）及 vision_compare 用量字段.
5. Record redacted request ids / model / status / tokens into `R2-provider-smoke-results.md`.

## Budget

Use the smallest images and a single query. Stop after one success per provider.
