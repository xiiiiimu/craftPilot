# CraftPilot R0 开放决策与真实联调

本页区分已冻结的内部设计、待选择的部署参数及待收集的真实证据。以下未验证项均不阻断 R1 的业务开发。

| 项目 | 当前证据 | 待验证与关闭条件 |
|---|---|---|
| O01 文本模型 | 已定义模型请求、流解析和工具意图契约 | R3 选定供应商与型号，采集完整工具帧、拒绝/截断/限流、重复调用、用量与计费样例。 |
| O02 Embedding 与视觉 | PRD 选定 OpenAI text-embedding-3-small 及 1536 维；视觉为 Qwen-VL 类 API | R2 真实验证维数、批次顺序、限流、图片输入、可见差异结构和成本。选择具体视觉型号。 |
| O03 MCP 客户端与服务 | 已核对 2026-07-28 官方工具、HTTP 及授权文档 | R4 固定 Java 客户端与服务版本，验证 JSON/SSE、请求元数据、目录、认证/撤销、MRTR 或明确错误。独立配置的真实材料服务可先验通标准 HTTP；外部供应商需单独验 OAuth/互操作。 |
| O04 检索质量与索引 | 已冻结共同范围、词法/余弦及 RRF 基线 | R2 用标注数据校准分词、候选上限、反馈阈值；HNSW 只在隔离过滤、召回和 p95 基准均符合时引入。 |
| O05 Worker 与费用 | 已冻结每 Run 单协调者、受限并行、DB 围栏与预算账本 | R3/R5 测并发、租约 TTL/心跳、HTTP 池、供应商费用与取消耗时；¥300 为目标。 |
| O06 数据保留及供应商策略 | 已定义撤销、最少数据和受控附件 | R1 确定开发环境保留参数；启用真实供应商前确定其保留条款和允许的数据分类，必要时限制 payload。 |
| O07 面经原题 | 工作区中无原题文件 | 标记尚未逐题核对。提供原题后可映射到真实提交、测试和指标。 |

[官方 MCP HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http) · [官方工具规范](https://modelcontextprotocol.io/specification/2026-07-28/server/tools) · [官方授权规范](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)
