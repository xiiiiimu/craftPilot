# R2 real provider smoke (offline from CI)

## Purpose

Prove at least one live OpenAI Embedding call and one live Qwen-VL compare without spending budget on every GitHub Actions run.

## Prerequisites

Set in local `.env` (never commit):

- `CRAFTPILOT_OPENAI_API_KEY`
- `CRAFTPILOT_QWEN_API_KEY` (DashScope / Model Studio key)
- Optional: `CRAFTPILOT_OPENAI_BASE_URL`, `CRAFTPILOT_QWEN_BASE_URL`, model overrides

Qwen-VL uses the OpenAI-compatible chat completions protocol documented by Alibaba Cloud Model Studio (`/compatible-mode/v1/chat/completions`, multimodal `image_url` content, default model `qwen3-vl-plus`).

## Procedure

1. Start local stack (`scripts/dev-up.ps1`) with keys loaded.
2. Create two revisions with sample images and an active survey (same path as R2 tests).
3. `POST /api/v1/merchants/{m}/surveys/{s}/retrieval/search` with a material query — expect `retrievalMode` hybrid/vector/lexical and a `model_call_attempt` row for `embedding` with `SUCCEEDED`, tokens, and model name.
4. `POST /api/v1/merchants/{m}/vision/compare` with left/right revision IDs — expect `SUCCEEDED` or explicit `GAP`, plus `model_call_attempt` for `vision_compare` with provider request id / token usage when the vendor returns them.
5. Record redacted request ids, model names, status, and token fields in the PR or handoff notes.

## Budget

Use the smallest images and a single query. Stop after one success per provider.
