package com.tianlin.aiarena

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 0.19.0 控制器行为：互相激发；成员四个逃生动作（重新提取 / 重新发送 / 跳过 / 换人）在进行中与结束后的语义。
 */
@RunWith(AndroidJUnit4::class)
class ArenaEscapeInspireInstrumentedTest {
    private val ds = ArenaService.DEEPSEEK
    private val db = ArenaService.DOUBAO
    private val km = ArenaService.KIMI
    private val qw = ArenaService.QWEN
    private val timing = ControllerTiming(
        pollIntervalMillis = 15,
        readCallbackTimeoutMillis = 500,
        responseTimeoutMillis = 20_000,
        maxConsecutiveReadErrors = 3,
        requiredStablePolls = 1,
        sendTimeoutMillis = 20_000,
        freshConversationTimeoutMillis = 20_000,
    )

    @Test fun inspireSendsEveryMemberTheOthersAnswersAndCountsItsOwnRounds() {
        val gateway = ScriptedGateway()
        val controller = onMain { ArenaSessionController(gateway, timing) }
        try {
            onMain { assertTrue(controller.startInitial("怎么练口语", ArenaService.defaultMembers)) }
            awaitHistory(controller, 1)
            onMain { gateway.sent.clear(); assertTrue(controller.startInspire(guidance = "多给例子")) }
            awaitHistory(controller, 2)
            onMain {
                val toDeepSeek = gateway.promptFor(ds)
                assertTrue(toDeepSeek.startsWith(ArenaPresets.inspireHint(1)))
                assertTrue(toDeepSeek.contains("【豆包 的回答】\nDOUBAO-1") && toDeepSeek.contains("【Kimi 的回答】\nKIMI-1"))
                assertFalse("Never its own answer", toDeepSeek.contains("DEEPSEEK-1"))
                assertTrue(toDeepSeek.endsWith("用户补充要求：\n多给例子"))
                assertEquals(RoundKind.INSPIRE, controller.history.last().kind)
                assertEquals("互相激发", ArenaTimeline.kindLabel(controller.history.last()))
                gateway.sent.clear()
                assertTrue(controller.startDebate())
            }
            awaitHistory(controller, 3)
            onMain { gateway.sent.clear(); assertTrue(controller.startInspire()) }
            awaitHistory(controller, 4)
            onMain {
                assertTrue("A discussion in between does not change the inspire count",
                    gateway.promptFor(km).startsWith(ArenaPresets.inspireHint(2)))
                assertTrue("Material is the previous (discussion) round", gateway.promptFor(km).contains("DEEPSEEK-3"))
            }
        } finally { onMain { controller.destroy() } }
    }

    @Test fun inspireNeedsTwoFullAnswers() {
        val gateway = ScriptedGateway(failSend = mutableSetOf(km, db))
        val controller = onMain { ArenaSessionController(gateway, timing) }
        try {
            onMain { controller.startInitial("问题", ArenaService.defaultMembers) }
            awaitHistory(controller, 1)
            onMain {
                assertFalse(controller.startInspire())
                assertTrue(controller.sessionMessage.contains("至少要有 2 份完整回答"))
            }
        } finally { onMain { controller.destroy() } }
    }

    @Test fun swappingAMemberThatIsStillAnsweringHandsThisRoundToTheNewcomer() {
        val gateway = ScriptedGateway(holdRead = mutableSetOf(km))
        val controller = onMain { ArenaSessionController(gateway, timing) }
        try {
            onMain { controller.startInitial("原问题", ArenaService.defaultMembers) }
            awaitPhase(controller, db, ParticipantPhase.COMPLETE)
            awaitPhase(controller, ds, ParticipantPhase.COMPLETE)
            onMain {
                assertEquals(ParticipantPhase.WAITING, controller.runs.getValue(km).phase)
                assertEquals(SwapTiming.NOW, controller.swapTiming(km))
                assertTrue(controller.swap(km, qw))
                assertTrue(km in gateway.cancelled)
                assertEquals(listOf(ds, db, qw), controller.sessionServices)
                assertEquals(listOf(ds, db, km, qw), controller.roundTabs)
                assertTrue("Still the same round", controller.isBusy)
            }
            awaitHistory(controller, 1)
            onMain {
                assertEquals("The newcomer opened a new conversation", 1, gateway.fresh.count { it == qw })
                assertEquals("Same material as the position: the question", "原问题", gateway.promptFor(qw))
                val round = controller.history.single()
                assertEquals(ParticipantPhase.COMPLETE, round.results.getValue(qw).phase)
                assertTrue(round.results.getValue(km).skipped)
                assertEquals(qw, round.results.getValue(km).replacedBy)
                assertEquals(1, gateway.sent.count { it.first == km })
                assertEquals(listOf(ds, db, qw), controller.nextRoundMembers())
            }
        } finally { onMain { controller.destroy() } }
    }

