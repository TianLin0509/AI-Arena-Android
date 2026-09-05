package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队长模式。用户要的是"只看队长那一条就够"，所以两件事必须成立：
 * 永远有人当队长（除非成员不够），以及队长的 prompt 真的要求它做整合。
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
        // 用户把千问设成队长后又把它移出了成员：不能让队长模式静默失效
        val resolved = CaptainPolicy.resolve(ArenaService.QWEN, members)
        assertEquals(ArenaService.DEEPSEEK, resolved)
    }

    @Test
    fun captainIsStableWhileMembersAreStillLoggingIn() {
        // 2026-09-05 的真实回归：启动时 WebView 逐个加载，"已登录"会从 0 家跳到 1 家再到 3 家。
        // 早先版本拿"已登录"数量判断，只剩一家时队长直接变 null，徽章和排序当场抖一下。
        // 队长是圆桌里的角色，只跟成员列表有关，跟谁先登录完无关。
        assertEquals(ArenaService.DEEPSEEK, CaptainPolicy.resolve(null, members))
        assertEquals(ArenaService.KIMI, CaptainPolicy.resolve(ArenaService.KIMI, members))
    }

    // ---------- forRound：本轮谁真的动手整合 ----------

    @Test
    fun roundCaptainIsTheDesignatedOneWhenItParticipated() {
        assertEquals(
            ArenaService.KIMI,
            CaptainPolicy.forRound(ArenaService.KIMI, listOf(ArenaService.DEEPSEEK, ArenaService.KIMI)),
        )
    }

    @Test
    fun roundCaptainFallsBackWhenDesignatedOneDidNotAnswer() {
        // 队长这一轮失败/被跳过时必须有人顶上，否则用户开了队长模式却没人整合
        assertEquals(
            ArenaService.DOUBAO,
            CaptainPolicy.forRound(ArenaService.KIMI, listOf(ArenaService.DOUBAO, ArenaService.DEEPSEEK)),
        )
    }

    @Test
    fun roundCaptainIsNullWhenCaptainModeOff() {
        assertNull(CaptainPolicy.forRound(null, members))
        assertNull(CaptainPolicy.forRound(ArenaService.KIMI, emptyList()))
    }

    @Test
    fun singleMemberHasNoCaptain() {
        // 只有一家的时候"整合"没有意义
        assertNull(CaptainPolicy.resolve(null, listOf(ArenaService.DEEPSEEK)))
        assertNull(CaptainPolicy.resolve(null, emptyList()))
    }

    // ---------- order / isCaptain ----------

    @Test
    fun captainIsMovedToFront() {
        assertEquals(
            listOf(ArenaService.KIMI, ArenaService.DEEPSEEK, ArenaService.DOUBAO),
            CaptainPolicy.order(members, ArenaService.KIMI),
        )
    }

    @Test
    fun orderIsUntouchedWhenCaptainModeOff() {
        assertEquals(members, CaptainPolicy.order(members, null))
        assertEquals(members, CaptainPolicy.order(members, ArenaService.QWEN))
    }

    @Test
    fun isCaptainOnlyForTheChosenOne() {
        assertTrue(CaptainPolicy.isCaptain(ArenaService.KIMI, ArenaService.KIMI))
        assertFalse(CaptainPolicy.isCaptain(ArenaService.DOUBAO, ArenaService.KIMI))
        assertFalse(CaptainPolicy.isCaptain(ArenaService.KIMI, null))
    }

    @Test
    fun judgePreferencePutsCaptainFirst() {
        val preference = CaptainPolicy.judgePreference(members, ArenaService.DOUBAO)
        assertEquals(ArenaService.DOUBAO, preference.first())
        assertEquals(members.size, preference.size)
    }

    // ---------- prompt ----------

    private fun buildDebate(asCaptain: Boolean) = DebatePromptBuilder.build(
        originalQuestion = "父母血压 145/92 需要吃药吗？",
        target = ArenaService.DEEPSEEK,
        responses = mapOf(
            ArenaService.DOUBAO to "建议先连续测一周再说。",
            ArenaService.KIMI to "已经属于 1 级高血压。",
        ),
        debateIndex = 1,
        asCaptain = asCaptain,
    )

    @Test
    fun captainPromptAsksForIntegrationAndStandaloneReading() {
        val prompt = buildDebate(asCaptain = true)
        assertTrue("没有点明队长身份", prompt.contains("队长"))
        assertTrue("没要求写共识", prompt.contains("共识"))
        assertTrue("没要求写分歧", prompt.contains("分歧"))
        // 用户可能只读这一条，必须能独立读懂
        assertTrue("没要求独立读懂", prompt.contains("独立读懂"))
        assertTrue("没禁止虚构", prompt.contains("不要替别人虚构"))
    }

    @Test
    fun captainGetsMoreRoomThanOrdinaryMember() {
        assertTrue(buildDebate(asCaptain = true).contains("400 个汉字"))
        assertTrue(buildDebate(asCaptain = false).contains("200 个汉字"))
    }

    @Test
    fun ordinaryDebatePromptIsUnchanged() {
        val prompt = buildDebate(asCaptain = false)
        assertTrue(prompt.startsWith("这是观点讨论第 1 轮。"))
        assertTrue(prompt.contains("请逐一讨论这些观点"))
        assertFalse("普通成员不该被称作队长", prompt.contains("队长"))
    }

    @Test
    fun bothVariantsStillCarryQuestionAndTeammateAnswers() {
        listOf(true, false).forEach { asCaptain ->
            val prompt = buildDebate(asCaptain)
            assertTrue(prompt.contains("父母血压 145/92 需要吃药吗？"))
            assertTrue(prompt.contains("豆包"))
            assertTrue(prompt.contains("Kimi"))
            // 自己的回答不该被当成"其他 AI 的回答"塞回去
            assertFalse(prompt.contains("【DeepSeek 的回答】"))
        }
    }

    @Test
    fun collapsedLinesMakeTeammatesShorterThanCaptain() {
        assertTrue(CaptainPolicy.TEAMMATE_COLLAPSED_LINES < CaptainPolicy.DEFAULT_COLLAPSED_LINES)
    }
}
