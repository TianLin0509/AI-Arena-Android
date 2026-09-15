package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Claude / ChatGPT / Gemini 作为境外备选成员接入：身份、默认成员与脚本形状不能退回去。 */
class ArenaForeignServicesTest {
    private val foreign = listOf(ArenaService.CLAUDE, ArenaService.CHATGPT, ArenaService.GEMINI)

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
    fun onlyGeminiAllowsQuestionsWithoutLogin() {
        assertEquals(listOf(ArenaService.GEMINI), ArenaService.entries.filter { it.guestUsable })
    }

    @Test
    fun appendedServicesKeepExistingEnumNamesStable() {
        assertEquals(
            listOf("DEEPSEEK", "DOUBAO", "KIMI", "QWEN", "YUANBAO", "ZHIPU", "CLAUDE", "CHATGPT", "GEMINI"),
            ArenaService.entries.map { it.name },
        )
        foreign.forEach { assertEquals(it, ArenaService.fromName(it.name)) }
    }

    @Test
    fun foreignMembersAndConversationUrlsSurviveSessionRoundTrip() {
        val urls = mapOf(
            ArenaService.CLAUDE to "https://claude.ai/chat/abc",
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
            currentRoundCaptain = ArenaService.CLAUDE,
            updatedAtMillis = 1L,
        )

        val decoded = ArenaSessionJson.decode(org.json.JSONObject(ArenaSessionJson.encode(original).toString()))

        assertEquals(foreign, decoded.services)
        assertEquals(urls, decoded.conversationUrls)
        assertEquals(ArenaService.CLAUDE, decoded.currentRoundCaptain)
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
