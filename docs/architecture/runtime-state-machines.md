# CraftPilot Runtime 状态机

## 时间与授权裁决

所有时间为 PostgreSQL `timestamptz`。在 `READ COMMITTED` 下先取得相关锁并读取已提交行，再取 `clock_timestamp()` 为该操作的裁决时间 t。事务只做有限数据库操作。截止判断用严格小于。成功提交的操作以锁内裁决为业务生效点，回包或提交可见时间可能晚于截止。已回滚操作无效。

| 操作或窗口 | 条件及事务 |
|---|---|
| 创建和启动调查 | 锁定 Group，先推进该群旧调查的到期状态，检查成员权限和活动调查唯一性，再创建 CREATED 并进入 ACTIVE。记录 `starts_at=t`、`planned_ends_at=t+48h`、`intake_until=planned_ends_at`、`closing_deadline_at=intake_until+10min`。 |
| 消息、投票及反馈提交 | 锁定 Group 与当前 Survey。群聊消息以锁内 `received_at=t` 持久化；重复键重放原归属。仅在 `starts_at<=t<intake_until` 且 ACTIVE 时增加调查归属、投票或可供研究的反馈投影。Worker 写反馈只锁 Survey 及其后继资源，无需锁 Group。超期消息仍写普通群聊，超期投票返回 409。已经接收、但尚未形成投影的原文保留；异步迟到投影不得进入冻结研究，并计入覆盖缺口。 |
| 提前关闭 | 群管理员或商家锁定 Group、Survey；若仍有效 ACTIVE，将 `intake_until=min(t,planned_ends_at)`，重算 `closing_deadline_at`，按同一裁决冻结并进入 CLOSING。已到期则应用自然关闭规则。重复关闭返回现状。 |
| 自然关闭 | `t>=intake_until` 时立即使用有效阶段 CLOSING，即使物理状态仍为 ACTIVE；定时器和推进写事务均可幂等物化。逻辑关闭时间为 intake_until，单独记录物化时间 `closing_started_at`。冻结 `evidence_watermark`，撤销订阅、未消费审批、未启动 Run，处理等待、暂停和未启动子任务。 |
| 收尾 | `intake_until<=t<closing_deadline_at` 且未 CLOSED。仅在截止前已启动、仍可执行的同一 Run 及已启动任务继续。允许读取冻结证据、已获准派发且来源已在冻结范围内的只读结果、对冻结输入做纯计算/模型推理、汇合、发布报告和故障换手。禁止新增 Ticket、Attempt、Run、Agent 子任务，禁止跨调查记忆写入、MCP 新采集、审批创建/决定/消费及人工恢复。未启动必需子任务按图失败，允许部分的任务记 SKIPPED。 |
| CLOSED | 所有获准收尾 Run 达终态可提前关闭，否则 `t>=closing_deadline_at` 时立即有效 CLOSED。物化写 `closed_at`，失效未完 Run、撤销权限、递增围栏及入队清理。物化延迟期间所有入口仍拒绝执行。新调查须先完成该群到期物化。 |
| 历史报告确认 | 商家在独立事务校验当前成员身份、报告 hash、可见性及待确认 Decision。既有报告可在 CLOSED 后确认；不触发 Subscription、Run 或工具权限。 |

调查证据发布持有同一 Survey 锁，在锁内分配单调 seq 并验证 ACTIVE；闭合时冻结已提交 seq。普通消息写与投票亦遵循此序。事务内的大量后代状态可经 Outbox 分批清理，父级时间/围栏先行失效。收尾生成的 Run 私有产物必须引用冻结源和许可范围，不占调查证据序列。迟到结果可写独立诊断，不能改变执行数据或重新获权。

## 对象及终态传播

| 对象 | 转移规则 |
|---|---|
| Subscription | ACTIVE → REVOKED（人工关闭）或 EXPIRED（自然截止）；Trigger 再查实时窗口和作用域。 |
| Ticket | CREATED → QUEUED → RUNNING ↔ WAITING_APPROVAL。终态 COMPLETED、FAILED、CANCELLED、EXPIRED。可重试故障在 ACTIVE 中可回 QUEUED；未决效果阻断。 |
| Assignment | READY → CLAIMED → DONE/FAILED/CANCELLED/EXPIRED。一个 Ticket 可先后拥有多个分配，单时刻一个活跃分配；同一 Assignment 对应一次 Attempt。关闭阶段可更新已有 Assignment 的归属和完成状态。Worker 换手沿用 Assignment、Attempt 和 Run。 |
| Attempt | PENDING → EXECUTING → SUCCEEDED/FAILED/CANCELLED/EXPIRED/LOST。LOST 表示无法安全恢复的尝试，须无未决效果且 ACTIVE 才可创建新 Assignment 和 Attempt。Run 终态与 Attempt、Assignment、Ticket 处置同事务。 |
| Run | QUEUED → RUNNING；RUNNING ↔ WAITING_APPROVAL 或 PAUSED；租约失效后由新 Worker 原子进入 RECOVERING，账本调和后恢复合法工作。SUCCEEDED/FAILED/CANCELLED/EXPIRED 为终态。用户取消先落 CANCEL_REQUESTED 并递增 epoch，清理后 CANCELLED；与到期争抢以先落的终止原因为准。调查截止时 WAITING_APPROVAL/PAUSED、排队未启动或无权收尾的 Run 直接 EXPIRED。 |
| Task / Join | READY → RUNNING → SUCCEEDED/FAILED/CANCELLED/SKIPPED/EXPIRED；BLOCKED 记录依赖或审批原因，解除后回 READY。Join 固化任务集合、必需/可选标记与截止；每个子任务只接纳一次最终结果，按逻辑任务键排序归并。必需失败阻断，可选失败生成缺口。父撤销通过父 Run 围栏即时阻断所有后代。 |
| Turn | REQUESTED → RESPONSE_COMMITTED / FAILED / ABANDONED；完整响应、工具意图、事件、后续待办和 Checkpoint 同提交。文本流增量可用于进度展示，不能触发工具。 |
| Decision | 报告发布为每个可确认样品结果写 PENDING_CONFIRMATION，唯一 report/revision。Owner 以版本与 report hash 确认，进入 CONFIRMED，记录所选建议及原因。报告失效使未确认 Decision 不可用；历史确认记录留存审计。 |
| Approval | PENDING → APPROVED/REJECTED/EXPIRED/REVOKED，APPROVED → CONSUMED/EXPIRED/REVOKED。有效期为 `min(created_at+24h, current intake_until)`，持久 expires_at 是签发时上界。审批、续跑领取及消费均验证 t、有效 ACTIVE、当前头、runVersion 和绑定操作。拒绝/单独过期仅可在仍 ACTIVE 时走预声明的无副作用分支。收尾不消费旧批准。 |