    @Test fun swappingAFinishedMemberOnlyTakesEffectNextRoundAndTheNewcomerGetsEveryAnswer() {
        val gateway = ScriptedGateway()
        val repository = MemoryRepository()
        val controller = onMain { ArenaSessionController(gateway, timing, repository) }
        try {
            onMain { controller.startInitial("原问题全文", ArenaService.defaultMembers) }
            awaitHistory(controller, 1)
            onMain {
                gateway.sent.clear()
                assertEquals(SwapTiming.NEXT_ROUND, controller.swapTiming(km))
                assertTrue(controller.swap(km, qw))
                assertTrue("Nothing is sent this round", gateway.sent.isEmpty())
                assertEquals(mapOf(km to qw), controller.pendingSwaps)
                assertEquals(ArenaService.defaultMembers, controller.sessionServices)
                assertFalse("A candidate booked for one seat is not offered for another", qw in controller.swapCandidates(ds))
            }
            // The booking survives a cold start.
            onMain {
                controller.destroy()
            }
            val restored = onMain { ArenaSessionController(gateway, timing, repository) }
            onMain {
                assertEquals(mapOf(km to qw), restored.pendingSwaps)
                assertEquals(listOf(ds, db, qw), restored.nextRoundMembers())
                assertTrue(restored.startInspire())
                assertEquals(listOf(ds, db, qw), restored.sessionServices)
                assertTrue(restored.pendingSwaps.isEmpty())
            }
            awaitHistory(restored, 2)
            onMain {
                assertFalse("The swapped-out member is not asked again", gateway.sent.any { it.first == km })
                assertEquals(1, gateway.fresh.count { it == qw })
                val toQwen = gateway.promptFor(qw)
                assertTrue(toQwen.contains("原问题全文"))
                assertTrue(listOf("DEEPSEEK-1", "DOUBAO-1", "KIMI-1").all { toQwen.contains(it) })
                assertTrue("Kimi's finished answer is still material for the others", gateway.promptFor(ds).contains("KIMI-1"))
                restored.destroy()
            }
        } finally { onMain { controller.destroy() } }
    }

    @Test fun swapAfterTheUserStoppedTheRoundWaitsForTheNextRound() {
        val gateway = ScriptedGateway(holdSend = mutableSetOf(ds, db, km))
        val controller = onMain { ArenaSessionController(gateway, timing) }
        try {
            onMain {
                controller.startInitial("停一下", ArenaService.defaultMembers)
                controller.cancelCurrentRound()
                gateway.sent.clear()
                assertEquals(SwapTiming.NEXT_ROUND, controller.swapTiming(ds))
                assertTrue(controller.swap(ds, qw))
                assertTrue(gateway.sent.isEmpty())
                assertFalse(controller.isBusy)
                assertEquals(mapOf(ds to qw), controller.pendingSwaps)
            }
        } finally { onMain { controller.destroy() } }
    }

