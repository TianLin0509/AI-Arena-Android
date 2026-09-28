package com.tianlin.aiarena

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 时光机与整场分享：只读回看往轮，不能把当前轮重复列出，也不能丢掉归档的综合答案。 */
class ArenaTimelineTest {
    private fun run(text: String, phase: ParticipantPhase = ParticipantPhase.COMPLETE, detail: String = "") =
        ParticipantRun(phase = phase, requestId = "r-$text", response = text, detail = detail)

    private fun round(number: Int, kind: RoundKind, guidance: String, results: Map<ArenaService, ParticipantRun>,
                      summary: DiscussionSummary? = null) = RoundRecord(
        number = number, kind = kind, answerMode = AnswerMode.PARALLEL, guidance = guidance, results = results,
        startedAtMillis = 1_000L * number, finishedAtMillis = 1_000L * number + 500, summary = summary,
    )

    private val history = listOf(
        round(1, RoundKind.INITIAL, "", mapOf(ArenaService.DEEPSEEK to run("一答"), ArenaService.DOUBAO to run("豆一"))),
        round(2, RoundKind.ITERATION, "追问内容", mapOf(
            ArenaService.DEEPSEEK to run("二答"),
            ArenaService.DOUBAO to run("", ParticipantPhase.ERROR, "发送超时"),
        ), summary = DiscussionSummary(phase = ParticipantPhase.COMPLETE, judge = ArenaService.DEEPSEEK, text = "综合二", roundNumber = 2)),
        round(3, RoundKind.DEBATE, "", mapOf(ArenaService.DEEPSEEK to run("三答"))),
    )

    @Test
    fun pastRoundsExcludeTheCurrentRoundAndKeepOrder() {
        assertEquals(listOf(1, 2), ArenaTimeline.pastRounds(history.reversed(), currentRound = 3).map { it.number })
        assertTrue(ArenaTimeline.pastRounds(history, currentRound = 1).isEmpty())
    }

    @Test
    fun memberTimelineSkipsRoundsItDidNotJoin() {
        assertEquals(listOf(1, 2), ArenaTimeline.pastRoundsFor(ArenaService.DOUBAO, history, currentRound = 4).map { it.number })
        assertEquals(listOf(1, 2, 3), ArenaTimeline.pastRoundsFor(ArenaService.DEEPSEEK, history, currentRound = 4).map { it.number })
        assertTrue(ArenaTimeline.pastRoundsFor(ArenaService.KIMI, history, currentRound = 4).isEmpty())
    }

    @Test
    fun roundQuestionsUseTheUsersOwnWords() {
        assertEquals("原问题", ArenaTimeline.roundQuestion(history[0], "原问题"))
        assertEquals("追问内容", ArenaTimeline.roundQuestion(history[1], "原问题"))
        assertEquals("让 AI 互相讨论：原问题", ArenaTimeline.roundQuestion(history[2], "原问题"))
        assertEquals("讨论要求：只说分歧", ArenaTimeline.roundQuestion(history[2].copy(guidance = "只说分歧"), "原问题"))
    }

    @Test
    fun failedRunsExplainThemselvesInsteadOfShowingNothing() {
        assertEquals("", ArenaTimeline.runNote(run("好")))
        assertTrue(ArenaTimeline.runNote(run("", ParticipantPhase.ERROR, "发送超时")).contains("发送超时"))
        assertEquals("这一轮没有参与", ArenaTimeline.runNote(null))
    }

    @Test
    fun onlyArchivedSummariesAppearInTheSummaryTimeline() {
        assertEquals(listOf(2), ArenaTimeline.pastSummaries(history, currentRound = 4).map { it.number })
    }

    @Test
    fun fullSessionShareListsEveryRoundAnswerAndSummary() {
        val current = DiscussionSummary(phase = ParticipantPhase.COMPLETE, judge = ArenaService.DOUBAO, text = "最新综合", roundNumber = 3)
        val shared = ShareTextPolicy.fullSession("原问题", 0L, history, current)
        assertFalse(shared.truncated)
        val text = shared.text
        listOf("原问题", "一答", "豆一", "二答", "发送超时", "综合二", "三答", "最新综合", "## 第 3 轮 · 互相讨论").forEach {
            assertTrue("missing $it", text.contains(it))
        }
        assertTrue(text.indexOf("一答") < text.indexOf("二答") && text.indexOf("综合二") < text.indexOf("三答"))
        assertTrue(text.indexOf("三答") < text.indexOf("最新综合"))
    }

    @Test
    fun fullSessionShareIsBoundedAndSaysSo() {
        val long = history.map { it.copy(results = it.results.mapValues { (_, r) -> r.copy(response = "长".repeat(30_000)) }) }
        val shared = ShareTextPolicy.fullSession("原问题", 0L, long, DiscussionSummary())
        assertTrue(shared.truncated)
        assertEquals(ShareTextPolicy.MAX_SESSION_SHARE_CHARACTERS, shared.text.length)
        assertTrue(shared.text.endsWith("字]"))
    }

    @Test
    fun archivedSummaryAndItsRoundSurviveTheSessionFile() {
        val snapshot = ArenaSessionSnapshot(
            id = "s1", originalQuestion = "原问题", roundNumber = 3, currentRoundKind = RoundKind.DEBATE,
            currentAnswerMode = AnswerMode.PARALLEL, services = listOf(ArenaService.DEEPSEEK, ArenaService.DOUBAO),
            runs = emptyMap(), history = history,
            summary = DiscussionSummary(phase = ParticipantPhase.COMPLETE, judge = ArenaService.DOUBAO, text = "最新", roundNumber = 3),
            updatedAtMillis = 9L,
        )
        val decoded = ArenaSessionJson.decode(JSONObject(ArenaSessionJson.encode(snapshot).toString()))
        assertEquals("综合二", decoded.history[1].summary?.text)
        assertEquals(2, decoded.history[1].summary?.roundNumber)
        assertNull(decoded.history[0].summary)
        assertEquals(3, decoded.summary.roundNumber)
    }

    @Test
    fun oldSessionFilesWithoutTheNewFieldsStillLoad() {
        val json = ArenaSessionJson.encode(ArenaSessionSnapshot(
            id = "s1", originalQuestion = "原问题", roundNumber = 1, currentRoundKind = RoundKind.INITIAL,
            currentAnswerMode = AnswerMode.PARALLEL, services = listOf(ArenaService.DEEPSEEK, ArenaService.DOUBAO),
            runs = emptyMap(), history = history.take(1), summary = DiscussionSummary(), updatedAtMillis = 1L,
        ))
        json.getJSONArray("history").getJSONObject(0).remove("summary")
        json.getJSONObject("summary").remove("roundNumber")
        val decoded = ArenaSessionJson.decode(JSONObject(json.toString()))
        assertNull(decoded.history.single().summary)
        assertEquals(0, decoded.summary.roundNumber)
    }
}
