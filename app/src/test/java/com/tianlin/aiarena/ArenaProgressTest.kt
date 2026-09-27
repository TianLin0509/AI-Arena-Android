package com.tianlin.aiarena

import org.junit.Assert.*
import org.junit.Test

class ArenaProgressTest {
    private val service = ArenaService.QWEN
    private val timing = ControllerTiming()
    private fun run(phase: ParticipantPhase, id: String = "r", detail: String = "") =
        ParticipantRun(phase = phase, requestId = id, detail = detail)

    @Test fun summaryAndRecoveryUseControllerDeadlineIncludingPreviousPreparation() {
        val tracker = ArenaProgressTracker()
        tracker.observe(service, run(ParticipantPhase.SENDING), 0, true)
        tracker.observe(service, run(ParticipantPhase.WAITING), 150_000, true)
        val progress = tracker.describe(service, run(ParticipantPhase.WAITING), 160_000, timing,
            answerDeadlineElapsedMillis = 300_000)!!
        assertTrue(progress.limit.contains("140秒"))
        assertEquals("已等待 160秒", progress.elapsed)
    }

    @Test fun coldStartShowsActualStageAndNoInventedEstimate() {
        val tracker = ArenaProgressTracker()
        val queued = run(ParticipantPhase.QUEUED)
        tracker.observe(service, queued, 10_000, false)
        val progress = tracker.describe(service, queued, 11_001, timing)!!
        assertEquals("准备新对话", progress.title)
        assertEquals("已等待 2秒", progress.elapsed)
        assertTrue(progress.expectation.contains("暂无足够记录"))
        assertTrue(progress.limit.contains("89秒"))
        assertTrue(progress.limit.contains("不是完成倒计时"))
    }

    @Test fun streamingTransitionsDoNotResetResponseDeadlineOrTotalElapsed() {
        val tracker = ArenaProgressTracker()
        tracker.observe(service, run(ParticipantPhase.QUEUED), 0, false)
        tracker.observe(service, run(ParticipantPhase.SENDING), 2_000, false)
        tracker.observe(service, run(ParticipantPhase.WAITING), 5_000, false)
        tracker.observe(service, run(ParticipantPhase.STREAMING), 10_000, false)
        tracker.observe(service, run(ParticipantPhase.WAITING), 25_000, false)
        val progress = tracker.describe(service, run(ParticipantPhase.WAITING), 35_000, timing)!!
        assertEquals("已等待 35秒", progress.elapsed)
        assertTrue(progress.limit.contains("270秒"))
        assertTrue(progress.needsAttention)
    }

    @Test fun timingReferencesUseOnlyMatchingCategoryAndBoundedSamples() {
        val samples = ArenaMemoryDurationSamples()
        val tracker = ArenaProgressTracker(samples)
        repeat(23) { index ->
            val id = "sample-$index"
            tracker.observe(service, run(ParticipantPhase.SENDING, id), 0, false)
            tracker.observe(service, run(ParticipantPhase.WAITING, id), 4_000, false)
            tracker.observe(service, run(ParticipantPhase.COMPLETE, id), 8_000, false)
            tracker.observe(service, run(ParticipantPhase.COMPLETE, id), 20_000, false)
        }
        assertEquals(20, samples.read("QWEN.text.send").size)
        tracker.observe(service, run(ParticipantPhase.SENDING), 0, false)
        val slow = tracker.describe(service, run(ParticipantPhase.SENDING), 5_000, timing)!!
        assertTrue(slow.expectation.contains("约 4秒"))
        assertTrue(slow.expectation.contains("近 20 次"))
        assertTrue(slow.expectation.contains("已超过参考"))
        tracker.observe(service, run(ParticipantPhase.SENDING, "files"), 0, true)
        val files = tracker.describe(service, run(ParticipantPhase.SENDING, "files"), 5_000, timing)!!
        assertTrue(files.expectation.contains("暂无足够记录"))
        assertTrue(files.limit.contains("195秒"))
    }

    @Test fun failuresRestorationAndManualExtractionDoNotCreateSuccessSamples() {
        val samples = ArenaMemoryDurationSamples()
        val tracker = ArenaProgressTracker(samples)
        tracker.observe(service, run(ParticipantPhase.SENDING), 0, false)
        tracker.observe(service, run(ParticipantPhase.ERROR), 4_000, false)
        tracker.observe(service, run(ParticipantPhase.COMPLETE, "restored"), 10_000, false)
        tracker.observe(service, run(ParticipantPhase.WAITING, "extract"), 20_000, false)
        tracker.observe(service, run(ParticipantPhase.COMPLETE, "extract"), 21_000, false)
        assertTrue(samples.read("QWEN.text.send").isEmpty())
        assertTrue(samples.read("QWEN.text.answer").isEmpty())
        assertNull(tracker.describe(service, run(ParticipantPhase.COMPLETE), 25_000, timing))
    }

    @Test fun serialWaitIsNeverLearnedAsPagePreparation() {
        val samples = ArenaMemoryDurationSamples()
        val tracker = ArenaProgressTracker(samples)
        val serial = run(ParticipantPhase.QUEUED, detail = "等上一家")
        tracker.observe(service, serial, 0, false)
        val progress = tracker.describe(service, serial, 200_000, timing)!!
        assertEquals("等待上一家回答", progress.title)
        assertTrue(progress.expectation.contains("暂无可靠剩余时间"))
        assertFalse(progress.limit.contains("已到"))
        tracker.observe(service, run(ParticipantPhase.SENDING), 205_000, false)
        assertTrue(samples.read("QWEN.text.prepare").isEmpty())
    }

    @Test fun newRequestResetsClockAndIgnoresOldTransportProgress() {
        val tracker = ArenaProgressTracker()
        tracker.observe(service, run(ParticipantPhase.SENDING, "old"), 0, true)
        tracker.observe(service, run(ParticipantPhase.SENDING, "new"), 10_000, true)
        assertNull(tracker.describe(service, run(ParticipantPhase.SENDING, "old"), 11_000, timing))
        val current = tracker.describe(service, run(ParticipantPhase.SENDING, "new"), 11_000, timing,
            ArenaSendProgress("old", "过期上传"))!!
        assertEquals("已等待 1秒", current.elapsed)
        assertEquals("上传附件并发送", current.title)
        assertEquals("正在解析", tracker.describe(service, run(ParticipantPhase.SENDING, "new"), 11_000, timing,
            ArenaSendProgress("new", "正在解析"))!!.title)
    }

    @Test fun challengeAndReachedLimitGiveActionWithoutPromisingCompletion() {
        val tracker = ArenaProgressTracker()
        val challenge = run(ParticipantPhase.SENDING, detail = "需要安全验证")
        tracker.observe(service, challenge, 0, false)
        val progress = tracker.describe(service, challenge, 61_000, timing)!!
        assertTrue(progress.expectation.contains("无法预估"))
        assertTrue(progress.limit.contains("已到本阶段等待上限"))
        assertTrue(progress.needsAttention)
    }
}
