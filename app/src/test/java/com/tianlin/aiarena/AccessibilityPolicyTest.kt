package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityPolicyTest {
    @Test
    fun largeTextScaleRespectsSystemScaleAndUpperBound() {
        assertEquals(1.0f, TextScalePolicy.composeFontScale(1.0f, false))
        assertEquals(1.25f, TextScalePolicy.composeFontScale(1.0f, true))
        assertEquals(1.75f, TextScalePolicy.composeFontScale(1.6f, true))
        assertEquals(125, TextScalePolicy.webViewTextZoom(true))
        assertEquals(100, TextScalePolicy.webViewTextZoom(false))
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
