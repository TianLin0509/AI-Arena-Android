package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionLengthPolicyTest {
    private val withoutQwen = listOf(ArenaService.DEEPSEEK, ArenaService.DOUBAO, ArenaService.KIMI)
    private val withQwen = listOf(ArenaService.DEEPSEEK, ArenaService.QWEN)

    @Test
    fun shortQuestionsProduceNoAdvisory() {
        assertNull(QuestionLengthPolicy.advisory("帮我比较几种家庭旅行方案", withoutQwen))
        assertNull(QuestionLengthPolicy.advisory("", withQwen))
    }

    @Test
    fun advisoryLimitFollowsTightestMemberBudget() {
        assertEquals(
            PromptBudgetPolicy.DEFAULT_BUDGET - ArenaLimits.PROMPT_TEMPLATE_RESERVE,
            QuestionLengthPolicy.advisoryLimit(withoutQwen),
        )
        assertEquals(
            PromptBudgetPolicy.QWEN_BUDGET - ArenaLimits.PROMPT_TEMPLATE_RESERVE,
            QuestionLengthPolicy.advisoryLimit(withQwen),
        )
    }

    @Test
    fun emptyMemberListFallsBackToDefaultBudget() {
        assertEquals(
            PromptBudgetPolicy.DEFAULT_BUDGET - ArenaLimits.PROMPT_TEMPLATE_RESERVE,
            QuestionLengthPolicy.advisoryLimit(emptyList()),
        )
    }

    @Test
    fun questionThatBlocksDebateWarnsBeforeTheUserPressesTheButton() {
        // 这个长度能发出初始回答，但一定塞不进千问的讨论 prompt。
        val question = "问".repeat(PromptBudgetPolicy.QWEN_BUDGET)
        val advisory = QuestionLengthPolicy.advisory(question, withQwen)

        assertNotNull("带千问时 8,000 字的问题必须提前预警", advisory)
        assertTrue(advisory!!.contains("千问"))
        assertTrue(advisory.contains("观点讨论"))
        assertTrue("不是硬上限，不能说成必须缩短到 24,000", !advisory.startsWith("问题超过"))
    }

    @Test
    fun sameQuestionIsFineWhenTheTightestMemberIsNotSelected() {
        val question = "问".repeat(PromptBudgetPolicy.QWEN_BUDGET)
        assertNull(QuestionLengthPolicy.advisory(question, withoutQwen))
    }

    @Test
    fun hardLimitStillReportsTheHardMessage() {
        val question = "问".repeat(ArenaLimits.MAX_QUESTION_CHARS + 1)

        assertTrue(QuestionLengthPolicy.exceedsHardLimit(question))
        val advisory = QuestionLengthPolicy.advisory(question, withoutQwen)
        assertNotNull(advisory)
        assertTrue(advisory!!.contains("${ArenaLimits.MAX_QUESTION_CHARS}"))
    }

    @Test
    fun exactlyAtTheAdvisoryLimitIsStillAccepted() {
        val limit = QuestionLengthPolicy.advisoryLimit(withoutQwen)
        assertNull(QuestionLengthPolicy.advisory("问".repeat(limit), withoutQwen))
        assertNotNull(QuestionLengthPolicy.advisory("问".repeat(limit + 1), withoutQwen))
    }
}
