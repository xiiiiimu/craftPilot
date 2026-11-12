# CraftPilot 自研 Agent Runtime 架构

本设计取代 PRD V1.2 的 DeerFlow 集成。Java 21 与 Spring Boot 同时拥有调查控制面和执行面。商家管理企划、不可变样品版本和最终决策；群成员在模拟群内回复、投票和表达购买意愿；调查主动触发研究。48 小时反馈窗口、最多 10 分钟在途收尾、审批最多 24 小时且受调查截止约束。业务范围见 [修订 PRD](../CraftPilot_PRD_V1.2_Runtime.docx)。

## 模块与部署

| 模块 | 职责与依赖 |
|---|---|
| 业务域 | Merchant、Group、Campaign、Style、Revision、BOM、Message、Survey、FeedbackIssue、Evidence、Decision。调查和群权限对 Runtime 提供单一授权来源。 |
| 控制面 | Trigger 与订阅、Ticket、Assignment、审批、预算、Run 指令。业务事务和 Outbox 同写。 |
| Runtime | Workflow 调度、Agent 循环、子 Agent、Checkpoint、租约、执行结果汇合。命令来自数据库，Worker 无状态可水平扩展。 |
| 能力适配 | 文本推理模型、OpenAI Embedding、Qwen-VL、Java 工具注册、MCP 客户端、检索和上下文。仅适配层联网。 |
| 证据与评测 | 验证引用、生成报告快照、记忆提议、回放、使用量和审计。 |

应用和 Worker 可用同一构件的不同运行配置。PostgreSQL 16 存业务真相、执行记录和 Outbox，pgvector 存向量，Redis 用于限流、短期缓存和会话，MinIO 存原图、证据快照和受限运行产物。WebSocket 承载站内群聊，Runtime 进度由持久事件游标读取。进程间交接依靠事务与租约。

## 真实业务流程

1. 在 48 小时内，消息以服务端接收时间归属调查。先按卡片和回复链定位样品版本，再从文本提取观点，按版本聚合 Issue，保留原文及独立用户数。定时批次、管理员指令或达到去重后的反馈阈值产生 Trigger，受限订阅接纳为 Ticket。
2. 版本化工作流先冻结调查、版本和证据边界，再加载 `launch-research` Skill。Supervisor 作为主 Agent，可调用真实 Java 工具查看 BOM、查询票数、检索意见和委派子 Agent。每次模型输出工具请求时，Runtime 做 schema、授权、截止、预算和幂等检查，执行后将真实结果作为 tool message 交回模型；模型依据结果决定下一次检索、提问或结束。
3. 子 Agent 可并行处理视觉差异与反馈证据。视觉 Agent 调 Qwen-VL 比较实际 V1/V2 图片，反馈 Agent 通过词法和向量混合检索找支持与反例。工作流收集结构化结果和来源。商家配置了资料连接器时，研究可用 MCP 查询其授权的供应商材料目录，将明确来源的文件片段列为 B 级证据。
4. 如研究提出将本轮摘要写入跨调查记忆，商家可审核指定内容与目的；审批通过后 Runtime 才能将已审核摘要保存为商家记忆。需要补充数据时可暂停并等待人工输入。报告生成、停顿、续跑和取消都有持久记录。
5. 汇合节点用实际投票与样本统计核验叙述，验证所有证据 ID 并输出 PRODUCE、REVISE、TRIAL 或 HOLD 建议。商家确认后的 Decision 引用同一报告和样品版本。Closed 后可确认历史建议，调查执行权限到期即失效。

## 调度与 Agent 循环

Workflow 是受控有向图，节点类型包括读取、Agent、分支、并行、汇合、审批和报告提交；Agent 节点内通过预算与轮数限制支持多步工具选择。每个节点有成功、可重试、部分结果或失败出口。视觉或连接器不可用可形成显式缺口；证据校验失败阻止报告发布。Workflow、Agent、Skill、工具 schema、模型策略固定版本，恢复绑定原版本。首个真实文本模型需要支持结构化 Tool Calling 和流式响应，具体供应商与型号由契约验证选定。业务闭环以真实调用记录为验收依据。


| 节点 | 路由与结果 |
|---|---|
| snapshot | 绑定已授权样品、票数和反馈快照。样品无效则失败；证据不足时继续生成带缺口的报告。 |
| research | 主 Agent 调用样品、票数和检索工具。存在两版图片且有剩余配额时可委派视觉比较，有可检索反馈时可委派反馈审阅。 |
| join | 等待已生成任务终态，按任务键汇合。视觉或连接器失败允许部分报告，授权错误终止研究。 |
| review | 需要补充信息时可暂停。跨调查记忆写入为可选分支，拒绝、过期或审批不可用时放弃该写入；调查到期时遵循 Run 的收尾规则。 |
| validate / publish | 检查证据、投票及建议输出，提交报告和终态事件；商家确认由业务域另行处理。 |

