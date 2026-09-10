# 构建、验证与发布边界

## 环境与统一主机闸门

JDK 17、Android SDK 36（platforms;android-36、build-tools;36.0.0）、Python 3.10+、Git 2.38+。Windows 使用 Git for Windows 提供的 sh。
Gradle Wrapper 已纳入仓库，不需要在工作根安装依赖。设置 `JAVA_HOME`、`ANDROID_HOME`；也可在当前工作树写未跟踪的 `local.properties`。

```text
python scripts/setup_project.py
python scripts/run_checks.py
```

setup 检查钩子完整和 LF、可用 Python/Git sh，保留不同的现有 hooksPath 并拒绝覆盖。linked worktree 共享 hooksPath，所有在用工作树必须先包含工作流文件。

run_checks 先执行 `tests/` 下全部 Python 工作流回归测试，再执行全部 `app/src/test` JVM 单测，检查报告中的测试类覆盖源码中的所有测试类、非零收集及无失败/跳过；执行 debug/release lint、两种 APK 构建及 instrumentation APK 编译。每次清理 JVM 测试任务输出并禁用构建缓存，以防拿缓存报告冒充执行。任何子命令失败均返回非零；耗时和 JSON 结果写入忽略的 `artifacts/`。

## 设备测试

`app/src/androidTest` 的全部测试类属于设备测试，主机闸门只编译，不执行。涉及运行时行为的任务，Author 和 Merger 都须在隔离模拟器执行全部 instrumentation 并保留结果。不能将构建成功说成真机通过。

请仅对本任务拥有的隔离设备操作，并在每条 adb 命令填写同一个明确 serial：

```text
adb -s SERIAL install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -r com.tianlin.aiarena.test/androidx.test.runner.AndroidJUnitRunner
```

adb 可能在 instrumentation 失败时仍退出 0，必须检查输出最终为 `OK (N tests)`，且无 `FAILURES`、`INSTRUMENTATION_FAILED` 或 crash。检查当前源码测试数量，不用历史固定数字代替。

不要对已登录设备运行会卸载目标包的 connectedAndroidTest、`pm clear`、卸载、wipe-data。UI 验证补充窄屏/大字、结果 Tab、队长总结和恢复路径；厂商网页真实登录/发送需使用专用测试账号与设备，并单独记录厂商和日期。

## 发布

当前产品版本 `0.13.2`、versionCode `22`，折叠回答 UI 基线 `aaecc778fcbe6a9a98aa8dfc1adcc8d908de3aee`。发布任务才更新版本；不采用每次合并自动 bump，以免普通流程变更触发用户升级。

保持 `com.tianlin.aiarena` 和原 release 密钥。`keystore.properties`、密钥和密码留在仓库外/忽略文件；无签名配置的 release 构建是未签名包。Debug APK 和 CI 未签名 APK 不能替换用户已安装的签名 release。

发布必须更新 README / CHANGELOG，核对 APK manifest、证书、升级序号和目标 commit；覆盖升级及 API 26 冒烟通过后再分发。GitHub Actions 和源码 push 不代表已经发布手机更新。

## 工作流来源

`scripts/merge_task.py` 与 `.githooks/*` 原样来自 `TianLin0509/project-prep` v0.1.0，提交 `441d2c732a7c3718acd5b499dccf3e9c218b2925`，MIT（Copyright 2026 TianLin0509）。本仓库同为 MIT。它绑定主干/候选完整 SHA，在一次性 Git fixture 验证，不在旧运行目录试验。

附件变更还须验证：系统文件选择、实际字节复制、限制与取消、上传失败不发文字、同一附件交付多个隔离 WebView、历史/重试引用保留，以及仅失败项串行恢复。合成网页通过不代表六家真实账号全部上传通过。

0.13.2 图片适配验收还须覆盖：豆包当前输入区关联菜单及附件区域、Normal 状态保留 blob 预览但远端 key 匹配；DeepSeek 原始状态被批量渲染跳过时的选图确认、唯一名称映射与两次稳定 localId 观察，以及同名歧义、旧 ID、额外卡、身份变化、上传/审核失败时不发送；Kimi 当前 DIV 发送控件和禁用时不触发回车兜底。官网账号自动上传及随机图片内容识别必须单独记录，不能用这些回归测试代替。

附件兼容还须覆盖：DeepSeek 超过 100 层的 React 树、current/alternate 切换、循环和超出 512 层时拒绝读取；Kimi 首次菜单点击被吞后的有界恢复、三次无响应结束及已打开菜单不重复点击。

并发变更还须验证：任何发送确认前所有成员都已启动、慢成员不阻塞其他成员、新对话乱序就绪及失败、单家超时与取消隔离、全局停止后的迟到回调、串行模式顺序，以及真实 WebView 中重叠上传和短暂焦点操作。不得用纯控制器 Fake 代替网页任务并发证据。
