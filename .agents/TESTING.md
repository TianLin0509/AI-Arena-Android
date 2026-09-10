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

### 0.13.2 r9 的已记录范围（2026-09-10）

Author 在隔离合成网页设备上通过主机闸门（8 项工作流、131 项 JVM、debug/release lint 与构建）和全部 122 项 Android instrumentation。独立 Merger 在保留登录的隔离 Android 副本中，对 r9 debug 候选完成单张 PNG、单个 TXT 两轮初始自动发送；DeepSeek、豆包、Kimi 的 App 展开正文均严格核对随机编号与数量通过。操作为 App 新会话、系统选文件、开始讨论，无网页手工操作或失败项重发；正文检查来自原生 UI，另有被动网页事件观察。豆包 TXT 中的 U+2011 连字符只作展示归一化，不放宽编号或数量核对。

真实证据由 Merger 保存为 `artifacts/20260909-real-attachment-r9-image-native-acceptance.json` 和 `artifacts/20260909-real-attachment-r9-document-native-acceptance.json`；Author 的红绿、主机及全量设备证据保留在任务工作树 `artifacts/20260909-real-attachment-r9-*`。这些是带日期的候选记录，不能代替最终 SHA 的独立闸门，也不覆盖其他格式、多附件、其他账号 / 网页版本、正式 R8 包实传、签名升级或 API 26 发布冒烟。

## 发布

当前产品版本 `0.13.2`、versionCode `22`，折叠回答 UI 基线 `aaecc778fcbe6a9a98aa8dfc1adcc8d908de3aee`。发布任务才更新版本；不采用每次合并自动 bump，以免普通流程变更触发用户升级。

保持 `com.tianlin.aiarena` 和原 release 密钥。`keystore.properties`、密钥和密码留在仓库外/忽略文件；无签名配置的 release 构建是未签名包。Debug APK 和 CI 未签名 APK 不能替换用户已安装的签名 release。

发布必须更新 README / CHANGELOG，核对 APK manifest、证书、升级序号和目标 commit；覆盖升级及 API 26 冒烟通过后再分发。GitHub Actions 和源码 push 不代表已经发布手机更新。

## 工作流来源

`scripts/merge_task.py` 与 `.githooks/*` 原样来自 `TianLin0509/project-prep` v0.1.0，提交 `441d2c732a7c3718acd5b499dccf3e9c218b2925`，MIT（Copyright 2026 TianLin0509）。本仓库同为 MIT。它绑定主干/候选完整 SHA，在一次性 Git fixture 验证，不在旧运行目录试验。

附件变更还须验证：系统文件选择、实际字节复制、限制与取消、上传失败不发文字、同一附件交付多个隔离 WebView、历史/重试引用保留，以及仅失败项串行恢复。合成网页通过不代表六家真实账号全部上传通过。

0.13.2 图片适配验收还须覆盖：豆包当前输入区关联菜单及附件区域、Normal 状态保留 blob 预览但远端 key 匹配；DeepSeek 原始状态被批量渲染跳过时的选图确认、唯一名称映射与两次稳定 localId 观察，以及同名歧义、旧 ID、额外卡、身份变化、上传/审核失败时不发送；Kimi 当前 DIV 发送控件和禁用时不触发回车兜底。官网账号自动上传及随机图片内容识别必须单独记录，不能用这些回归测试代替。

附件兼容还须覆盖：DeepSeek 超过 100 层的 React 树、current/alternate 切换、循环和超出 512 层时拒绝读取；Kimi 菜单标识延迟初始化时不提前点击；Kimi、豆包现代菜单首次点击被吞后的有界恢复、三次无响应结束及已打开菜单不重复点击。

Kimi 本地上传标签恢复只允许唯一已打开且绑定到 toolkit 的菜单、同一 label/input、空 file list、本请求未交付，至少相隔 2 秒且最多三次；必须验证已打开菜单不先关闭、菜单关闭/换 input/取消后无旧尝试、native 已交付但 change 延迟时不再触摸、重复 chooser 不重复 URI 也不中止首个合法上传。其他厂商上传项不扩大重试；手动 picker 只允许当前用户打开的原网页。

Kimi 菜单点击后卸载原 input 的路径必须用生产 pool 和真实 native 文件回传验证：document 没有收到 change 时，唯一绑定的原 input 仍核对名称和字节大小，TXT/PNG 实际字节正确后才发正文；取消、完成、prepare 替换时清理 input 监听，替换 input 的事件和旧请求迟到事件不得确认新请求。豆包当前可信 attachmentStates 晚恢复额外草稿时须及时报告冲突、保持正文未发送且不删除草稿。

上传入口几何恢复须验证 JS 定位后、native 回调前实际改变 WebView 尺寸：不触摸旧坐标，只有当前请求、页面代次、可信 URL 和控件序号仍匹配且尚未 DOWN 时才能归还预占，重新读取 rect/hit。连续失配最多三个快照并受 4 秒恢复窗口约束，原生/网页尺寸须非零且有限，NaN/Infinity 不得误点原点；取消及新请求不能收到旧触摸。异步归还期间已交付 URI 时转入 readiness，保留唯一 input 的 capture 监听，不能撤销合法上传或重复交付。恢复窗口不替代原有网页脚本无响应 watchdog。

原生 UP 与浏览器实际 click 不同步，上传焦点须等到本轮控件 click 默认行为结束或 broker 交付；Kimi 本地标签仅由精确 input click 确认。单次等待最多 1500ms，有既存次数上限的菜单/标签可继续原有恢复，不能重置计数；不等文件 change、网络上传或解析。须验证实际延迟 input 激活时的网页焦点、真实 URI、解析期间不持焦点，以及取消后迟到 chooser/确认不能影响其他任务。不能把“真实现场提前失焦”直接表述为所有间歇上传失败的已证实根因。

发送脚本与豆包补点须共享 request 级点击预占：真正发送后等待送达证据，DOM 用户行或输入框清空延迟时不得二次点击。验证生产 pool 延迟用户 DOM/清空仍只发送一次，disabled 不占用唯一点击，取消旧 timer 后显式新请求仍可发送。确认无误前不把官网后来答对代替 App 当前展示正确。

Kimi 文件卡还须覆盖：错误态没有 .file-ext 时由唯一 img.file-card-icon.alt 核对扩展名并立即返回错误；成功态、扩展名冲突、多图标、错名、旧卡及重复卡不得误放行。必须有生产 pool 的实际 URI 交付后失败不发送正文，以及晚出现额外文件或图片时明确阻止发送的回归。

并发变更还须验证：任何发送确认前所有成员都已启动、慢成员不阻塞其他成员、新对话乱序就绪及失败、单家超时与取消隔离、全局停止后的迟到回调、串行模式顺序，以及真实 WebView 中重叠上传和短暂焦点操作。不得用纯控制器 Fake 代替网页任务并发证据。
