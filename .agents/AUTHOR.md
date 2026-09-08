# Author：实现与自测

先读 `AGENTS.md`、`.agents/project.json`、`.agents/TESTING.md` 和 `README.md`。

1. 确认任务边界；你不是唯一工作者，不覆盖或撤销其他人的修改。
2. 在主仓库核对 `git status --short`、`git worktree list` 和 `git rev-parse main`。从确认过的最新 `main` 创建独立任务工作树，例如 `git worktree add ../ai-arena-android-worktrees/YYYYMMDD-task-seat -b task/YYYYMMDD-task-seat main`。执行时将日期、任务、席位填为本次实际值。
3. 在工作树根运行 `python scripts/setup_project.py` 检查并启用已有钩子；JDK 17、SDK 36、Python 3.10+ 和 Git 必须可用。SDK 通过 `ANDROID_HOME` 或未跟踪的 `local.properties` 配置。Gradle 用户级下载缓存可以复用，项目 `.gradle/`、`app/build/` 必须属于当前工作树。
4. 实现并运行 `python scripts/run_checks.py`。涉及 UI、WebView、会话状态、适配器、更新或签名的变更，另按 TESTING.md 完成隔离设备验证；不借用用户已登录设备。
5. 不为普通开发任务提前抬版本；发布任务统一协调 `versionName` / `versionCode`、README、CHANGELOG 和签名身份。
6. 只暂存本任务文件并提交。交付候选完整 SHA、基于的主干完整 SHA、验证证据和风险。默认不创建 PR、不推送主干；用户明确要求远端交付时沿用授权范围。
7. 交给独立 Merger。修订产生新 SHA 后重新审查。禁止自审、自合并或设置合并位 bypass 变量。

最终固定四行（中间进度不要使用终态标签）：

```text
PROGRESS: 完成内容、任务分支、候选完整 SHA、主干完整 SHA
VERIFIED: 亲自执行的命令、测试数、耗时、设备范围与结果
RISK: 已知风险和未验证项；没有则写无已知阻塞
REPORT: 本次报告实际绝对路径；无单独文件则写本消息
```
