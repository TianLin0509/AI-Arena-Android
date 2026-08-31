package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebatePromptBuilderTest {
    @Test
    fun `free debate prompt includes other answers but excludes target answer`() {
        val prompt = DebatePromptBuilder.build(
            originalQuestion = "如何安排家庭旅行？",
            target = ArenaService.DEEPSEEK,
            responses = mapOf(
                ArenaService.DEEPSEEK to "deepseek-own-answer",
                ArenaService.DOUBAO to "doubao-answer",
                ArenaService.KIMI to "kimi-answer",
            ),
        )

        assertTrue(prompt.contains("如何安排家庭旅行"))
        assertTrue(prompt.contains("doubao-answer"))
        assertTrue(prompt.contains("kimi-answer"))
        assertFalse(prompt.contains("deepseek-own-answer"))
        assertTrue(prompt.contains("观点讨论第 1 轮"))
        assertTrue(prompt.contains("200 个汉字以内"))
    }

    @Test
    fun `summary labels independent iteration guidance as a new round question`() {
        val prompt = DiscussionSummaryPromptBuilder.build(
            originalQuestion = "第一轮问题",
            history = listOf(
                RoundRecord(
                    number = 2,
                    kind = RoundKind.ITERATION,
                    answerMode = AnswerMode.PARALLEL,
                    guidance = "这是完全独立的新一轮 Prompt",
                    results = emptyMap(),
                    startedAtMillis = 1,
                    finishedAtMillis = 2,
                ),
            ),
            responses = mapOf(
                ArenaService.DEEPSEEK to "观点A",
                ArenaService.DOUBAO to "观点B",
            ),
        )

        assertTrue(prompt.contains("本轮问题：这是完全独立的新一轮 Prompt"))
        assertFalse(prompt.contains("用户补充：这是完全独立的新一轮 Prompt"))
    }

    @Test
    fun `summary prompt contains structure latest viewpoints and custom instruction`() {
        val prompt = DiscussionSummaryPromptBuilder.build(
            originalQuestion = "家庭旅行怎么安排？",
            history = listOf(
                RoundRecord(
                    number = 1,
                    kind = RoundKind.INITIAL,
                    answerMode = AnswerMode.PARALLEL,
                    guidance = "",
                    results = emptyMap(),
                    startedAtMillis = 1,
                    finishedAtMillis = 2,
                ),
            ),
            responses = mapOf(
                ArenaService.DEEPSEEK to "观点A",
                ArenaService.DOUBAO to "观点B",
            ),
            customInstruction = "只给三条建议",
        )

        assertTrue(prompt.contains("一句话结论"))
        assertTrue(prompt.contains("仍有分歧或需要核验"))
        assertTrue(prompt.contains("观点A"))
        assertTrue(prompt.contains("观点B"))
        assertTrue(prompt.contains("只给三条建议"))
    }

    @Test
    fun `question policy accepts limit and rejects over limit`() {
        assertTrue(QuestionPolicy.isValid("问".repeat(ArenaLimits.MAX_QUESTION_CHARS)))
        assertFalse(QuestionPolicy.isValid("问".repeat(ArenaLimits.MAX_QUESTION_CHARS + 1)))
        assertFalse(QuestionPolicy.isValid("   "))
    }

    @Test
    fun `answer modes expose distinct execution contracts`() {
        assertTrue(AnswerMode.PARALLEL.description.contains("相互重叠"))
        assertTrue(AnswerMode.SERIAL.description.contains("回答结束后"))
    }

    @Test
    fun `confirmed login survives chat page navigation and background probe delay`() {
        val navigation = LoginTrustPolicy.duringNavigation(previouslyConfirmed = true)
        val backgroundMiss = LoginTrustPolicy.afterProbe(
            probeSignedIn = false,
            pageVisibleToUser = false,
            previouslyConfirmed = true,
        )

        assertEquals(ConnectionState.SIGNED_IN, navigation.state)
        assertTrue(navigation.confirmedSignedIn)
        assertEquals(ConnectionState.SIGNED_IN, backgroundMiss.state)
        assertTrue(backgroundMiss.confirmedSignedIn)
    }

    @Test
    fun `visible login probe can revoke stale confirmation`() {
        val decision = LoginTrustPolicy.afterProbe(
            probeSignedIn = false,
            pageVisibleToUser = true,
            previouslyConfirmed = true,
        )

        assertEquals(ConnectionState.NEEDS_LOGIN, decision.state)
        assertFalse(decision.confirmedSignedIn)
    }

    @Test
    fun `one hidden explicit login probe does not erase trusted spa session`() {
        val decision = LoginTrustPolicy.afterProbe(
            probeSignedIn = false,
            pageVisibleToUser = false,
            previouslyConfirmed = true,
            explicitLoginVisible = true,
            consecutiveExplicitLoginProbes = 1,
        )

        assertEquals(ConnectionState.SIGNED_IN, decision.state)
        assertTrue(decision.confirmedSignedIn)
    }

    @Test
    fun `two hidden explicit login probes revoke a false early confirmation`() {
        val decision = LoginTrustPolicy.afterProbe(
            probeSignedIn = false,
            pageVisibleToUser = false,
            previouslyConfirmed = true,
            explicitLoginVisible = true,
            consecutiveExplicitLoginProbes = 2,
        )

        assertEquals(ConnectionState.NEEDS_LOGIN, decision.state)
        assertFalse(decision.confirmedSignedIn)
    }

    @Test
    fun `free debate caps each quoted answer`() {
        val longAnswer = "长".repeat(2_500)
        val prompt = DebatePromptBuilder.build(
            originalQuestion = "测试",
            target = ArenaService.KIMI,
            responses = mapOf(
                ArenaService.DEEPSEEK to longAnswer,
                ArenaService.KIMI to "own",
            ),
        )

        assertTrue(prompt.contains("长".repeat(2_000)))
        assertFalse(prompt.contains("长".repeat(2_001)))
    }

    @Test
    fun `only signed in state is usable`() {
        assertTrue(ConnectionState.SIGNED_IN.isUsable())
        assertFalse(ConnectionState.NEEDS_LOGIN.isUsable())
        assertFalse(ConnectionState.LOADING.isUsable())
        assertFalse(ConnectionState.ERROR.isUsable())
    }
}
