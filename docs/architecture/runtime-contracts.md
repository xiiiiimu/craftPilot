# CraftPilot Runtime 接口契约

以下均为 CraftPilot 自有契约。JSON 使用 UTC 时间、UUID、版本号和显式 schemaVersion。租户、角色和 scope 从认证及数据库读取。错误包含 code、traceId、retryable，鉴权失败统一处理以避免资源枚举。

## 业务与执行 API

| 接口 | 请求与结果 |
|---|---|
| POST /api/v1/surveys/{id}/tickets | `{revisionIds, objective}`，管理员身份与 Idempotency-Key。返回 202 和 ticketId。到期返回 409 SURVEY_WINDOW_CLOSED。Outbox 触发走相同准入服务。 |
| GET /api/v1/tickets/{id} | 返回 Ticket、尝试、Run、可见审批及报告引用。跨租户返回 404。 |
| GET /api/v1/runtime/runs/{id}/events?afterSeq=N | 返回按 run 内 seq 排序的授权事件及 nextCursor。重连允许重读，客户端按 runId、seq 去重。 |
| POST /api/v1/tickets/{id}/cancel | `{expectedVersion,reason}`，同键重复返回原取消结果，接受时返回 202。终态返回现状。 |
| POST /api/v1/approvals/{id}/decision | `{expectedVersion,decision,answer?}`，商家身份。通过写入绑定操作的续跑指令。拒绝或过期封闭授权。旧版本返回 409，已过期返回 410。 |
| POST /api/v1/runtime/runs/{id}/resume | `{expectedVersion,checkpointId}`，授权商家，仅在有效 ACTIVE 且可恢复的显式暂停时接受。HITL 通过审批端点，故障恢复由 Worker 发起。 |
| POST /api/v1/decisions/{id}/confirm | `{expectedVersion,reportHash,decision,note}`，由商家确认可见历史报告，按报告版本去重。 |

所有写命令使用 `Idempotency-Key`，存调用者、操作、目标、请求摘要和原结果。同键异载荷返回 409。响应丢失时以原键重试。鉴权与资源可见性校验先于幂等结果重放；响应只引用资源 ID，读接口重新授权。业务 API 以以下契约为准。

## 模块接口

| 契约 | 输入、输出和失败语义 |
|---|---|
| TriggerService.accept | SourceEvent 含来源类型、来源键、survey、revision、occurredAt；规则评估记录阈值、批次和 disposition。输出 ticketId 或已去重、已到期、证据不足等理由。 |
| ModelPort.infer | `ModelRequest(profileVersion, messages, toolSchemas, outputSchema, maxTokens, deadline)` → 文本增量、工具参数增量、完整 ToolRequests 或 Final、Usage。工具请求含 providerCallId、toolName、arguments 和 ordinal；同一响应的调用 ID 必须唯一。工具结果保留该 ID，按原 ordinal 配对回传。仅完整响应可落工具意图。401/配置错误终止，限流及网络故障按预算重试。模型响应无法确认时记录计费不确定性。 |
| EmbeddingPort.embed / VisionPort.compare | 输入带内容摘要、模型版本和授权图片 ID。Embedding 验证 1536 维及顺序；视觉返回 visibleFeatures、differences、uncertainty、sourceImageIds。不可用时留下缺口，缓存限定租户及输入版本。 |
| WorkflowEngine.advance | `runId, expectedHead, leaseEpoch, event` → 下一节点、分支、并行任务、暂停或终态。版本化图校验必需节点、分支目标、最大循环及 join 规则。 |
| AgentExecutor.step | 以 ContextSnapshot、AgentSpec 和剩余预算执行一次推理，持久化模型输出与工具意图。结果为工具调用批次、委派、人工请求或完成。超轮数给出可审计终态。 |
| AgentDelegator.spawn / join | 输入 parentTask、agentSpecVersion、allowedEvidenceIds 或过滤范围、deadline、预算配额；分配 childTaskId。子任务只返回类型化结果、证据和缺口。必需子任务失败阻断，允许部分结果的节点记录缺口。 |
| SkillRegistry.resolve | `skillId, version, policy, context` → 校验 hash 的指令、输入/输出 schema、所需工具及 AgentSpec。仅发布的清单可用，版本失效暂停或终止并记录原因。 |
| ToolRegistry.dispatch | `runId, taskId, invocationId, toolVersion, arguments, leaseEpoch` → `ToolResult(status, dataRef, evidenceIds, retryClass)`。服务器为每个调用派生范围。校验失败或禁止无网络访问。仅已确认安全的调用可重试。 |
| Retrieval.search | query、授权 scope、模型版本、topK、过滤器 → chunkRef、证据 ID、分数、版本与来源；执行授权域内词法与向量融合，查询故障记录缺口。 |
| Memory.read / propose / commit | 读取已发布、未撤销且适用的偏好。提案绑定摘要 hash、来源、用途和适用范围；commit 必须消费单次审批。撤销即刻使条目不可读，后续运行重新验证。 |
| ContextBuilder.build | 固定版本的指令、授权工具、当前对话状态、证据、记忆和 Token 预算 → ContextSnapshot 与被省略内容原因。对证据过多做摘要并保留引用。预算不足时缩小检索或终止。 |
| CheckpointStore.append / claimResume | 追加不可变快照并 CAS 推进当前头；恢复提交 expectedHead、runVersion、resumeCommandId。失配拒绝，安全恢复从已持久化步骤继续。 |
| ApprovalService.request / decide / consume | 暂停绑定 head、操作摘要及到期时间；决定与消费均检查授权。工具写入、回执及审批消费同事务，临界失败按 [状态机](runtime-state-machines.md) 处理。 |
| RunControl.cancel / recover | 取消提交围栏并向子任务传播；恢复先调和工具执行账本，再恢复可用头。不可调和的外部写进入人工核查。 |
| EvidenceVerifier.validate / ReportStore.publish | 校验出处、采集时间、版本、可信等级、主张与证据关联。运行授权和租约有效时原子提交报告。证据不全时输出未知或阻断关键结论。 |
| Telemetry.record / EvalRunner.execute | 按 run、task、invocation 串联阶段和使用量。评测输入 fixtureVersion、profileVersion、mode，输出指标、traceRef 和实际证据。 |