    @Test fun swappingAFailedMemberAfterTheRoundLetsTheNewcomerTakeItOver() {
        val gateway = ScriptedGateway(failSend = mutableSetOf(km))
        val controller = onMain { ArenaSessionController(gateway, timing) }
        try {
            onMain { controller.startInitial("原问题", ArenaService.defaultMembers) }
            awaitHistory(controller, 1)
            onMain {
                assertEquals(ParticipantPhase.ERROR, controller.runs.getValue(km).phase)
                assertTrue(controller.swap(km, qw))
            }
            awaitPhase(controller, qw, ParticipantPhase.COMPLETE)
            onMain {
                assertEquals("原问题", gateway.promptFor(qw))
                assertEquals(1, gateway.fresh.count { it == qw })
                val round = controller.history.single()
                assertEquals(ParticipantPhase.COMPLETE, round.results.getValue(qw).phase)
                assertEquals(qw, round.results.getValue(km).replacedBy)
                assertEquals(3, controller.completedCount)
                assertFalse(controller.isBusy)
            }
        } finally { onMain { controller.destroy() } }
    }

    @Test fun resendingAFinishedMemberKeepsTheEarlierAnswer() {
        val gateway = ScriptedGateway()
        val controller = onMain { ArenaSessionController(gateway, timing) }
        try {
            onMain { controller.startInitial("问题", ArenaService.defaultMembers) }
            awaitHistory(controller, 1)
            onMain { assertTrue(MemberActionPolicy.resendNeedsConfirm(controller.runs.getValue(ds))); assertTrue(controller.resend(ds)) }
            awaitPhase(controller, ds, ParticipantPhase.COMPLETE)
            onMain {
                val run = controller.runs.getValue(ds)
                assertEquals("DEEPSEEK-2", run.response)
                assertEquals(listOf("DEEPSEEK-1"), run.previousResponses)
                assertEquals(run, controller.history.single().results.getValue(ds))
                assertEquals("Others untouched", 1, gateway.sent.count { it.first == db })
            }
        } finally { onMain { controller.destroy() } }
    }

    @Test fun resendingWhileAnsweringRestartsOnlyThatMemberAndKeepsWhatItHad() {
        val gateway = ScriptedGateway(holdRead = mutableSetOf(km), streamingText = mapOf(km to "半截回答"))
        val controller = onMain { ArenaSessionController(gateway, timing) }
        try {
            onMain { controller.startInitial("问题", ArenaService.defaultMembers) }
            awaitPhase(controller, km, ParticipantPhase.STREAMING)
            onMain {
                assertTrue(controller.resend(km))
                assertTrue(km in gateway.cancelled)
                assertEquals(listOf("半截回答"), controller.runs.getValue(km).previousResponses)
                gateway.holdRead.clear()
            }
            awaitHistory(controller, 1)
            onMain {
                assertEquals(2, gateway.sent.count { it.first == km })
                assertEquals("KIMI-2", controller.runs.getValue(km).response)
                assertEquals(listOf("半截回答"), controller.runs.getValue(km).previousResponses)
                assertEquals(1, gateway.sent.count { it.first == ds })
            }
        } finally { onMain { controller.destroy() } }
    }

    @Test fun queuedWorkflowMemberIsSentAtOnceWithTheAnswersAvailableNow() {
        val gateway = ScriptedGateway(holdRead = mutableSetOf(ds))
        val controller = onMain { ArenaSessionController(gateway, timing) }
        val order = listOf(km, ds, db)
        try {
            onMain { assertTrue(controller.startInitial("接力题", ArenaService.defaultMembers, AnswerMode.SERIAL, relayOrder = order)) }
            awaitPhase(controller, ds, ParticipantPhase.WAITING)
            onMain {
                assertEquals(ParticipantPhase.QUEUED, controller.runs.getValue(db).phase)
                assertFalse("A queued member is sent without a duplicate warning", MemberActionPolicy.resendNeedsConfirm(controller.runs.getValue(db)))
                assertTrue(controller.resend(db))
            }
            awaitPhase(controller, db, ParticipantPhase.COMPLETE)
            onMain {
                val toDoubao = gateway.promptFor(db)
                assertTrue(toDoubao.startsWith("接力题"))
                assertTrue(toDoubao.contains("【Kimi 的回答】\nKIMI-1"))
                assertFalse("DeepSeek had not answered yet", toDoubao.contains("DEEPSEEK"))
                assertTrue(controller.isBusy)
                gateway.holdRead.clear()
            }
            awaitHistory(controller, 1)
            onMain { assertEquals("Doubao is not sent a second time when the queue reaches it", 1, gateway.sent.count { it.first == db }) }
        } finally { onMain { controller.destroy() } }
    }

