# AI Arena Android（AI 圆桌）

[![Android CI](https://github.com/TianLin0509/AI-Arena-Android/actions/workflows/android-ci.yml/badge.svg)](https://github.com/TianLin0509/AI-Arena-Android/actions/workflows/android-ci.yml)

当前版本：`v0.2.0`（`versionCode 2`）

面向国内 Android 用户的多 AI 圆桌工具。应用本身无需注册账号、没有自建后端；用户只需在内嵌的 AI 官网完成一次登录，之后由 App 复用本机 WebView 登录状态，汇总多家回答并支持后续讨论。

> 本项目通过真实 AI 网页工作，不是厂商官方 API。网页 DOM 改版后可能需要更新适配器。

## 产品定位

- 默认成员：DeepSeek、豆包、Kimi。
- 可选成员：千问、元宝、智谱。
- 每次可选择 2-4 家 AI。
- 聚焦普通用户，尤其是中老年用户的日常提问、群策群力和幻觉风险提示。
- 登录信息、成员选择和讨论历史只保存在 Android 应用沙箱内。

## 主要功能

- 并行回答：快速送达多家 AI，各家生成过程相互重叠。
- 严格串行：上一家稳定完成或失败后，才发送给下一家。
- 独立迭代：必须输入本轮 Prompt；向上一轮成功成员发送完全相同的用户原文，不附加原问题、旧回答或 App 模板。
- 观点讨论：每家收到其他成员的最新观点，可附加用户本轮讨论要求。
- 讨论总结：提炼结论、共识、分歧、待核验信息和行动建议。
- 交叉核验卡：提示参与成员数、共识、分歧和需要继续核实的内容，不生成虚假置信度。
- 单家补救：重发、重新提取或跳过，不要求整轮重跑。
- 本地历史：最近 20 个会话，支持冷启动恢复和损坏索引自愈。
- 语音输入、大字模式、回答/总结朗读。
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
- 当前基线：`versionName=0.2.0`，`versionCode=2`。
- 小修复：`0.2.1 / versionCode 3`。
- 向后兼容的新功能：`0.3.0 / versionCode 4`。
- 破坏兼容性的改动：升级主版本。

为了保留网页登录态，升级必须保持：

- 相同 `applicationId`：`com.tianlin.aiarena`。
- 相同签名密钥。
- 覆盖安装；禁止卸载、`pm clear`、擦除 AVD 或更换包名。

Debug 构建使用 Android 默认 debug key，只适合开发测试。正式分发前必须建立并安全保存独立 release keystore；丢失正式签名密钥后无法对原安装进行保登录升级。

## 隐私与安全边界

- App 不读取、导出或上传 Cookie、Token。
- 无 AI 圆桌账号、无自建服务器、无跨设备同步。
- 网页登录数据由 Android WebView 保存在本机应用沙箱。
- “重新提取”不会重发问题；如果 WebView 已切到另一条对话，需要先打开对应原对话。
- AI 输出可能不准确，尤其是健康、金融和政策信息，仍应查阅权威来源。

## 验证基线

`v0.2.0` 已在 Android 36 模拟器上完成：

- JVM：30/30 PASS。
- Android instrumentation：34/34 PASS。
- Lint、Debug APK 构建：PASS。
- 千问原失败会话重新提取：PASS，203 字且未重发。
- 元宝/智谱真实并行、严格串行、独立迭代、观点讨论和总结：PASS。
- 语音、大字、朗读、复制分享、历史恢复、成员边界和 18 次页面切换：PASS。

## 相关项目

- Chrome 扩展版：[TianLin0509/ai-arena-extension](https://github.com/TianLin0509/ai-arena-extension)

## License

[MIT](LICENSE)
