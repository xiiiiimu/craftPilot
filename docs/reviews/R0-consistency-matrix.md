# CraftPilot R0 一致性矩阵

API 列包含公开 REST 及模块接口。数据列为冻结逻辑模型；具体 DDL 与实现留待编码。T01–T13 对应 [评测用例](../architecture/agent-evaluation.md#r0-补充验收用例)，当前均待执行。

| 需求 | 模块 | API / 契约 | 数据 | 状态或不变量 | 验收 / 阶段 |
|---|---|---|---|---|---|
| FR-01 多租户、RBAC、隔离 | 权限、群、broker | groups/members；authorize | merchant_member、group_member、复合 FK | 当前成员、租户/群/调查复查 | T11 R1/R4 |
| FR-02 企划、样品、BOM、附件 | 商品、资产 | campaigns、styles、revisions、assets | launch_campaign、candidate_style、sample_revision、bom_item、asset | 不可变版本；STAGED→AVAILABLE | T10/T13 R1 |
| FR-03 群聊、回复、广播 | 群聊、Outbox | groups/messages、WS、补读 | message、message_survey_link、outbox | 持久化先于广播，groupSeq 去重 | T01/T10/T13 R1 |
| FR-04 调查、投票、购买意愿 | Survey、Poll | surveys、close、polls/vote | survey_session、survey_revision、survey_poll、survey_vote | ACTIVE→CLOSING→CLOSED；48h/10min | T01/T13 R1 |
| FR-05 反馈归因与聚合 | 反馈 Worker、Evidence | issues、attribution、EmbeddingPort | feedback_opinion、feedback_issue、issue_opinion、feedback_attribution_event | NEEDS_REVIEW；冻结水位 | T08/T13 R2 |
| FR-06 主动触发与分派 | 控制面 | TriggerService、AssignmentService、tickets | source_event、trigger、subscription、ticket、assignment、attempt | 订阅撤销；单活分派；旧 Ticket 不唤醒 | T01/T03/T13 R1/R3 |
| FR-07 Model Adapter、Tool Calling | 模型、Tool Registry | ModelPort、ToolRegistry.dispatch | model_call_attempt、agent_turn、tool_release、tool_invocation | 完整帧→意图→真实工具结果 | T03/T06/T12 R3 |
| FR-07 Workflow、并行与 Join | 执行内核 | WorkflowEngine.advance、AgentDelegator | workflow_release、agent_task、task_join | 节点状态、固定汇合、部分成功 | T05 R3/R4 |
| FR-07 Supervisor/Handoff | Agent 协作 | Supervisor.plan、Handoff.submit | agent_spec、handoff、agent_task | 唯一交接、继承范围、预算切片 | T05 R4 |
| FR-07 Skills | Skill Registry | snapshot、activate | skill_release、skill_snapshot | 固定版本、实时撤销 | T06 R4 |
| FR-07 MCP | 连接器 | tools/list、tools/call | connector_config、connector_catalog、tool_dispatch_attempt | 目录版本、错误分类、只读 | T07 R4 |
| FR-07 RAG、Embedding、Evidence | 证据及检索 | Retrieval.search、EvidenceVerifier | evidence_ref/chunk、retrieval_profile/execution、claim_evidence | 共同过滤、词法/向量融合、撤销、核验 | T08/T12 R2/R3 |
| FR-07 视觉比较 | 视觉端口 | VisionPort.compare、vision.compare | model_call_attempt、asset、evidence_ref | 图片范围、模型版本、证据或缺口 | T12/T13 R2 |
| FR-07 Context 与 Memory | 上下文、记忆 | compact、read/propose/commit/revoke | context_snapshot/summary、memory_entry/proposal | 保留调用对与来源，批准发布、失效 | T09 R3/R4 |
| FR-06 恢复、Checkpoint、围栏 | Runtime | claim/renew/recover、resume、cancel | agent_run、checkpoint、resume_command、effect_receipt | 当前头、owner+epoch、不可复活 | T03/T04 R3 |
| FR-08 HITL | 审批、恢复 | approvals/decision | approval、resume_command、effect_receipt | PENDING→APPROVED→CONSUMED；过期拒绝 | T02/T04 R4 |
| FR-07 商家决策 | 报告、Decision | ReportStore.publish、decisions/confirm | research_report、report_claim、decision | 已发布→待确认→已确认；独立授权 | T02/T13 R4 |
| FR-06 可靠交付、死信 | Outbox、账本 | claim、ack、DLQ replay | outbox、consumer_receipt、api_command | 至少一次；同库效果唯一 | T03/T10 R1/R3 |
| FR-01/08 Prompt 注入、SSRF | Broker、出站及 Context | dispatch、activate、MCP | 工具与连接器配置、授权及审计 | schema 校验、固定端点、无动态执行 | T06/T07/T11 R3/R4 |
| FR-09/10 Trace、费用、评测 | 可观测性、Eval | events、Telemetry、EvalRunner | runtime_event、usage_ledger、budget_reservation、eval_case_result | 预留/结算/未知；回放和真实独立 | T12/T13 R3/R5 |
