package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队长总结（0.11）：初始回答人人平等，谁都能当队长；总结独立成一步，喂完整回答、按深度换 prompt。
 * 家人反馈旧总结"比较浅"，所以这里盯三件事：默认有人当队长、完整回答真的进了 prompt、三档深度各说各话。
 */
class ArenaCaptainTest {

    private val members = listOf(ArenaService.DEEPSEEK, ArenaService.DOUBAO, ArenaService.KIMI)

    // ---------- resolve ----------

    @Test
    fun storedCaptainIsRespected() {
        assertEquals(ArenaService.KIMI, CaptainPolicy.resolve(ArenaService.KIMI, members))
    }

    @Test
    fun noStoredCaptainFallsBackToFirstMember() {
        assertEquals(ArenaService.DEEPSEEK, CaptainPolicy.resolve(null, members))
    }

    @Test
    fun captainRemovedFromMembersFallsBackInsteadOfDisabling() {
        // 用户把千问设成队长后又把它移出了成员：不能让总结没人做
        assertEquals(ArenaService.DEEPSEEK, CaptainPolicy.resolve(ArenaService.QWEN, members))
    }

    @Test
    fun singleMemberHasNoCaptain() {
        // 只有一家的时候"整合"没有意义
        assertNull(CaptainPolicy.resolve(null, listOf(ArenaService.DEEPSEEK)))
        assertNull(CaptainPolicy.resolve(null, emptyList()))
    }

    // ---------- isCaptain / judgePreference ----------

    @Test
    fun isCaptainOnlyForTheChosenOne() {
        assertTrue(CaptainPolicy.isCaptain(ArenaService.KIMI, ArenaService.KIMI))
        assertFalse(CaptainPolicy.isCaptain(ArenaService.DOUBAO, ArenaService.KIMI))
        assertFalse(CaptainPolicy.isCaptain(ArenaService.KIMI, null))
    }

    @Test
    fun judgePreferencePutsCaptainFirstAndKeepsEveryoneElseInOrder() {
        val preference = CaptainPolicy.judgePreference(members, ArenaService.DOUBAO)
        assertEquals(listOf(ArenaService.DOUBAO, ArenaService.DEEPSEEK, ArenaService.KIMI), preference)
    }

    @Test
    fun judgePreferenceIsUntouchedWithoutCaptain() {
        assertEquals(members, CaptainPolicy.judgePreference(members, null))
        assertEquals(members, CaptainPolicy.judgePreference(members, ArenaService.QWEN))
    }

    // ---------- 队长总结的 prompt ----------

    private val longAnswer = buildString {
        repeat(60) { append("第 $it 点：老年人每天饮水 1500 到 1700 毫升，少量多次，不要等口渴才喝。") }
    }

    private fun buildSummary(depth: SummaryDepth, quoteLimit: Int = ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS) =
        DiscussionSummaryPromptBuilder.build(
            originalQuestion = "老年人每天喝多少水合适？",
            history = listOf(
                RoundRecord(
                    number = 1,
                    kind = RoundKind.INITIAL,
                    answerMode = AnswerMode.PARALLEL,
                    guidance = "",
                    results = emptyMap(),
                    startedAtMillis = 0,
                    finishedAtMillis = 1,
                ),
            ),
            responses = mapOf(
                ArenaService.DEEPSEEK to longAnswer,
                ArenaService.DOUBAO to "1500 毫升左右，心肾有病的要问医生。",
                ArenaService.KIMI to "2000 毫升。",
            ),
            quoteLimit = quoteLimit,
            depth = depth,
        )

    @Test
    fun summaryPromptNamesTheCaptainAndFeedsFullAnswers() {
        val prompt = buildSummary(SummaryDepth.STANDARD)
        assertTrue("没有点明队长身份", prompt.lines().first().contains("队长"))
        assertTrue("没说明是 3 份完整回答", prompt.contains("3 份完整回答"))
        // 旧版只引用 2000 字片段，是"总结浅"的一半原因；现在按控制器给的上限喂完整回答
        assertTrue("长回答被截断了", prompt.contains(longAnswer))
        assertTrue(prompt.contains("【DeepSeek 的完整回答】"))
        assertTrue(prompt.contains("老年人每天喝多少水合适？"))
    }

    @Test
    fun eachDepthHasItsOwnStructureAndLength() {
        val brief = buildSummary(SummaryDepth.BRIEF)
        val standard = buildSummary(SummaryDepth.STANDARD)
        val deep = buildSummary(SummaryDepth.DEEP)

        assertTrue(brief.contains("简明总结") && brief.contains("不超过 200 字") && brief.contains("一句话结论"))
        assertTrue(standard.contains("标准总结") && standard.contains("不超过 500 字"))
        assertTrue("标准档要写共识", standard.contains("共识"))
        assertTrue("标准档要写分歧", standard.contains("分歧"))
        assertTrue(deep.contains("深入总结") && deep.contains("不超过 1200 字"))
        assertTrue("深入档要逐条核对事实", deep.contains("事实核对"))
        assertTrue("深入档要给分步做法", deep.contains("分步骤"))
        // 简明档不要求共识 / 分歧两段，别把标准档的结构漏进去
        assertFalse(brief.contains("事实核对"))
    }

    @Test
    fun everyDepthKeepsThePlainLanguageGuardrails() {
        SummaryDepth.entries.forEach { depth ->
            val prompt = buildSummary(depth)
            assertTrue("$depth 没要求白话", prompt.contains("白话"))
            assertTrue("$depth 没禁止照抄", prompt.contains("不要照抄"))
            assertTrue("$depth 没禁止编造", prompt.contains("不确定"))
        }
    }

    @Test
    fun quoteLimitStillCompressesWhenBudgetRequires() {
        // 上下文超预算时控制器会逐步缩小引用；builder 必须听 quoteLimit 的
        val prompt = buildSummary(SummaryDepth.DEEP, quoteLimit = 300)
        assertFalse(prompt.contains(longAnswer))
        assertTrue(prompt.contains(longAnswer.take(300)))
    }

    @Test
    fun depthDefaultsToStandardAndSurvivesUnknownNames() {
        assertEquals(SummaryDepth.STANDARD, SummaryDepth.fromName(null))
        assertEquals(SummaryDepth.STANDARD, SummaryDepth.fromName("nonsense"))
        assertEquals(SummaryDepth.DEEP, SummaryDepth.fromName("DEEP"))
        assertTrue(SummaryDepth.BRIEF.maxChars < SummaryDepth.STANDARD.maxChars)
        assertTrue(SummaryDepth.STANDARD.maxChars < SummaryDepth.DEEP.maxChars)
    }

    // ---------- 观点讨论：各家平等 ----------

    @Test
    fun debatePromptTreatsEveryoneEqually() {
        val prompt = DebatePromptBuilder.build(
            originalQuestion = "父母血压 145/92 需要吃药吗？",
            target = ArenaService.DEEPSEEK,
            responses = mapOf(
                ArenaService.DOUBAO to "建议先连续测一周再说。",
                ArenaService.KIMI to "已经属于 1 级高血压。",
            ),
            debateIndex = 1,
        )
        assertTrue(prompt.startsWith("这是观点讨论第 1 轮。"))
        assertTrue(prompt.contains("请逐一讨论这些观点"))
        assertFalse("讨论轮不再有队长版", prompt.contains("队长"))
        assertTrue(prompt.contains("豆包"))
        assertTrue(prompt.contains("Kimi"))
        // 自己的回答不该被当成"其他 AI 的回答"塞回去
        assertFalse(prompt.contains("【DeepSeek 的回答】"))
    }
}
