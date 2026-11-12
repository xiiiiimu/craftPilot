# 已废弃的 CraftPilot DeerFlow 集成契约

本文件仅供历史源码研究。DeerFlow 的部署、固定运行时版本、API 适配及启用步骤均已废弃。当前执行面由 CraftPilot 自研。 [现行架构](architecture/runtime-architecture.md) · [现行接口](architecture/runtime-contracts.md)

静态审阅候选为 `bytedance/deer-flow` 的 `main@04a9833763f7daae9b3ff19d9c1d45fb6ab21c61`。该提交仅标记当时的静态源码审阅范围。以下接口和测试设计均为历史记录。 [源码审计](source-audit.md#deerflow)

## 已核实的上游 HTTP 表面

下表路径来自 FastAPI 路由前缀和装饰器。以下网关路径仅记录上游源码行为。 [Gateway 挂载](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/app/gateway/app.py)

| Java 适配器方法 | 上游方法和路径 | 已读源码行为 |
|---|---|---|
| createThread | `POST /api/threads` | 请求允许 `thread_id`、`assistant_id`、`metadata`、`project_id`。指定 ID 时，服务在生命周期锁内查重并初始化检查点。 |
| startRun | `POST /api/threads/{thread_id}/runs` | 后台执行，响应含 `run_id`、`thread_id`、`status`。接受 `Idempotency-Key`，路由按调用身份与线程限定重试键。请求模型允许 `assistant_id`、`input`、`metadata`、`context`、`multitask_strategy` 等字段，拒绝未声明字段。 |
| getRunStatus | `GET /api/threads/{thread_id}/runs/{run_id}` | 按调用者作用域读取并检查 Run 所属线程。结果正文另通过该 Run 的 `/messages` 查询，运行成功还须通过报告校验。 |
| requestCancel | `POST /api/threads/{thread_id}/runs/{run_id}/cancel?wait=false&action=interrupt` | interrupt 保留检查点，rollback 是另一动作。源码根据取消结果可返回 202 或在部分 wait 场景返回 204。冲突可能返回 409。随后继续按 Run ID 查询。 |
| resumeFromCheckpoint | `POST /api/threads/{thread_id}/runs` 为待联调候选 | 模型允许 `command`、`checkpoint_id`。HITL 校验 `command.resume.responses`、审批请求 ID 和当前检查点。另有 `POST /api/threads/{thread_id}/runs/{run_id}/tool-approvals/{request_id}`，属于另一审批入口，须分别验证。 |

[Thread 路由](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/app/gateway/routers/threads.py) · [Run 路由](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/app/gateway/routers/thread_runs.py) · [请求模型](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/app/gateway/run_models.py) · [HITL 校验](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/app/gateway/hitl_approval.py)

可选实时观测使用 `GET /api/threads/{thread_id}/runs/{run_id}/join` SSE，或以 `POST .../runs/stream` 同步启动并订阅。创建流的 `on_disconnect` 默认 `cancel`，可显式请求 `continue`；`stream_resumable` 仅接受 null/false，`webhook` 仅接受 null。审计事件可用同一 Run 的 `GET .../events`，支持 `after_seq` 和 `limit`。首版采用后台创建和轮询，SSE 仅作进度展示。具体 SSE 事件名、数据结构、持久事件范围和断线表现由实测固定。

## 历史 Java 适配与生命周期设计

- `createThread(scope)` 使用预先持久化的稳定 UUID，绑定一条 Ticket，复用前校验映射与所有者。每条 Ticket 只读取自身线程。
- `startRun(thread, request, idempotencyKey)` 先保存 Attempt、请求摘要及稳定重试键。网络结果不明时用同一身份、线程和键对账。无法消歧时保留 UNKNOWN，禁止自动另开 Run。确认旧尝试已终止且调查仍允许新 Run 时，才创建新的 attempt_no 和幂等键。
- `getRunStatus` 保留原始上游状态和时间。上游状态映射、暂停标记及终态须由契约测试确定。未知值进入 UNKNOWN 并告警，晚到事件不能覆盖已提交的业务终态。Run 成功后拉取本次报告，校验后在事务内发布。
- `requestCancel` 先记录 CANCEL_REQUESTED，再异步发送请求和对账。202、冲突、查询暂失联与服务重启进入对账流程。授权到期先冻结本地任务和工具权限，远程状态继续单独追踪，直至确认停止或升级运维。晚到结果留审计记录。
- `resumeFromCheckpoint(approval)` MVP 默认关闭远程恢复能力。启用前锁定 Ticket、审批、当前 Attempt 和预期检查点，并检查授权与当前中断。审批写入与恢复投递分开，投递时再次验期，重复点击只产生一次恢复意图。过期、拒绝、旧检查点、缺失中断或协议不支持均保持关闭；必要时请求取消。

## 历史业务工具及报告设计

服务间使用专用认证。Java 签发短时工具授权，绑定 merchant、survey、ticket、attempt、允许的版本与工具及有效期。每次工具调用按数据库状态再次核对 token 绑定、租户与证据范围。ACTIVE 支持授权读取，CLOSING 仅允许已在途 Attempt 在收尾时限内读冻结证据，CLOSED 拒绝工具访问。DeerFlow 服务及子 Agent 仅能访问授权接口，无业务数据库凭证；关闭共享记忆和跨线程引用，禁用任意执行及非白名单网络工具。图片通过限时私有 URL 或代理读取，时限受调查/收尾授权约束。

自有工具能力为读取样品、BOM 与证据，获取受控图片分析结果，以及提交结构化研究结果。结果含 survey、style、revision、建议 `PRODUCE|REVISE|TRIAL|HOLD`、逐条主张及 evidence IDs、不确定性、缺失信息和模型使用量。Java 对每个 evidence ID 校验真实性和范围，并补齐来源 URI、时间和 A/B/C 等级。缺失或不可验证的事实标为未知。商家另行确认建议。

## 历史适配器契约测试设计

以下测试为原集成方案的历史记录，已退出 CraftPilot 交付范围。

1. 创建固定线程 ID 后重放，检查同身份一致性与异身份隔离；验证服务账号权限和网关路由。
2. 启动后台 Run，模拟请求提交后连接中断，验证同键重放、异载荷冲突语义、响应 ID 及跨进程重启对账。核实状态全集、结果读取、Token 及事件字段。
3. 覆盖流断线、重复或乱序事件与可查询终态。测试运行中取消、结束与取消竞态、不同 worker 取消、网关重启、重复取消及远程未知。
4. 触发可重复的审批中断，记录当前检查点、请求 ID 与恢复请求。覆盖通过、拒绝、重复、过期、旧检查点与越权，并证明拒绝后无工具副作用。
5. 用跨租户、跨样品版本、伪造证据和截止时间后调用验证工具拒绝；确认禁用能力无法经 Skills 或 Subagent 绕开。

原方案曾包含 DeerFlow 与 Mock Adapter 的切换。该切换随集成方案一并废弃。
