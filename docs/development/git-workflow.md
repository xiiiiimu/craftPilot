# CraftPilot Git 与测试先行流程

`main` 保存已审查的稳定基线。新功能使用 `feature/*`，缺陷修复使用 `fix/*`。从最新 main 创建分支，先写失败用例并记录失败确实来自目标行为缺失，再完成实现、使测试通过、重构并执行完整回归。迁移、基础设施和文档先定义可运行的验收检查，再实施变更并复验。

```text
git switch main
git pull --ff-only
git switch -c feature/short-description
```

提交前只暂存已核对文件，检查 `git diff --cached --name-status`，运行仓库及凭证扫描。功能分支推送后创建 PR，贴上 Red、Green 与回归证据，等待必需状态检查与其他审查者批准。失败时修复实现或测试环境，保留有价值的断言。

首个空仓库基线需先创建 `main`，供 GitHub 加载工作流。随后通过 PR 合并，关闭直接推送、强推和删除。保护配置见 [CI 与保护](ci-setup.md)。

发现凭证时停止推送，记录文件、提交与可见范围，立即吊销和轮换受影响凭证，再与仓库所有者处理残留。历史泄漏不会因新增忽略规则消失。
