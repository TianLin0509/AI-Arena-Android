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
            SimpleComposer("已输入草稿", {}, "继续追问…", "3 家 AI", false, true, { sends++ }, { stops++ })
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

    @Test fun questionPageHasNoAttachmentEntryAndAvatarOpensProvider() {
        var opened: ArenaService? = null
        compose.setContent { ArenaTheme {
            SimpleAskHome("", {}, ArenaService.defaultMembers, 3, {}, {}, { opened = it }, {}, {}, {}, {}, null, false, null, {})
        } }
        compose.onNodeWithTag("choose-attachments").assertDoesNotExist()
        compose.onNodeWithContentDescription("打开 豆包 网页").performClick()
        compose.runOnIdle { assertEquals(ArenaService.DOUBAO, opened) }
        compose.onNodeWithTag("simple-send").assertIsDisplayed()
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
            compose.onNodeWithText("生成综合答案").performScrollTo().assertIsDisplayed()
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
            compose.setContent { ArenaTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                SimpleSummary(controller, ArenaService.defaultMembers, prefs, true, "", {}, {}, null, null, remember { SnackbarHostState() })
            } } }
            compose.onNodeWithText("由 Kimi 整理 · 深入").assertIsDisplayed()
            compose.onNodeWithText("下次由 DeepSeek 整理 · 标准").assertIsDisplayed()
            compose.onNodeWithContentDescription("打开 Kimi 网页").assertIsDisplayed()
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
            compose.setContent { ArenaTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                SimpleSummary(controller, ArenaService.defaultMembers, prefs, true, "", {}, {}, null, null, remember { SnackbarHostState() })
            } } }
            compose.onNodeWithText("DeepSeek 本轮未完成，将由 豆包 整理。").assertIsDisplayed()
            compose.onNodeWithText("生成综合答案").performClick()
            compose.runOnIdle { assertEquals(ArenaService.DOUBAO, sentTo) }
            compose.onNodeWithText("由 豆包 整理 · 标准").assertIsDisplayed()
        } finally { inst.runOnMainSync { controller.destroy(); prefs.saveCaptain(oldCaptain); prefs.saveDepth(oldDepth) } }
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
