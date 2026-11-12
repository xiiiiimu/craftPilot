# CraftPilot Java 后端

Java 21、Spring Boot 3.5 和 Maven Wrapper。单一构建模块按包组织。应用入口依赖 identity 和 infrastructure，identity 依赖 common 与 Spring JDBC/Security 接口，infrastructure 依赖 common 及数据库、Redis、MinIO 客户端。common 不依赖业务包。

identity 使用 Spring Data JDBC 提供的 JDBC 基础和 `JdbcClient` 执行参数化 SQL。商家读取通过成员关系在查询内授权，避免将来自 URL 的商家 ID 当成授权。数据库外键和复合键约束成员关系，权限由 PostgreSQL 实时读取。

从 `backend` 运行 `.\mvnw.cmd verify`。测试使用临时 PostgreSQL、Redis 和 MinIO 容器，需要手动启动 Docker Desktop。使用说明见 [本地后端启动](../docs/development/backend-setup.md)。
