# CraftPilot 远程源码审计

架构变更后，DeerFlow 与 DeepSeek SuperAgent 均仅作远程源码设计参考。下文保留原审计时点的源码事实；其中 DeerFlow 集成建议已废弃，现行设计见 [自研 Runtime 架构](architecture/runtime-architecture.md)。

以附件《CraftPilot_完整业务与技术架构_PRD_V1.2》§17 为研究范围，以本次请求为执行约束。通过 GitHub API 获取默认分支、分支提交和文件树，再按提交读取相关原始文件和测试片段。以下是静态审阅范围，未执行上游测试。参考仓库源码未写入本地，未复制源码到 CraftPilot。分支提交是本次审阅快照。

## DeerFlow

[仓库](https://github.com/bytedance/deer-flow) · `main` · `04a9833763f7daae9b3ff19d9c1d45fb6ab21c61`

网关挂载 Thread、Run 和 Assistant 路由，线程创建写入元数据与空检查点，后台 Run 支持幂等准入。SkillActivationMiddleware 处理显式 slash skill 激活，SubagentLimitMiddleware 按 run 标识计入既往委派，SandboxMiddleware 包装工具调用并维护沙箱状态。Compose 包含 Redis、Nginx、前端、Gateway 和 provisioner，网关运行 uvicorn。

借鉴执行面、可选流式观测、异步取消和受控工具。上游会话权限、检查点和沙箱生命周期无法表达 CraftPilot 的调查授权。Java 自研执行及控制面，管理 Ticket、审批、证据、预算与截止时间。原部署 DeerFlow 服务与独立服务账号的建议已废弃。沙箱授权测试源码包含拒绝、复用沙箱重检及 fail-open/closed 分支，CraftPilot 采用 fail-closed。

许可证。MIT。若分发软件副本或实质部分，保留版权和许可声明。

实际查阅的文件

- [`backend/app/gateway/routers/threads.py`](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/app/gateway/routers/threads.py)
- [`backend/app/gateway/routers/runs.py`](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/app/gateway/routers/runs.py)
- [`backend/app/gateway/routers/thread_runs.py`](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/app/gateway/routers/thread_runs.py)
- [`backend/app/gateway/routers/assistants_compat.py`](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/app/gateway/routers/assistants_compat.py)
- [`backend/app/gateway/app.py`](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/app/gateway/app.py)
- [`backend/packages/harness/deerflow/agents/middlewares/skill_activation_middleware.py`](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/packages/harness/deerflow/agents/middlewares/skill_activation_middleware.py)
- [`backend/packages/harness/deerflow/agents/middlewares/subagent_limit_middleware.py`](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/packages/harness/deerflow/agents/middlewares/subagent_limit_middleware.py)
- [`backend/packages/harness/deerflow/sandbox/middleware.py`](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/packages/harness/deerflow/sandbox/middleware.py)
- [`docker/docker-compose.yaml`](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/docker/docker-compose.yaml)
- [`backend/app/gateway/run_models.py`](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/app/gateway/run_models.py)
- [`backend/app/gateway/hitl_approval.py`](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/app/gateway/hitl_approval.py)
- [`backend/packages/harness/deerflow/runtime/stream_modes.py`](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/packages/harness/deerflow/runtime/stream_modes.py)
- [`backend/tests/test_sandbox_authorization.py`](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/backend/tests/test_sandbox_authorization.py)
- [`LICENSE`](https://github.com/bytedance/deer-flow/blob/04a9833763f7daae9b3ff19d9c1d45fb6ab21c61/LICENSE)

## DeepSeek Harness

[仓库](https://github.com/deepseek-ai/dsh-superagent-prototype) · `main` · `8c32a9dc5d72c033816c6a8110b37f40cf94fba4`

SourceHost 提供各来源的接入上下文，GitHub 适配把评论、议题和 CI 输入映射为 Trigger。Evals 处理评估与路由，Tickets 在接纳后持久化 Ticket、订阅关联 Thread 并启动首个 Assignment。DshExecutor 按 assignment 排入 launch effect，按 ticket 排入 delivery effect，background 提供停止子任务逻辑。Thread 视图通过订阅定位关联 Ticket。订阅文档允许某些结束的 Ticket 被人工消息恢复。sources.spec.ts 用模拟命令和来源上下文测试接入。

借鉴接入、触发评估、任务、执行尝试和后台投递的分离。省去外部群接入与通用编排会话，使用 Java Outbox、调查级订阅和受限触发规则。收尾事务撤销订阅，旧 Ticket 永不由新群聊恢复。

许可证。MIT。分发副本或实质部分时保留版权和许可声明。README 将其定位为研究原型。

实际查阅的文件

- [`src/sources/github-trigger.ts`](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/src/sources/github-trigger.ts)
- [`src/sources/host.ts`](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/src/sources/host.ts)
- [`src/core/evals.ts`](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/src/core/evals.ts)
- [`src/core/tickets.ts`](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/src/core/tickets.ts)
- [`src/core/assignments.ts`](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/src/core/assignments.ts)
- [`src/executor/background.ts`](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/src/executor/background.ts)
- [`src/app/views/threads.ts`](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/src/app/views/threads.ts)
- [`prompts/subscriptions.md`](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/prompts/subscriptions.md)
- [`tests/sources.spec.ts`](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/tests/sources.spec.ts)
- [`src/executor/dsh.ts`](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/src/executor/dsh.ts)
- [`README.md`](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/README.md)
- [`LICENSE`](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/LICENSE)

## Dokuly

[仓库](https://github.com/Dokuly-PLM/dokuly) · `master` · `de196aac13e9f0a887ec8e08f9b22ad02d1545e7`

Assembly 区分展示版本与主次计数，记录最新版本标记、发布状态及修订说明。BOM 行关联 BOM，可指向 Part、PCBA 或 Assembly。ECO 用 AffectedItem 连接变更对象和关联议题。Document 记录版本、文件关系、前序版本及锁定字段。已读 BOM 测试文件为占位，模型字段本身无法证明端到端版本不可改写。

采用稳定款式 ID、不可改写的样品快照、前序版本及修订说明，BOM 和附件绑定版本。省去 PCB、嵌套装配和完整 ECO 审批，用版本关系和商家决策记录追溯改版。

许可证。GPL v3。分发受 GPL 覆盖的衍生程序涉及相应源代码和许可义务，CraftPilot 独立实现领域模型。

实际查阅的文件

- [`dokuly/assemblies/models.py`](https://github.com/Dokuly-PLM/dokuly/blob/de196aac13e9f0a887ec8e08f9b22ad02d1545e7/dokuly/assemblies/models.py)
- [`dokuly/assembly_bom/models.py`](https://github.com/Dokuly-PLM/dokuly/blob/de196aac13e9f0a887ec8e08f9b22ad02d1545e7/dokuly/assembly_bom/models.py)
- [`dokuly/eco/models.py`](https://github.com/Dokuly-PLM/dokuly/blob/de196aac13e9f0a887ec8e08f9b22ad02d1545e7/dokuly/eco/models.py)
- [`dokuly/documents/models.py`](https://github.com/Dokuly-PLM/dokuly/blob/de196aac13e9f0a887ec8e08f9b22ad02d1545e7/dokuly/documents/models.py)
- [`dokuly/assembly_bom/tests.py`](https://github.com/Dokuly-PLM/dokuly/blob/de196aac13e9f0a887ec8e08f9b22ad02d1545e7/dokuly/assembly_bom/tests.py)
- [`LICENSE`](https://github.com/Dokuly-PLM/dokuly/blob/de196aac13e9f0a887ec8e08f9b22ad02d1545e7/LICENSE)

## Fider

[仓库](https://github.com/getfider/fider) · `main` · `76461958130f4d9d80e1464735612139a38a9c0f`

API handler 通过命令总线处理响应与重复议题；action 层负责权限和校验。存储层写评论时绑定租户与帖子并处理审核；投票使用带租户、用户及帖子标识的插入，冲突时忽略。状态枚举覆盖开放、计划、处理中、完成、拒绝、重复和删除。存储测试包含重复投票、关闭帖子投票及跨租户读取。

借鉴显式状态、评论归属和数据库投票幂等。群聊消息、样品投票、议题归并和购买意愿分别建模；成员投票与文本 mentions 独立计数，权限还必须验证群、调查及样品版本。

许可证。AGPL v3。对修改版提供网络交互时须按许可提供相应源代码的获取方式，CraftPilot 独立实现业务。

实际查阅的文件

- [`app/handlers/apiv1/post.go`](https://github.com/getfider/fider/blob/76461958130f4d9d80e1464735612139a38a9c0f/app/handlers/apiv1/post.go)
- [`app/actions/post.go`](https://github.com/getfider/fider/blob/76461958130f4d9d80e1464735612139a38a9c0f/app/actions/post.go)
- [`app/services/sqlstore/postgres/vote.go`](https://github.com/getfider/fider/blob/76461958130f4d9d80e1464735612139a38a9c0f/app/services/sqlstore/postgres/vote.go)
- [`app/services/sqlstore/postgres/comment.go`](https://github.com/getfider/fider/blob/76461958130f4d9d80e1464735612139a38a9c0f/app/services/sqlstore/postgres/comment.go)
- [`app/models/enum/post_status.go`](https://github.com/getfider/fider/blob/76461958130f4d9d80e1464735612139a38a9c0f/app/models/enum/post_status.go)
- [`app/services/sqlstore/postgres/post_test.go`](https://github.com/getfider/fider/blob/76461958130f4d9d80e1464735612139a38a9c0f/app/services/sqlstore/postgres/post_test.go)
- [`LICENSE`](https://github.com/getfider/fider/blob/76461958130f4d9d80e1464735612139a38a9c0f/LICENSE)

## Sift

[仓库](https://github.com/Gitter09/sift) · `main` · `5e0af723277b05a44fbc3ea76fc765d52ebf4c37`

DedupFilter 在进程内按 item.id 去重。Embedder 用 SentenceTransformer 生成归一化向量。Clusterer 批量做 UMAP 降维和 HDBSCAN，处理小样本及噪声，并选代表文本。Analyzer 用代表文本生成结构化主题。已读测试覆盖去重与小样本聚类行为，未验证标注集上的主题质量。

采用持久幂等和版本内增量语义匹配，避免进程重启丢去重状态、跨调查聚类或抹掉多用户共识。Java 调 OpenAI 生成向量并经 pgvector 搜索；摘要必须返回证据 ID，独立用户数由数据库计算，使用标注回放校准。

许可证。MIT。分发副本或实质部分时保留版权和许可声明。

实际查阅的文件

- [`sift/pipeline/dedup.py`](https://github.com/Gitter09/sift/blob/5e0af723277b05a44fbc3ea76fc765d52ebf4c37/sift/pipeline/dedup.py)
- [`sift/pipeline/embedder.py`](https://github.com/Gitter09/sift/blob/5e0af723277b05a44fbc3ea76fc765d52ebf4c37/sift/pipeline/embedder.py)
- [`sift/pipeline/clusterer.py`](https://github.com/Gitter09/sift/blob/5e0af723277b05a44fbc3ea76fc765d52ebf4c37/sift/pipeline/clusterer.py)
- [`sift/pipeline/analyzer.py`](https://github.com/Gitter09/sift/blob/5e0af723277b05a44fbc3ea76fc765d52ebf4c37/sift/pipeline/analyzer.py)
- [`tests/test_pipeline.py`](https://github.com/Gitter09/sift/blob/5e0af723277b05a44fbc3ea76fc765d52ebf4c37/tests/test_pipeline.py)
- [`tests/test_dedup.py`](https://github.com/Gitter09/sift/blob/5e0af723277b05a44fbc3ea76fc765d52ebf4c37/tests/test_dedup.py)
- [`LICENSE`](https://github.com/Gitter09/sift/blob/5e0af723277b05a44fbc3ea76fc765d52ebf4c37/LICENSE)

## AtelierSynth Studio

[仓库](https://github.com/isurulkh/ateliersynth-studio) · `main` · `ae585b76863aa8745e5453c6f623e31faa380b55`

工具注册层暴露图转稿、精修与变体生成。图转稿包装实现并报告进度；变体生成固定其余参数、单轴尝试并逐项返回结果，model 轴存在重复使用原 model ID 的回退。这些文件展示视觉生成及受控对照流程，未验证其具备真实 V1/V2 图片差异分析或可量化评测。

借鉴固定比较条件、记录输入和逐项反馈。以实际样品版本图片为输入，由 Qwen-VL 类 API 返回可见特征、差异、不确定性及图片证据。MVP 省去图稿生成、虚拟试穿和生产级技包。

许可证。README 声明 MIT 并指向 LICENSE；锁定提交的该路径返回 404，GitHub API 无 SPDX 值。授权尚未核实，当前仅参考公开可见的工作流设计，代码和资产复用待完整授权确认。

实际查阅的文件

- [`apps/agent/tools/registry.py`](https://github.com/isurulkh/ateliersynth-studio/blob/ae585b76863aa8745e5453c6f623e31faa380b55/apps/agent/tools/registry.py)
- [`apps/agent/tools/image_to_sketch.py`](https://github.com/isurulkh/ateliersynth-studio/blob/ae585b76863aa8745e5453c6f623e31faa380b55/apps/agent/tools/image_to_sketch.py)
- [`apps/agent/tools/generate_variants.py`](https://github.com/isurulkh/ateliersynth-studio/blob/ae585b76863aa8745e5453c6f623e31faa380b55/apps/agent/tools/generate_variants.py)
- [`apps/agent/tools/refine_sketch.py`](https://github.com/isurulkh/ateliersynth-studio/blob/ae585b76863aa8745e5453c6f623e31faa380b55/apps/agent/tools/refine_sketch.py)
- [`README.md`](https://github.com/isurulkh/ateliersynth-studio/blob/ae585b76863aa8745e5453c6f623e31faa380b55/README.md)

[LICENSE 路径核查](https://github.com/isurulkh/ateliersynth-studio/blob/ae585b76863aa8745e5453c6f623e31faa380b55/LICENSE)
