# Merger：独立审查与本地合并

先读 `AGENTS.md`、`.agents/project.json`、`.agents/TESTING.md` 和 `README.md`。
Author 不得在同一 Agent 会话兼任本任务 Merger。只有独立审查 PASS 才可合并。

1. 核实本地主干与远端状态（远端只读检查或 fetch），记录 `git rev-parse main` 和候选完整 SHA。主干相对 Author 基线前进时，要求 Author 整合最新主干并重测，不能替他 rebase 或沿用旧证据。
2. 亲读 `git diff main...TASK_BRANCH`；核对范围、用户要求、测试覆盖、凭据和发布影响。业务改动按 TESTING.md 加做设备验收；纯文档和流程改动可不重新执行设备测试，但明确说明。
3. 在干净且位于 `main` 的主工作目录，绑定本次亲审的两个完整 SHA 执行：

```text
python scripts/merge_task.py TASK_BRANCH --expected-head FULL_CANDIDATE_SHA --expected-trunk FULL_TRUNK_SHA --dry-run
python scripts/merge_task.py TASK_BRANCH --expected-head FULL_CANDIDATE_SHA --expected-trunk FULL_TRUNK_SHA
```

参数必须替换为亲验值。两次均执行完整主机闸门。dry-run 会临时试合并，通过后恢复主干；不是只读命令。候选或主干变化就重审。

- 退出 0：按实际输出区分 dry-run / 合并 / 已包含。
- 退出 1：验证失败，已知试合并已撤销，交 Author 修订。
- 退出 2：前提不符或现场被保留；检查 HEAD、MERGE_HEAD、索引与工作区，禁止盲目 reset / abort / stash。
- 退出 3：提交已存在但后置检查失败，报告已有 SHA 并保留现场。

脚本不 fetch、push、rebase，也不自动发布或抬 App 版本。获得远端同步授权后，核对远端无分叉，以进程级 `PROJECT_PREP_ALLOW_PUSH=1` 执行普通 `git push origin main`，随后清除该变量；禁止 force push。本地 PASS 不等于已发布 APK。

配置变更需另外运行新配置；当前合并使用的是合并前主干配置。对新装工作流的一次性引导提交必须在记录中明示，不能冒称独立功能审查。

最终输出：

```text
RESULT: PASS 或 REVISE
BLOCKERS: 阻塞位置、问题和修订要求；没有则写无
VERIFIED: 亲验命令、结果、候选/主干完整 SHA，已合并则附合并 SHA
NEXT: 下一步责任人及动作；已完成则写本地合并完成
NOTES: 作出判断的具体依据和设备验证范围
```
