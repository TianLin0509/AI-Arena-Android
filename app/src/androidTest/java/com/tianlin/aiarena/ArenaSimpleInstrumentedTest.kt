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
    private class FixtureRepository : ArenaSessionRepository {
        private val members = ArenaService.defaultMembers
        private val runs = members.associateWith { ParticipantRun(phase = ParticipantPhase.COMPLETE, requestId = "fixture-${it.name}",
            response = "**把这 30 分钟留给开口，而不是继续收藏学习资料。**\n\n先围绕日常交流，重复练少量真正会用到的表达。\n\n### 每天只做三件事\n\n- **5 分钟听：**选一段简短的对话，听懂大意。\n- **15 分钟说：**关掉原文，用自己的话复述。\n- **10 分钟用：**记下卡住的三处。\n\n### 怎样知道自己在进步？\n\n每周录一段一分钟的音频，比较停顿和表达完整度。") }
        private var snapshot = ArenaSessionSnapshot("simple-ui", "每天只有 30 分钟，怎么把英语口语练起来？", askedAtMillis = 123L,
            roundNumber = 1, currentRoundKind = RoundKind.INITIAL, currentAnswerMode = AnswerMode.PARALLEL,
            services = members, runs = runs, history = listOf(RoundRecord(1, RoundKind.INITIAL, AnswerMode.PARALLEL, "", runs, 1L, 2L)),
            summary = DiscussionSummary(), updatedAtMillis = 123L)
        override fun newSessionId() = "simple-ui-new"
        override fun save(snapshot: ArenaSessionSnapshot) { this.snapshot = snapshot }
        override fun load(id: String) = snapshot
        override fun loadActive() = snapshot
        override fun setActiveSession(id: String?) = Unit
        override fun listRecent(limit: Int) = emptyList<RecentArenaSession>()
        override fun forget(id: String) = Unit
    }
}
