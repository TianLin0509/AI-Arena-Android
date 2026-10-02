package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ChatGPT / Gemini 作为境外备选成员接入：身份、默认成员与脚本形状不能退回去。Claude 因封号风险于 2026-10-01 移除。 */
class ArenaForeignServicesTest {
    private val foreign = listOf(ArenaService.CHATGPT, ArenaService.GEMINI)

    @Test
    fun foreignServicesAreOptionalOverseasMembers() {
        foreign.forEach { service ->
            assertTrue("${service.name} 标为境外", service.overseas)
            assertTrue("${service.name} 仍是适配中成员", service.experimental)
            assertFalse("${service.name} 不进默认成员", service in ArenaService.defaultMembers)
            assertTrue(service.url.startsWith("https://"))
        }
        assertEquals(foreign.toSet(), ArenaService.entries.filter { it.overseas }.toSet())
        assertEquals(listOf(ArenaService.DEEPSEEK, ArenaService.DOUBAO, ArenaService.KIMI), ArenaService.defaultMembers)
    }

    @Test
    fun overseasMembersAreASeparateCollapsedGroup() {
        assertEquals(foreign, ArenaMemberGroups.overseas)
        assertEquals(
            listOf(ArenaService.DEEPSEEK, ArenaService.DOUBAO, ArenaService.KIMI, ArenaService.QWEN, ArenaService.YUANBAO, ArenaService.ZHIPU),
            ArenaMemberGroups.domestic,
        )
        assertEquals(ArenaService.entries.size, ArenaMemberGroups.domestic.size + ArenaMemberGroups.overseas.size)
    }

    @Test
    fun overseasGroupStaysCollapsedUntilOneOfItsMembersIsSelected() {
        assertFalse(ArenaMemberGroups.overseasExpandedByDefault(ArenaService.defaultMembers))
        assertFalse(ArenaMemberGroups.overseasExpandedByDefault(emptyList()))
        assertTrue(ArenaMemberGroups.overseasExpandedByDefault(listOf(ArenaService.DEEPSEEK, ArenaService.GEMINI)))
    }

    @Test
    fun overseasSummaryReportsSelectedCountAndNetworkRequirement() {
        assertEquals("需境外网络", ArenaMemberGroups.overseasSummary(ArenaService.defaultMembers))
        assertEquals("已选 2 家 · 需境外网络", ArenaMemberGroups.overseasSummary(listOf(ArenaService.CHATGPT, ArenaService.GEMINI, ArenaService.KIMI)))
    }

    @Test
    fun onlyGeminiAllowsQuestionsWithoutLogin() {
        assertEquals(listOf(ArenaService.GEMINI), ArenaService.entries.filter { it.guestUsable })
    }

    @Test
    fun appendedServicesKeepExistingEnumNamesStable() {
        assertEquals(
            listOf("DEEPSEEK", "DOUBAO", "KIMI", "QWEN", "YUANBAO", "ZHIPU", "CHATGPT", "GEMINI"),
            ArenaService.entries.map { it.name },
        )
        foreign.forEach { assertEquals(it, ArenaService.fromName(it.name)) }
        assertEquals("已移除的 Claude 不再解析成成员", null, ArenaService.fromName("CLAUDE"))
    }

    @Test
    fun savedSessionsThatIncludedClaudeStillOpenWithoutIt() {
        val original = ArenaSessionSnapshot(
            id = "s_claude",
            originalQuestion = "旧会话",
            roundNumber = 1,
            currentRoundKind = RoundKind.INITIAL,
            currentAnswerMode = AnswerMode.PARALLEL,
            services = foreign,
            runs = ArenaService.entries.associateWith { ParticipantRun() },
            history = emptyList(),
            summary = DiscussionSummary(judge = ArenaService.GEMINI),
            conversationUrls = mapOf(ArenaService.CHATGPT to "https://chatgpt.com/c/def"),
            currentRoundCaptain = ArenaService.CHATGPT,
            updatedAtMillis = 1L,
        )
        val json = org.json.JSONObject(ArenaSessionJson.encode(original).toString())
        json.put("services", org.json.JSONArray(listOf("CLAUDE", "CHATGPT", "GEMINI")))
        json.getJSONObject("conversationUrls").put("CLAUDE", "https://claude.ai/chat/abc")
        json.put("currentRoundCaptain", "CLAUDE")

        val decoded = ArenaSessionJson.decode(json)

        assertEquals(foreign, decoded.services)
        assertEquals(mapOf(ArenaService.CHATGPT to "https://chatgpt.com/c/def"), decoded.conversationUrls)
        assertEquals(null, decoded.currentRoundCaptain)
    }

    @Test
    fun foreignMembersAndConversationUrlsSurviveSessionRoundTrip() {
        val urls = mapOf(
            ArenaService.CHATGPT to "https://chatgpt.com/c/def",
            ArenaService.GEMINI to "https://gemini.google.com/app/8fb051286895ae52",
        )
        val original = ArenaSessionSnapshot(
            id = "s_foreign",
            originalQuestion = "境外成员会话",
            roundNumber = 1,
            currentRoundKind = RoundKind.INITIAL,
            currentAnswerMode = AnswerMode.PARALLEL,
            services = foreign,
            runs = ArenaService.entries.associateWith { ParticipantRun() },
            history = emptyList(),
            summary = DiscussionSummary(judge = ArenaService.GEMINI),
            conversationUrls = urls,
            currentRoundCaptain = ArenaService.CHATGPT,
            updatedAtMillis = 1L,
        )

        val decoded = ArenaSessionJson.decode(org.json.JSONObject(ArenaSessionJson.encode(original).toString()))

        assertEquals(foreign, decoded.services)
        assertEquals(urls, decoded.conversationUrls)
        assertEquals(ArenaService.CHATGPT, decoded.currentRoundCaptain)
    }

    @Test
    fun sendButtonsCoverEnglishAndChineseLabels() {
        foreign.forEach { service ->
            val candidates = ArenaWebViewPool.sendButtonSelectors(service)
            assertTrue("${service.name} 英文界面", candidates.any { it.contains("Send") })
            assertTrue("${service.name} 中文界面", candidates.any { it.contains("发送") })
        }
    }

    @Test
    fun responseScriptsAnchorOnTheTaggedUserMessage() {
        foreign.forEach { service ->
            val script = ArenaWebResponseScript.build(service, "req_foreign")
            assertTrue("${service.name} 必须按本轮用户消息限定回答", script.contains("scopeAfterTag(picked.nodes, tagged"))
        }
    }

    @Test
    fun geminiCompletionWaitsForBusyFlagAndActions() {
        val script = ArenaWebResponseScript.build(ArenaService.GEMINI, "req_gemini")

        assertTrue(script.contains("aria-busy=true"))
        assertTrue(script.contains("message-actions"))
    }

    @Test
    fun geminiModeReadsTheCurrentModelFromTheModePicker() {
        val script = ArenaWebModeScript.build(ArenaService.GEMINI)

        assertTrue(script.contains("bard-mode-switcher button"))
        assertTrue(script.contains("currently"))
    }
}