人工审批决定在事务内记录一次 ResumeCommand，唯一键绑定 Run、暂停头及审批操作。Worker 领取以 CAS 校验当前头、已决定状态、有效期、权限、版本及版本快照，记录已应用命令并推进当前头；审批必须仍有效，且真正产生效果时再核验并消费。暂停头在恢复后作为批准绑定锚点，实际消费使用该命令与当前头的同一运行链和待执行操作摘要，禁止其他分支借用批准。人工显式暂停恢复使用同一命令机制，故障接管用 leaseEpoch 机制。旧头只可审计。运行到期后的商家确认走 Decision 事务。

## 提交与故障恢复

一个 Run 只有一个协调 Worker 持有 lease。协调器在单进程以有界任务并行，独立心跳；每次换手或撤销原子增加 `lease_epoch`。续租仅当前 owner+epoch 且旧租约未过期时成功。执行线程带入不可变身份，所有状态和效果写入再验 owner、epoch、lease_until、权限及状态。并行子任务完成通过持久结果和业务键串行合并到 Run head。CAS 失败重读并只重放本地合并操作。

| 故障点 | 恢复行为 |
|---|---|
| 模型请求前、请求后、完整响应落库前 | 先持久 model_call_attempt 和预算预留。未发送可派发，已发送无持久响应按供应商能力先查；无查询且仍允许可限次重推理，记录可能重复费用。流片段不触发工具。 |
| 响应、工具意图或 Checkpoint 提交回包丢失 | 以逻辑 Turn、transition_key 查询当前头和关联意图。原子提交成功则续跑，失败则安全重试本地提交。 |
| 工具 PREPARED / DISPATCHING | 先提交逻辑调用与 intent。准备态可重新领取；DISPATCHING 以后即视为可能已发送。每次物理发送写 dispatch attempt，记录请求身份、预算及许可裁决。 |
| 只读工具结果丢失 | 先查持久收据，在预算和窗口内可重发，记录多次到达及费用；收尾仅允许冻结输入上的许可读取或模型计算。 |
| 同库业务写结果丢失 | 以稳定 operationKey 查询 effect_receipt。效果、唯一收据、审批消费、调用结果和推进事件同事务。重复成功返回既有收据且重验可见性。 |
| 外部写结果未知 | 当前发布工具均禁止外部写。未来仅在具备期限、业务幂等和状态查询契约时允许扩展；UNKNOWN 只查询，无法确认进入人工对账，不可自动重发或新建尝试绕过。 |
| 旧 Worker 与新 Worker 并发，或取消与迟到回包 | 失去围栏的 Worker 仅追加隔离诊断。有效 owner 才可提交可见结果。取消中断 HTTP/任务，远端执行或费用继续独立对账。 |
| 部分子 Agent 成功或 Worker 故障 | 保留已确认结果与预算账本；按子任务逻辑键接管未完项，重试不得增加委派名额。必需任务不可完成则 Run 失败或过期。 |

逻辑调用 PREPARED → DISPATCHING → SUCCEEDED/FAILED_FINAL/FAILED_RETRYABLE/UNKNOWN，待派发可 DENIED/CANCELLED。FAILED_RETRYABLE 在规则和期限允许时创建下一次物理发送记录。UNKNOWN 可由已确认收据调和为成功、失败或人工解决。整个链路使用至少一次派发、幂等收据和对账。

业务写、派发、恢复及报告发布在同一事务先锁定适用的商家、成员及已选授权记录，检查启用状态。撤销更新同一权威行，避免权限检查与业务效果竞态；动态证据候选在返回、派发和引用时再验。锁序为上述授权行（类别固定、同类按 ID）→ Group（仅群/调查入口）→ Survey → Ticket → Assignment → Run → Task（同类按 ID）→ Approval → Invocation → Budget。下游事务不得反向加锁。关闭和租约清理仅撤销权限，可从 Group 或 Survey 开始且不回锁授权行。凭证、MinIO、模型、MCP 和广播均在事务外。外部派发在事务内取得权限后再发网络请求；受控连接器接收端再次验期。第三方模型接收时间无法和数据库原子裁决，迟到回包不能生效，可能仍产生费用。
