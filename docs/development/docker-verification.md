# CraftPilot R1-0 Docker 实测记录

验证日期为 2026-11-12（Asia/Shanghai）。环境为 Windows Docker Desktop 的 `desktop-linux` 上下文、Docker Engine 29.8.1 和 Docker Compose v5.5.1。项目根目录为 `E:\shopper`。

| 项目 | 实测结果 |
|---|---|
| Compose 配置 | `docker compose config --quiet` 退出码 0。三个服务均配置 healthcheck 和 `restart: "no"`。 |
| 服务启动 | `dev-up.ps1` 启动 3 个容器，全部变为 healthy。 |
| PostgreSQL 连接与健康 | PostgreSQL 16.15；专用用户 `craftpilot_dev` 经 TCP 认证成功，`rolsuper=f`，默认 schema 为 `craftpilot_app`。应用 schema 内有 0 张表，可留给后续 Flyway。 |
| pgvector | 执行 `CREATE EXTENSION IF NOT EXISTS vector` 成功，查询 `pg_extension.extversion` 返回 `0.8.7`；相同向量的距离为 0。 |
| PostgreSQL 读写 | 用开发用户在独立验证 schema 内写入并读回 `r1-0-persisted`。 |
| Redis | Redis 7.4.12；`PING` 返回 `PONG`，`CONFIG GET appendonly` 返回 `yes`。 |
| MinIO | `RELEASE.2025-09-07T16-13-09Z`；容器 healthy，宿主机 readiness HTTP 200；认证读写对象成功，匿名 GET 返回 HTTP 403。 |
| 手动停机与重启 | `dev-down.ps1` 停机后，PostgreSQL 的 `PG_VERSION`、Redis 的 AOF 目录、MinIO 的 `.minio.sys` 都仍在宿主机。随后通过 `dev-up.ps1` 启动，容器 ID 未变，三种探针均读回 `r1-0-persisted`。 |
| 状态命令 | 停机时执行 `dev-status.ps1`，能显示退出状态和配置端口，运行中的 CraftPilot 容器数仍为 0。 |
| 端口 | 运行时检查 Docker 实际发布地址，PostgreSQL 15432、Redis 17379、MinIO 19000/19001 的 HostIp 全部为 `127.0.0.1`。 |
| 最终状态与重启策略 | 再次执行 Compose stop 后，三个容器均为 `exited`、退出码 0。逐个检查 `HostConfig.RestartPolicy.Name` 均为 `no`。 |
| 其他项目 | 验证前后，29 个其他容器的 ID、状态和端口、15 个其他网络及 22 个既有卷的清单一致。未启停其他项目或修改 Docker Desktop 设置。 |

引擎重启行为以实际容器的 `restart=no` 验证。本轮未重启共享 Docker Engine。

验证探针保留在当前工作区的绑定数据目录中。PostgreSQL 使用 `craftpilot_r1_verification.persistence_probe`，Redis 使用 `craftpilot:r1-0:persistence-probe`，MinIO 使用私有桶 `craftpilot-r1-0-verification` 的 `probe.txt`。它们与业务 schema、业务缓存及未来业务桶分开。当前三个服务均已停止，下次使用前手动运行 `scripts/dev-up.ps1`。

[安装、连接和排查](docker-setup.md) · [固定镜像摘要和健康配置](../../compose.yaml)
