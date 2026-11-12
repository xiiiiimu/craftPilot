# CraftPilot CI 与仓库保护

工作流为 `CraftPilot CI`，检查名为 `Build, test and repository security`。它在 PR 指向 main、main 推送或手动触发时运行。Ubuntu 托管 Runner 安装 Java 21 并缓存 Maven，使用 `backend/mvnw` 执行 `clean verify`。当前 `FoundationTest` 由 Surefire 运行。若后续引入 Failsafe，必须在 Maven 配置中绑定 `integration-test` 和 `verify`，其报告已纳入校验。测试依赖由 Testcontainers 创建，使用临时端口和隔离凭证。

门禁检查 Git 跟踪路径、Word 包元数据、Gitleaks 历史扫描、Compose 镜像固定与回环策略、编译及实际测试、OSV 依赖漏洞扫描。缺少报告、少于基线用例数、失败或跳过均阻断。CI 上传文本测试摘要、运行依赖树与 OSV JSON，保留 14 天。OSV 发现漏洞或执行失败均保留非零退出码。依赖图和镜像摘要核对用于确定扫描范围，镜像整体扫描结果另行记录。

在 PowerShell 本地运行

```powershell
Push-Location backend
.\mvnw.cmd -B -ntp clean verify
Pop-Location
python scripts/check-repository.py tests
python scripts/check-repository.py tracked
gitleaks git --staged --redact
gitleaks git --redact --log-opts=--all
osv-scanner scan source --lockfile backend/pom.xml
```

完整镜像扫描可用有 Docker 访问权限的 Trivy 执行 `trivy image --scanners vuln IMAGE@sha256:DIGEST`，或用 `osv-scanner scan image IMAGE@sha256:DIGEST`。镜像扫描需读取供应商漏洞库，失败应记为 NOT VERIFIED。先扫描 Compose 的固定基础镜像，再扫描派生后的 MinIO 本地镜像。

## GitHub main 保护

1. 先等待工作流报告实际检查名。打开 **Settings → Branches → Add branch protection rule**，分支模式写 `main`。
2. 勾选 **Require a pull request before merging**，要求 1 次其他审查者批准。
3. 勾选 **Require status checks to pass before merging**，选择 GitHub Actions 的 `Build, test and repository security`，并要求分支与 main 保持最新。
4. 勾选 **Do not allow bypassing the above settings**，保留强推、删除与绕过规则关闭，保存后检查规则实际作用于 main。
5. 个人仓库作者不能批准自己的 PR。需要另一位有资格的审查者；保护不可用时记录仓库套餐或权限限制，并保留 PR 人工审核流程。

[GitHub 分支保护文档](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/managing-a-branch-protection-rule)
