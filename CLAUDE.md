# CLAUDE.md

CraftPilot：面向原创手作商家的调研平台。商家在群聊里发起限时调研，Agent 把样品反馈整理成有证据可查的建议（PRODUCE / REVISE / TRIAL / HOLD），由商家人工确认。目前只有后端，没有前端。

## 当前任务

按 `docs/evaluation/plan.md` 推进评测与优化，按编号（P0-1…P0-5、P1-1…P1-6）一次只做一项。

## 技术栈

- Java 21、Spring Boot 4.0.9、Maven Wrapper，单模块，按包分层（`backend/`）
- Jackson 3：import 是 `tools.jackson.*`，不是 `com.fasterxml.jackson.*`
- 数据访问用 `JdbcClient` 写参数化 SQL，不用 JPA
- PostgreSQL 16 + pgvector、Redis、MinIO；Flyway 迁移在 `backend/src/main/resources/db/migration/`
- 模型：OpenAI 兼容的对话和 Embedding 接口、Qwen-VL；MCP 走 Streamable HTTP

## 构建与测试（Windows）

- 测试用 Testcontainers 启动临时依赖，**必须先启动 Docker Desktop**
- 完整构建：`cd backend; .\mvnw.cmd -B -ntp clean verify`（耗时数分钟，最后再跑）
- 调试阶段只跑单个类：`.\mvnw.cmd -B -ntp test -Dtest=R3RuntimeTest`
- 主要测试类：`R3RuntimeTest`、`R4RuntimeTest`（Runtime）、`R5FaultInjectionTest`（故障注入）、`R5SecurityAcceptanceTest`（安全）、`R5EvaluationHarnessTest`（评测场景）、`R5MessageReplayTest`（1200 条消息回放）
- `R2/R3/R4ProviderSmokeTest`、`R5ProviderE2ETest` 会调用真实付费接口，已在 `pom.xml` 的 surefire `excludes` 里排除，不要移除
- 新增的评测类测试打 `@Tag("eval")`，并从默认构建中排除

## 代码规则

- 多租户：所有业务表主键含 `merchant_id`，**每条 SQL 都必须带 `merchant_id` 条件**；商家权限通过成员关系在查询内校验，不信任 URL 里的商家 ID
- 数据库变更只能新建迁移 `V6__...sql`、`V7__...sql`，**不要修改已有的迁移文件**
- Runtime 的状态改动要考虑租约（`lease_epoch`）、取消、幂等；改 `runtime/` 下的代码必须配测试
- 错误统一用 `ApiFailure` 抛出，不要返回堆栈信息
- 不要提交 `.env`、`data/`、根目录下的 `r3-*.txt` / `r4-*.txt` / `r5-*.txt` 日志

## 工作方式

- 每项改动开新分支（如 `fix/lease-renewal`、`eval/runner`）
- 先写能复现问题的失败测试，再改代码
- 只改与当前任务相关的文件，不要顺手重构
- 不要修改测试断言来让测试通过；如果认为断言本身错了，先说明理由，等确认
- 不要自己 commit 或 push，改完给出改动摘要，等我确认
- 每完成一项，说明改了哪些文件、为什么这样改、跑了哪些测试、结果如何