    @Test fun skippingAFinishedAnswerLeavesItOutOfTheNextDiscussion() {
        val gateway = ScriptedGateway()
        val controller = onMain { ArenaSessionController(gateway, timing) }
        try {
            onMain { controller.startInitial("问题", ArenaService.defaultMembers) }
            awaitHistory(controller, 1)
            onMain {
                assertTrue(controller.skip(db))
                val skipped = controller.runs.getValue(db)
                assertTrue(skipped.skipped)
                assertEquals("The text is kept", "DOUBAO-1", skipped.response)
                assertFalse(controller.skip(db))
                gateway.sent.clear()
                assertTrue(controller.startDebate())
            }
            awaitHistory(controller, 2)
            onMain {
                assertEquals(setOf(ds, km), gateway.sent.map { it.first }.toSet())
                assertTrue(gateway.sent.none { it.second.contains("DOUBAO-1") })
            }
        } finally { onMain { controller.destroy() } }
    }

    @Test fun skippingThenReextractingBringsTheAnswerBack() {
        val gateway = ScriptedGateway()
        val controller = onMain { ArenaSessionController(gateway, timing) }
        try {
            onMain { controller.startInitial("问题", ArenaService.defaultMembers) }
            awaitHistory(controller, 1)
            onMain { assertTrue(controller.skip(db)); assertEquals(2, controller.completedCount); assertTrue(controller.reextract(db)) }
            awaitPhase(controller, db, ParticipantPhase.COMPLETE)
            onMain {
                assertFalse(controller.runs.getValue(db).skipped)
                assertEquals(3, controller.completedCount)
                assertEquals("Re-extraction never sends", 1, gateway.sent.count { it.first == db })
            }
        } finally { onMain { controller.destroy() } }
    }

    @Test fun reextractingWhileAnsweringRestartsTheReadingWithoutSending() {
        val gateway = ScriptedGateway(holdRead = mutableSetOf(ds))
        val controller = onMain { ArenaSessionController(gateway, timing) }
        try {
            onMain { controller.startInitial("问题", ArenaService.defaultMembers) }
            awaitPhase(controller, ds, ParticipantPhase.WAITING)
            onMain {
                assertTrue(controller.reextract(ds))
                assertEquals(ParticipantPhase.WAITING, controller.runs.getValue(ds).phase)
                assertTrue(controller.isBusy)
                gateway.holdRead.clear()
            }
            awaitHistory(controller, 1)
            onMain {
                assertEquals(1, gateway.sent.count { it.first == ds })
                assertEquals(ParticipantPhase.COMPLETE, controller.runs.getValue(ds).phase)
                assertFalse(ds in gateway.cancelled)
            }
        } finally { onMain { controller.destroy() } }
    }

    @Test fun skippingARunningMemberStopsOnlyThatOne() {
        val gateway = ScriptedGateway(holdRead = mutableSetOf(km))
        val controller = onMain { ArenaSessionController(gateway, timing) }
        try {
            onMain { controller.startInitial("问题", ArenaService.defaultMembers) }
            awaitPhase(controller, km, ParticipantPhase.WAITING)
            awaitPhase(controller, ds, ParticipantPhase.COMPLETE)
            awaitPhase(controller, db, ParticipantPhase.COMPLETE)
            onMain {
                assertTrue(controller.skip(km))
                assertFalse(controller.isBusy)
                assertEquals(listOf(km), gateway.cancelled)
                assertEquals(listOf(ds, db), controller.nextRoundMembers())
            }
        } finally { onMain { controller.destroy() } }
    }

