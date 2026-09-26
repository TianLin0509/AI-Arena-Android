package com.tianlin.aiarena

import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.io.File

/** Production MainActivity and ArenaApp; synthetic conversations and no provider submissions. */
class ArenaSimpleAppInstrumentedTest {
    private var previousActive: String? = null
    @get:Rule(order = 0) val fixture = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val store = ArenaSessionStore(context)
            previousActive = store.loadActive()?.id
            val runs = ArenaService.defaultMembers.associateWith { ParticipantRun(phase = ParticipantPhase.COMPLETE,
                requestId = "ui-${it.name}", response = "**${it.shortName} 的建议：每天开口，比只看资料更重要。**\n\n### 每天只做三件事\n\n- 5 分钟听一段短对话。\n- 15 分钟关掉原文，自己复述。\n- 10 分钟模拟日常交流。\n\n### 每周回看一次\n\n录下一分钟表达，对比停顿和表达完整度。") }
            val now = System.currentTimeMillis()
            val snapshot = ArenaSessionSnapshot("session_simple_a_ui", "每天只有 30 分钟，怎么把英语口语练起来？",
                askedAtMillis = now, roundNumber = 1, currentRoundKind = RoundKind.INITIAL,
                currentAnswerMode = AnswerMode.PARALLEL, services = ArenaService.defaultMembers, runs = runs,
                history = listOf(RoundRecord(1, RoundKind.INITIAL, AnswerMode.PARALLEL, "", runs, now, now)),
                summary = DiscussionSummary(), updatedAtMillis = now)
            store.save(snapshot); store.setActiveSession(snapshot.id)
            ArenaGuidePreferences(context).markOnboardingSeen()
            ArenaNavigationPreferences(context).markRoundtableOpened()
        }
        override fun after() {
            ArenaSessionStore(InstrumentationRegistry.getInstrumentation().targetContext).setActiveSession(previousActive)
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun productionNavigationPreservesSelectedAnswerAndDraftAcrossWebpage() {
        compose.onNodeWithTag("simple-answer-DEEPSEEK").assertIsDisplayed()
        compose.onNodeWithTag("choose-attachments").assertDoesNotExist()
        capture("production-answer")
        compose.onNodeWithTag("answer-tab-KIMI").performClick()
        compose.onNodeWithTag("simple-composer").performTextInput("保留这条追问草稿")
        // On compact screens the IME moves the answer avatar outside the lazy viewport.
        // Finish editing as a user would before navigating, without clearing the draft.
        compose.runOnUiThread {
            compose.activity.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("answer-scroll").performScrollToNode(hasContentDescription("打开 Kimi 网页"))
        compose.onNodeWithContentDescription("打开 Kimi 网页").performClick()
        // Header's explicit back bypasses webpage history and must restore the selected tab.
        compose.onNodeWithContentDescription("返回 AI 圆桌主界面").performClick()
        compose.onNodeWithTag("answer-tab-KIMI").assertIsSelected()
        compose.onNodeWithText("保留这条追问草稿").assertExists()
        compose.onNodeWithContentDescription("历史与设置").performClick()
        compose.onNodeWithText("设置", substring = false).performClick()
        compose.onNodeWithText("AI 成员").assertIsDisplayed()
        compose.onNodeWithText("账号与登录").assertDoesNotExist()
        capture("production-settings")
    }

    @Test fun initialQuestionKeepsSelectedMemberWhileItsPageIsLoading() = verifyInitialMembers(setOf(ArenaService.DOUBAO))

    @Test fun coldStartCanQueueAllSelectedMembersBeforeLoginProbeCompletes() = verifyInitialMembers(ArenaService.defaultMembers.toSet())

    private fun verifyInitialMembers(loading: Set<ArenaService>) {
        compose.onNodeWithContentDescription("新提问").performClick()
        compose.runOnUiThread {
            val field = MainActivity::class.java.getDeclaredField("webViewPool").apply { isAccessible = true }
            val pool = field.get(compose.activity) as ArenaWebViewPool
            pool.destroy() // Fail navigation locally; this UI contract must never submit to a website.
            ArenaService.defaultMembers.forEach { service ->
                pool.statuses[service] = ServiceStatus(
                    if (service in loading) ConnectionState.LOADING else ConnectionState.SIGNED_IN,
                    "isolated loading fixture",
                )
            }
        }
        compose.onNodeWithTag("simple-composer").performTextInput("Keep all three selected members")
        compose.onNodeWithTag("simple-send").performClick()
        compose.onNodeWithTag("answer-tab-DEEPSEEK").assertExists()
        compose.onNodeWithTag("answer-tab-DOUBAO").assertExists()
        compose.onNodeWithTag("answer-tab-KIMI").assertExists()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val inst = InstrumentationRegistry.getInstrumentation()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(inst.targetContext.getExternalFilesDir(null), "20260921-simple-a-$name.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
