package com.tianlin.aiarena

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class ArenaComposerLayoutInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun discussionComposerKeepsDraftAndSendVisibleInKeyboardSizedViewport() {
        var draft by mutableStateOf("检查正在输入的文字")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                ArenaTheme {
                    Box(Modifier.width(320.dp).height(240.dp).testTag("composer-viewport")) {
                        ModeComposer(
                            mode = RoundMode.DISCUSS, onMode = {}, text = draft, onText = { draft = it },
                            ready = true, busy = false, attachmentDraft = null, onChooseAttachments = null,
                            attachmentNotice = null, scope = "发给本轮成功的 3 家 AI",
                            options = { ChipChoice("讨论方式", DebateStyle.entries.map { it.name to it.displayName },
                                DebateStyle.DEBATE.name, true, {}) },
                            preset = { PresetCard(PresetKey.DEBATE, listOf(
                                PresetPart("这是观点讨论第 2 轮，请逐条讨论下面的完整观点，并保留明确结论。", false),
                                PresetPart("原问题", true), PresetPart("队友1的回答", true), PresetPart("队友2的回答", true)),
                                false, true, {}) },
                            onStop = {}, onSend = {}, onCollapsedChange = {},
                        )
                    }
                }
            }
        }
        try {
            compose.onNodeWithTag("simple-composer").assertIsDisplayed()
            compose.onNodeWithTag("simple-send").assertIsDisplayed()
            val field = compose.onNodeWithTag("simple-composer").fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag("composer-viewport").fetchSemanticsNode().boundsInRoot
            assertTrue("The editable line must remain visible", field.height >= with(compose.density) { 40.dp.toPx() })
            assertTrue("The composer must remain inside the available viewport", field.bottom <= viewport.bottom + 1f)
            compose.onNodeWithTag("simple-composer").assertTextEquals(draft)
        } finally {
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.getExternalFilesDir(null), "20261003-ime-discussion-layout.png")
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun attachmentsAndExpandedPresetDoNotCoverMultilineDraftOrItsSendButton() {
        val original = "第一行补充\n第二行检查错别字\n第三行保留我的观点\n第四行确认发送内容"
        var draft by mutableStateOf(original)
        var sent = ""
        val attachments = AttachmentDraft((1..3).map {
            ArenaAttachment("ime-file-$it", "附件$it.txt", "text/plain", 20L, "a".repeat(64))
        })
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                ArenaTheme { Box(Modifier.width(320.dp).height(220.dp).testTag("composer-viewport")) {
                    Fixture(RoundMode.DISCUSS, draft, { draft = it }, attachments = attachments,
                        notice = "请确认附件支持与本轮成员；完整文字和附件将一起发送。", onSend = { sent = draft })
                } }
            }
        }
        assertEditableRowWithin("composer-viewport")
        compose.onNodeWithTag("edit-preset").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("preset-card").performClick()
        assertEditableRowWithin("composer-viewport")
        compose.onNodeWithTag("simple-composer").performTextInputSelection(TextRange(original.length))
        compose.onNodeWithTag("simple-composer").performTextInput("\n第五行正在输入")
        val expected = original + "\n第五行正在输入"
        compose.onNodeWithTag("simple-composer").assertTextEquals(expected)
        compose.onNodeWithTag("composer-settings").performScrollToNode(hasContentDescription("移除附件 附件2.txt"))
        saveScreen("attachments")
        compose.onNodeWithContentDescription("移除附件 附件2.txt").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("附件1.txt", "附件3.txt"), attachments.attachments.map { it.name }) }
        assertEditableRowWithin("composer-viewport")
        compose.onNodeWithTag("simple-send").performClick()
        compose.runOnIdle { assertEquals(expected, sent) }
        saveScreen("attachments")
    }

    @Test fun changingAvailableHeightPreservesModeDraftAndCollapsedState() {
        var height by mutableStateOf(500.dp)
        var mode by mutableStateOf(RoundMode.DISCUSS)
        var draft by mutableStateOf("保留这份草稿")
        var collapsed by mutableStateOf(false)
        var sends = 0
        compose.setContent { ArenaTheme {
            Box(Modifier.width(320.dp).height(height).testTag("composer-viewport")) {
                Fixture(mode, draft, { draft = it }, onMode = { mode = it }, collapsed = collapsed,
                    onCollapsedChange = { collapsed = it }, onSend = { sends++ })
            }
        } }
        for (next in RoundMode.entries) {
            compose.runOnIdle { height = 210.dp; mode = next }
            assertEditableRowWithin("composer-viewport")
            compose.onNodeWithTag("simple-composer").assertTextEquals("保留这份草稿")
            compose.runOnIdle { height = 500.dp }
            compose.onNodeWithTag("mode-${next.name}").assertIsSelected()
        }
        compose.onNodeWithContentDescription("收起提问区").performScrollTo().performClick()
        compose.onNodeWithTag("simple-composer").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开提问区").performClick()
        compose.onNodeWithTag("simple-composer").assertTextEquals("保留这份草稿")
        compose.runOnIdle { assertEquals(0, sends); assertEquals(RoundMode.SUMMARY, mode) }
    }

    @Test fun shortViewportRetainsStopWhileBusyAndSettingsStayReachable() {
        var stopped = 0
        compose.setContent { ArenaTheme {
            Box(Modifier.width(320.dp).height(180.dp).testTag("composer-viewport")) {
                Fixture(RoundMode.DISCUSS, "本轮进行中", {}, busy = true, onStop = { stopped++ })
            }
        } }
        assertEditableRowWithin("composer-viewport")
        compose.onNodeWithContentDescription("停止等待").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, stopped) }
        compose.onNodeWithTag("edit-preset").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        assertEditableRowWithin("composer-viewport")
    }

    @Test fun realKeyboardKeepsTypedTextAndSendAboveImeAndBackRetainsDraft() {
        val inst = InstrumentationRegistry.getInstrumentation()
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        var imeHeight by mutableIntStateOf(0)
        var draft by mutableStateOf("")
        var sent = ""
        compose.setContent {
            val density = LocalDensity.current
            val bottom = WindowInsets.ime.getBottom(density)
            SideEffect { imeHeight = bottom }
            ArenaTheme {
                Column(Modifier.fillMaxSize().testTag("keyboard-viewport").navigationBarsPadding().imePadding()) {
                    Spacer(Modifier.height(160.dp))
                    Spacer(Modifier.weight(1f))
                    Fixture(RoundMode.DISCUSS, draft, { draft = it }, onSend = { sent = draft })
                }
            }
        }
        compose.onNodeWithTag("simple-composer").performClick()
        compose.waitUntil(15_000) { imeHeight > 0 }
        val typed = "键盘弹出后能看到这句话\n第二行检查输入是否正确"
        compose.onNodeWithTag("simple-composer").performTextInput(typed)
        compose.onNodeWithTag("simple-composer").assertIsFocused().assertTextEquals(typed)
        val viewport = compose.onNodeWithTag("keyboard-viewport").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("simple-composer", "simple-send")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must stay above the real keyboard", bounds.bottom <= viewport.bottom - imeHeight + 1f)
            assertTrue("$tag needs a usable height", bounds.height >= with(compose.density) { 40.dp.toPx() })
        }
        saveScreen("keyboard-open")
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(15_000) { imeHeight == 0 }
        compose.onNodeWithTag("simple-composer").assertTextEquals(typed)
        compose.onNodeWithTag("simple-send").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(typed, sent) }
        saveScreen("keyboard-back")
    }

    private fun assertEditableRowWithin(viewportTag: String) {
        val viewport = compose.onNodeWithTag(viewportTag).fetchSemanticsNode().boundsInRoot
        for (tag in listOf("simple-composer", "simple-send")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag is above the viewport", bounds.top >= viewport.top - 1f)
            assertTrue("$tag is below the viewport", bounds.bottom <= viewport.bottom + 1f)
            assertTrue("$tag needs a usable height", bounds.height >= with(compose.density) { 40.dp.toPx() })
        }
    }

    private fun saveScreen(label: String) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val bitmap = inst.uiAutomation.takeScreenshot()
        File(inst.targetContext.getExternalFilesDir(null), "20261003-ime-$label.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Composable
    private fun Fixture(mode: RoundMode, text: String, onText: (String) -> Unit,
        attachments: AttachmentDraft? = null, notice: String? = null, busy: Boolean = false,
        onMode: (RoundMode) -> Unit = {}, collapsed: Boolean = false, onCollapsedChange: (Boolean) -> Unit = {},
        onSend: () -> Unit = {}, onStop: () -> Unit = {}) {
        ModeComposer(mode, onMode, text, onText, true, busy, attachments, {}, notice, "发给本轮成功的 3 家 AI",
            options = { ChipChoice("讨论方式", DebateStyle.entries.map { it.name to it.displayName }, DebateStyle.DEBATE.name, !busy, {}) },
            preset = { PresetCard(PresetKey.DEBATE, listOf(
                PresetPart("这是观点讨论第 2 轮，请逐条讨论下面的完整观点，并保留明确结论。".repeat(8), false),
                PresetPart("原问题", true), PresetPart("队友1的回答", true)), false, !busy, {}) },
            onStop = onStop, onSend = onSend, collapsed = collapsed, onCollapsedChange = onCollapsedChange)
    }
}
