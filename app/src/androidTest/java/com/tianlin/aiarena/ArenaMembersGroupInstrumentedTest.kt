package com.tianlin.aiarena

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 国外三家是拓展成员：成员选择页默认**不展示**它们，点开「国外 AI（拓展）」才出现。
 * 用不到境外网络的家人不应该在列表里看到一串打不开的名字。
 */
class ArenaMembersGroupInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun overseasMembersAreHiddenUntilTheGroupIsOpened() {
        val toggled = mutableListOf<ArenaService>()
        compose.setContent {
            ArenaTheme {
                RoundtableMembersPage(
                    selectedServices = ArenaService.defaultMembers,
                    loginNeededServices = emptyList(),
                    statuses = emptyMap(),
                    onToggle = { toggled += it },
                    onOpenService = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("DeepSeek").assertIsDisplayed()
        compose.onNodeWithText("智谱").performScrollTo().assertIsDisplayed()
        listOf("Claude", "ChatGPT", "Gemini").forEach { compose.onNodeWithText(it).assertDoesNotExist() }

        compose.onNodeWithText("国外 AI（拓展）").performScrollTo().performClick()

        listOf("Claude", "ChatGPT", "Gemini").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Claude").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(ArenaService.CLAUDE), toggled) }
    }

    @Test
    fun groupStartsOpenWhenAnOverseasMemberIsAlreadySelected() {
        compose.setContent {
            ArenaTheme {
                RoundtableMembersPage(
                    selectedServices = listOf(ArenaService.DEEPSEEK, ArenaService.GEMINI),
                    loginNeededServices = emptyList(),
                    statuses = emptyMap(),
                    onToggle = {},
                    onOpenService = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Gemini").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已选 1 家 · 需境外网络").performScrollTo().assertIsDisplayed()
    }
}