运行预算在父 Run 原子预留。委派按逻辑节点键登记，重试不会重复建子任务，未用完的配额在汇合后归还。可观察事件包括节点开始/完成、工具准入/结果、审批、Checkpoints、取消和用量；所有事件含作用域及 trace，敏感正文仅保留受控引用。

## 远程源码依据

左列为已审阅的上游实现，右列为 CraftPilot 自主设计。六仓库文件、提交和许可证见 [源码审计](../source-audit.md)。

| 已验证源码行为及文件 | CraftPilot 自主设计 |
|---|---|
| DeerFlow 网关校验恢复指向当前检查点，Run 支持异步取消。[HITL](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/app/gateway/hitl_approval.py) · [Run 路由](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/app/gateway/routers/thread_runs.py) | Java 实现不可变 Checkpoint、当前头校验、取消命令及租约围栏。 |
| DeerFlow 中间件处理 Skill 激活，子 Agent 限额计入既往委派。[Skill](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/packages/harness/deerflow/agents/middlewares/skill_activation_middleware.py) · [子 Agent](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/packages/harness/deerflow/agents/middlewares/subagent_limit_middleware.py) | 版本化 Skill 清单在节点激活，子 Agent 继承收窄后的授权并共享持久预算。 |
| DeepSeek 接纳 Ticket 后登记订阅、分配 Assignment，Executor 将启动与投递入队。[Ticket](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/src/core/tickets.ts) · [Executor](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/src/executor/dsh.ts) | Trigger、Ticket、Assignment、Attempt 分离，调查结束在同事务撤销订阅。 |
| Dokuly 为装配记录版本和发布状态，BOM 行关联装配件或零件。[版本](https://github.com/Dokuly-PLM/dokuly/blob/de196aac13e9f0a887ec8e08f9b22ad02d1545e7/dokuly/assemblies/models.py) · [BOM](https://github.com/Dokuly-PLM/dokuly/blob/de196aac13e9f0a887ec8e08f9b22ad02d1545e7/dokuly/assembly_bom/models.py) | 样品不可变快照及版本绑定 BOM。 |
| Fider 投票在数据库冲突时忽略。[投票存储](https://github.com/getfider/fider/blob/76461958130f4d9d80e1464735612139a38a9c0f/app/services/sqlstore/postgres/vote.go) | 按调查、版本、投票项和成员记录，投票与文本观点分别统计。 |
| Sift 对代表反馈文本生成主题摘要。[摘要](https://github.com/Gitter09/sift/blob/5e0af723277b05a44fbc3ea76fc765d52ebf4c37/sift/pipeline/analyzer.py) | 按当前版本证据检索，确定性校验引用和人数。 |
| AtelierSynth 在固定条件下做单轴生成变体。[变体](https://github.com/isurulkh/ateliersynth-studio/blob/ae585b76863aa8745e5453c6f623e31faa380b55/apps/agent/tools/generate_variants.py) | 用真实样品图比较可见差异，记录输入和不确定性。 |

## 模块调用与版本边界

控制面通过准入服务和 Outbox 发布任务，Runtime 单向调用业务查询端口和受控写入端口，业务域通过接口接收已验证报告，不依赖 Runtime 内部类型。权限服务只读租户、成员与授权状态。模型适配器和 MCP 适配器无法写业务表，所有工具由 broker 调用。运行记录、Outbox 与公开进度使用不同用途的游标。

- Supervisor 执行 launch-research 工作流。它读取可用样品和反馈，输出受 schema 约束的 Handoff 请求。视觉比较及反馈检索为独立角色，子 Agent 获得目标、证据边界、任务预算与截止。结果走 Join 回 Supervisor。Handoff 保存来源、目标、交接摘要和预留预算；父任务仍拥有报告提交权。
- Run 创建时绑定 Skill Snapshot，保存解析后的指令、schema、工具许可、AgentSpec 和内容摘要。升级创建新快照，停用和撤销仍实时生效。只有许可的节点可激活 Skill，不能动态装载代码。
- Context 依固定顺序装配系统指令、允许的工具、完整工具调用与结果对、授权证据、当前任务和有效记忆。压缩保留引用与来源映射，历史摘要不授予任何权限。模型发送前及结果发布前重验已读内容的授权。
- Hybrid Retrieval 同时覆盖材料编号等精确词法查询及用户同义表述。PostgreSQL 做规范化词法排序与 pgvector 余弦检索，在共同授权候选集上融合。当前调查和历史材料分别授权。Memory 用于已审核的商家偏好；用途限于计划与建议，事实仍须证据。
- 当前部署无需新中间件。Worker 每 Run 一个协调者，Java 有界并发负责并行子任务与 IO，数据库租约决定谁可提交。连接池、HTTP 并发和预算独立限额。当前只读 MCP 查询在反馈接收窗口内启动，收尾时只接纳已授权且源快照属于冻结范围的结果。新远程资料不进入关闭后的报告。

参考仓库只可远程按文件查阅。GPL、AGPL 或授权不明项目只参考设计，不复用受限制代码。现行自研模块不得引入 DeerFlow Thread、Run、Cancel、Resume API 或上游运行 ID。
