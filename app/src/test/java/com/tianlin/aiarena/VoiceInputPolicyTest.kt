package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputPolicyTest {
    @Test
    fun blankQuestionUsesTrimmedTranscript() {
        val result = VoiceInputPolicy.merge("", "  请帮我制定旅行计划  ")

        assertEquals("请帮我制定旅行计划", result.text)
        assertEquals(9, result.addedCharacters)
        assertFalse(result.truncated)
    }

    @Test
    fun transcriptAppendsToExistingQuestionOnNewLine() {
        val result = VoiceInputPolicy.merge("已有问题", "再补充预算")

        assertEquals("已有问题\n再补充预算", result.text)
        assertFalse(result.truncated)
    }

    @Test
    fun blankTranscriptDoesNotChangeQuestion() {
        val result = VoiceInputPolicy.merge("已有问题", "   ")

        assertEquals("已有问题", result.text)
        assertEquals(0, result.addedCharacters)
    }

    @Test
    fun transcriptIsBoundedByQuestionLimit() {
        val result = VoiceInputPolicy.merge("12345678", "abcdef", maxCharacters = 12)

        assertEquals("12345678\nabc", result.text)
        assertEquals(3, result.addedCharacters)
        assertTrue(result.truncated)
    }

    @Test
    fun voiceStateRejectsDuplicateRequestAndPublishesOneEvent() {
        val state = VoiceInputState()

        assertTrue(state.begin())
        assertFalse(state.begin())
        assertTrue(state.active)
        state.finish(VoiceInputOutcome.Success("测试语音"))

        assertFalse(state.active)
        assertEquals("测试语音", (state.event?.outcome as VoiceInputOutcome.Success).transcript)
        val eventId = state.event!!.id
        assertEquals("测试语音", (state.take(eventId) as VoiceInputOutcome.Success).transcript)
        assertEquals(null, state.take(eventId))
        assertEquals(null, state.event)
    }

    @Test
    fun largeTextScaleRespectsSystemScaleAndUpperBound() {
        assertEquals(1.0f, TextScalePolicy.composeFontScale(1.0f, false))
        assertEquals(1.25f, TextScalePolicy.composeFontScale(1.0f, true))
        assertEquals(1.75f, TextScalePolicy.composeFontScale(1.6f, true))
        assertEquals(125, TextScalePolicy.webViewTextZoom(true))
        assertEquals(100, TextScalePolicy.webViewTextZoom(false))
    }

    @Test
    fun speechTextIsNormalizedAndChunkedWithoutLoss() {
        val raw = "# 标题\n\n**第一句**。" + "甲".repeat(80) + "，" + "乙".repeat(80) + "。结束。"
        val chunks = SpeechTextPolicy.chunks(raw, maxCharacters = 100)

        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.length <= 100 })
        assertFalse(chunks.joinToString("").contains('#'))
        assertFalse(chunks.joinToString("").contains('*'))
        assertTrue(chunks.joinToString("").contains("第一句"))
        assertTrue(chunks.joinToString("").endsWith("结束。"))
    }

    @Test
    fun emptySpeechTextProducesNoChunks() {
        assertTrue(SpeechTextPolicy.chunks("   ").isEmpty())
        assertTrue(SpeechTextPolicy.chunks("内容", maxCharacters = 0).isEmpty())
    }

    @Test
    fun shareTextContainsQuestionAndSummaryAndIsBounded() {
        val normal = ShareTextPolicy.discussionSummary("原问题", "总结结论")
        assertTrue(normal.text.contains("原问题"))
        assertTrue(normal.text.contains("总结结论"))
        assertFalse(normal.truncated)

        val long = ShareTextPolicy.discussionSummary("问".repeat(10_000), "答".repeat(20_000))
        assertTrue(long.truncated)
        assertEquals(ShareTextPolicy.MAX_SHARE_CHARACTERS, long.text.length)
        assertTrue(long.text.endsWith("]"))
    }

    @Test
    fun trustSignalHighlightsMedicalVerificationWithoutFakeScore() {
        val signal = DiscussionTrustPolicy.analyze(
            question = "老年人如何安全服药",
            summary = "已形成的共识：遵医嘱。\n仍有分歧。\n需核验剂量，并咨询医生确认。",
            providerCount = 3,
        )

        assertEquals(3, signal.providerCount)
        assertTrue(signal.consensusReviewed)
        assertTrue(signal.differencesReviewed)
        assertTrue(signal.verificationReminderCount >= 1)
        assertTrue(signal.domainCaution.contains("医生或药师"))
    }

    @Test
    fun promptBudgetCompressesQuotesButNeverCutsLongOriginalSilently() {
        val longQuote = "引".repeat(2_000)
        val compressed = PromptBudgetPolicy.fit(ArenaService.QWEN) { limit ->
            "原".repeat(6_900) + longQuote.take(limit)
        }
        assertTrue(compressed != null)
        assertTrue(compressed!!.compressed)
        assertTrue(compressed.text.length <= PromptBudgetPolicy.QWEN_BUDGET)
        assertTrue(compressed.quoteLimit < ArenaLimits.MAX_QUOTED_RESPONSE_CHARS)

        val impossible = PromptBudgetPolicy.fit(ArenaService.QWEN) {
            "原问题".repeat(3_000)
        }
        assertEquals(null, impossible)
    }
}
