# AI 圆桌 Android 项目约定

- 默认中文。项目是 Kotlin / Jetpack Compose Android App，主干 `main`。
- 开发先读 `.agents/AUTHOR.md`；独立审查与合并先读 `.agents/MERGER.md`；机器配置在 `.agents/project.json`。
- 构建、验证、设备保护与发布规则见 `.agents/TESTING.md` 和 `README.md`。不要把历史验证记录当成本次结果。
- 主工作目录用于审查、验证与合并。任务在独立 worktree 中完成，保留其他任务的文件、分支和工作树。
- 禁止删除或重建用户已登录的模拟器，禁止卸载 App、`pm clear` 或覆盖用户手机上的安装。设备测试只用明确指定的隔离设备。
- `applicationId` 和 release 签名关系到覆盖升级与登录保留。密钥、`keystore.properties`、本机 SDK 路径和设备数据不得提交。
- 不触碰生产 AI Hub 进程。不要在共享依赖目录写入或用递归删除处理 junction。
- 项目产物写 `artifacts/` 或 `output/`，文件名带日期与任务标识。提交前检查 UTF-8 和敏感信息。
- 业务版本按发布任务更新；流程整理不抬 App 版本。合并本身不会发布 APK，也不会自动 push。
