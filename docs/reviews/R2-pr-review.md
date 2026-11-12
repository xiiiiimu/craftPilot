# R2 PR Review — feature/r2-feedback-retrieval-vision

Reviewer: independent Bugbot pass on branch changes for PR #3.

## Verdict

Approve after remediation of authorization and attribution consistency findings (landed in follow-up commit on the same branch).

## Findings addressed

1. **Retrieval survey whitelist** — `RetrievalService.search` now requires `revisionId` ∈ `survey_revision` before lexical/vector recall.
2. **Search validation** — `RetrievalController` applies `@Valid`; blank queries rejected (422).
3. **Manual attribution stale opinions** — override supersedes prior ACTIVE opinions, revokes their evidence, then re-aggregates onto the new revision (upsert on opinion unique key).
4. **Vision evidence survey binding** — vision evidence creation requires both revisions on the survey whitelist.

## Residual notes

- Real OpenAI Embedding / Qwen-VL smoke remains an offline gate (`docs/development/R2-provider-smoke.md`); local `.env` had no provider keys at implementation time.
- CI must stay on HTTP stubs; do not auto-merge until Actions is green and provider smoke is recorded when keys are available.
