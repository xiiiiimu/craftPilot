# CraftPilot R1 业务与可靠事件独立审查

**审查结论：建议合并（待本报告对应 Head 的 CI 成功）**

审查日期：2026-11-13（Asia/Shanghai）  
PR：https://github.com/xiiiiimu/craftPilot/pull/2  
Base：`3259fccacd1a7cdc9ccf5e893af54c0aea844475`  
说明：提交本报告后 Head 将更新；合并必须以最新 Head CI 为准。

证据标记：已执行并验证 / 静态审查确认 / 文档推断 / 尚未验证。

## 功能覆盖（已执行并验证）

| 能力 | 证据 |
|---|---|
| Campaign / Style / Revision / BOM | `R1BusinessTest` 目录用例 |
| Asset STAGED→AVAILABLE / 失败不误报 | 上传成功 + 失败态内容拒绝 |
| Group / Message 幂等 / 补拉 | 顺序与并发同 `clientMsgId` |
| WebSocket 鉴权 hint | Basic 握手 + subscribe + MESSAGE_HINT |
| Survey / Vote / Purchase Intent | ACTIVE 订阅、PREFERENCE 与 PURCHASE_INTENT 分统计、关闭幂等、窗口后 409 |
| Subscription 撤销 | 关闭后 ACTIVE 订阅为 0 |
| Outbox 原子写 / SKIP LOCKED / 重启恢复 | 回滚无残留、claim、重投收据仍唯一 |
| Flyway 空库与 V1→V2 升级 | `FlywayR1MigrationTest` + 约束反例 |
| Foundation 回归 | 7/7 |

本地：`mvnw -B -ntp clean verify` → **18 tests, 0 failures, 0 errors, 0 skipped**。

## 问题分级

### P0
无。

### P1
无。

### P2
1. WebSocket hub 进程内，多实例需后续共享层。  
2. Revision 不可变主要靠无写 API；DB 超管仍可改行。  
3. MinIO 镜像包级告警仍在（本机开发限制）。

## 分支保护
Require PR + required check `Build, test and repository security` + enforce admins；approving reviews = 0；禁止 force push/delete。

## 合并建议
最新 Head CI 全绿且无 P0/P1 后，由项目所有者决定合并。**不自动合并。**
