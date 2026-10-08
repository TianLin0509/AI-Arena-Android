package com.tianlin.aiarena

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.19.0：互相激发的组装与计轮、四个逃生动作背后的规则（跳过、重新发送保留旧回答、排队成员立即发送、换人接手的材料）。
 */
class ArenaEscapeInspireTest {
    private val ds = ArenaService.DEEPSEEK
    private val db = ArenaService.DOUBAO
    private val km = ArenaService.KIMI
    private val qw = ArenaService.QWEN

    private fun done(text: String) = ParticipantRun(phase = ParticipantPhase.COMPLETE, requestId = "r-$text", response = text)

    // 任务书第二节原文，逐字（两端一致）。
    private val specBody = "以下是其他 AI 对同一问题的回答，供你参考和启发。请不要逐条点评或复述，而是把它们当作灵感：" +
        "先找出其中你没想到、能让答案更好的思路、事实或角度，再与你自己的思考碰撞、组合、延伸，争取提出任何一方单独都没想到的新想法；" +
        "你原有回答中仍然站得住的独到之处请保留，不必为了一致而趋同。请直接给出你升级后的完整回答，最后用一两句话说明受到了哪些启发、新增了什么。" +
        "请直接在对话正文中完整写出你的回答。"

    @Test fun inspirePresetMatchesTheSpecWordForWord() {
        assertEquals("{轮次引导}\n\n$specBody\n\n{队友回答}", DefaultPresets.template(PresetKey.INSPIRE))
        assertEquals("这是第1轮互相激发。请先读完其他 AI 的回答，留意哪些地方触动了你、补上了你的盲区。", ArenaPresets.inspireHint(1))
        assertEquals("这是第2轮互相激发。大家已经互相启发过一轮，请寻找还没被挖掘的方向，让答案再上一个台阶。", ArenaPresets.inspireHint(2))
        assertEquals("这是第3轮互相激发。请聚焦仍可突破的地方，避免重复已有内容。", ArenaPresets.inspireHint(3))
        assertEquals("这是第7轮互相激发。请聚焦仍可突破的地方，避免重复已有内容。", ArenaPresets.inspireHint(7))
    }

    @Test fun inspireGivesEachMemberTheOthersFullAnswersButNeverItsOwn() {
        val long = "长".repeat(5_000)
        val answers = linkedMapOf(ds to "DS-$long", db to "DB-答案", km to "KM-答案")
        val prompt = InspirePromptBuilder.build(db, answers, inspireIndex = 2, guidance = "请举例")
        assertTrue(prompt.startsWith(ArenaPresets.inspireHint(2) + "\n\n" + specBody))
        assertTrue("Same format as the discussion", prompt.contains("【DeepSeek 的回答】\nDS-$long"))
        assertTrue(prompt.contains("【Kimi 的回答】\nKM-答案"))
        assertFalse("Its own answer is excluded", prompt.contains("DB-答案"))
        assertTrue("Full answers, not the 2000-char discussion quote", prompt.contains(long))
        assertTrue(prompt.indexOf("DS-") < prompt.indexOf("KM-"))
        assertTrue(prompt.endsWith("用户补充要求：\n请举例"))
    }

    @Test fun inspirePresetKeepsTheTeammateSlotAndShowsItsOwnRoundHint() {
        assertTrue(ArenaPresets.validate(PresetKey.INSPIRE, "只有一句") != null)
        assertEquals(null, ArenaPresets.validate(PresetKey.INSPIRE, "自定义 {队友回答}"))
        val parts = ArenaPresets.view(PresetKey.INSPIRE, DefaultPresets, peers = 2, answers = 3, style = DebateStyle.DEBATE,
            debateIndex = 2, depth = SummaryDepth.STANDARD, members = 3)
        assertTrue(parts.first().text.startsWith("这是第2轮互相激发。"))
        assertEquals(listOf("队友1的回答", "队友2的回答"), parts.filter { it.slot }.map { it.text })
    }

    @Test fun inspireAndDiscussionHaveTheirOwnTimelineLabelsAndColour() {
        val round = RoundRecord(3, RoundKind.INSPIRE, AnswerMode.PARALLEL, "", emptyMap(), 1, 2)
        assertEquals("互相激发", ArenaTimeline.kindLabel(round))
        assertEquals("让 AI 互相激发：原问题", ArenaTimeline.roundQuestion(round, "原问题"))
        assertEquals("激发要求：多给数字", ArenaTimeline.roundQuestion(round.copy(guidance = "多给数字"), "原问题"))
        val entry = ArenaTimeMachine.entries(listOf(round), "原问题", 3, RoundKind.INSPIRE, false, null, 1, emptyMap(), emptyList(), DiscussionSummary(), false).single()
        assertEquals(TimeKind.INSPIRE, entry.kind)
        assertTrue(TimeKind.entries.count { it.hue == TimeKind.INSPIRE.hue } == 1)
        assertEquals(listOf("独立迭代", "工作流", "观点讨论", "互相激发", "队长总结"), RoundMode.entries.map { it.label })
    }

