package com.tianlin.aiarena

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 0.19.0 界面：成员卡片四个逃生动作、换人对话框、之前的回答、五个模式在窄屏大字下不截断。 */
class ArenaEscapeUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fourEscapeActionsAreAlwaysVisibleAndANewcomerOnlyGetsSwap() {
        var run by mutableStateOf(ParticipantRun(phase = ParticipantPhase.STREAMING, requestId = "r", response = "正在写"))
        val calls = mutableListOf<String>()
        compose.setContent { ArenaTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            SimpleAnswer(ArenaService.KIMI, run, ServiceStatus(ConnectionState.SIGNED_IN), {}, null, null, true,
                onReextract = { calls.add("extract") }, onResend = { calls.add("resend") }, onSummary = {},
                onSkip = { calls.add("skip") }, onSwap = { calls.add("swap") })
        } } }
        listOf("reextract", "resend", "skip", "swap").forEach { action ->
            compose.onNodeWithTag("action-$action-KIMI").assertIsDisplayed().assertHasClickAction()
            val height = compose.onNodeWithTag("action-$action-KIMI").fetchSemanticsNode().size.height
            assertTrue("$action is at least 48dp tall", height >= with(compose.density) { 48.dp.roundToPx() })
        }
        compose.onNodeWithTag("action-reextract-KIMI").performClick()
        compose.onNodeWithTag("action-skip-KIMI").performClick()
        compose.onNodeWithTag("action-swap-KIMI").performClick()
        // Resending while it is answering asks first.
        compose.onNodeWithTag("action-resend-KIMI").performClick()
        compose.onNodeWithText("网页里可能出现重复提问", substring = true).assertIsDisplayed()
        compose.onNodeWithText("确认重新发送").performClick()
        compose.runOnIdle { assertEquals(listOf("extract", "skip", "swap", "resend"), calls) }

        // A member with no task this round (just swapped in) only offers 换人.
        compose.runOnIdle { run = ParticipantRun(); calls.clear() }
        compose.onNodeWithTag("action-swap-KIMI").assertIsDisplayed()
        listOf("reextract", "resend", "skip").forEach { compose.onNodeWithTag("action-$it-KIMI").assertDoesNotExist() }

        // A queued workflow member is sent straight away, no duplicate warning.
        compose.runOnIdle { run = ParticipantRun(phase = ParticipantPhase.QUEUED, requestId = "q") }
        compose.onNodeWithTag("action-resend-KIMI").performClick()
        compose.runOnIdle { assertEquals(listOf("resend"), calls) }
    }

    @Test fun earlierAnswersStayReadableAfterAResend() {
        compose.setContent { ArenaTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            SimpleAnswer(ArenaService.DEEPSEEK, ParticipantRun(ParticipantPhase.COMPLETE, "r", "新的回答", previousResponses = listOf("第一次的回答")),
                ServiceStatus(), {}, null, null, false, {}, {}, {})
        } } }
        compose.onNodeWithText("之前的回答（1 份）").assertIsDisplayed()
        compose.onNodeWithText("第一次的回答").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开之前的回答").performScrollTo().performClick()
        compose.onNodeWithText("第一次的回答").performScrollTo().assertIsDisplayed()
    }

    @Test fun skippedMemberSaysSoAndCannotBeSkippedTwice() {
        compose.setContent { ArenaTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            SimpleAnswer(ArenaService.DOUBAO, ParticipantRun(ParticipantPhase.ERROR, "r", "保留的回答", detail = "已跳过本轮", skipped = true),
                ServiceStatus(), {}, null, null, false, {}, {}, {})
        } } }
        compose.onNodeWithTag("member-status-DOUBAO").assertTextEquals("已跳过")
        compose.onNodeWithTag("action-skip-DOUBAO").assertIsNotEnabled()
        compose.onNodeWithText("保留的回答").assertIsDisplayed()
        compose.onNodeWithText("本轮之后的讨论、激发和总结不带它", substring = true).assertIsDisplayed()
    }

    @Test fun swapDialogExplainsWhenTheNewMemberTakesOver() {
        var picked: ArenaService? = null
        compose.setContent { ArenaTheme {
            SwapMemberDialog(ArenaService.KIMI, ParticipantRun(ParticipantPhase.WAITING, "r"),
                listOf(ArenaService.QWEN, ArenaService.YUANBAO, ArenaService.CHATGPT),
                mapOf(ArenaService.QWEN to ServiceStatus(ConnectionState.SIGNED_IN)), pending = null,
                onPick = { picked = it }, onCancelPending = {}, onDismiss = {})
        } }
        compose.onNodeWithTag("swap-explanation").assertTextContains("马上接手本轮", substring = true)
        compose.onNodeWithText("需境外网络", substring = true).assertExists()
        compose.onNodeWithTag("swap-to-QWEN").performClick()
        compose.runOnIdle { assertEquals(ArenaService.QWEN, picked) }
    }

    @Test fun fiveModesFitANarrowScreenAtLargeFontWithoutClipping() {
        var selected by mutableStateOf(RoundMode.INSPIRE.name)
        compose.setContent { ArenaTheme {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 1.3f)) {
                Box(Modifier.width(320.dp)) {
                    ModeSegmented(RoundMode.entries.map { it.name to it.label }, selected, true, { selected = it })
                }
            }
        } }
        RoundMode.entries.forEach { mode ->
            val node = compose.onNodeWithTag("mode-${mode.name}", useUnmergedTree = true).assertIsDisplayed().onChildren().onFirst()
            val layouts = mutableListOf<TextLayoutResult>()
            node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            assertTrue("${mode.label} has a text layout", layouts.isNotEmpty())
            val layout = layouts.first()
            val text = layout.layoutInput.text.text
            val info = "${mode.label}: lines=${layout.lineCount} size=${layout.size} max=${layout.layoutInput.constraints.maxWidth} text=${text.replace('\n', '|')}"
            assertEquals("Every character is shown — $info", text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)
                .let { end -> if (end < text.length && text.substring(end).isBlank()) text.length else end })
            assertTrue("At most two lines — $info", layout.lineCount <= 2)
            assertFalse("No ellipsis — $info", layout.isLineEllipsized(layout.lineCount - 1))
            assertTrue("Fits its cell — $info", layout.size.width <= layout.layoutInput.constraints.maxWidth)
            assertEquals("Same label as the mode — $info", mode.label, text.replace("\n", ""))
        }
        compose.onNodeWithTag("mode-INSPIRE").assertIsSelected()
        compose.onNodeWithTag("mode-DISCUSS").performClick()
        compose.runOnIdle { assertEquals(RoundMode.DISCUSS.name, selected) }
    }
}
