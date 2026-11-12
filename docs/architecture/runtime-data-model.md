# CraftPilot Runtime 持久化模型

PostgreSQL 16 为唯一执行状态来源。实体 ID 用 UUID。复合关联表可直接用业务复合主键。所有租户数据行包含 merchant_id；业务父子关系通过复合外键约束租户。与调查执行相关的行同时绑定 survey_id，并沿 Ticket、Run、Task 约束同一调查。时间使用 timestamptz，版本用 bigint。字段为逻辑模型，迁移实现须用集成测试验证约束。

| 表 | 关键字段及约束 |
|---|---|
| survey_session | group_id、status、starts_at、planned_ends_at、intake_until、closing_deadline_at、closing_started_at、closed_at、evidence_seq、evidence_watermark、version。部分唯一索引约束同租户同群最多一个 ACTIVE/CLOSING。 |
| message / message_survey_link / feedback_opinion | client_msg_id、group_seq、payload_hash、received_at、回复与版本、message_id、作者、观点类型；消息键包含 merchant/group/sender/client_msg_id。调查和样品链用租户复合外键验证。 |
| feedback_issue / issue_opinion / survey_vote | 按 survey/style/revision/type 归并；关联行按 issue/opinion 去重，独立人数按作者计算。投票唯一键为 merchant、survey、poll、member，revision 由投票题确定。 |
| feedback_attribution_event | feedback_id、old/new revision、actor、version、occurred_at；同一调查内人工修正，稳定事件键驱动重新聚合，保留原证据及历史。 |
| evidence_ref / evidence_chunk | evidence_id、survey、revision、source_id、URI、captured_at、grade、checksum、visibility、seq；chunk 记录文档版本、偏移、embedding_model、embedding_hash、vector(1536)。按租户及业务边界建立过滤索引。 |
| source_event / trigger / subscription | 来源及稳定键、调查、规则版本、事件高水位、评估结果、状态及有效期。来源键与调查规则触发键分别唯一。 |
| ticket / agent_run_attempt / agent_run | Ticket 绑定调查、授权版本集合与截止；Attempt 带 ticket_id、assignment_id、attempt_no、status、retry_reason，唯一 ticket/attempt_no；Run 一对一绑定 Attempt，含 workflowVersion、状态、version、checkpoint_head、lease_owner、lease_epoch、lease_until、started_at、execution_deadline。部分唯一索引限制单 Ticket 活跃 Run。 |
| workflow_release / agent_spec / skill_release / tool_release / model_profile | immutable id、version、schema_version、hash、配置与生效状态。版本引用固定，停用通过独立状态记录并影响授权。 |
| agent_task / agent_turn | run_id、parent_task_id、节点与逻辑位置、AgentSpec、scope、status、started_at、预算切片、结果引用；Turn 记录 context_id、模型响应摘要、选定工具批次、Token。逻辑任务键在 Run 内唯一。 |
| context_snapshot / checkpoint | Context 记指令版本、消息位置、证据和记忆引用、预算估计及删减；Checkpoint 含 run_id、seq、parent_id、schema_version、节点游标、状态和工具收据引用，唯一 run/seq 及 run/transition_key。推进 head 与事件同事务。 |
| approval / resume_command | run/task/head、operation_digest、kind、status、created_at、expires_at、decision_actor、decided_at、consumed_at、version。每个待决操作一个审批，resume 按 Run、暂停头及意图唯一，含命令状态、预期版本、applied_checkpoint_id。 |
| tool_invocation / tool_dispatch_attempt / effect_receipt | invocation 按 run/task/turn/ordinal 唯一，记录参数摘要、工具版本、operation_key、效果类别和结果。每次物理发送单列 attempt、epoch、时限及远程请求 ID。effect_receipt 唯一 merchant/operation_key。 |
| connector_config / connector_catalog | tenant、连接器端点标识、凭证引用、协议版本、工具白名单、输入输出 schema hash、目录版本、撤销状态；密钥由部署注入。 |
| memory_proposal / memory_entry | 提案内容摘要、来源、范围和审批；条目存 merchant、style/category scope、来源决策或报告、创建人、有效期、撤销时间。关联来源撤回时停用。 |
| research_report / decision | report 按 Run 唯一，含证据关系及内容 hash。决策以 report/revision 唯一，记录建议、确认选择、商家身份、状态、version、报告 hash 和确认时间，重复确认按键去重。 |
| runtime_event / usage_ledger / budget_reservation | 每 Run 的持久 seq、事件类型和 trace；用量按实际请求及供应商 ID 去重，记录估算、实测和未知账单。预算以行锁预留，再按回执结算，未知费用保留预留直至核对。 |
| outbox / consumer_receipt / api_command | 聚合、事件类型、租约 epoch、重试及 DLQ；消费键唯一；API 请求摘要及原始结果按调用身份、资源和命令键唯一。 |
| eval_suite / eval_run / eval_case_result | 固定数据集和评测版本、模式、版本组合、预期、实际、trace、判定和缺口。评测租户及凭证隔离。 |

## 事务与物理存储