    @Test fun skippedCompletedAnswerIsLeftOutOfLaterDiscussion() {
        val runs = mapOf(ds to done("DS"), db to done("DB").copy(phase = ParticipantPhase.ERROR, skipped = true), km to done("KM"))
        val adopted = RoundMaterials.adopted(runs)
        assertEquals(setOf(ds, km), adopted.keys)
        val prompt = DebatePromptBuilder.build("原问题", ds, adopted)
        assertFalse(prompt.contains("DB"))
        assertTrue(prompt.contains("KM"))
        assertEquals("The skipped member sits the next round out", listOf(ds, km), RoundMaterials.nextParticipants(listOf(ds, db, km), runs, emptyMap()))
    }

    @Test fun resendKeepsTheEarlierAnswerAsPrevious() {
        val first = RoundMaterials.keepPrevious(done("第一次"))
        assertEquals("", first.response)
        assertEquals(listOf("第一次"), first.previousResponses)
        val second = RoundMaterials.keepPrevious(first.copy(response = "第二次", phase = ParticipantPhase.COMPLETE))
        assertEquals(listOf("第一次", "第二次"), second.previousResponses)
        assertEquals("Nothing to keep when there was no answer", ParticipantRun(), RoundMaterials.keepPrevious(ParticipantRun()))
    }

    @Test fun queuedWorkflowMemberIsAssembledFromTheAnswersAvailableNow() {
        val order = listOf(km, ds, db, qw)
        // Kimi answered, DeepSeek still streaming: Doubao sent now only gets Kimi.
        val runs = mapOf(km to done("KM"), ds to ParticipantRun(ParticipantPhase.STREAMING, "r", "半截"), db to ParticipantRun(ParticipantPhase.QUEUED, "r2"))
        val earlier = RoundMaterials.earlierAnswers(order, db, runs)
        assertEquals(listOf(km), earlier.keys.toList())
        val prompt = RelayPromptBuilder.build("接力题", earlier)
        assertTrue(prompt.startsWith("接力题"))
        assertTrue(prompt.contains("【Kimi 的回答】\nKM"))
        assertFalse(prompt.contains("半截"))
        // Nothing before the first member.
        assertTrue(RoundMaterials.earlierAnswers(order, km, runs).isEmpty())
    }

    @Test fun swappedInMemberGetsEveryAnswerAndTheOriginalQuestion() {
        val base = linkedMapOf(ds to "DS答", db to "DB答", km to "KM答")
        // Kimi answered but is swapped out for Qwen from the next round: Kimi's answer is still material.
        val runs = base.mapValues { (_, text) -> done(text) }
        val pending = mapOf(km to qw)
        assertEquals(listOf(ds, db, qw), RoundMaterials.nextParticipants(listOf(ds, db, km), runs, pending))
        assertEquals(listOf(ds, db, qw), RoundMaterials.swappedRoster(listOf(ds, db, km), pending))

        val inspire = RoundMaterials.forNewcomer(InspirePromptBuilder.build(qw, base, 1), "原问题是什么")
        assertTrue(inspire.startsWith("（你是中途加入这场讨论的成员。原始问题：\n原问题是什么）"))
        assertTrue(listOf("DS答", "DB答", "KM答").all { inspire.contains(it) })
        val debate = DebatePromptBuilder.build("原问题是什么", qw, base)
        assertEquals("The discussion preset already carries the question", debate, RoundMaterials.forNewcomer(debate, "原问题是什么"))
        assertTrue(listOf("DS答", "DB答", "KM答").all { debate.contains(it) })
        assertEquals("原问题是什么", RoundMaterials.forNewcomer("原问题是什么", "原问题是什么"))
    }

    @Test fun swapTakesOverNowOnlyWhileTheOldMemberIsStillOwed() {
        listOf(ParticipantPhase.QUEUED, ParticipantPhase.SENDING, ParticipantPhase.WAITING, ParticipantPhase.STREAMING, ParticipantPhase.ERROR).forEach { phase ->
            assertEquals(phase.name, SwapTiming.NOW, MemberActionPolicy.swapTiming(ParticipantRun(phase, "r")))
        }
        assertEquals(SwapTiming.NEXT_ROUND, MemberActionPolicy.swapTiming(done("x")))
        assertEquals(SwapTiming.NEXT_ROUND, MemberActionPolicy.swapTiming(ParticipantRun(ParticipantPhase.ERROR, "r", skipped = true)))
        assertEquals(SwapTiming.NEXT_ROUND, MemberActionPolicy.swapTiming(ParticipantRun(ParticipantPhase.ERROR, "r", stopped = true)))
        assertEquals("No task this round", SwapTiming.NEXT_ROUND, MemberActionPolicy.swapTiming(ParticipantRun()))
        assertTrue(MemberActionPolicy.swapExplanation(ds, ParticipantRun(ParticipantPhase.STREAMING, "r")).contains("马上接手本轮"))
        assertTrue(MemberActionPolicy.swapExplanation(ds, done("x")).contains("从下一轮起"))
        assertTrue(MemberActionPolicy.swapExplanation(ds, ParticipantRun(ParticipantPhase.ERROR, "r", skipped = true)).contains("本轮不会自动发送"))
    }

