package com.tianlin.aiarena

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ArenaSimpleInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun iterationBubbleCopyAndShareKeepCurrentQuestionAfterRestore() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val repository = FixtureRepository()
        val pending = mutableListOf<Pair<String, (SendOutcome) -> Unit>>()
        val answer = "本轮独立问题的回答正文"
        val nextQuestion = "这次改问：如何安排一次短途旅行？"
        var copied = ""; var shared = ""
        val gateway = object : ArenaGateway {
            override fun sendPrompt(service: ArenaService, prompt: String, requestId: String, callback: (SendOutcome) -> Unit) {
                pending += requestId to callback
            }
            override fun readResponse(service: ArenaService, requestId: String, callback: (ResponseSnapshot) -> Unit) =
                callback(ResponseSnapshot(found = true, text = answer, streaming = false))
        }
        var controller by mutableStateOf<ArenaSessionController?>(null)
        inst.runOnMainSync {
            controller = ArenaSessionController(gateway, ControllerTiming(pollIntervalMillis = 15, requiredStablePolls = 1), repository)
        }
        val original = controller!!.originalQuestion
        compose.setContent { ArenaTheme {
            var draft by remember { mutableStateOf("") }
            SimpleRoundStage(ArenaService.defaultMembers.associateWith { ServiceStatus(ConnectionState.SIGNED_IN) }, controller!!,
                draft, { draft = it }, {}, {}, {}, remember { SnackbarHostState() },
                { _, text -> copied = text; true }, { _, text -> shared = text; true }, false,
                ArenaCaptainPreferences(LocalContext.current))
        } }
        try {
            compose.onNodeWithTag("simple-composer").performTextInput(nextQuestion)
            compose.onNodeWithTag("simple-send").performClick()
            compose.onNodeWithTag("current-question").assertTextEquals(nextQuestion)
            compose.runOnIdle {
                assertTrue(controller!!.isBusy)
                assertEquals(1, controller!!.history.size)
                pending.toList().forEach { (id, callback) -> callback(SendOutcome(true, id, "fixture receipt")) }
            }
            compose.waitUntil(5_000) { controller!!.completedCount == 3 }
            compose.onNodeWithTag("current-question").assertTextEquals(nextQuestion)
            compose.onNodeWithTag("answer-scroll").performScrollToNode(hasContentDescription("复制 DeepSeek 的回答"))
            compose.onNodeWithContentDescription("复制 DeepSeek 的回答").performClick()
            compose.onNodeWithText("分享", substring = false).performClick()
            compose.runOnIdle {
                listOf(copied, shared).forEach { text ->
                    assertTrue(text, text.contains(nextQuestion) && text.contains(answer))
                    assertFalse(text, text.contains(original))
                }
                controller!!.destroy()
                controller = ArenaSessionController(FixtureGateway(), sessionRepository = repository)
            }
            compose.onNodeWithTag("answer-scroll").performScrollToNode(hasTestTag("current-question"))
            compose.onNodeWithTag("current-question").assertTextEquals(nextQuestion)
            compose.onNodeWithText(answer, substring = false).assertExists()
            compose.onNodeWithTag("answer-tab-summary").performClick()
            compose.onNodeWithTag("current-question").assertTextEquals("讨论主题：$original")
        } finally { inst.runOnMainSync { controller?.destroy() } }
    }

    @Test fun timelineReplaysEarlierRoundAndArchivedSummaryAfterFollowUp() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val repository = FixtureRepository(DiscussionSummary(phase = ParticipantPhase.COMPLETE, judge = ArenaService.DEEPSEEK,
            text = "第一轮综合正文", detail = "总结完成", roundNumber = 1))
        val pending = mutableListOf<Pair<String, (SendOutcome) -> Unit>>()
        val gateway = object : ArenaGateway {
            override fun sendPrompt(service: ArenaService, prompt: String, requestId: String, callback: (SendOutcome) -> Unit) {
                pending += requestId to callback
            }
            override fun readResponse(service: ArenaService, requestId: String, callback: (ResponseSnapshot) -> Unit) =
                callback(ResponseSnapshot(found = true, text = "第二轮回答正文", streaming = false))
        }
        var shared = ""
        lateinit var controller: ArenaSessionController
        inst.runOnMainSync {
            controller = ArenaSessionController(gateway, ControllerTiming(pollIntervalMillis = 15, requiredStablePolls = 1), repository)
        }
        compose.setContent { ArenaTheme {
            var draft by remember { mutableStateOf("") }
            SimpleRoundStage(ArenaService.defaultMembers.associateWith { ServiceStatus(ConnectionState.SIGNED_IN) }, controller,
                draft, { draft = it }, {}, {}, {}, remember { SnackbarHostState() },
                { _, _ -> true }, { _, text -> shared = text; true }, false, ArenaCaptainPreferences(LocalContext.current))
        } }
        try {
            compose.onNodeWithTag("simple-composer").performTextInput("换个角度：只说最关键的一条")
            compose.onNodeWithTag("simple-send").performClick()
            compose.runOnIdle { pending.toList().forEach { (id, callback) -> callback(SendOutcome(true, id, "fixture receipt")) } }
            compose.waitUntil(5_000) { !controller.isBusy && controller.completedCount == 3 }
            compose.onNodeWithTag("current-round-label").assertTextContains("第 2 轮 · 独立迭代", substring = true)
            compose.onNodeWithText("第二轮回答正文").assertExists()

            compose.onNodeWithTag("open-time-machine").performClick()
            compose.onNodeWithTag("time-machine").assertIsDisplayed()
            compose.onNodeWithTag("time-entry-1-round").performClick()
            compose.onNodeWithTag("reviewing-banner").assertIsDisplayed()
            compose.onNodeWithText("把这 30 分钟留给开口", substring = true).assertExists()
            compose.onNodeWithTag("simple-composer").assertDoesNotExist()

            compose.onNodeWithTag("open-time-machine").performClick()
            compose.onNodeWithTag("time-entry-1-summary").performClick()
            compose.onNodeWithText("第一轮综合正文").assertExists()
            compose.onNodeWithTag("back-to-latest").performClick()
            compose.onNodeWithTag("simple-composer").assertExists()

            compose.onNodeWithContentDescription("历史与设置").performClick()
            compose.onNodeWithTag("menu-version").assertTextContains("v${BuildConfig.VERSION_NAME}", substring = true)
            compose.onNodeWithTag("share-session").performClick()
            compose.runOnIdle {
                assertTrue(shared, shared.contains("第一轮综合正文") && shared.contains("第二轮回答正文") &&
                    shared.contains("换个角度：只说最关键的一条") && shared.contains("把这 30 分钟留给开口"))
                assertTrue(shared.indexOf("第一轮综合正文") < shared.indexOf("第二轮回答正文"))
            }
        } finally { inst.runOnMainSync { controller.destroy() } }
    }

    @Test fun fourModesShowTheirPresetAndWorkflowOrder() {
        val inst = InstrumentationRegistry.getInstrumentation()
        lateinit var controller: ArenaSessionController
        inst.runOnMainSync { controller = ArenaSessionController(FixtureGateway(), sessionRepository = FixtureRepository()) }
        compose.setContent { ArenaTheme {
            var draft by remember { mutableStateOf("") }
            SimpleRoundStage(ArenaService.defaultMembers.associateWith { ServiceStatus(ConnectionState.SIGNED_IN) }, controller,
                draft, { draft = it }, {}, {}, {}, remember { SnackbarHostState() }, null, null, false, ArenaCaptainPreferences(LocalContext.current))
        } }
        try {
            compose.onNodeWithTag("preset-card").assertDoesNotExist()
            compose.onNodeWithTag("mode-DISCUSS").performClick()
            compose.onNodeWithText("预设 · 观点讨论 · 互挑错").assertIsDisplayed()
            compose.onNodeWithText("队友1的回答").assertExists()
            compose.onNodeWithTag("choice-COLLAB").performClick()
            compose.onNodeWithText("预设 · 观点讨论 · 取长补短").assertIsDisplayed()
            compose.onNodeWithTag("mode-RELAY").performClick()
            compose.onNodeWithTag("relay-order").assertIsDisplayed()
            compose.onNodeWithContentDescription("把 豆包 提前一位").performClick()
            compose.onNodeWithText("第1位的回答").assertExists()
            compose.onNodeWithTag("mode-SUMMARY").performClick()
            compose.onNodeWithText("预设 · 队长总结").assertIsDisplayed()
            compose.onNodeWithText("只发给队长", substring = true).assertIsDisplayed()
        } finally { inst.runOnMainSync { controller.destroy() } }
    }

    @Test fun presetEditorKeepsRequiredSlotsAndCanRestoreDefault() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val store = ArenaPresetStore(inst.targetContext)
        val before = store.custom(PresetKey.DEBATE)
        var message = ""
        try {
            compose.setContent { ArenaTheme { PresetEditorDialog(PresetKey.DEBATE, store, { message = it }, {}) } }
            compose.onNodeWithTag("preset-editor").performTextClearance()
            compose.onNodeWithTag("preset-editor").performTextInput("只保留一句话，没有占位")
            compose.onNodeWithTag("preset-save").performClick()
            compose.onNodeWithText("必须保留 {队友回答}", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("preset-editor").performTextClearance()
            compose.onNodeWithTag("preset-editor").performTextInput("请逐条反驳：{队友回答}")
            compose.onNodeWithTag("preset-save").performClick()
            compose.runOnIdle {
                assertEquals("请逐条反驳：{队友回答}", store.template(PresetKey.DEBATE))
                assertTrue(message.contains("已保存"))
                assertTrue(DebatePromptBuilder.build("问", ArenaService.DEEPSEEK, mapOf(ArenaService.DOUBAO to "豆包答"), presets = store)
                    .startsWith("请逐条反驳：【豆包 的回答】"))
                assertNull(store.save(PresetKey.DEBATE, null))
                assertEquals(DefaultPresets.template(PresetKey.DEBATE), store.template(PresetKey.DEBATE))
            }
        } finally { inst.runOnMainSync { store.save(PresetKey.DEBATE, before) } }
    }

    @Test fun blockingDraftIsShownVerbatimAndReplacedOnlyAfterConfirmation() {
        var replaced: String? = null
        var sends = 0
        compose.setContent { ArenaTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            SimpleAnswer(ArenaService.KIMI,
                ParticipantRun(phase = ParticipantPhase.ERROR, requestId = "r", detail = "Kimi 新对话输入框里有未发出的草稿（常见于上次被网页拒收的问题），本轮未发送；请打开原网页清除草稿后重试"),
                ServiceStatus(), {}, null, null, false, {}, { sends++ }, {},
                draft = "上次被拒收的问题原文", onReplaceDraft = { replaced = it })
        } } }
        compose.onNodeWithTag("blocking-draft-KIMI").assertTextEquals("上次被拒收的问题原文")
        compose.onNodeWithTag("replace-draft-KIMI").performScrollTo().performClick()
        compose.onNodeWithText("「上次被拒收的问题原文」", substring = true).assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertNull(replaced) }
        compose.onNodeWithTag("replace-draft-KIMI").performScrollTo().performClick()
        compose.onNodeWithText("清空并重发").performClick()
        compose.runOnIdle { assertEquals("上次被拒收的问题原文", replaced); assertEquals(0, sends) }
    }

    @Test fun websiteRejectionIsVisibleWithoutOpeningErrorDetailsAndNeverAutoResends() {
        val cases = listOf(
            ArenaKimiRejection.busyDetail to "官网当前繁忙",
            "本轮消息进入了豆包网页待发送队列，尚未确认送达；请打开原网页核对，勿重复发送" to "待发送队列",
            "本轮消息定位信息已丢失，请打开原网页核对；不会自动重复发送" to "无法确认",
        )
        var detail by mutableStateOf(cases.first().first)
        var opens = 0
        var sends = 0
        compose.setContent { ArenaTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            SimpleAnswer(ArenaService.KIMI,
                ParticipantRun(phase = ParticipantPhase.ERROR, requestId = "current-request", detail = "连续读取失败：$detail"),
                ServiceStatus(), { opens++ }, null, null, false, {}, { sends++ }, {})
        } } }
        cases.forEach { (reason, visibleReason) ->
            compose.runOnIdle { detail = reason }
            compose.onNodeWithText(visibleReason, substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("可能已经回答", substring = true).assertDoesNotExist()
            compose.onNodeWithText("打开网页", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("重发本轮问题").performScrollTo().performClick()
            compose.onNodeWithText("是否已收到或仍在排队", substring = true).assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, sends) }
            compose.onNodeWithText("取消", substring = false).performClick()
        }
        compose.runOnIdle { assertEquals(3, opens); assertEquals(0, sends) }
    }

    @Test fun tabSwitchesOneReadableAnswerAndAvatarDoesNotCollapseText() {
        var selected by mutableStateOf(ArenaService.DEEPSEEK.name)
        val opened = mutableListOf<ArenaService>()
        val members = ArenaService.defaultMembers
        compose.setContent {
            ArenaTheme {
                Column {
                    SimpleAnswerTabs(members, selected, emptyMap()) { selected = it }
                    val service = ArenaService.fromName(selected)!!
                    SimpleAnswer(service, ParticipantRun(phase = ParticipantPhase.COMPLETE, response = "${service.shortName} 正文"),
                        ServiceStatus(), { opened += service }, null, null, false, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("answer-tab-KIMI").performClick()
        compose.onNodeWithTag("simple-answer-KIMI").assertIsDisplayed()
        compose.onNodeWithTag("simple-answer-DEEPSEEK").assertDoesNotExist()
        compose.onNodeWithContentDescription("打开 Kimi 网页").performClick()
        compose.onNodeWithText("Kimi 正文").performClick().assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(ArenaService.KIMI), opened) }
    }

    @Test fun narrowLargeTextKeepsFourthMemberAndSummaryReachable() {
        var selected by mutableStateOf(ArenaService.DEEPSEEK.name)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.75f)) {
                ArenaTheme { Column(Modifier.width(280.dp)) {
                    SimpleAnswerTabs(ArenaService.entries.take(4), selected, emptyMap()) { selected = it }
                } }
            }
        }
        compose.onNodeWithTag("answer-tab-QWEN").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("answer-tab-summary").performScrollTo().performClick().assertIsSelected()
    }

    @Test fun busyComposerStopsAndNeverDispatchesANewPrompt() {
        var sends = 0; var stops = 0
        compose.setContent { ArenaTheme {
            InputBar("已输入草稿", {}, "继续追问…", false, true, { sends++ }, { stops++ }, null, null, null)
        } }
        compose.onNodeWithContentDescription("停止等待").performClick()
        compose.runOnIdle { assertEquals(0, sends); assertEquals(1, stops) }
    }

    @Test fun clippedAnswerStillExplainsHowToReadTheCompleteOriginal() {
        compose.setContent { ArenaTheme {
            Column { SimpleAnswer(ArenaService.DEEPSEEK,
                ParticipantRun(phase = ParticipantPhase.COMPLETE, response = "已保留的部分", responseTruncated = true, originalResponseLength = 18000),
                ServiceStatus(), {}, null, null, false, {}, {}, {}) }
        } }
        compose.onNodeWithText("原回答约 18000 字", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("打开 DeepSeek 网页").assertIsDisplayed()
    }

    @Test fun settingsOnlyShowsTwoFrequentItemsUntilExpanded() {
        compose.setContent { ArenaTheme {
            SimpleSettingsPage(ArenaService.defaultMembers, false, {}, {}, {}, {}, {}, {}, null, {},
                null, false, {}, {}, null, {}, null)
        } }
        compose.onNodeWithText("AI 成员").assertIsDisplayed()
        compose.onNodeWithText("大字阅读").assertIsDisplayed()
        compose.onNodeWithText("账号与登录").assertDoesNotExist()
        compose.onNodeWithText("界面风格").assertDoesNotExist()
        compose.onNodeWithText("更多设置").performClick()
        compose.onNodeWithText("账号与登录").assertIsDisplayed()
        compose.onNodeWithText("重新加载 AI 网页").assertDoesNotExist()
        compose.onNodeWithText("帮助与故障处理").performClick()
        compose.onNodeWithText("重新加载 AI 网页").performScrollTo().assertIsDisplayed()
        capture("settings")
    }

    @Test fun questionPageCanSendAttachmentOnlyAndNewQuestionClearsDraft() {
        var opened: ArenaService? = null
        var sends = 0
        var picks = 0
        val draft = AttachmentDraft(listOf(ArenaAttachment("ui-file", "说明.txt", "text/plain", 12, "a".repeat(64))))
        compose.setContent { ArenaTheme {
            SimpleAskHome("", {}, ArenaService.defaultMembers, 3, {}, {}, { opened = it }, {}, { sends++ }, {}, {}, null, false, null, {},
                attachmentDraft = draft, onChooseAttachments = { picks++ })
        } }
        compose.onNodeWithTag("choose-attachments").assertIsDisplayed().performClick()
        compose.onNodeWithText("说明.txt").assertIsDisplayed()
        compose.onNodeWithTag("simple-send").performClick()
        compose.runOnIdle { assertEquals(1, sends); assertEquals(1, picks) }
        compose.onNodeWithContentDescription("打开 豆包 网页").performClick()
        compose.runOnIdle { assertEquals(ArenaService.DOUBAO, opened) }
        compose.onNodeWithTag("new-session").performClick()
        compose.onNodeWithText("说明.txt").assertDoesNotExist()
        capture("home")
    }

    @Test fun fullRoundKeepsTabAndDraftWhenLeavingForAWebpage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var showWeb by mutableStateOf(false)
        var controller: ArenaSessionController? = null
        val repository = FixtureRepository()
        instrumentation.runOnMainSync { controller = ArenaSessionController(FixtureGateway(), sessionRepository = repository) }
        compose.setContent { ArenaTheme {
            var guidance by remember { mutableStateOf("我的追问草稿") }
            val holder = rememberSaveableStateHolder()
            if (!showWeb) holder.SaveableStateProvider("roundtable") {
                SimpleRoundStage(ArenaService.defaultMembers.associateWith { ServiceStatus(ConnectionState.SIGNED_IN) }, controller!!,
                    guidance, { guidance = it }, {}, {}, { showWeb = true }, remember { SnackbarHostState() }, null, null,
                    false, ArenaCaptainPreferences(LocalContext.current))
            } else androidx.compose.material3.Button(onClick = { showWeb = false }) { androidx.compose.material3.Text("回到圆桌") }
        } }
        try {
            compose.onNodeWithTag("simple-answer-DEEPSEEK").assertIsDisplayed()
            capture("answers")
            compose.onNodeWithTag("answer-tab-KIMI").performClick()
            compose.onNodeWithContentDescription("打开 Kimi 网页").performClick()
            compose.onNodeWithText("回到圆桌").performClick()
            compose.onNodeWithTag("answer-tab-KIMI").assertIsSelected()
            compose.onNodeWithText("我的追问草稿").assertIsDisplayed()
            compose.onNodeWithTag("answer-tab-summary").performClick()
            compose.onNodeWithText("选择队长总结").performScrollTo().assertIsDisplayed()
            capture("summary")
        } finally { instrumentation.runOnMainSync { controller!!.destroy() } }
    }

    @Test fun historicalSummaryUsesItsActualAuthorAndDepthInsteadOfNextPreferences() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val prefs = ArenaCaptainPreferences(inst.targetContext)
        val oldCaptain = prefs.loadCaptain(); val oldDepth = prefs.loadDepth()
        lateinit var controller: ArenaSessionController
        inst.runOnMainSync {
            prefs.saveCaptain(ArenaService.DEEPSEEK); prefs.saveDepth(SummaryDepth.STANDARD)
            controller = ArenaSessionController(FixtureGateway(), sessionRepository = FixtureRepository(
                savedSummary = DiscussionSummary(ParticipantPhase.COMPLETE, ArenaService.KIMI, text = "历史综合正文".repeat(20), depth = SummaryDepth.DEEP)))
        }
        try {
            compose.setContent { ArenaTheme {
                var draft by remember { mutableStateOf("") }
                SimpleRoundStage(ArenaService.defaultMembers.associateWith { ServiceStatus(ConnectionState.SIGNED_IN) }, controller,
                    draft, { draft = it }, {}, {}, {}, remember { SnackbarHostState() }, null, null, false, prefs)
            } }
            compose.onNodeWithTag("answer-tab-summary").performClick()
            compose.onNodeWithText("由 Kimi 整理").assertIsDisplayed()
            compose.onNodeWithText("队长总结 · 深入").assertIsDisplayed()
            compose.onNodeWithTag("mode-SUMMARY").performClick()
            compose.onNodeWithTag("choice-DEEPSEEK").assertIsSelected()
            compose.onNodeWithTag("choice-STANDARD").assertIsSelected()
        } finally { inst.runOnMainSync { controller.destroy(); prefs.saveCaptain(oldCaptain); prefs.saveDepth(oldDepth) } }
    }

    @Test fun failedPreferredCaptainExplainsWhoWillActuallyGenerate() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val prefs = ArenaCaptainPreferences(inst.targetContext)
        val oldCaptain = prefs.loadCaptain(); val oldDepth = prefs.loadDepth()
        var sentTo: ArenaService? = null
        lateinit var controller: ArenaSessionController
        inst.runOnMainSync {
            prefs.saveCaptain(ArenaService.DEEPSEEK); prefs.saveDepth(SummaryDepth.STANDARD)
            val gateway = object : ArenaGateway {
                override fun sendPrompt(service: ArenaService, prompt: String, requestId: String, callback: (SendOutcome) -> Unit) {
                    sentTo = service; callback(SendOutcome(false, requestId, "test does not use website"))
                }
                override fun readResponse(service: ArenaService, requestId: String, callback: (ResponseSnapshot) -> Unit) = Unit
            }
            controller = ArenaSessionController(gateway, sessionRepository = FixtureRepository(failedMember = ArenaService.DEEPSEEK))
        }
        try {
            compose.setContent { ArenaTheme {
                var draft by remember { mutableStateOf("") }
                SimpleRoundStage(ArenaService.defaultMembers.associateWith { ServiceStatus(ConnectionState.SIGNED_IN) }, controller,
                    draft, { draft = it }, {}, {}, {}, remember { SnackbarHostState() }, null, null, false, prefs)
            } }
            compose.onNodeWithTag("mode-SUMMARY").performClick()
            compose.onNodeWithText("DeepSeek 本轮未完成，将由 豆包 整理").assertIsDisplayed()
            compose.onNodeWithTag("simple-send").performClick()
            compose.runOnIdle { assertEquals(ArenaService.DOUBAO, sentTo) }
            compose.onNodeWithText("由 豆包 整理").assertIsDisplayed()
        } finally { inst.runOnMainSync { controller.destroy(); prefs.saveCaptain(oldCaptain); prefs.saveDepth(oldDepth) } }
    }

    @Test fun summaryRetryAfterDraftWasClearedKeepsOriginalFilesAndPrompt() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val file = ArenaAttachment("summary-file", "保留.txt", "text/plain", 4, "a".repeat(64))
        val original = DiscussionSummary(phase = ParticipantPhase.ERROR, judge = ArenaService.DOUBAO,
            prompt = "原始完整总结问题", attachments = listOf(file), detail = "上传失败")
        var sent: List<ArenaAttachment>? = null
        var deliveredPrompt: String? = null
        lateinit var controller: ArenaSessionController
        inst.runOnMainSync {
            controller = ArenaSessionController(object : ArenaGateway {
                override fun sendPrompt(service: ArenaService, prompt: String, requestId: String, callback: (SendOutcome) -> Unit) =
                    fail("Retry must use attachment gateway")
                override fun sendPromptWithAttachments(service: ArenaService, prompt: String, requestId: String,
                    attachments: List<ArenaAttachment>, callback: (SendOutcome) -> Unit) {
                    assertEquals(ArenaService.DOUBAO, service); sent = attachments; deliveredPrompt = prompt
                }
                override fun readResponse(service: ArenaService, requestId: String, callback: (ResponseSnapshot) -> Unit) = Unit
            }, sessionRepository = FixtureRepository(savedSummary = original))
        }
        try {
            compose.setContent { ArenaTheme {
                var draft by remember { mutableStateOf("") }
                SimpleRoundStage(ArenaService.defaultMembers.associateWith { ServiceStatus(ConnectionState.SIGNED_IN) }, controller,
                    draft, { draft = it }, {}, {}, {}, remember { SnackbarHostState() }, null, null, false, ArenaCaptainPreferences(inst.targetContext))
            } }
            compose.onNodeWithTag("answer-tab-summary").performClick()
            compose.onNodeWithText("本轮附件：保留.txt").assertIsDisplayed()
            compose.onNodeWithTag("answer-scroll").performScrollToNode(hasText("按原内容重试"))
            compose.onNodeWithText("按原内容重试", substring = false).performClick()
            compose.onNodeWithText("确认重试", substring = false).performClick()
            compose.runOnIdle { assertEquals(listOf(file), sent); assertEquals(original.prompt, deliveredPrompt) }
        } finally { inst.runOnMainSync { controller.destroy() } }
    }

    @Test fun summarySecurityChallengeKeepsTheActionVisibleWhileWaiting() {
        var opened: ArenaService? = null
        compose.setContent { ArenaTheme { Column {
            SimpleSummaryResult(DiscussionSummary(ParticipantPhase.WAITING, ArenaService.QWEN,
                detail = "千问安全验证处理中；完成后将自动继续提取")) { opened = it }
        } } }
        compose.onNodeWithText("千问安全验证处理中", substring = true).assertIsDisplayed()
        compose.onNodeWithText("打开 千问 网页").performClick()
        compose.runOnIdle { assertEquals(ArenaService.QWEN, opened) }
    }

    @Test fun placeholderSummaryExplainsTheMissingBodyAndLinksToTheActualAuthor() {
        var opened: ArenaService? = null
        compose.setContent { ArenaTheme { Column {
            SimpleSummaryResult(DiscussionSummary(ParticipantPhase.COMPLETE, ArenaService.DOUBAO,
                text = "已生成文档，请查收。")) { opened = it }
        } } }
        compose.onNodeWithText("总结好像没有正文", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("打开 豆包 网页").performClick()
        compose.runOnIdle { assertEquals(ArenaService.DOUBAO, opened) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val inst = InstrumentationRegistry.getInstrumentation()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(inst.targetContext.getExternalFilesDir(null), "20260921-simple-a-$name.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private class FixtureGateway : ArenaGateway {
        override fun sendPrompt(service: ArenaService, prompt: String, requestId: String, callback: (SendOutcome) -> Unit) =
            callback(SendOutcome(false, requestId, "UI fixture must not send"))
        override fun readResponse(service: ArenaService, requestId: String, callback: (ResponseSnapshot) -> Unit) = Unit
    }
    private class FixtureRepository(savedSummary: DiscussionSummary = DiscussionSummary(), failedMember: ArenaService? = null) : ArenaSessionRepository {
        private val members = ArenaService.defaultMembers
        private val runs = members.associateWith { ParticipantRun(phase = ParticipantPhase.COMPLETE, requestId = "fixture-${it.name}",
            response = "**把这 30 分钟留给开口，而不是继续收藏学习资料。**\n\n先围绕日常交流，重复练少量真正会用到的表达。\n\n### 每天只做三件事\n\n- **5 分钟听：**选一段简短的对话，听懂大意。\n- **15 分钟说：**关掉原文，用自己的话复述。\n- **10 分钟用：**记下卡住的三处。\n\n### 怎样知道自己在进步？\n\n每周录一段一分钟的音频，比较停顿和表达完整度。") }
        private val restoredRuns = runs.mapValues { (member, run) -> if (member == failedMember) run.copy(phase = ParticipantPhase.ERROR, response = "") else run }
        private var snapshot = ArenaSessionSnapshot("simple-ui", "每天只有 30 分钟，怎么把英语口语练起来？", askedAtMillis = 123L,
            roundNumber = 1, currentRoundKind = RoundKind.INITIAL, currentAnswerMode = AnswerMode.PARALLEL,
            services = members, runs = restoredRuns, history = listOf(RoundRecord(1, RoundKind.INITIAL, AnswerMode.PARALLEL, "", restoredRuns, 1L, 2L)),
            summary = savedSummary, updatedAtMillis = 123L)
        override fun newSessionId() = "simple-ui-new"
        override fun save(snapshot: ArenaSessionSnapshot) { this.snapshot = snapshot }
        override fun load(id: String) = snapshot
        override fun loadActive() = snapshot
        override fun setActiveSession(id: String?) = Unit
        override fun listRecent(limit: Int) = emptyList<RecentArenaSession>()
        override fun forget(id: String) = Unit
    }
}