## 工具清单与 MCP

| 工具 | 输入与结果 |
|---|---|
| sample.get_snapshot | revisionId → 图片 ID、BOM、版本历史和 A 级证据。 |
| survey.get_stats | revisionId → 投票、独立人数、mentions、样本窗口与证据。 |
| evidence.search | revisionId、query、topK → 授权片段与证据 ID。 |
| vision.compare | 两个已授权 revisionId → 可见差异、图片 ID、不确定性。 |
| connector.material_lookup | 预配置 connectorId、材料标识 → 受控 MCP 结构化结果、外部来源和采集时间。 |
| memory.propose_preference | 可审核摘要及来源 → 绑定审批意图；后续 commit 由 Workflow 调用。 |

MCP 客户端的初始协议目标为 2026-07-28 Streamable HTTP，连接器验收固定协议版本与服务实现。使用授权身份获取 `tools/list`，把允许的工具及 schema hash 固化为发布配置，再以 `tools/call` 执行实际查询。遵循该版本请求元数据及 HTTP 标头约定。分页有上限，目录漂移停止相关工具，响应超时记录失败。首个资料连接器只读，可由 CraftPilot 应用的独立配置提供标准 MCP 端点并读取商家真实上传的材料资料，从而完成真实 HTTP 往返，无需新增基础设施。MCP MRTR 仅允许登记过的字段、schema 和人工审批；关闭期或不支持的输入返回 MCP_INPUT_UNSUPPORTED。关闭响应流仅表示请求放弃，远端效果需按工具策略确认。 [工具协议](https://modelcontextprotocol.io/specification/2026-07-28/server/tools) · [传输协议](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports)

## R1 业务接口及错误

| 接口 | 请求、权限、响应 |
|---|---|
| POST /api/v1/groups | Owner，`{name}` → 201 groupId。 |
| PUT /api/v1/groups/{id}/members/{memberId} | Owner 或获授权群管理员，`{role,enabled,expectedVersion?}`；主体必须属于同一商家。只有 Owner 可授予群管理员权限。撤销即时失效。 |
| POST /api/v1/campaigns | Owner，`{name,description?}` → 201 campaignId。 |
| POST /api/v1/campaigns/{id}/styles | Owner，`{name,category}` → 201 styleId。 |
| POST /api/v1/styles/{id}/revisions | Owner，`{parentRevisionId?,assetIds,bomItems,notes?,costInputs?}` → 201 revisionId、revisionNo。首版 parent 为空，后续 parent 必须为当前版本，失配 409。 |
| POST /api/v1/assets / GET /api/v1/assets/{id}/content | 前者接收受限大小的认证 multipart，后者走应用权限代理。内容通过类型、完整性及权限校验后才可引用。 |
| POST /api/v1/groups/{id}/messages | 成员，`{clientMsgId,text?,assetIds?,replyToId?,card?}` → 201 messageId、groupSeq、receivedAt、surveyId 或 null。样品卡片限有权限人员。 |
| GET /api/v1/groups/{id}/messages?afterSeq=N | 当前成员，返回稳定游标和消息。WS 按 groupSeq 提示，重连从此 API 补读。 |
| POST /api/v1/groups/{id}/surveys | 管理员或 Owner，`{revisionIds}` → 201 surveyId、startsAt、intakeUntil，活跃或收尾冲突 409。 |
| POST /api/v1/surveys/{id}/close | 管理员或 Owner，返回有效状态和两截止时间，同键/重复关闭保持原结果。 |
| POST /api/v1/surveys/{id}/polls | Owner 或群管理员，`{revisionId,kind,options}` → 201 pollId。仅有效 ACTIVE，题型区分偏好与购买意愿。 |
| PUT /api/v1/surveys/{id}/polls/{pollId}/vote | 成员，`{optionId,expectedVersion?}` → 单成员有效票。调查截止返回 409。购买意愿由投票题类型显式标记，和成交分开。 |
| POST /api/v1/surveys/{id}/feedback/{id}/attribution | 获授权群管理员，`{revisionId,expectedVersion}` → 归因版本；仅 ACTIVE 且目标在调查白名单。更新追加归因审计并入队重新聚合。 |
| GET /api/v1/surveys/{id}/issues?revisionId=... | 有权限的商家/群管理员，返回意见、出处、独立人数及覆盖缺口。 |
| GET /api/v1/decisions/{id} | Owner，返回待确认建议、报告 hash 及版本。确认采用表中现有命令。 |

HTTP 422 表示 schema 或领域参数无效，409 表示版本、幂等载荷、活跃调查或执行状态冲突，410 表示授权人读取到已过期审批。不存在或不可见资源返回 404，可见但角色不足返回 403。状态变更采用事务内读写，REST 与 WS 共用服务端权限与事件源。

## 执行模块的补充契约

- `AssignmentService.allocate(ticketId, capabilityProfile, expectedTicketVersion, eventId)` 原子生成 Assignment、Attempt、Run 与 Outbox；`claim(runId, owner)` 取得当前 epoch 和 leaseUntil；`renew` 校验 owner、epoch 和未过期租约；`complete` 原子传播结果。重复事件或不合规阶段返回原引用或明确拒绝。
- `Supervisor.plan(taskId, snapshotId, turnId)` 返回带角色、目标、必需/可选标记和限额的 Handoff 请求。`Handoff.submit(handoffKey, parentTaskId, targetRole, objective, evidenceScope, budget, deadline)` 生成唯一 childTask，权限取交集。Join 返回排序后的 outputs、evidenceIds、gaps、usedBudget。失败、超时和取消走已冻结的分支规则。
- `SkillRegistry.snapshot(runId, workflowRelease, skillReleases)` 固定 manifest、指令 hash、角色、schema 与工具配置；`activate(snapshotId,nodeId,taskScope)` 读取该快照再验实时撤销，失败关闭。
- `Retrieval.search` 在 merchant/group/survey/revision、可见性、截止、证据水位及模型版本共同过滤后执行词法和向量两路排序。词法使用中英文规范化及显式材料编号匹配，向量使用精确余弦基线，采用 RRF 合并，默认 k=60，同分按 evidence ID、chunk ordinal 排序。支持单路降级并记录 `retrievalMode`，两路失败记录缺口。固定 profile 版本和召回候选上限。
- `EvidenceVerifier` 检查证据 hash、引用范围、当前可见性、事实类型、原文片段和报告版本。票数、BOM 等结构化事实对照源值。语言主张附引用原文；仅支持性不足时标作推断/未知或送人工核查。引用存在不代表主张被支持。
- `ContextBuilder.compact(snapshotId, policyVersion, tokenBudget)` 返回摘要引用、保留的工具消息对、证据来源映射、被删项和 Token 估算。撤销素材时由允许的源重建，无法重建则终止相应节点；模型配额保留工具 schema 和输出余量。
- `Memory.revoke(entryId, expectedVersion)` 由 Owner 提交，写撤销事件并失效缓存。条目绑定实际证据及可见性，可关联已提交报告；研究中提议可引用已持久化证据，不依赖尚未生成报告。禁止未审核条目或超范围跨调查读取。

## MCP 线协议与互操作门槛

文档核验目标为 **2026-07-28 Streamable HTTP**。单一登记的 HTTPS endpoint 对每条 JSON-RPC 消息使用 POST，客户端发送 JSON Content-Type、同时接受 JSON 与 SSE，并按规范提交 `MCP-Protocol-Version`、`Mcp-Method`、适用的 `Mcp-Name` 以及 `_meta` 协议信息。按请求 id 校验最终响应。按照已登记输入 schema 的 x-mcp-header 声明生成 Mcp-Param-*，遵循规范的值编码和 header/body 一致性规则。HeaderMismatch 时刷新目录并校验 hash，禁止自动接受漂移。该版本无需旧式 initialize/session 或 GET SSE。工具发现持久化分页、目录版本及输入/输出 schema hash；仅白名单名称可调用。协议或 schema 漂移拒绝且重新发布。 [Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http) · [工具契约](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)

首个受控材料目录使用部署注入的独立服务凭证与短时租户/调查 scope，服务端重新验证 audience、有效期、origin 与数据范围。第三方受保护连接器需另验 OAuth 发现、资源绑定与授权范围，凭证不转发给模型或跨源 endpoint。区分 HTTP 401/403、429/5xx、JSON-RPC error、工具 isError、超时、SSE 无最终帧和 MRTR。401/403 不重试，429/临时故障仅在许可与预算内重试只读调用。MRTR 上下文与 requestState 加密持久化，关联原调用、审批和截止；禁止自动转成写入。实际客户端、供应商和部署线报文待 R4 验证。 [MCP 授权规范](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)
