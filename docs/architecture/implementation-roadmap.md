# CraftPilot 实施路线图

本文件取代旧实施计划中的 DeerFlow 集成步骤。当前仅提交设计，后续编码从设计审查通过后开始。各阶段按完整 Runtime 契约递进。

| 阶段 | 交付与退出条件 |
|---|---|
| R0 设计审查 | 修订 PRD 和七份架构文档，关闭设计 P0。冻结裁决及数据契约，登记真实联调的阶段门槛。 |
| R1 业务与可靠事件 | Java 模块、PostgreSQL 16、pgvector、Redis、MinIO、Flyway、权限、企划、样品、BOM、群聊、调查、订阅、Outbox、投票和购买意愿。通过并发开启、消息幂等、广播顺序、版本及跨租户测试。 |
| R2 反馈、检索和视觉 | 归因、观点提取、语义归并、投票、Evidence、OpenAI Embedding、Qwen-VL、Hybrid Retrieval、引用验证。以真实图片和数据验证证据链、范围过滤与错误降级。 |
| R3 执行内核 | Trigger/Ticket/Assignment/Attempt、持久 Workflow、Run/Task、模型协议、上下文、工具调用、账本、预算、Checkpoint、取消与故障恢复。用真实模型和 Java 工具验证一次多轮推理，Worker 换手后按账本续跑。 |
| R4 扩展能力与人机协作 | Skill Snapshot、Supervisor/Handoff、并行 Join、真实 MCP、人工暂停与审批、记忆发布/撤回、商家决策。通过持久汇合、协议兼容和审批竞争测试。 |
| R5 全链路验收 | 1200 消息回放、真实模型和 MCP 运行、成本、审计、安全和故障注入。完成全部能力矩阵、一键启动和 V1 到 V2 演示。 |

R1/R2 可先验证无 Agent 的业务组件，R3/R4 的能力逐项联调。最终发布依赖全部阶段的退出条件。在 R2/R3/R4 分别准入真实视觉、文本模型与 MCP 联调，不阻断 R1；固定模型能力矩阵与 MCP 连接器协议，验证文本供应商的工具 schema、流式帧和用量字段；Qwen-VL 具体型号及费用以实际账户核验。仅使用现有技术栈，应用提供的 MCP 材料目录可使用同一构件和存储。

审查入口 [架构](runtime-architecture.md) · [接口](runtime-contracts.md) · [状态](runtime-state-machines.md) · [数据](runtime-data-model.md) · [安全](agent-security.md) · [评测](agent-evaluation.md)。

阶段准入以 [实施就绪表](../reviews/R0-implementation-readiness.md) 为准。R1 先实现业务事务、稳定事件和租户约束，R2 提供 Runtime 消费的真实证据和检索端口，R3 提供持久任务执行与授权接口，R4 在其上提供完整协作与人工流程，R5 统一验收。R3/R4 的发布要求实际工具执行，录制回放仅用于确定性测试。设计审查后由用户决定开始编码。
