# CraftPilot Docker 本地开发环境

项目位于 `E:\shopper`，Compose 项目名为 `craftpilot`，服务加入独立 bridge 网络 `craftpilot_dev`。只有 PostgreSQL、Redis 和 MinIO 三个服务。

## 手动启动与停止

在 Windows 开始菜单中打开 Docker Desktop，等待 Linux Engine 就绪。如需关闭登录自启，在 Docker Desktop 的 **Settings → General** 取消勾选 **Start Docker Desktop when you sign in to your computer**，然后应用设置。此项由使用者手动操作。

启动 Docker Desktop 只启动引擎。所有 CraftPilot 容器使用 `restart: "no"`，引擎重启不会依据重启策略自动启动它们。通过以下命令显式启动或停止。

```powershell
Set-Location E:\shopper
.\scripts\dev-up.ps1
.\scripts\dev-status.ps1
.\scripts\dev-down.ps1
```

`dev-up.ps1` 等待健康检查，不会自动打开 Docker Desktop。已存在容器的配置或重启策略有差异时，脚本会报错并保留现有容器。停止脚本调用 Compose `stop`，保留容器、网络和数据。状态脚本只查看状态与配置端口。脚本限定当前项目和本地 Windows Docker Engine，不改全局设置。

## 端口与持久化

| 服务 | 固定镜像标签 | 本机连接 | 绑定目录 |
|---|---|---|---|
| PostgreSQL 16 + pgvector | `pgvector/pgvector:0.8.7-pg16-trixie` | `127.0.0.1:15432` | `data/postgres` |
| Redis 7 | `redis:7.4.12-alpine3.22` | `127.0.0.1:17379` | `data/redis` |
| MinIO | `craftpilot/minio-dev:2026-11-12`，从固定 Chainguard 摘要构建 | API `127.0.0.1:19000`，Console `127.0.0.1:19001` | `data/minio` |

PostgreSQL 和 Redis 在 Compose 固定摘要。MinIO 的 Dockerfile 固定 Chainguard MinIO 与 BusyBox 健康探针的基础镜像摘要。首次初始化空目录时，启动脚本为非 root MinIO 准备写入权限。R1-0 使用的旧镜像保留为历史记录，升级与备份见 [R1-1.5 报告](R1-1.5-ci-report.md)。

## 本地环境变量

当前工作区已有生成的 `.env`，无需覆盖。新工作区可把 `.env.example` 复制为 `.env`，为下列三个密码分别填入独立随机值。模板中的密码均为空。`.env` 和 `data/` 已加入 Git 忽略规则。

| 变量 | 用途 |
|---|---|
| `CRAFTPILOT_POSTGRES_DB` / `CRAFTPILOT_POSTGRES_USER` | 开发库 `craftpilot` 和非超级用户 `craftpilot_dev`。 |
| `CRAFTPILOT_POSTGRES_ADMIN_PASSWORD` | 首次初始化 PostgreSQL 的管理员密码。 |
| `CRAFTPILOT_POSTGRES_PASSWORD` | Spring Boot 和 Flyway 的开发数据库密码。 |
| `CRAFTPILOT_POSTGRES_PORT` / `CRAFTPILOT_REDIS_PORT` | 对应 PostgreSQL 和 Redis 的本机端口。 |
| `CRAFTPILOT_MINIO_ROOT_USER` / `CRAFTPILOT_MINIO_ROOT_PASSWORD` | MinIO 本地管理凭证，后续应用接入时可配置专用账户。 |
| `CRAFTPILOT_MINIO_API_PORT` / `CRAFTPILOT_MINIO_CONSOLE_PORT` | 分别为 S3 API 和管理界面端口。 |

PostgreSQL 首次初始化时安装 `public.vector`，为应用用户创建 `craftpilot_app` schema。Spring Boot 的 JDBC URL 为 `jdbc:postgresql://127.0.0.1:15432/craftpilot`。Flyway 的 `default-schema` 和 `schemas` 使用 `craftpilot_app`，应用用户默认搜索路径为 `craftpilot_app, public`。初始化 SQL 只在空数据目录首次运行。已有数据库的角色、库名和密码变更需在库内显式维护。

Redis 只供本机开发使用，启用 AOF `everysec`。PostgreSQL 仍是事务、租约与审批的权威来源。MinIO API 使用 S3 客户端访问 `http://127.0.0.1:19000`，本地可启用 path-style。业务桶维持默认私有权限，通过认证或应用受控授权访问。

## 排查

- Engine 不可用时先手动打开 Docker Desktop，确认处于 Linux 容器模式。脚本拒绝远程 Docker 上下文。
- 在项目根目录执行 `docker compose config --quiet` 校验配置。完整配置输出会展开凭证，排查时使用 quiet。
- 执行 `docker compose logs --tail 80 postgres`，将服务名替换为 `redis` 或 `minio` 查看对应日志。
- 端口被占用时，先检查监听者。未创建容器时可以在 `.env` 更换本机端口。已有容器配置不一致时先备份并人工评估变更。
- 若出现 bind mount 访问错误，检查 Docker Desktop 对 E 盘工作区的访问权限。已有数据需要核对版本和目录来源，保留原数据。
- 常规停机始终使用 `dev-down.ps1` 或 `docker compose stop`。容器、镜像、卷和数据目录的清理不属于正常停机。

实测见 [验证记录](docker-verification.md)。
