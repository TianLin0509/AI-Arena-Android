package com.tianlin.aiarena

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话文件的两个可选字段：各家对话地址、每轮的队长。
 * 它们决定"打开历史时各站切回哪条对话"和"队长徽章贴在哪一轮"，写丢或读错都会在界面上误导人。
 */
class ArenaSessionJsonTest {

    private fun snapshot(
        conversationUrls: Map<ArenaService, String> = emptyMap(),
        currentRoundCaptain: ArenaService? = null,
        roundCaptain: ArenaService? = null,
    ) = ArenaSessionSnapshot(
        id = "s1",
        originalQuestion = "西红柿炒鸡蛋先放糖还是先放盐？",
        roundNumber = 2,
        currentRoundKind = RoundKind.DEBATE,
        currentAnswerMode = AnswerMode.PARALLEL,
        services = ArenaService.defaultMembers,
        runs = ArenaService.entries.associateWith { ParticipantRun(phase = ParticipantPhase.COMPLETE, requestId = "r", response = "答") },
        history = listOf(
            RoundRecord(
                number = 2,
                kind = RoundKind.DEBATE,
                answerMode = AnswerMode.PARALLEL,
                guidance = "",
                results = mapOf(ArenaService.DEEPSEEK to ParticipantRun(phase = ParticipantPhase.COMPLETE, requestId = "r", response = "答")),
                startedAtMillis = 1L,
                finishedAtMillis = 2L,
                captain = roundCaptain,
            ),
        ),
        summary = DiscussionSummary(),
        conversationUrls = conversationUrls,
        currentRoundCaptain = currentRoundCaptain,
        updatedAtMillis = 3L,
    )

    @Test
    fun conversationUrlsAndCaptainSurviveRoundTrip() {
        val urls = mapOf(
            ArenaService.DEEPSEEK to "https://chat.deepseek.com/a/chat/s/abc",
            ArenaService.DOUBAO to "https://www.doubao.com/chat/123",
            ArenaService.KIMI to "https://www.kimi.com/chat/xyz",
        )
        val original = snapshot(urls, currentRoundCaptain = ArenaService.DEEPSEEK, roundCaptain = ArenaService.DEEPSEEK)

        val decoded = ArenaSessionJson.decode(JSONObject(ArenaSessionJson.encode(original).toString()))

        assertEquals(urls, decoded.conversationUrls)
        assertEquals(ArenaService.DEEPSEEK, decoded.currentRoundCaptain)
        assertEquals(ArenaService.DEEPSEEK, decoded.history.single().captain)
    }

    @Test
    fun filesWrittenBeforeTheseFieldsExistedStillLoad() {
        // 0.8.0 及更早写出的文件没有这两个字段：地址当空、队长当"当时没开"，不能报错也不能瞎猜
        val json = ArenaSessionJson.encode(snapshot()).apply {
            remove("conversationUrls")
            remove("currentRoundCaptain")
            getJSONArray("history").getJSONObject(0).remove("captain")
        }

        val decoded = ArenaSessionJson.decode(JSONObject(json.toString()))

        assertTrue(decoded.conversationUrls.isEmpty())
        assertNull(decoded.currentRoundCaptain)
        assertNull(decoded.history.single().captain)
    }

    @Test
    fun captainOffIsStoredAsNullNotAsAnyMember() {
        // 队长模式关着跑的一轮，恢复后也必须是"没有队长"，界面才不会把徽章贴到第一位成员头上
        val decoded = ArenaSessionJson.decode(JSONObject(ArenaSessionJson.encode(snapshot()).toString()))

        assertNull(decoded.currentRoundCaptain)
        assertNull(decoded.history.single().captain)
    }

    @Test
    fun onlyHttpsConversationUrlsAreKept() {
        val decoded = ArenaSessionJson.decode(
            JSONObject(
                ArenaSessionJson.encode(
                    snapshot(
                        mapOf(
                            ArenaService.DEEPSEEK to "javascript:alert(1)",
                            ArenaService.KIMI to "https://www.kimi.com/chat/ok",
                        ),
                    ),
                ).toString(),
            ),
        )

        assertEquals(mapOf(ArenaService.KIMI to "https://www.kimi.com/chat/ok"), decoded.conversationUrls)
    }
}