1. 收消息、判断归属、写业务记录与 Outbox 同一事务。证据高水位在 Survey 锁内冻结，收尾只读取当时已提交的证据；运行中生成的视觉分析等派生产物可绑定冻结输入作为本 Run 产物，不能扩大源证据范围。
2. PostgreSQL 短事务领取 Outbox，使用 `FOR UPDATE SKIP LOCKED`。每次接管递增 epoch，提交结果以 epoch 和期限为条件。网络放在事务外。只有达到授权裁决点且持有当前租约的提交才能改变业务。
3. 大内容进入 MinIO，数据库保存对象版本、摘要及授权所有者。先暂存再发布引用，失败暂存对象由过期清理；数据库记录可撤回，恢复时重校验可见性。Redis 丢失时从数据库重建缓存，审批、预算结算和租约都不依赖缓存。
4. 检索严格区分 embedding 模型和维数，先用范围内精确检索作为基线。HNSW 上线需要验证过滤后召回。旧快照的敏感源被撤销时阻断重用，按保留策略清理正文并留脱敏审计。

## 业务关系与补充记录

| 表 | 键、约束及用途 |
|---|---|
| merchant / merchant_member / craft_group / group_member | 商家与登录主体关联，merchant_member 唯一 merchant/principal；group_member 复合引用商家成员和群。Group 持有 next_message_seq。角色及撤销通过数据库裁决。 |
| launch_campaign / candidate_style / sample_revision / bom_item | 复合父键确保同一商家。版本唯一 merchant/style/revision_no，parent 引用同款版本；款式行锁序列分配新版本。BOM 绑定不可变版本，数量、单位、币种和成本缺口明确。 |
| asset / revision_asset | asset 存 tenant、owner、kind、对象 key/version/hash、状态与校验结果；revision_asset 保存版本绑定。消息附件通过 message_asset 关联，所有引用须匹配商家与调用目的。 |
| survey_revision / survey_poll / survey_poll_option / survey_vote | 调查样品白名单冻结。投票项绑定 poll 及 revision，票唯一 merchant/survey/poll/member，option 外键确认属于该 poll；购买意愿使用明确题型。 |
| assignment / ticket_revision | Assignment 记录 ticket、capability_profile、status、created/claimed/finished_at、terminal_reason；部分唯一约束单 Ticket 一个 READY/CLAIMED。Attempt 唯一引用 Assignment。ticket_revision 必须是 survey_revision 子集。 |
| skill_snapshot / handoff / task_join | 快照固定 run、release IDs、hash、物化 manifest 与权限配置。Handoff 唯一 run/handoff_key，绑定父子 task、角色、限额和来源摘要。Join 固定成员、必需标记、策略、截止、结果归并版本。 |
| model_call_attempt / context_summary | 请求物理尝试含 turn、ordinal、epoch、request hash、预算预留、状态、provider request id、计费和结果引用。摘要绑定 context、策略版本、来源映射与撤销依赖。 |
| report_claim / claim_evidence / evidence_scope_grant | 主张记录事实/推断/未知、引用区间和校验状态。每条证据关系复合约束报告与授权目标；可用历史材料须有显式授权，记录用途和撤销状态。 |
| retrieval_profile / retrieval_execution | 词法规范化、向量模型、候选上限、融合参数及版本。记录候选 ID、排序、实际检索路径与水位；正文经 Evidence 按需重新授权读取。 |

关系引用示例为 `(merchant_id,group_id,survey_id)` → Survey 唯一键，`(merchant_id,survey_id,ticket_id)` → Ticket，`(merchant_id,survey_id,run_id,task_id)` → Task。Approval 和 Checkpoint 不能挂到另一 Run。活动 Run 部分索引覆盖 QUEUED、RUNNING、WAITING_APPROVAL、PAUSED、RECOVERING、CANCEL_REQUESTED。投票、反馈和报告约束引用各自调查授权版本。允许历史资料的引用由独立授权关系判定。迁移用直接 SQL 反例验证跨租户及跨调查 FK，避免仅依赖应用测试。

## Outbox 与对象一致性

- 生产者与领域状态同事务。Outbox claim 短事务用 `FOR UPDATE SKIP LOCKED` 领取到期 PENDING 或租约过期的 IN_FLIGHT，更新 owner、epoch 和 lease_until 后释放行锁。消费者业务事务按锁序写 effect/consumer receipt 及结果，完成时以当前 epoch 条件更新；若成功提交但 ack 丢失，重复消费者返回收据。跨网络处理用工具账本，消费收据不可在外部效果前标记完成。DLQ 重放保留 event_id；越期业务事件记录拒绝。
- WS 只广播持久 group_seq 提示；客户端依据持久消息补读、去重和处理乱序。Redis 丢失后重新认证会话，缓存从 DB 重建；硬限额和调查授权始终由 DB 判定。
- MinIO 状态 STAGED → AVAILABLE → REVOKED / DELETE_PENDING → DELETED。暂存对象以不可重用 key 和校验版本上传；服务在事务外核验，事务内以 CAS 发布引用。扫描器先取得删除声明，再删除对象；AVAILABLE 及被引用的对象不可被暂存清理。失败删除可重试，下载代理实时查权限。对象读缺失返回明确失败并告警。数据库事务内不调 MinIO。