    @Test fun summaryAndASingleMemberResendCanRunTogether() {
        val gateway = ScriptedGateway()
        val controller = onMain { ArenaSessionController(gateway, timing) }
        try {
            onMain { controller.startInitial("问题", ArenaService.defaultMembers) }
            awaitHistory(controller, 1)
            onMain {
                gateway.holdRead += ds
                assertTrue(controller.startSummary(listOf(ds)))
                assertFalse("The captain's page is busy writing the summary", controller.resend(ds))
                assertTrue(controller.resend(km))
            }
            awaitPhase(controller, km, ParticipantPhase.COMPLETE)
            onMain {
                assertEquals("The summary was not cancelled by the resend", ParticipantPhase.WAITING, controller.summary.phase)
                gateway.holdRead.clear()
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && onMain { controller.summary.phase != ParticipantPhase.COMPLETE }) Thread.sleep(20)
            onMain { assertEquals(ParticipantPhase.COMPLETE, controller.summary.phase) }
        } finally { onMain { controller.destroy() } }
    }

    @Test fun swappingAMemberStuckOpeningItsPageSurvivesTheSynchronousCancelCallback() {
        val gateway = ScriptedGateway(holdFresh = mutableSetOf(km))
        val controller = onMain { ArenaSessionController(gateway, timing) }
        try {
            onMain { controller.startInitial("原问题", ArenaService.defaultMembers) }
            awaitPhase(controller, ds, ParticipantPhase.COMPLETE)
            awaitPhase(controller, db, ParticipantPhase.COMPLETE)
            onMain {
                assertEquals(ParticipantPhase.QUEUED, controller.runs.getValue(km).phase)
                assertTrue(controller.swap(km, qw))
                assertTrue("The round must not end before the newcomer answers", controller.isBusy || controller.runs.getValue(qw).phase == ParticipantPhase.COMPLETE)
                assertFalse("A member replaced this round is not offered back", km in controller.swapCandidates(qw))
            }
            awaitHistory(controller, 1)
            onMain {
                assertEquals(ParticipantPhase.COMPLETE, controller.history.single().results.getValue(qw).phase)
                assertFalse("The stuck member was never sent", gateway.sent.any { it.first == km })
            }
        } finally { onMain { controller.destroy() } }
    }

    @Test fun resendingAMemberStuckOpeningItsPageFinishesTheRound() {
        val gateway = ScriptedGateway(holdFresh = mutableSetOf(km))
        val controller = onMain { ArenaSessionController(gateway, timing) }
        try {
            onMain { controller.startInitial("原问题", ArenaService.defaultMembers) }
            awaitPhase(controller, ds, ParticipantPhase.COMPLETE)
            awaitPhase(controller, db, ParticipantPhase.COMPLETE)
            onMain { assertTrue(controller.resend(km)) }
            awaitHistory(controller, 1)
            onMain {
                assertEquals(ParticipantPhase.COMPLETE, controller.history.single().results.getValue(km).phase)
                assertEquals(1, gateway.sent.count { it.first == km })
            }
        } finally { onMain { controller.destroy() } }
    }

