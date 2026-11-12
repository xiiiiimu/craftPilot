# CraftPilot R1 业务与可靠事件独立审查

**审查结论：建议合并（待本报告提交后最新 Head CI 成功）**

审查日期：2026-11-13（Asia/Shanghai）  
PR：https://github.com/xiiiiimu/craftPilot/pull/2  
审查时 Base：`3259fccacd1a7cdc9ccf5e893af54c0aea844475`  
审查时 Head：`d94d55fb813b9f0908cd0a063696b791e3e9d6c8`  
审查前 CI：https://github.com/xiiiiimu/craftPilot/actions/runs/43289672725（success，与审查时 Head 一致）

证据标记：已执行并验证 / 静态审查确认 / 文档推断 / 尚未验证。

## 审查范围

- `V2__r1_business.sql` 与复合租户 FK、部分唯一索引  
- Catalog / Asset / Group / Survey / Outbox / WebSocket 实现  
- `FoundationTest` + `R1BusinessTest`  
- CI 工作流未削弱（gitleaks / compose / verify / OSV）  
- 实施报告 `docs/development/R1-business-implementation-report.md`

## 关键检查

| 项 | 结论 | 级别 |
|---|---|---|
| Revision 无更新 API；版本号唯一 | 通过 | 静态审查 + 测试 |
| Asset 失败保持非 AVAILABLE | 通过 | 已执行并验证 |
| 跨租户资产/消息 404 | 通过 | 已执行并验证 |
| 消息 client_msg_id 幂等与冲突 | 通过 | 已执行并验证 |
| WS 认证后订阅；广播后 REST 补拉 | 通过 | 已执行并验证 |
| Survey 并发开启 409；关闭幂等；窗口后拒绝投票 | 通过 | 已执行并验证 |
| 并发投票单行唯一 | 通过 | 已执行并验证 |
| Outbox SKIP LOCKED、重启重投、消费收据幂等 | 通过 | 已执行并验证 |
| Flyway V1+V2；Foundation 7 项回归 | 通过 | 已执行并验证 |
| 本地 `clean verify` 14/0/0/0 | 通过 | 已执行并验证 |
| PR CI success @ d94d55f | 通过 | 已执行并验证 |

## 问题分级

### P0
无。

### P1
无阻断项。

### P2
1. WebSocket hub 为进程内实现，多实例需后续共享层。  
2. Revision 不可变主要靠应用层无写路径；DB 超管仍可改行。  
3. MinIO 镜像包级告警仍在（本机开发限制，非本 PR 引入）。

## 尚未验证
- 未对用户持久化 `E:\shopper\data` 做破坏性升级演练（按约束未触碰数据）。  
- 未做生产级多节点 WS/Outbox 压测。  
- R3 Ticket/Run 路径刻意未实现。

## 分支保护（复核）
保留 Require PR、required check `Build, test and repository security`、enforce admins、禁止 force push/delete；approving reviews = 0（单人项目）。

## 合并建议
在本审查报告进入 PR 分支且**新 Head CI 全绿**、无 P0/P1 后，可由项目所有者合并。  
**本审查不自动合并。**