    @Test fun resendAsksFirstWheneverItCouldDuplicateAQuestion() {
        assertTrue(MemberActionPolicy.resendNeedsConfirm(ParticipantRun(ParticipantPhase.STREAMING, "r")))
        assertTrue(MemberActionPolicy.resendNeedsConfirm(done("x")))
        assertTrue("Uncertain send", MemberActionPolicy.resendNeedsConfirm(ParticipantRun(ParticipantPhase.ERROR, "r",
            detail = "发送后未检测到与本轮正文一致的新消息，请检查原网页；不会自动重复发送")))
        assertFalse("Queued workflow member is sent at once", MemberActionPolicy.resendNeedsConfirm(ParticipantRun(ParticipantPhase.QUEUED, "r")))
        assertFalse("Clearly never sent", MemberActionPolicy.resendNeedsConfirm(ParticipantRun(ParticipantPhase.ERROR, "r", detail = "已停止，这一家还没来得及发送")))
        assertFalse(MemberActionPolicy.canReextract(ParticipantRun(ParticipantPhase.ERROR, "r", detail = "新对话未能就绪，未发送；请打开原网页确认后重试")))
        assertTrue(MemberActionPolicy.canReextract(ParticipantRun(ParticipantPhase.STREAMING, "r")))
        assertTrue(MemberActionPolicy.canReextract(ParticipantRun(ParticipantPhase.ERROR, "r", detail = "发送失败")))
        assertFalse("Only swap without a task", MemberActionPolicy.hasTask(ParticipantRun()))
        assertEquals("已换成Kimi", MemberActionPolicy.statusWord(ParticipantRun(ParticipantPhase.ERROR, "r", skipped = true, replacedBy = km)))
        assertEquals("已跳过", MemberActionPolicy.statusWord(ParticipantRun(ParticipantPhase.ERROR, "r", skipped = true)))
    }

    @Test fun escapeFieldsSurviveTheSessionFile() {
        val runs = ArenaService.entries.associateWith { ParticipantRun() } + mapOf(
            ds to done("新").copy(previousResponses = listOf("旧一", "旧二")),
            db to ParticipantRun(ParticipantPhase.ERROR, "r", skipped = true, replacedBy = qw, stopped = true),
        )
        val snapshot = ArenaSessionSnapshot(id = "s1", originalQuestion = "问", roundNumber = 2, currentRoundKind = RoundKind.INSPIRE,
            currentAnswerMode = AnswerMode.PARALLEL, services = listOf(ds, qw, km), runs = runs,
            history = listOf(RoundRecord(2, RoundKind.INSPIRE, AnswerMode.PARALLEL, "", runs, 1, 2)), summary = DiscussionSummary(),
            updatedAtMillis = 3, pendingSwaps = mapOf(km to ArenaService.YUANBAO), roundNewcomers = setOf(qw))
        val decoded = ArenaSessionJson.decode(JSONObject(ArenaSessionJson.encode(snapshot).toString()))
        assertEquals(RoundKind.INSPIRE, decoded.currentRoundKind)
        assertEquals(RoundKind.INSPIRE, decoded.history.single().kind)
        assertEquals(listOf("旧一", "旧二"), decoded.runs.getValue(ds).previousResponses)
        assertEquals(runs.getValue(db), decoded.runs.getValue(db))
        assertEquals(mapOf(km to ArenaService.YUANBAO), decoded.pendingSwaps)
        assertEquals(setOf(qw), decoded.roundNewcomers)
        // Older files without these fields read as empty.
        val legacy = ArenaSessionJson.encode(snapshot).apply { remove("pendingSwaps"); remove("roundNewcomers") }
        val old = ArenaSessionJson.decode(JSONObject(legacy.toString()))
        assertTrue(old.pendingSwaps.isEmpty() && old.roundNewcomers.isEmpty())
    }

    @Test fun modeLabelsBreakInTheMiddleWhenTheyDoNotFit() {
        assertEquals("独立\n迭代", twoLineLabel("独立迭代"))
        assertEquals("工作\n流", twoLineLabel("工作流"))
        assertEquals("总结", twoLineLabel("总结"))
    }
}
