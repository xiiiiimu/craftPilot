# CraftPilot R1 业务与可靠事件实施报告

核查时间：2026-11-13（Asia/Shanghai）  
分支：`feature/r1-business-reliable-events`  
基线：`3259fcc`（R1-1.6 merged）

**最终状态：`R1 BUSINESS PASSED — PR READY`（以本报告提交后最新 PR Head CI 为准）**

## 功能矩阵

| 能力 | 状态 | 主要代码位置 |
|---|---|---|
| Campaign / Style / Revision / BOM | 已实现并测试 | `catalog/CatalogService.java`, `CatalogController.java` |
| 不可变 Revision 快照 | API 无更新路径；`snapshot_hash` 固化 | `CatalogService.createRevision` |
| Asset STAGED→AVAILABLE / 失败不误报可用 | 已实现并测试 | `catalog/AssetService.java` |
| MinIO 私有对象代理读取 | 已实现并测试 | `GET .../assets/{id}/content` |
| Group / Membership | 已实现并测试 | `groupchat/GroupService.java` |
| 消息幂等、group_seq、补拉 | 已实现并测试 | `GroupService.postMessage/listMessages` |
| WebSocket 鉴权订阅与 hint 广播 | 已实现并测试 | `events/WebSocketConfiguration`, `GroupRealtimeHub` |
| Survey ACTIVE/CLOSING/CLOSED、48h/10min | 已实现并测试 | `survey/SurveyService.java` |
| 并发开启冲突 | DB 部分唯一索引 + 409 | `survey_session_one_open_idx` |
| Vote / Purchase Intent / 统计 | 已实现并测试 | `SurveyService.vote/stats` |
| Outbox 同事务写入、SKIP LOCKED、重启恢复、消费幂等 | 已实现并测试 | `events/OutboxWriter`, `OutboxDispatcher` |
| 订阅创建与关闭撤销 | 已实现并测试 | `subscription` + close/advance |
| 多租户隔离 | 复合 FK + 服务校验 + 测试 | `tenant/TenantAccess` |
| 原有 7 个基础集成测试 | 回归通过（Flyway 断言扩展为 V1+V2） | `FoundationTest` |

R1 明确不做：R2 Evidence/归因聚合、R3 Ticket/Run/Workflow/MCP。Subscription/Outbox 为后续 Runtime 保留稳定事件契约。

## 数据库迁移

- `V1__identity.sql`：不变  
- `V2__r1_business.sql`：新增 craft_group、group_member、launch_campaign、candidate_style、sample_revision、bom_item、asset、revision_asset、group_message、message_*、survey_*、subscription、outbox、consumer_receipt、api_command 及约束/索引  

空库：Flyway 应用 V1+V2。  
现有 R1 库：可从 V1 升级到 V2（不改历史脚本）。本轮未删除 `E:\shopper\data`。

## API / WebSocket

REST（均在 `/api/v1`，商家作用域路径）：

- Campaigns / Styles / Revisions / Assets  
- Groups / Members / Messages（`afterSeq` 游标）  
- Surveys / Close / Polls / Vote / Stats  

WebSocket：`/api/v1/ws`  
握手走 Spring Security 认证；客户端发送 `subscribe{merchantId,groupId}`，服务端复核群成员后订阅。广播仅为已提交 `MESSAGE_HINT`；客户端用 REST 补拉。

## Survey 与时间裁决

- 开启：锁 Group → 推进到期调查 → 唯一开放约束 → ACTIVE，`planned_ends_at/intake_until=+48h`，`closing_deadline=+10min`  
- 自然/人工关闭：物化为 CLOSING，冻结 watermark，撤销 subscription  
- 截止后：CLOSED；重复 close 经 `api_command` 幂等  
- 投票仅 `ACTIVE && t < intake_until`  

## Outbox

- 业务写与 Outbox 同事务  
- Dispatcher：`FOR UPDATE SKIP LOCKED` 领取 PENDING/FAILED/过期 IN_FLIGHT  
- 至少一次；`consumer_receipt` 幂等  
- 失败退避，耗尽进 DLQ  
- 测试中关闭定时轮询，改为 `dispatchOnce()` 显式驱动  

## 测试结果（本地已执行）

命令：

```powershell
cd E:\shopper\backend
.\mvnw.cmd -B -ntp clean verify
```

结果：**Tests run: 14, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**

- `FoundationTest`：7  
- `R1BusinessTest`：7（目录/资产/租户、资产失败态、消息幂等+WS+Outbox、调查/投票/关闭、并发投票、多 Worker claim、Flyway）  

## 剩余风险

- MinIO 镜像仍有包级安全告警：仅限本机开发，非生产就绪。  
- Revision 业务不可变依赖“无更新 API + 快照哈希”；DB 超管仍可直接 UPDATE（与既有设计一致，靠流程与审计约束）。  
- R1 未实现完整 Trigger→Ticket 执行链（属 R3）。  
- WebSocket 广播为进程内 hub；多实例部署需后续共享订阅层（当前单体可测）。  

## R2 准入

R1 业务与可靠事件在本分支验证通过后，具备进入 R2（反馈/证据/检索）的数据与事件前置条件。需本 PR 合并且 main CI 成功后开始 R2。
