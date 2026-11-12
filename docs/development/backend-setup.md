# CraftPilot 后端本地开发

## 启动

1. 手动打开 Docker Desktop，等待 Linux Engine 就绪。
2. 在项目根目录运行 `.\scripts\dev-up.ps1`。
3. 设置当前终端的 `JAVA_HOME` 为 Java 21 JDK，进入 `backend` 执行 `.\mvnw.cmd package`。
4. 回到项目根目录运行 `.\scripts\backend-up.ps1`。后端默认监听 `127.0.0.1:18080`。
5. 打开 `/actuator/health/liveness` 查看应用存活，打开 `/actuator/health/readiness` 查看 PostgreSQL、Redis 和 MinIO 就绪。后端可用 Ctrl+C 停止，基础设施用 `.\scripts\dev-down.ps1` 停止。

启动脚本读取 Compose 同目录的 `.env`。可通过进程环境覆盖本地配置。应用直接启动时需要 `CRAFTPILOT_JDBC_URL`、`CRAFTPILOT_POSTGRES_USER`、`CRAFTPILOT_POSTGRES_PASSWORD`、`CRAFTPILOT_REDIS_PORT`、`CRAFTPILOT_MINIO_ENDPOINT`、`CRAFTPILOT_MINIO_ACCESS_KEY`、`CRAFTPILOT_MINIO_SECRET_KEY` 和 `CRAFTPILOT_MINIO_BUCKET`。可选变量为 `CRAFTPILOT_HTTP_PORT`、`CRAFTPILOT_HTTP_ADDRESS`、`CRAFTPILOT_REDIS_HOST`。Spring 运行配置不依赖 Docker 自动启动。

Flyway 只管理 `craftpilot_app`。连接池上限为 8，获取连接超时为 3 秒。就绪探针缓存 5 秒，MinIO 只检查配置桶，Redis 执行 PING。脚本默认选择 R1-0 已验证的私有桶作为本地健康目标。可改为其他预置私有桶，并用 `CRAFTPILOT_MINIO_ACCESS_KEY` 与 `CRAFTPILOT_MINIO_SECRET_KEY` 指定只获该桶所需权限的账号。未设置时本地脚本使用 Compose 的管理凭证。应用启动不会创建桶或修改桶策略。

## 开发认证

开发认证要求唯一活动 profile 为 `dev`、显式设置 `CRAFTPILOT_DEV_AUTH_ENABLED=true`、且监听回环地址。启动脚本设置这些条件。外部环境的开发认证开关应保持关闭。

首次需要 API 账号时，在启动前通过当前进程的 `CRAFTPILOT_BOOTSTRAP_LOGIN` 和 `CRAFTPILOT_BOOTSTRAP_PASSWORD` 提供新的开发登录名与随机密码。密码长度至少 16 字符且不超过 72 UTF-8 字节。使用安全输入或终端环境注入，不把密码写进命令历史、仓库或日志。启动时一次性创建用户、独立商家、Owner 关系及 24 小时凭证。成功后从父终端移除这两个环境变量，后续启动沿用数据库记录。重复登录名会拒绝引导，凭证过期后可用新的开发登录名。R1-1 不开放公共注册。

API 客户端使用 HTTP Basic 访问本机服务。未认证返回 401；没有有效成员关系时商家资源返回 404；成员操作需要该商家的 Owner，否则返回 403。撤销成员时发送 UUID 格式的 Idempotency-Key；相同目标重放返回 204，同键改目标返回 409。Owner 成员关系受保护。请求和错误响应以 `X-Trace-Id` 关联。生产身份接入留待相应部署阶段。

用 PowerShell 安全输入初始化账号示例

```powershell
$env:CRAFTPILOT_BOOTSTRAP_LOGIN = Read-Host 'New local login'
$secure = Read-Host 'Development password' -AsSecureString
$env:CRAFTPILOT_BOOTSTRAP_PASSWORD = [Net.NetworkCredential]::new('', $secure).Password
try {
    .\scripts\backend-up.ps1
} finally {
    Remove-Item Env:CRAFTPILOT_BOOTSTRAP_LOGIN,Env:CRAFTPILOT_BOOTSTRAP_PASSWORD -ErrorAction SilentlyContinue
}
```

## 测试

从 `backend` 执行 `.\mvnw.cmd -B -ntp verify`。Testcontainers 使用临时端口及数据库，不依赖开发库或其数据。结果位于 `backend/target/surefire-reports`，测试无需先启动 Compose 服务。
