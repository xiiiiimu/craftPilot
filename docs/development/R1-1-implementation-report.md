# CraftPilot R1-1 实施报告

2026-11-13，Asia/Shanghai。**R1-1 PASSED**。

## 工程和依赖

根目录 `E:\shopper\backend`。Maven 单模块、按包分层，`identity` 与 `infrastructure` 共用 `common`，启动入口组装。采用 Spring Data JDBC 的 JDBC 基础设施与 `JdbcClient`，使租户授权条件、参数绑定和复合键在 SQL 中清楚可查。Spring Boot BOM 管理 Spring Web、Validation、Actuator 与下表未单独覆盖的依赖。

| 组件 | 构建解析结果 |
|---|---|
| JDK / Maven / Wrapper | Java 21.0.11 / Maven 3.9.16 / Wrapper 3.3.4 |
| Spring Boot / Security / Data JDBC | 3.5.16 / 6.5.11 / 3.5.13 |
| PostgreSQL JDBC / Flyway / Lettuce | 42.7.11 / 11.7.2 / 6.6.0.RELEASE |
| MinIO Java SDK / OkHttp JVM | 9.0.4 / 5.5.1 |
| Testcontainers / JUnit Jupiter / AssertJ | 1.21.4 / 5.12.2 / 3.27.7 |

Spring Boot 3.5.16 是 3.5 系列最后一个 OSS 发行版本，符合本轮 Spring Boot 3.x 约束。部署到共享环境前需另行确认维护与补丁方案。 [Spring Boot 发行说明](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)

## 新增与修改

- `backend/pom.xml`、`backend/.mvn/wrapper/maven-wrapper.properties`、`backend/mvnw`、`backend/mvnw.cmd` 和发行包附带的两个 `mvnwDebug` 脚本。
- `backend/src/main/java/com/craftpilot/CraftPilotApplication.java`、`common/{ApiError,ApiFailure,ApiExceptionHandler,TraceFilter}.java`、`identity/{IdentityController,IdentityDtos,IdentityService,DevBootstrap}.java`、`infrastructure/{SecurityConfiguration,DependencyHealthConfiguration}.java`。
- `backend/src/main/resources/application.yml`、`db/migration/V1__identity.sql`、`backend/src/test/java/com/craftpilot/FoundationTest.java`。
- `scripts/backend-up.ps1`、`backend/README.md`、`docs/development/backend-setup.md` 和本报告。
- 根 `.gitignore` 增加后端构建产物规则。R1-0 的 Compose、服务启停脚本和 `.env` 保持原样。

## Flyway、身份与租户约束

`V1__identity.sql` 在 `craftpilot_app` 下创建 `principal`、`merchant`、`merchant_role`、`merchant_member`、`dev_credential`、`membership_revoke_command`。会员主键为 `(merchant_id, principal_id)`，含独立的主体、商家与角色外键，并有有效成员查询索引。撤销回执的两组复合外键要求操作者与目标都属于同一商家。未配置 Hibernate 自动 DDL。

商家权限由带当前主体、有效成员状态和商家条件的参数化 SQL 计算。撤销事务重新检查 Owner，锁定商家行，原子保存撤销时间与幂等回执。随后使用同一认证凭证发起的请求会重新读取数据库。普通成员可读取自己所属商家，Owner 可查看成员及撤销非 Owner 成员。群管理员是未来群级授权，平台运维身份不会自动获得租户权限。此阶段隔离由 SQL 授权和数据库约束实现。

开发 HTTP Basic 仅在单独激活 `dev`、显式启用开关并监听回环地址时可用。开发凭证以 BCrypt 存储并由数据库验证到期时间。引导需要明确提供环境变量，新建专用本地账号和商家，凭证有效期 24 小时。日志记录 HTTP 方法、状态、耗时和 Trace ID。

## 实际 API

| API | 授权及结果 |
|---|---|
| GET /actuator/health、/liveness、/readiness | 本机健康访问；存活与 PostgreSQL、Redis、MinIO 依赖就绪分开。后两者位于 `/actuator/health` 下。 |
| GET /api/v1/me | 认证主体 DTO。 |
| GET /api/v1/me/merchants | 当前主体的有效商家关系，分页。 |
| GET /api/v1/merchants/{id} | 当前商家成员；资源不可见返回 404。 |
| GET /api/v1/merchants/{id}/members | 当前商家 Owner，分页。 |
| DELETE /api/v1/merchants/{id}/members/{principalId} | Owner；UUID 格式 `Idempotency-Key`；撤销成功或相同命令重放返回 204，同键异目标返回 409。 |

