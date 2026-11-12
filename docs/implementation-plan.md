# CraftPilot 分阶段实施计划

需求基线为 [PRD V1.2 自研 Runtime 修订版](CraftPilot_PRD_V1.2_Runtime.docx)。旧计划中的 DeerFlow 运行时集成已废弃。下文保留适用的业务实施细节，当前 Agent 架构、阶段及退出标准以自研 Runtime 路线图为准。 [现行路线图](architecture/implementation-roadmap.md)

本页为历史业务设计记录。调查、API、表和执行约束均以 R0 修订版为准。

## M0 远程源码研究与架构审查

六仓库研究以远程源码为依据。DeerFlow 服务部署、版本固定、接口联调及旧适配器方案已废弃。 [自研 Runtime 架构](architecture/runtime-architecture.md)

## M1 商品企划、权限与基础设施

Java 21 与 Spring Boot 3.3+ 使用模块化单体，Flyway 管理 PostgreSQL 16 与 pgvector。Redis 7 用于缓存、限流与短期状态；MinIO 私有桶存图片和附件。开发环境用 Compose。业务模块包括租户与群、企划样品、消息调查、反馈证据、Ticket 与决策。

建立 Merchant、Membership、Group、LaunchCampaign、CandidateStyle、SampleRevision、BOMItem 和 Asset。业务服务从认证身份派生租户，查询和组合外键都约束 merchant_id，调查对象继续约束群与调查。群管理员只管理已获授权的群，顾客仅参与所属群，商家负责最终决策。对历史样品保存不可变内容、父版本、附件摘要和修订说明，同款式版本号唯一。新版本通过事务写入，BOM 数量、单位和币种结构化，缺失成本保存为未知。验收覆盖并发建版本、V1/V2 历史可读、跨租户 403/404、未授权文件访问。

## M2 群聊、调查与事件处理

REST 写群消息，WebSocket 向通过成员校验的连接广播。发送接口按 merchant/group/sender/client_msg_id 幂等，同键异内容返回冲突。消息、回复及样品卡片必须属于相同租户与群，卡片还校验样品版本。数据库事务保存消息、调查关联及 Outbox，提交后投递。客户端按消息 ID 去重，断线按持久游标补读。

| 状态转换 | 事务规则 |
|---|---|
| CREATED → ACTIVE | 服务端定 starts_at 与 planned_ends_at。数据库部分唯一索引约束同群最多一条 ACTIVE/CLOSING，并发开启返回 409。 |
| ACTIVE → CLOSING | 到期或授权人手动关闭。保留计划结束时间，另存实际反馈截止时间与收尾截止。原子撤销订阅、禁开 Ticket/Run、冻结证据读取范围。仅保留已在途 Attempt 的有限收尾。 |
| CLOSING → CLOSED | 收尾完成可提前关闭，最迟在反馈截止后 10 分钟失效。到期使未完成 Ticket 终态化，撤销工具权限并经 Outbox 请求取消自研 Runtime 的 Run。 |

锁内以数据库时间记录 received_at，调查接收窗口为 [starts_at, intake_until)。消息事务与关闭操作按同一调查锁定顺序串行化归属，并同时验证 ACTIVE 状态和时限。到期后的消息继续在群中显示。所有写入与工具入口都执行时限校验，即使定时任务积压也不会延长权限。临界点以数据库时间和事务裁决顺序为准，测试覆盖消息与关闭竞争。

Outbox 以 `FOR UPDATE SKIP LOCKED` 短事务领取批次，提交租约后调用外部服务，完成时校验租约。重试指数退避，耗尽转死信。消费端按 event_id 和操作业务键去重，支持过期租约回收。审批、关闭、调查关联和 Agent 启动均通过数据库状态及截止时间约束，支持重复投递。验收包括重复消息、广播前持久化、同群并发开启、截止瞬间、Worker 重启和终态不可复活。

## M3 反馈、视觉与证据

先按卡片与回复链归因，模糊归因进入 NEEDS_REVIEW，由管理员确认后进入后续分析。观点保留原文及 author/message ID，记录类别、款式与版本。按 `merchant/survey/style/revision/type` 和向量模型范围查询候选 Issue，以余弦相似度增量匹配。小数据先做精确检索，HNSW 上线前对比召回与延迟。并发写入按匹配分区序列化最终归并，避免同主题双建。相似阈值和边界案例经标注集校准；人工合并仍保留旧议题与证据映射。

采用 OpenAI `text-embedding-3-small`，PRD 设定 1536 维。持久化模型、维数、内容 hash 与时间；模型变更时重嵌入并切换索引。请求幂等、缓存及日志按租户及输入版本隔离。网络重放不重复落库，同用户重复表达增加 mentions，多用户相似表达保留共识。投票和购买意愿使用独立数据记录，不推算实际成交。

Qwen-VL 类 API 的具体模型 ID 与费用待联调。输入经授权的真实样品图，输出 `visible_features`、`differences`、`uncertainty`、`source_image_ids`，记录模型和输入摘要。材质、合身度、成本及销量须另有真实依据。EvidenceRef 保存来源、采集时间、URI、版本及可信级别。A 为站内原始记录，B 为商家材料或可验证公开来源，C 为明确标注的模型推断。验收覆盖 V1/V2 隔离、并发匹配、原文可追溯、模型故障和人工修正。

## M4 自研 Runtime 与商家确认

Trigger、Ticket、Run、Checkpoint、工具调用、审批、取消和对账均由 CraftPilot Java 实现。模型只在当前授权内研究并提交证据报告，商家可确认历史建议。阶段及验收见 [现行路线图](architecture/implementation-roadmap.md)。

## M5 回放、成本与交付验收

准备 3 商家、6 群、12 调查、约 1200 消息、约 300 有效观点、至少 20 候选款式和 30–50 张图片，覆盖娃衣、饰品与 V1/V2。可复现演示 V1 → 调查 → 议题 → 有证据的研究报告 → 商家确认 → V2 新调查。回放记录输入、预期、实际及日志，注入并发、重复、队列积压、关闭、审批过期、取消、模型故障、预算超限、证据缺失及跨租户访问。

按 PRD FR-01 至 FR-10 验收。测试集中消息幂等与明确卡片归因要求 100%，跨租户泄露和关闭后旧 Ticket 唤醒均为 0；模糊归因 ≥85%、Issue macro-F1 ≥0.80 为标注评测目标。记录事件、调查、Ticket、Attempt 和 trace 关联，统计调用、Token、延迟、取消成功率和成本。¥300/月作为预算目标，价格和运行成本以联调实测为准。最终提供一键启动、演示账号、迁移和配置说明。