    @Test fun reextractingAFinishedAnswerThatCannotBeReadKeepsTheAnswer() {
        val gateway = ScriptedGateway(holdRead = mutableSetOf(km))
        val controller = onMain { ArenaSessionController(gateway, timing) }
        try {
            onMain { controller.startInitial("问题", ArenaService.defaultMembers) }
            awaitPhase(controller, ds, ParticipantPhase.COMPLETE)
            onMain { gateway.failRead += ds; assertTrue("During the round", controller.reextract(ds)) }
            awaitPhase(controller, ds, ParticipantPhase.COMPLETE)
            onMain {
                assertEquals("DEEPSEEK-1", controller.runs.getValue(ds).response)
                assertTrue(controller.runs.getValue(ds).detail.contains("保留原回答"))
                gateway.holdRead.clear()
            }
            awaitHistory(controller, 1)
            onMain { assertTrue("After the round", controller.reextract(ds)) }
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline && onMain { controller.isBusy }) Thread.sleep(20)
            onMain {
                assertEquals(ParticipantPhase.COMPLETE, controller.runs.getValue(ds).phase)
                assertEquals("DEEPSEEK-1", controller.runs.getValue(ds).response)
                assertEquals(3, controller.completedCount)
            }
        } finally { onMain { controller.destroy() } }
    }

    // ---------------------------------------------------------------------------------------------

    /** 每次发送的回答都不同（KIMI-1、KIMI-2…），可以看出重发后旧回答有没有保住。 */
    private class ScriptedGateway(
        val holdRead: MutableSet<ArenaService> = mutableSetOf(),
        val holdSend: MutableSet<ArenaService> = mutableSetOf(),
        val failSend: MutableSet<ArenaService> = mutableSetOf(),
        val streamingText: Map<ArenaService, String> = emptyMap(),
        /** 开新对话迟迟不回；取消时像真实网页池一样同步回调 false。 */
        val holdFresh: MutableSet<ArenaService> = mutableSetOf(),
        val failRead: MutableSet<ArenaService> = mutableSetOf(),
    ) : ArenaGateway {
        private val pendingFresh = mutableMapOf<ArenaService, (Boolean) -> Unit>()
        val sent = mutableListOf<Pair<ArenaService, String>>()
        val fresh = mutableListOf<ArenaService>()
        val cancelled = mutableListOf<ArenaService>()
        private val sendCounts = mutableMapOf<ArenaService, Int>()

        fun promptFor(service: ArenaService): String = sent.last { it.first == service }.second

        override fun openFreshConversation(service: ArenaService, callback: (Boolean) -> Unit) {
            fresh += service
            if (service in holdFresh) pendingFresh[service] = callback else callback(true)
        }

        override fun sendPrompt(service: ArenaService, prompt: String, requestId: String, callback: (SendOutcome) -> Unit) {
            sent += service to prompt
            sendCounts.merge(service, 1, Int::plus)
            when (service) {
                in failSend -> callback(SendOutcome(false, requestId, "输入框没有出现，本轮未发送"))
                in holdSend -> Unit
                else -> callback(SendOutcome(true, requestId, "ok"))
            }
        }

        override fun readResponse(service: ArenaService, requestId: String, callback: (ResponseSnapshot) -> Unit) {
            if (service in failRead) {
                callback(ResponseSnapshot(found = false, text = "", streaming = false, detail = "页面暂时读不到"))
                return
            }
            if (service in holdRead) {
                val partial = streamingText[service]
                callback(if (partial != null) ResponseSnapshot(found = true, text = partial, streaming = true)
                    else ResponseSnapshot(found = false, text = "", streaming = false))
                return
            }
            callback(ResponseSnapshot(found = true, text = "${service.name}-${sendCounts[service] ?: 0}", streaming = false))
        }

        override fun cancelAutomation(service: ArenaService) {
            cancelled += service
            holdFresh -= service
            pendingFresh.remove(service)?.invoke(false)
        }
    }

    private class MemoryRepository : ArenaSessionRepository {
        private val snapshots = linkedMapOf<String, ArenaSessionSnapshot>()
        private var active: String? = null
        private var sequence = 0
        override fun newSessionId(): String = "session_escape_${++sequence}"
        override fun save(snapshot: ArenaSessionSnapshot) { snapshots[snapshot.id] = snapshot }
        override fun load(id: String): ArenaSessionSnapshot? = snapshots[id]
        override fun loadActive(): ArenaSessionSnapshot? = active?.let(snapshots::get)
        override fun setActiveSession(id: String?) { active = id }
        override fun listRecent(limit: Int): List<RecentArenaSession> = emptyList()
        override fun forget(id: String) { snapshots.remove(id) }
    }

    private fun awaitHistory(controller: ArenaSessionController, size: Int, timeoutMillis: Long = 8_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (onMain { controller.history.size >= size && !controller.isBusy }) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for history size $size")
    }

    private fun awaitPhase(controller: ArenaSessionController, service: ArenaService, phase: ParticipantPhase, timeoutMillis: Long = 8_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (onMain { controller.runs.getValue(service).phase == phase }) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for $service $phase, now ${onMain { controller.runs.getValue(service) }}")
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { result.set(block()) } catch (error: Throwable) { failure.set(error) }
        }
        failure.get()?.let { throw AssertionError("Main-thread block failed", it) }
        return result.get()
    }
}
