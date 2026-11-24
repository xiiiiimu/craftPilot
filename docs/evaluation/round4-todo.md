# 第 4 轮待办：Agent Runtime 上下文构造

来源：第 3 轮中途对 `ContextBuilder` 的只读审查（2026-11-24），以及第 3 轮的评测数据（[round3.md](round3.md)）。编号沿用审查报告。④（评测导出 `eval-contexts.jsonl` 和 `raw_final_content`）已在 `f72a0df` 完成，不再列出。

改动量是估算，不含评测重跑。凡是改 `runtime/` 的条目都要先写失败测试。

## 影响评测结果

| 编号 | 问题 | 证据 | 建议改法 | 改动量 |
|---|---|---|---|---|
| ① | 一致性校验的纠正轮回放上一轮答案时，用的是 `agent_turn.response_summary`，它在 `AgentLoop` 写入时被截到 900 字符（列宽 1000）。带 `pollReadings` / `concerns` 的最终答案可能更长，模型在纠正轮看到的是被截断、不合法的 JSON | `AgentLoop.java` 写入 `truncate(infer.content(), 900)`；`ContextBuilder` 在 `consistency_feedback` 不为空时回放 `response` | 新迁移加一列保存完整的最终答案内容（或只为带 feedback 的轮次保存），`ContextBuilder` 回放完整内容；**开启一致性校验前必须先修** | 迁移 + 约 15 行 + 1 个测试 |
| ② | 历史固定只保留最近 6 轮。第 8 轮时第 1 轮（通常是 `survey_get_stats`）被丢掉，模型看不到投票数，而一致性校验对比的是整个 Run 的工具结果，纠正提示也让模型"重读 survey_get_stats" | C18 第 8 轮输入 token 从 1790 降到 1754 | 始终保留 `survey_get_stats` 的结果（或第 1 轮），或改成按 token 预算裁剪，并把丢掉的轮次记进快照（见 ⑤） | 约 40 行 + 2 个测试 |
| ③ | 上下文里没有剩余轮数信号，最后一轮也不强制给最终答案；模型在证据稀少时一直搜索直到耗尽轮数或 token。**改变业务行为，需要先确认** | C18 在 v2 / v4 / v4.1 / v4.2 都跑满 8 轮；step 1c 有 18 个 Run 因 MAX_TURNS / TOKEN_BUDGET / MAX_TOOL_CALLS 停止，hold_sparse 因此 13 → 6 | 在系统或用户消息里写"第 N / 8 轮"，最后一轮不提供工具（`tool_choice: none`）或明确要求给出最终答案 | 约 20 行 + 1 个测试 |

## 影响可调试性

| 编号 | 问题 | 建议改法 | 改动量 |
|---|---|---|---|
| ⑤ | `context_snapshot` 的 `instruction_version` 写死为 `'r3-ctx-1'`（不是实际的提示词版本），`evidence_refs_json` 总是 `[]`，`omitted_reason` 总是 `""`（即使窗口丢掉了轮次）；也不记录模型名和 `max_tokens` | 写入实际的提示词版本 / content hash、窗口丢掉的轮次和原因 | 约 20 行 + 1 个测试 |
| ⑥ | `ContextBuilder` 没有直接的单元测试。现有覆盖都是间接的（`ResearchReleaseV2Test` 检查系统提示词、`FinalConsistencyTest` 检查纠正文字、各类桩按是否有 `"role":"tool"` 分支），6 轮窗口、tool_calls 与 tool 消息的配对、feedback 的位置都没有测试 | 补 `ContextBuilder` 测试：窗口边界、配对、feedback 顺序、空工具批次 | 约 120 行测试 |

## 只是代码整洁

| 编号 | 问题 | 建议改法 | 改动量 |
|---|---|---|---|
| ⑦ | 两条历史查询（有无 `task_id`）几乎重复；`loadToolResults` 每轮一次查询（N+1）；注释"keep last 6 turns under budget"有误导（并没有按预算裁剪）；`truncate` 在 `AgentLoop` 和 `SupervisorLoop` 各有一份 | 合并查询、一次取回所有工具结果、改注释、抽公共方法 | 约 30 行 |
| ⑧ | `tool_batch_json` 解析失败时，`catch` 只加回 assistant 文本，却仍然加入该轮的 tool 消息，形成没有对应 tool_calls 的 tool 消息，OpenAI 会返回 400。目前没有发生过 | 解析失败时同时跳过该轮的 tool 消息，或整轮降级为文本摘要 | 约 5 行 + 1 个测试 |

## 建议顺序

① → ⑥（先有测试兜底）→ ② → ⑤ → ⑧ → ⑦；③ 改变业务行为，单独确认后再做。①②③ 做完后，用 v2 在 dev 上重跑一次作为新的参照，再决定是否重新评估一致性校验（需要能输出 `pollReadings` / `concerns` 的提示词）。
