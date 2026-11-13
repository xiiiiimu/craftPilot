# R2 PR Review — feature/r2-feedback-retrieval-vision

Reviewer: independent Bugbot passes + live provider smoke gate.

## Verdict

**R2 PASSED — PR READY**（不要自动合并）

P0/P1 无未关闭项。最新 Head CI `8e25cb8192a9a3f257f60a1efee4f62494ee47d4`（Actions run `43335837550`）success。

## Findings addressed

1. **Retrieval survey whitelist** — `revisionId` ∈ `survey_revision` before recall.
2. **Search validation** — `@Valid`；空白 query → 422.
3. **Manual attribution stale opinions** — supersede + revoke evidence，再聚合；`createFromOpinion` 对 REVOKED 同源证据会重新激活。
4. **Vision evidence survey binding** — 两侧 revision 均须在调查白名单。
5. **NULL watermark/material JDBC** — 可复现 unsafe SQL 失败测试 + 生产分支/类型转换（bigint / varchar(80)）。
6. **Qwen-VL 图片尺寸** — 供应商限制宽高 >10；前置 GAP `IMAGE_DIMENSION_TOO_SMALL`；live smoke 使用 64×64 授权样例。

## Live smoke（脱敏）

见 `docs/development/R2-provider-smoke-results.md`：

- Embedding：SUCCEEDED / hybrid / pgvector chunks>0
- Vision：SUCCEEDED / tokens 与脱敏 request id 已记录

## Residual notes

- `R2ProviderSmokeTest` 不进默认 CI（防额度消耗）；需本地密钥手动跑。
- 不要把密钥提交进仓库；不要自动合并 main。
