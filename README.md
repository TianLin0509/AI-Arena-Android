# AI Arena Android（AI 圆桌）

[![Android CI](https://github.com/TianLin0509/AI-Arena-Android/actions/workflows/android-ci.yml/badge.svg)](https://github.com/TianLin0509/AI-Arena-Android/actions/workflows/android-ci.yml)

当前版本：`v0.8.0`（`versionCode 12`）

面向国内 Android 用户的多 AI 圆桌工具。应用本身无需注册账号、没有自建后端；用户只需在内嵌的 AI 官网完成一次登录，之后由 App 复用本机 WebView 登录状态，汇总多家回答并支持后续讨论。

> 本项目通过真实 AI 网页工作，不是厂商官方 API。网页 DOM 改版后可能需要更新适配器。

## 产品定位

- 默认成员：DeepSeek、豆包、Kimi。
- 可选成员：千问、元宝、智谱。
- 每次可选择 2-4 家 AI。
- 聚焦普通用户，尤其是中老年用户的日常提问、群策群力和幻觉风险提示。
- 登录信息、成员选择和讨论历史只保存在 Android 应用沙箱内。

## 界面风格

「设置 → 界面风格」提供六套可切换的风格，选中即时生效并保存在本机。
每套风格同时决定配色、圆角、描边粗细、阴影模型和字号基准，不只是换主色：

| 风格 | 定位 | 特点 |
|---|---|---|
| 净白 | 默认 | 白底、渐变标题；容器只靠浅灰填色区分，不画描边（对标豆包 / Kimi） |
| 清朗 | 经典 | 明亮通透的青蓝，圆角适中，浅描边 |
| 墨韵 | 中式 | 宣纸底 + 朱砂主色，小圆角、无阴影、标题衬线 |
| 夜航 | 深色 | OLED 友好的深蓝灰，系统状态栏图标跟随反色 |
| 长辈 | 无障碍 | 纯白高对比、字号 ×1.18、触摸目标 60dp、2dp 粗描边 |
| 暖阳 | 亲和 | 暖橙渐变，大圆角与柔和阴影 |

「长辈」风格可与「大字模式」叠加，二者互不冲突。

## 队长模式（0.8.0）

用户反馈"一个人看不过来那么多信息"，于是引入队长：

| 环节 | 队长的不同之处 |
|---|---|
| 观点讨论 | prompt 换成队长版：先写共识与分歧、指出可能的错漏，再给结论；篇幅 400 字（普通成员 200 字） |
| 结果页 | 置顶 + 「队长」徽章 + 「已整合大家的观点」；其他成员默认收成 2 行 |
| 讨论总结 | 默认由队长执笔 |
| 队长本轮没答上来 | 自动顺延给第一位参与者，不会没人整合 |

队长默认是第一位成员，可在「设置 → 队长」里改或整体关掉。
思路参考 Chrome 扩展 AI 圆桌派的 `captain-mode.js`。

## 面向家人的引导与兜底（0.7.0）

- 首次启动三步说明；登录引导页按「第 1、2、3 家」编号，附「怎么登录？」三句话。
- 打开某家 AI 的网页时顶部有登录提示条，探测到登录成功后变成绿色「已登录 · 返回圆桌」。
- 底栏只有三个去处：圆桌 / 历史 / 设置。首页只有一个永远可点的主按钮，没登录够两家时它带你去登录。
- 每家 AI 失败时显示白话「怎么办？」（原因 + 下一步），按钮按建议排序；讨论总结失败同理。
- 断网提示、上次异常退出提示都是非弹窗的提示条。
- 设置页「遇到问题？按顺序试」：重新加载 AI 网页 → 清除卡住的讨论 → 重启应用（独立进程中转，登录不丢）。

## 主要功能

- 并行回答：快速送达多家 AI，各家生成过程相互重叠。
- 严格串行：上一家稳定完成或失败后，才发送给下一家。
- 独立迭代：必须输入本轮 Prompt；向上一轮成功成员发送完全相同的用户原文，不附加原问题、旧回答或 App 模板。
- 观点讨论：每家收到其他成员的最新观点，可附加用户本轮讨论要求。
- 队长模式（默认开）：指定一位 AI 当队长，观点讨论时由它把大家的共识与分歧汇总成一条，
  结果页置顶并打徽章，其他成员默认收起 —— 一屏看完，不用逐条读。「讨论总结」也默认由队长写。
- 讨论总结：提炼结论、共识、分歧、待核验信息和行动建议。
- 交叉核验卡：提示参与成员数、共识、分歧和需要继续核实的内容，不生成虚假置信度。
- 单家补救：重发、重新提取或跳过，不要求整轮重跑。
- 本地历史：最近 20 个会话，支持冷启动恢复和损坏索引自愈。
- 语音输入、大字模式、六套界面风格、回答/总结朗读。
- 每家回答可展开全文、单独复制和朗读。
- 提问阶段就提示上下文预算风险，而不是等到点「观点讨论」才失败。
- 一键复制或调用 Android 系统分享总结。
- 24,000 字问题上限、12,000 字回答截取提示、最近 8 轮有界历史和上下文预算保护。
- 用户提问中不包含内部 request ID 或 `AI_ARENA_ID`。

## AI 网页适配状态

| AI | 官网入口 | 当前状态 |
|---|---|---|
| DeepSeek | `chat.deepseek.com` | 真实发送与提取已验证 |
| 豆包 | `doubao.com` | 真实发送、图片行和竞态提取已验证 |
| Kimi | `kimi.com` | 真实发送、最终答案与思考分离已验证 |
| 千问 | `qianwen.com` | 真实发送与提取已验证；支持 CAPTCHA 明确提示与恢复提取 |
| 元宝 | `yuanbao.tencent.com` | 真实并行、串行、迭代、讨论和总结已验证 |
| 智谱 | `chatglm.cn` | 真实并行、串行、迭代和讨论已验证 |

千问厂商安全验证不会被绕过。页面已有目标答案时，App 优先提取答案；没有答案时等待用户完成验证并在最终超时后给出明确提示。

## 构建

要求：

- JDK 17
- Android SDK 36
- Windows PowerShell、macOS 或 Linux shell

Windows：

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

macOS / Linux：

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

设备测试请手工覆盖安装测试 APK 后运行 instrumentation，避免使用会卸载目标包的测试流程污染已登录设备：

```powershell
adb install -r -t app\build\outputs\apk\debug\app-debug.apk
adb install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -r com.tianlin.aiarena.test/androidx.test.runner.AndroidJUnitRunner
```

## 版本与升级规则

- `versionName` 使用语义化版本：`主版本.次版本.修订版本`。
- `versionCode` 每次发布必须严格递增，供 Android 判断升级顺序。
- 当前基线：`versionName=0.4.0`，`versionCode=4`。
- 小修复：`0.4.1 / versionCode 5`。
- 向后兼容的新功能：`0.5.0 / versionCode 6`。
- 破坏兼容性的改动：升级主版本。

为了保留网页登录态，升级必须保持：

- 相同 `applicationId`：`com.tianlin.aiarena`。
- 相同签名密钥。
- 覆盖安装；禁止卸载、`pm clear`、擦除 AVD 或更换包名。

分发给用户请用 **release 包**（已签名、开启 R8，约 1.4 MB）：

```text
app/build/outputs/apk/release/app-release.apk
```

release 签名从仓库外、且 gitignore 的 `keystore.properties` 读取；该文件不存在时
（例如 CI）构建照常进行，只是产出未签名包。

> **密钥丢失 = 永远无法对已安装版本做保登录升级。** 请把 keystore 与其
> properties 文件异地备份（网盘 / 密码管理器 / 离线介质），且不要提交进 git。

## 隐私与安全边界

- App 不读取、导出或上传 Cookie、Token。
- 无 AI 圆桌账号、无自建服务器、无跨设备同步。
- 网页登录数据由 Android WebView 保存在本机应用沙箱。
- “重新提取”不会重发问题；如果 WebView 已切到另一条对话，需要先打开对应原对话。
- AI 输出可能不准确，尤其是健康、金融和政策信息，仍应查阅权威来源。

## 验证基线

`v0.3.0`：

- JVM 单元测试：64/64 PASS。
- Android instrumentation：34/34 PASS。
- Lint：0 error / 0 warning；Debug APK 构建 PASS。
- 真机压力测试（Android 14 模拟器）：6 次连续旋转、4 次深浅色切换、3 档系统字号变化、
  12 轮页面来回切换、1500 次 Monkey 事件、强杀重启，全部无崩溃无 ANR。
- 5 套皮肤逐一切换截图核对；长辈皮肤 + 大字模式叠加验证通过。
- 20,000 字与 24,050 字提问的两级长度提示分别验证。
- 覆盖安装保留首次安装时间与 Cookie；千问 / 元宝 / 智谱 3/3 仍可用。

`v0.2.0` 已在 Android 36 模拟器上完成：

- JVM：30/30 PASS；Android instrumentation：34/34 PASS。
- 千问原失败会话重新提取：PASS，203 字且未重发。
- 元宝/智谱真实并行、严格串行、独立迭代、观点讨论和总结：PASS。

## 相关项目

- Chrome 扩展版：[TianLin0509/ai-arena-extension](https://github.com/TianLin0509/ai-arena-extension)

## License

[MIT](LICENSE)
