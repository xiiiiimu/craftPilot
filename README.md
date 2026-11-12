# CraftPilot

CraftPilot 面向原创手作商家。计划用站内群聊组织限时调研，收集样品反馈并生成可追溯的改版、试产或上新建议，由商家确认决定。

[![CraftPilot CI](https://github.com/xiiiiimu/craftPilot/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/xiiiiimu/craftPilot/actions/workflows/ci.yml)

## 当前状态

| 状态 | 范围 |
|---|---|
| 已实现 | Java 21 + Spring Boot 模块化单体基础、Flyway 身份迁移、开发认证、商家成员权限、健康检查和请求追踪。 |
| 已测试 | PostgreSQL、Redis、MinIO 本机连接、空库迁移、租户隔离、成员撤销和真实容器集成。实际结果见实施报告。 |
| 设计已完成 | 商品企划、样品版本、BOM、群聊、48 小时调查、反馈聚合和证据化人工决策。自研 Runtime 规划覆盖 Workflow、Multi-Agent、Skills、MCP、RAG、Memory、Checkpoint、HITL 和评测。 |
| 待真实验证 | 推理模型和 Qwen-VL、Embedding、MCP 互操作与端到端研究质量。 |

PostgreSQL 16 + pgvector 保存业务数据，Redis 提供短期状态，MinIO 存储私有对象。自研 Runtime 尚在设计阶段。DeerFlow 与 DeepSeek SuperAgent 仅作远程源码参考。

## 本地开发

手动打开 Docker Desktop，进入项目根目录。新工作区按配置指南设置 `.env`，业务对象使用私有桶。

```powershell
.\scripts\dev-up.ps1
Push-Location backend
.\mvnw.cmd -B -ntp clean verify
Pop-Location
.\scripts\backend-up.ps1
```

后端默认监听 `127.0.0.1:18080`。使用 `/actuator/health/readiness` 检查依赖。Ctrl+C 停止后端，再运行 `.\scripts\dev-down.ps1` 停止容器。所有服务的重启策略为 `no`，数据保留在本机 `data/`。开发认证仅用于回环地址，镜像及依赖风险见 CI 报告。

## 项目文档

- [架构与实施路线图](docs/architecture/implementation-roadmap.md)
- [Docker 配置](docs/development/docker-setup.md)与[后端启动](docs/development/backend-setup.md)
- [R1-1 实施与测试](docs/development/R1-1-implementation-report.md)
- [分支与 PR](docs/development/git-workflow.md)、[CI 门禁](docs/development/ci-setup.md)和[阶段证据](docs/development/R1-1.5-ci-report.md)
