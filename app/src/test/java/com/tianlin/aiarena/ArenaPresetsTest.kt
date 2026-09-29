package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 预设提示词（参考 AI圆桌 Lite）：必须保留材料占位、单遍填充、界面展示与各模式的组装。 */
class ArenaPresetsTest {
    private val answers = linkedMapOf(ArenaService.DEEPSEEK to "深度答案", ArenaService.DOUBAO to "豆包答案", ArenaService.KIMI to "Kimi 答案")

    @Test
    fun everyDefaultTemplateKeepsItsRequiredSlots() {
        PresetKey.entries.forEach { key -> assertNull(key.name, ArenaPresets.validate(key, DefaultPresets.template(key))) }
    }

    @Test
    fun editedTemplatesMustKeepMaterialSlots() {
        assertNotNull(ArenaPresets.validate(PresetKey.DEBATE, "   "))
        assertTrue(ArenaPresets.validate(PresetKey.RELAY, "{问题} 但没有前面回答")!!.contains("{前面回答}"))
        assertTrue(ArenaPresets.validate(PresetKey.SUMMARY, "请总结")!!.contains("{各家回答}"))
        assertNull(ArenaPresets.validate(PresetKey.COLLAB, "取长补短：{队友回答}"))
        assertNotNull(ArenaPresets.validate(PresetKey.DEBATE, "{队友回答}" + "字".repeat(ArenaPresets.MAX_TEMPLATE_CHARS)))
    }

    @Test
    fun fillingIsSinglePassSoAnswersQuotingSlotsAreNotReplacedAgain() {
        val filled = ArenaPresets.fill("A {队友回答} B {问题}", mapOf("队友回答" to "某家写了 {问题}", "问题" to "Q"))
        assertEquals("A 某家写了 {问题} B Q", filled)
    }

    @Test
    fun supplementIsAppendedAfterThePreset() {
        assertEquals("预设", ArenaPresets.withSupplement("预设", "  "))
        assertEquals("预设\n\n用户补充要求：\n只要表格", ArenaPresets.withSupplement("预设", "只要表格"))
    }

    @Test
    fun customTemplateDrivesTheDebatePromptAndExcludesTheTargetsOwnAnswer() {
        val custom = PresetSource { key -> if (key == PresetKey.DEBATE) "逐条反驳：{队友回答}" else DefaultPresets.template(key) }
        val prompt = DebatePromptBuilder.build("问", ArenaService.DEEPSEEK, answers, guidance = "简短", presets = custom)
        assertTrue(prompt.startsWith("逐条反驳：【豆包 的回答】\n豆包答案"))
        assertFalse(prompt.contains("深度答案"))
        assertTrue(prompt.endsWith("用户补充要求：\n简短"))
    }

    @Test
    fun relayPromptCarriesEarlierAnswersInOrderOrJustTheQuestion() {
        assertEquals("问题本身", RelayPromptBuilder.build("问题本身", emptyMap()))
        val prompt = RelayPromptBuilder.build("接力题", linkedMapOf(ArenaService.KIMI to "第一位", ArenaService.DEEPSEEK to "第二位"))
        assertTrue(prompt.startsWith("接力题\n\n前面的成员已按顺序回答"))
        assertTrue(prompt.indexOf("【Kimi 的回答】\n第一位") < prompt.indexOf("【DeepSeek 的回答】\n第二位"))
        val clipped = RelayPromptBuilder.build("题", mapOf(ArenaService.KIMI to "长".repeat(50)), quoteLimit = 10)
        assertTrue(clipped.contains("长".repeat(10)) && !clipped.contains("长".repeat(11)))
    }

    @Test
    fun summaryUsesTheTemplateDepthAndCustomisation() {
        val custom = PresetSource { key -> if (key == PresetKey.SUMMARY) "队长请整理：{各家回答}\n{总结要求}" else DefaultPresets.template(key) }
        val prompt = DiscussionSummaryPromptBuilder.build("问题", emptyList(), answers, depth = SummaryDepth.BRIEF, presets = custom)
        assertTrue(prompt.startsWith("队长请整理：共 3 份完整回答（来自 DeepSeek、豆包、Kimi）。"))
        assertTrue(prompt.contains("请做一份简明总结"))
    }

    @Test
    fun presetViewShowsSlotsAsChipsWithoutRealAnswers() {
        val parts = ArenaPresets.view(PresetKey.DEBATE, DefaultPresets, peers = 3, answers = 4, style = DebateStyle.DEBATE,
            debateIndex = 2, depth = SummaryDepth.STANDARD, members = 4)
        assertEquals(listOf("队友1的回答", "队友2的回答", "队友3的回答"), parts.filter { it.slot && it.text.startsWith("队友") }.map { it.text })
        assertTrue(parts.any { !it.slot && it.text.contains("这是观点讨论第 2 轮。") })
        val relay = ArenaPresets.view(PresetKey.RELAY, DefaultPresets, 1, 3, DebateStyle.DEBATE, 1, SummaryDepth.STANDARD, members = 3)
        assertEquals(listOf("你的问题", "第1位的回答", "第2位的回答"), relay.filter { it.slot }.map { it.text })
        val summary = ArenaPresets.view(PresetKey.SUMMARY, DefaultPresets, 1, 3, DebateStyle.DEBATE, 1, SummaryDepth.DEEP, members = 3)
        assertTrue(summary.any { !it.slot && it.text.contains("请做一份深入总结") })
    }

    @Test
    fun roundHintsDifferByStyleAndStayDefinedBeyondTheThirdRound() {
        assertTrue(ArenaPresets.roundHint(DebateStyle.DEBATE, 1).startsWith("这是观点讨论第 1 轮。\n"))
        assertTrue(ArenaPresets.roundHint(DebateStyle.COLLAB, 2).startsWith("这是第 2 轮协作。\n"))
        assertTrue(ArenaPresets.roundHint(DebateStyle.DEBATE, 9).contains("第 9 轮"))
    }

    @Test
    fun roundLabelsFollowTheLiteNames() {
        fun round(kind: RoundKind, relay: Boolean = false, style: DebateStyle? = null) =
            RoundRecord(1, kind, AnswerMode.PARALLEL, "", emptyMap(), 0, 0, relay = relay, style = style)
        assertEquals("提问", ArenaTimeline.kindLabel(round(RoundKind.INITIAL)))
        assertEquals("工作流", ArenaTimeline.kindLabel(round(RoundKind.INITIAL, relay = true)))
        assertEquals("独立迭代", ArenaTimeline.kindLabel(round(RoundKind.ITERATION)))
        assertEquals("观点讨论", ArenaTimeline.kindLabel(round(RoundKind.DEBATE)))
        assertEquals("观点讨论 · 取长补短", ArenaTimeline.kindLabel(round(RoundKind.DEBATE, style = DebateStyle.COLLAB)))
    }
}
