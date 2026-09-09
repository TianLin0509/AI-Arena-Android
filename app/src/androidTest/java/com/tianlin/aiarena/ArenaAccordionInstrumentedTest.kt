package com.tianlin.aiarena

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
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

/** Real Compose touch/layout regressions; fixtures never create a WebView or send a prompt. */
class ArenaAccordionInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val opened = mutableListOf<ArenaService>()
    private var copies = 0
    private var shares = 0
    private var retries = 0

    @Test fun idleNewSessionOpensImmediatelyWithoutConfirmation() {
        var newSessions = 0
        compose.setContent {
            ArenaTheme { RoundHeader("观点讨论", "本轮已完成", false) { newSessions++ } }
        }
        compose.onNodeWithTag("new-session").assertIsDisplayed().performClick()
        compose.onNodeWithText("开始新会话？").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, newSessions) }
    }

    @Test fun busyNewSessionRequiresConfirmationAndCancelKeepsCurrentRound() {
        var newSessions = 0
        compose.setContent {
            ArenaTheme { RoundHeader("观点讨论", "正在等待回答", true) { newSessions++ } }
        }
        compose.onNodeWithTag("new-session").performClick()
        compose.onNodeWithText("将停止等待本轮回答，已收到的内容会保留在历史中。").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, newSessions) }
        compose.onNodeWithTag("new-session").performClick()
        compose.onNodeWithText("开始新会话").performClick()
        compose.runOnIdle { assertEquals(1, newSessions) }
        compose.onNodeWithText("开始新会话？").assertDoesNotExist()
    }

    @Test fun narrowElderHeaderKeepsNewSessionAndTitleVisible() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                ArenaTheme(ArenaSkin.ELDER) {
                    Column(Modifier.width(280.dp)) {
                        RoundHeader("观点讨论", "三家已经完成回答", false) {}
                    }
                }
            }
        }
        listOf("新会话", "观点讨论").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Clipped header: $label", layouts.single().hasVisualOverflow)
        }
        val title = compose.onNodeWithText("观点讨论").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("new-session").fetchSemanticsNode().boundsInRoot
        assertTrue("New session must stay to the right of the title", action.left >= title.right)
    }

    private fun showMembers(count: Int, error: Boolean = false, longAnswers: Boolean = false) {
        compose.setContent {
            ArenaTheme {
                val expanded = remember { mutableStateMapOf<String, Boolean>() }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    ArenaService.entries.take(count).forEach { service ->
                        ProviderResultCard(
                            service = service,
                            status = ServiceStatus(ConnectionState.SIGNED_IN),
                            run = ParticipantRun(
                                phase = if (error) ParticipantPhase.ERROR else ParticipantPhase.COMPLETE,
                                requestId = "fixture-${service.name}",
                                response = if (error) "" else "${service.shortName} 的完整回答\n\n" +
                                    "**具体建议**：每天留一段休息时间。\n\n".repeat(if (longAnswers) 80 else 1),
                                detail = if (error) "等待回答超时" else "回答完成",
                            ),
                            expanded = expanded[service.name] == true,
                            onExpandedChange = { expanded[service.name] = it },
                            onClick = { opened += service },
                            onCopy = { copies++ }, onShare = { shares++ },
                            recoveryEnabled = true, canReextract = true,
                            onRetrySend = { retries++ }, onRetryExtraction = { retries++ }, onSkip = {},
                        )
                    }
                }
            }
        }
    }

    @Test fun multipleAnswersExpandIndependentlyAndBothCollapseTargetsWork() {
        showMembers(3)
        compose.onNodeWithTag("answer-body-DEEPSEEK").assertDoesNotExist()
        compose.onNodeWithTag("answer-row-DEEPSEEK").performClick()
        compose.onNodeWithTag("answer-row-DOUBAO").performScrollTo().performClick()
        compose.onNodeWithTag("answer-body-DEEPSEEK").assertExists()
        compose.onNodeWithTag("answer-body-DOUBAO").assertExists()
        compose.onNodeWithTag("answer-body-DEEPSEEK").performScrollTo().performClick()
        compose.onNodeWithTag("answer-body-DEEPSEEK").assertDoesNotExist()
        compose.onNodeWithTag("answer-body-DOUBAO").assertExists()
        compose.onNodeWithTag("answer-row-DOUBAO").performScrollTo().performClick()
        compose.onNodeWithTag("answer-body-DOUBAO").assertDoesNotExist()
    }

    @Test fun copyShareAndOriginalPageDoNotCollapseTheAnswer() {
        showMembers(2)
        compose.onNodeWithTag("answer-row-DEEPSEEK").performClick()
        listOf("复制 DeepSeek 的回答", "分享 DeepSeek 的回答", "跳转到 DeepSeek 网页").forEach {
            compose.onNodeWithContentDescription(it).performScrollTo().performClick()
            compose.onNodeWithTag("answer-body-DEEPSEEK").assertExists()
        }
        compose.runOnIdle {
            assertEquals(1, copies)
            assertEquals(1, shares)
            assertEquals(listOf(ArenaService.DEEPSEEK), opened)
        }
    }

    @Test fun foldingLongScrolledAnswerReturnsToItsStatusRow() {
        showMembers(4, longAnswers = true)
        compose.onNodeWithTag("answer-row-DOUBAO").performScrollTo().performClick()
        repeat(3) { compose.onRoot().performTouchInput { swipeUp() } }
        compose.onNodeWithTag("answer-body-DOUBAO").performClick()
        compose.onNodeWithTag("answer-body-DOUBAO").assertDoesNotExist()
        compose.onNodeWithTag("answer-row-DOUBAO").assertIsDisplayed()
    }

    @Test fun fourthMemberIsReachableAndExpandable() {
        showMembers(4)
        compose.onNodeWithTag("answer-row-QWEN").performScrollTo().performClick()
        compose.onNodeWithTag("answer-body-QWEN").assertExists()
        compose.onNodeWithTag("answer-row-QWEN").performScrollTo().performClick()
        compose.onNodeWithTag("answer-body-QWEN").assertDoesNotExist()
    }

    @Test fun failedEmptyAnswerStillExposesRecoveryAndOriginalPage() {
        showMembers(2, error = true)
        compose.onNodeWithTag("answer-row-DEEPSEEK").performClick()
        compose.onNodeWithText("怎么办？").assertExists()
        compose.onAllNodesWithContentDescription("跳转到 DeepSeek 网页")[0].performScrollTo().performClick()
        compose.onNodeWithText("重新提取").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, opened.size) }
        compose.onNodeWithText("怎么办？").assertExists()
    }

    @Test fun compactComposerPreservesActionsAndCapsInputHeightAtTwoLines() {
        var iterations = 0
        var debates = 0
        var summaryOpen = false
        compose.setContent {
            ArenaTheme {
                var value by remember { mutableStateOf("") }
                var expanded by remember { mutableStateOf(false) }
                Column(Modifier.width(340.dp)) {
                    NextRoundPanel(value, { value = it }, true, true, expanded,
                        { expanded = !expanded; summaryOpen = expanded }, { iterations++ }, { debates++ })
                }
            }
        }
        compose.onNodeWithText("补充你的要求，或直接点击下方按钮。").assertExists()
        compose.onNodeWithTag("round-iterate").assertIsNotEnabled()
        compose.onNodeWithTag("round-debate").performClick()
        compose.onNodeWithTag("summary-toggle").performClick()
        compose.runOnIdle { assertEquals(1, debates); assertTrue(summaryOpen) }
        compose.onNodeWithTag("summary-toggle").performClick()
        compose.runOnIdle { assertFalse(summaryOpen) }
        compose.onNodeWithTag("round-guidance").performTextInput("第一行\n第二行")
        val twoLines = compose.onNodeWithTag("round-guidance").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("round-guidance").performTextInput("\n第三行\n第四行")
        val fourLines = compose.onNodeWithTag("round-guidance").fetchSemanticsNode().boundsInRoot.height
        assertEquals(twoLines, fourLines, 1f)
        compose.onNodeWithTag("round-iterate").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, iterations) }
    }

    @Test fun narrowElderLayoutKeepsThreeActionLabelsAndPlaceholderUnclipped() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                ArenaTheme(ArenaSkin.ELDER) {
                    Column(Modifier.width(300.dp)) {
                        NextRoundPanel("", {}, false, false, false, {}, {}, {})
                    }
                }
            }
        }
        listOf("队长总结", "观点讨论", "独立迭代", "补充你的要求，或直接点击下方按钮。").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("No text layout for $label", layouts.isNotEmpty())
            assertFalse("Clipped text: $label", layouts.single().hasVisualOverflow)
            android.util.Log.i("AccordionLayout", "$label: ${layouts.single().layoutInput.style.fontSize}, lines=${layouts.single().lineCount}")
        }
        compose.onNodeWithTag("round-iterate").assertIsNotEnabled()
        compose.onNodeWithTag("round-debate").assertIsNotEnabled()
        compose.onNodeWithTag("summary-toggle").assertIsEnabled()
    }
}