请求错误返回统一 code、message、traceId、retryable，响应带 `X-Trace-Id`。参数无效 422，未认证 401，权限不足 403 或资源不可见 404。

## 自动化测试

从 `E:\shopper\backend` 执行 `.\mvnw.cmd -B -ntp -l target/r1-1-final-verify.log verify`。最终结果为 **BUILD SUCCESS，7 个测试通过、0 失败、0 错误、0 跳过**。Testcontainers 使用临时端口和空数据库，应用连接不指向 R1-0 开发数据。

| 用例 | 已验证内容 |
|---|---|
| T1 | Flyway 在空 PostgreSQL 16.15 上应用 V1，重复迁移执行数为 0，数据库拒绝无效 FK、错误角色、重复会员及跨租户回执。 |
| T2 | A/B、猜测 UUID、无商家、双商家角色隔离及成员撤销后立即失效。 |
| T3 | 未认证、伪造头、错误及过期凭证、Owner 与 Member 权限差异、撤销重放与冲突，非 dev profile 启用开发认证时启动失败。 |
| T4 | 真实 PostgreSQL、Redis 与 MinIO 就绪；暂停测试 Redis 后 readiness 503，Redis 组件 DOWN，liveness 保持 200。 |
| T5 | 无效请求返回 422，错误正文和响应头 Trace ID 一致。捕获日志中未出现测试密码或其 Basic 编码。 |
| T6 | Maven Wrapper 全量编译、测试、可执行 JAR 打包成功。 |

本地日志为 `backend/target/r1-1-final-verify.log`，明细为 `backend/target/surefire-reports/com.craftpilot.FoundationTest.txt`。构建日志记载 `Successfully applied 1 migration`、`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`。

## R1-0 真实环境联调

手动执行 `.\scripts\dev-up.ps1`，用 `.\scripts\backend-up.ps1` 启动构建所得 JAR。使用既有 Compose 端口 15432、17379 和 19000，在 `127.0.0.1:18080` 实测 readiness 200，db、redis、minio 均 UP。使用临时开发凭证访问 `/api/v1/me` 与 `/api/v1/me/merchants` 均为 200，商家角色为 OWNER。匿名访问 `/api/v1/me` 为 401。

手动重启后端并重测相同接口，全部结果一致。Flyway 启动日志显示首次应用 V1，后续 `Schema "craftpilot_app" is up to date. No migration necessary.`。开发库历史表查询仅有 `1|t` 一条 SQL 迁移。既有 PostgreSQL、Redis 和 MinIO 的 R1-0 探针均读回 `r1-0-persisted`。实测日志在 `backend/target/r1-1-local-smoke.log`。开发库保留 V1 和本机烟测创建的专用账号、商家及 24 小时凭证记录，明文密码未落盘。

联调后已停止后端及本项目三个服务。Docker 实际检查均为 `exited restart=no`。

## 依赖风险和 R1-2 前置条件

MinIO 使用 R1-0 的 `dappros/minio:RELEASE.2025-09-07T16-13-09Z`，摘要为 `sha256:624bad2fe79a8613aa3c5c9b765ebcab365080b8445cf1b05c357cc7753725f0`。该镜像为第三方重新发布，上游项目已归档。对应版本早于修复 CVE-2025-62506 的上游发行。运行 `docker scout cves --only-severity critical,high local://dappros/minio:RELEASE.2025-09-07T16-13-09Z` 时 Docker Scout 要求账号登录，**完整镜像扫描 NOT VERIFIED**。镜像标签、摘要和公告核对不构成安全审计。共享部署前需核验受维护镜像及最小权限凭证。 [镜像来源](https://hub.docker.com/r/dappros/minio) · [上游修复发布](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z) · [安全公告](https://github.com/advisories/GHSA-jjjj-jwhf-8rgr)

R1-2 以已通过的身份、数据库迁移与测试底座为前置。新增租户关系应复用 `merchant_id` 和当前成员查询，并对新复合外键编写跨租户反例。生产认证、商品、群聊、调查、Outbox 和 Agent Runtime 属于后续阶段。本轮已停在 R1-1。

[启动与开发认证说明](backend-setup.md)
