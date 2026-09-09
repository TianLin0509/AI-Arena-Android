package com.tianlin.aiarena

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArenaSessionControllerInstrumentedTest {
    private val fastTiming = ControllerTiming(
        pollIntervalMillis = 15,
        readCallbackTimeoutMillis = 500,
        responseTimeoutMillis = 2_000,
        maxConsecutiveReadErrors = 3,
        requiredStablePolls = 1,
    )

    @Test
    fun newSessionResetPreservesCompletedHistoryButClearsColdStartSelection() {
        val repository = FakeSessionRepository()
        val controller = onMain { ArenaSessionController(FakeGateway(), fastTiming, repository) }
        onMain { assertTrue(controller.startInitial("应保留在历史里的问题", ArenaService.defaultMembers)) }
        awaitHistorySize(controller, 1)
        onMain {
            controller.reset()
            assertEquals(SessionStage.IDLE, controller.stage)
            assertEquals("", controller.originalQuestion)
            assertEquals(0, controller.completedCount)
            assertEquals(null, repository.loadActive())
            val saved = repository.load(controller.recentSessions.single().id)!!
            assertEquals("应保留在历史里的问题", saved.originalQuestion)
            assertEquals(3, saved.runs.values.count { it.phase == ParticipantPhase.COMPLETE })
            controller.destroy()
            val restarted = ArenaSessionController(FakeGateway(), fastTiming, repository)
            assertEquals(SessionStage.IDLE, restarted.stage)
            assertEquals(1, restarted.recentSessions.size)
            restarted.destroy()
        }
    }

    @Test
    fun newSessionDuringSendIgnoresLateCallbacksAndPreservesInterruptedHistory() {
        val repository = FakeSessionRepository()
        val gateway = DeferredSendGateway()
        val controller = onMain { ArenaSessionController(gateway, fastTiming, repository) }
        onMain {
            assertTrue(controller.startInitial("中止后仍保留的问题", ArenaService.defaultMembers))
            assertTrue(controller.isBusy)
            controller.reset()
            gateway.releaseAllCallbacks()
            assertFalse(controller.isBusy)
            assertEquals(SessionStage.IDLE, controller.stage)
            assertEquals(null, repository.loadActive())
            assertTrue(controller.runs.values.all { it.phase == ParticipantPhase.IDLE })
            assertEquals("中止后仍保留的问题", controller.recentSessions.single().title)
            controller.destroy()
        }
    }

    private val attachmentFixture = ArenaAttachment("00000000-0000-0000-0000-000000000001", "report.pdf", "application/pdf", 64, "a".repeat(64))

    @Test fun invalidAttachmentContainersNeverDecodeAsTextOnlyHistory() {
        val source = recoverySnapshot("不能丢附件").copy(lastRoundAttachments = listOf(attachmentFixture))
        listOf("lastRoundAttachments", "round", "summary").forEach { location ->
            listOf<Any>("broken", JSONObject(), JSONObject.NULL).forEach { invalid ->
                val encoded = ArenaSessionJson.encode(source)
                when (location) {
                    "lastRoundAttachments" -> encoded.put(location, invalid)
                    "round" -> encoded.getJSONArray("history").getJSONObject(0).put("attachments", invalid)
                    else -> encoded.getJSONObject("summary").put("attachments", invalid)
                }
                assertTrue("Invalid $location must fail closed", runCatching { ArenaSessionJson.decode(encoded) }.isFailure)
            }
        }
        val legacy = ArenaSessionJson.encode(source)
        legacy.remove("lastRoundAttachments")
        legacy.getJSONArray("history").getJSONObject(0).remove("attachments")
        legacy.getJSONObject("summary").remove("attachments")
        assertTrue(ArenaSessionJson.decode(legacy).lastRoundAttachments.isEmpty())
    }

    @Test fun unreadableAttachmentHistoryIsRemovedFromIndexButItsOriginalFileIsKept() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directoryName = "attachment_corruption_${System.nanoTime()}"
        val store = ArenaSessionStore(context, directoryName, directoryName)
        val source = recoverySnapshot("损坏容器保留文件").copy(lastRoundAttachments = listOf(attachmentFixture))
        store.save(source)
        val original = File(context.filesDir, "$directoryName/${source.id}.json")
        val damaged = ArenaSessionJson.encode(source).put("lastRoundAttachments", "bad-container").toString()
        original.writeText(damaged, Charsets.UTF_8)
        assertEquals(null, store.load(source.id))
        store.forget(source.id)
        assertTrue(store.listRecent().isEmpty())
        assertTrue(original.isFile)
        assertEquals(damaged, original.readText(Charsets.UTF_8))
    }

    @Test fun synchronousResetWaitsBehindOldAsyncSaveAndKeepsNewestAttachmentReferences() {
        val firstSaveEntered = CountDownLatch(1)
        val releaseOldSave = CountDownLatch(1)
        val oldSaveFinished = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val writtenRounds = CopyOnWriteArrayList<Int>()
        val repository = object : FakeSessionRepository() {
            override fun save(snapshot: ArenaSessionSnapshot) {
                val block = first.getAndSet(false)
                if (block) {
                    firstSaveEntered.countDown()
                    check(releaseOldSave.await(4, TimeUnit.SECONDS)) { "Old save was never released" }
                }
                super.save(snapshot)
                writtenRounds += snapshot.roundNumber
                if (block) oldSaveFinished.countDown()
            }
        }
        val controller = onMain { ArenaSessionController(FakeGateway(), fastTiming, repository) }
        onMain { controller.startInitial("保存顺序测试", ArenaService.defaultMembers) }
        awaitHistorySize(controller, 1)
        assertTrue(firstSaveEntered.await(2, TimeUnit.SECONDS))
        var release: Thread? = null
        try {
            onMain {
                assertTrue(controller.startIteration(guidance = "新一轮及新附件", attachments = listOf(attachmentFixture)))
                release = Thread { Thread.sleep(150); releaseOldSave.countDown() }.apply { start() }
                assertTrue(controller.reset())
            }
            assertTrue(oldSaveFinished.await(2, TimeUnit.SECONDS))
            onMain {
                assertEquals(listOf(1, 2), writtenRounds.toList())
                val saved = repository.load(controller.recentSessions.single().id)!!
                assertEquals(2, saved.roundNumber)
                assertEquals(listOf(attachmentFixture), saved.lastRoundAttachments)
                assertEquals(null, repository.loadActive())
                controller.destroy()
            }
        } finally { releaseOldSave.countDown(); release?.join() }
    }

    @Test fun failedSynchronousSaveDoesNotResetSessionOrDiscardAttachmentReferences() {
        val fail = AtomicBoolean(false)
        val repository = object : FakeSessionRepository() {
            override fun save(snapshot: ArenaSessionSnapshot) {
                if (fail.get()) throw java.io.IOException("disk unavailable")
                super.save(snapshot)
            }
        }
        val controller = onMain { ArenaSessionController(FakeGateway(), fastTiming, repository) }
        onMain { controller.startInitial("不能丢的问题", ArenaService.defaultMembers, attachments = listOf(attachmentFixture)) }
        awaitHistorySize(controller, 1)
        fail.set(true)
        onMain {
            assertFalse(controller.reset())
            assertEquals("不能丢的问题", controller.originalQuestion)
            assertEquals(SessionStage.READY, controller.stage)
            assertEquals(listOf(attachmentFixture), controller.lastRoundAttachments)
            assertTrue(controller.storageWarning?.contains("保存失败") == true)
            fail.set(false)
            assertTrue(controller.reset())
            assertEquals(SessionStage.IDLE, controller.stage)
            controller.destroy()
        }
    }

    @Test fun attachmentsReachEveryMemberAndPersistThroughHistoryAndColdStart() {
        val repository = FakeSessionRepository()
        val gateway = FakeGateway()
        val controller = onMain { ArenaSessionController(gateway, fastTiming, repository) }
        onMain { assertTrue(controller.startInitial("", ArenaService.defaultMembers, attachments = listOf(attachmentFixture))) }
        awaitHistorySize(controller, 1)
        onMain {
            assertEquals(AttachmentPromptPolicy.DEFAULT_QUESTION, controller.originalQuestion)
            assertEquals(3, gateway.attachmentRecords.size)
            assertTrue(gateway.attachmentRecords.all { it.third == listOf(attachmentFixture) })
            assertEquals(listOf(attachmentFixture), controller.history.single().attachments)
            controller.destroy()
            val encoded = ArenaSessionJson.encode(repository.loadActive()!!)
            repository.save(ArenaSessionJson.decode(encoded))
            val restored = ArenaSessionController(FakeGateway(), fastTiming, repository)
            assertEquals(listOf(attachmentFixture), restored.lastRoundAttachments)
            restored.destroy()
        }
    }

    @Test fun uploadFailureNeverDispatchesTextAndRetryUsesSavedOriginalAttachment() {
        val repository = FakeSessionRepository()
        val gateway = FakeGateway(attachmentFailures = setOf(ArenaService.DEEPSEEK))
        val controller = onMain { ArenaSessionController(gateway, fastTiming, repository) }
        onMain { controller.startInitial("附件原始问题", ArenaService.defaultMembers, attachments = listOf(attachmentFixture)) }
        awaitHistorySize(controller, 1)
        onMain {
            assertFalse(gateway.sentServices.contains(ArenaService.DEEPSEEK))
            assertEquals(ParticipantPhase.ERROR, controller.runs.getValue(ArenaService.DEEPSEEK).phase)
            assertEquals(2, controller.completedCount)
            controller.destroy()
        }
        val retryGateway = FakeGateway()
        val restored = onMain { ArenaSessionController(retryGateway, fastTiming, repository) }
        onMain { assertTrue(restored.retryFailed(resend = true)) }
        awaitProviderPhase(restored, ArenaService.DEEPSEEK, ParticipantPhase.COMPLETE)
        onMain {
            assertEquals(listOf(ArenaService.DEEPSEEK), retryGateway.sentServices.toList())
            assertEquals("附件原始问题", retryGateway.attachmentRecords.single().second)
            assertEquals(listOf(attachmentFixture), retryGateway.attachmentRecords.single().third)
            assertEquals(3, restored.completedCount)
            restored.destroy()
        }
    }

    @Test fun followupIterationAndDebateDeliverTheirOwnAttachmentsWithTheRightPrompt() {
        val gateway = FakeGateway()
        val controller = onMain { ArenaSessionController(gateway, fastTiming) }
        onMain { controller.startInitial("最初问题", ArenaService.defaultMembers) }
        awaitHistorySize(controller, 1)
        onMain { assertTrue(controller.startIteration(guidance = "只按这个新要求处理附件", attachments = listOf(attachmentFixture))) }
        awaitHistorySize(controller, 2)
        onMain {
            assertTrue(gateway.attachmentRecords.takeLast(3).all { it.second == "只按这个新要求处理附件" && it.third == listOf(attachmentFixture) })
            assertEquals(listOf(attachmentFixture), controller.history.last().attachments)
            assertTrue(controller.startDebate(attachments = listOf(attachmentFixture)))
        }
        awaitHistorySize(controller, 3)
        onMain {
            assertTrue(gateway.attachmentRecords.takeLast(3).all { it.second.contains(AttachmentPromptPolicy.DEFAULT_QUESTION) && it.third == listOf(attachmentFixture) })
            assertEquals(listOf(attachmentFixture), controller.history.last().attachments)
            controller.destroy()
        }
    }

    private fun twoFailedSnapshot(): ArenaSessionSnapshot {
        val original = recoverySnapshot("DeepSeek 原轮内容")
        return original.copy(
            runs = original.runs + (ArenaService.DOUBAO to ParticipantRun(ParticipantPhase.ERROR, "doubao-existing-request", detail = "应用重启，已停止等待")),
            lastRoundPrompts = original.lastRoundPrompts + (ArenaService.DOUBAO to "豆包原轮内容"),
            lastRoundAttachments = listOf(attachmentFixture),
        )
    }

    @Test fun failedBatchWaitsForEachRecoveryAndNeverResendsCompletedMember() {
        val repository = FakeSessionRepository()
        val snapshot = twoFailedSnapshot()
        repository.save(snapshot); repository.setActiveSession(snapshot.id)
        val gateway = FakeGateway(waitReads = mapOf(ArenaService.DEEPSEEK to 4))
        val controller = onMain { ArenaSessionController(gateway, fastTiming, repository) }
        onMain {
            assertTrue(controller.retryFailed(resend = true))
            assertEquals(listOf(ArenaService.DEEPSEEK), gateway.sentServices.toList())
            assertEquals(ParticipantPhase.COMPLETE, controller.runs.getValue(ArenaService.KIMI).phase)
        }
        awaitProviderPhase(controller, ArenaService.DOUBAO, ParticipantPhase.COMPLETE)
        onMain {
            assertEquals(listOf(ArenaService.DEEPSEEK, ArenaService.DOUBAO), gateway.sentServices.toList())
            assertTrue(gateway.attachmentRecords.all { it.third == listOf(attachmentFixture) })
            assertFalse(controller.isBusy)
            controller.destroy()
        }
    }

    @Test fun failedBatchExtractionKeepsRequestIdsWithoutSendingOrClearingAttachments() {
        val repository = FakeSessionRepository()
        val snapshot = twoFailedSnapshot()
        repository.save(snapshot); repository.setActiveSession(snapshot.id)
        val gateway = FakeGateway()
        val controller = onMain { ArenaSessionController(gateway, fastTiming, repository) }
        onMain { assertTrue(controller.retryFailed(resend = false)) }
        awaitProviderPhase(controller, ArenaService.DOUBAO, ParticipantPhase.COMPLETE)
        onMain {
            assertTrue(gateway.sentServices.isEmpty())
            assertEquals("failed_request", controller.runs.getValue(ArenaService.DEEPSEEK).requestId)
            assertEquals("doubao-existing-request", controller.runs.getValue(ArenaService.DOUBAO).requestId)
            assertEquals(listOf(attachmentFixture), controller.lastRoundAttachments)
            controller.destroy()
        }
    }

    @Test fun cancellingBatchPreventsQueuedRecoveryAndLateCallbacks() {
        val repository = FakeSessionRepository()
        val snapshot = twoFailedSnapshot().copy(lastRoundAttachments = emptyList())
        repository.save(snapshot); repository.setActiveSession(snapshot.id)
        val gateway = DeferredSendGateway()
        val controller = onMain { ArenaSessionController(gateway, fastTiming, repository) }
        onMain {
            assertTrue(controller.retryFailed(resend = true))
            controller.cancelCurrentRound()
            gateway.releaseAllCallbacks()
            assertEquals(listOf(ArenaService.DEEPSEEK), gateway.sentServices)
            assertFalse(controller.isBusy)
            assertEquals(ParticipantPhase.COMPLETE, controller.runs.getValue(ArenaService.KIMI).phase)
            controller.destroy()
        }
    }

    @Test fun summaryRetryKeepsExactPromptAndAttachmentsAndTargetsOnlyCaptain() {
        val gateway = FakeGateway(attachmentFailures = setOf(ArenaService.KIMI))
        val controller = onMain { ArenaSessionController(gateway, fastTiming) }
        onMain { controller.startInitial("总结测试", ArenaService.defaultMembers) }
        awaitHistorySize(controller, 1)
        onMain {
            val before = gateway.attachmentRecords.size
            assertTrue(controller.startSummary(listOf(ArenaService.KIMI), "结合附件回答", attachments = listOf(attachmentFixture)))
            assertEquals(ParticipantPhase.ERROR, controller.summary.phase)
            val originalPrompt = controller.summary.prompt
            assertTrue(controller.retrySummary())
            val attempts = gateway.attachmentRecords.drop(before)
            assertEquals(2, attempts.size)
            assertTrue(attempts.all { it.first == ArenaService.KIMI && it.second == originalPrompt && it.third == listOf(attachmentFixture) })
            controller.destroy()
        }
    }

    @Test
    fun parallelModeDispatchesAllBeforeResponsesFinish() {
        val gateway = FakeGateway()
        val controller = onMain { ArenaSessionController(gateway, fastTiming) }

        onMain {
            assertTrue(controller.startInitial("并行测试", ArenaService.defaultMembers, AnswerMode.PARALLEL))
            assertEquals(
                listOf(ArenaService.DEEPSEEK, ArenaService.KIMI, ArenaService.DOUBAO),
                gateway.sentServices.toList(),
            )
            assertEquals(SessionStage.INITIAL, controller.stage)
        }

        awaitHistorySize(controller, 1)
        onMain {
            assertEquals(SessionStage.READY, controller.stage)
            assertEquals(3, controller.completedCount)
            assertEquals(AnswerMode.PARALLEL, controller.history.single().answerMode)
            controller.destroy()
        }
    }

    @Test
    fun serialModeWaitsForEachStableAnswerBeforeNextSend() {
        val gateway = FakeGateway()
        val controller = onMain { ArenaSessionController(gateway, fastTiming.copy(pollIntervalMillis = 80)) }

        onMain {
            assertTrue(controller.startInitial("串行测试", ArenaService.defaultMembers, AnswerMode.SERIAL))
            assertEquals(listOf(ArenaService.DEEPSEEK), gateway.sentServices.toList())
        }

        awaitHistorySize(controller, 1)
        assertEquals(ArenaService.defaultMembers, gateway.sentServices.toList())
        onMain {
            assertEquals(3, controller.completedCount)
            assertEquals(AnswerMode.SERIAL, controller.history.single().answerMode)
            controller.destroy()
        }
    }

    @Test
    fun independentIterationSendsOnlyTheRequiredNewPromptThenDebateUsesLatestResults() {
        val gateway = FakeGateway()
        val controller = onMain { ArenaSessionController(gateway, fastTiming) }

        onMain { controller.startInitial("多轮测试", ArenaService.defaultMembers, AnswerMode.PARALLEL) }
        awaitHistorySize(controller, 1)
        val sentBeforeIteration = gateway.sentRecords.size
        val newPrompt = "只发送这一句，不要附加任何旧问题或旧回答"
        onMain { assertTrue(controller.startIteration(AnswerMode.PARALLEL, newPrompt)) }
        awaitHistorySize(controller, 2)
        val iterationRecords = gateway.sentRecords.drop(sentBeforeIteration)
        assertEquals(ArenaService.defaultMembers.size, iterationRecords.size)
        assertTrue(iterationRecords.all { (_, prompt) -> prompt == newPrompt })
        assertEquals(newPrompt, controller.history.last().guidance)

        onMain { assertTrue(controller.startDebate(AnswerMode.PARALLEL)) }
        awaitHistorySize(controller, 3)
        assertTrue(gateway.prompts.any { it.contains("观点讨论第 1 轮") })
        onMain {
            assertEquals(
                listOf(RoundKind.INITIAL, RoundKind.ITERATION, RoundKind.DEBATE),
                controller.history.map { it.kind },
            )
            assertEquals(3, controller.completedCount)
            controller.destroy()
        }
    }

    @Test
    fun independentIterationRejectsBlankPromptWithoutSendingAnything() {
        val gateway = FakeGateway()
        val controller = onMain { ArenaSessionController(gateway, fastTiming) }

        onMain { controller.startInitial("首轮问题", ArenaService.defaultMembers, AnswerMode.PARALLEL) }
        awaitHistorySize(controller, 1)
        val sentBefore = gateway.sentRecords.size

        onMain {
            assertFalse(controller.startIteration(AnswerMode.PARALLEL, "   "))
            assertEquals(sentBefore, gateway.sentRecords.size)
            assertEquals(1, controller.roundNumber)
            assertTrue(controller.sessionMessage.contains("请输入本轮独立迭代"))
            controller.destroy()
        }
    }

    @Test
    fun independentIterationDoesNotAutomaticallyResendToPreviouslyFailedProvider() {
        val gateway = FakeGateway(neverRespond = setOf(ArenaService.DOUBAO))
        val controller = onMain {
            ArenaSessionController(gateway, fastTiming.copy(responseTimeoutMillis = 180))
        }

        onMain { controller.startInitial("首轮含失败成员", ArenaService.defaultMembers, AnswerMode.PARALLEL) }
        awaitHistorySize(controller, 1)
        val sentBefore = gateway.sentRecords.size
        val prompt = "只发送给上一轮已完成的成员"

        onMain { assertTrue(controller.startIteration(AnswerMode.PARALLEL, prompt)) }
        awaitHistorySize(controller, 2)
        val iterationRecords = gateway.sentRecords.drop(sentBefore)

        onMain {
            assertEquals(2, iterationRecords.size)
            assertTrue(iterationRecords.all { (_, sentPrompt) -> sentPrompt == prompt })
            assertFalse(iterationRecords.any { (service, _) -> service == ArenaService.DOUBAO })
            controller.destroy()
        }
    }

    @Test
    fun summaryUsesOnePreferredJudgeAndKeepsRoundHistory() {
        val gateway = FakeGateway()
        val controller = onMain { ArenaSessionController(gateway, fastTiming) }

        onMain { controller.startInitial("总结测试", ArenaService.defaultMembers, AnswerMode.PARALLEL) }
        awaitHistorySize(controller, 1)
        onMain {
            assertTrue(controller.startSummary(ArenaService.defaultMembers, "只给三条建议"))
        }
        awaitSummaryComplete(controller)

        onMain {
            assertEquals(ParticipantPhase.COMPLETE, controller.summary.phase)
            assertEquals(ArenaService.DEEPSEEK, controller.summary.judge)
            assertEquals(1, controller.history.size)
            assertTrue(gateway.prompts.any { it.contains("多 AI 讨论的队长") })
            assertTrue(gateway.prompts.any { it.contains("只给三条建议") })
            controller.destroy()
        }
    }

    @Test
    fun qwenSummaryWaitsThroughSecurityChallengeAndCompletes() {
        val gateway = FakeGateway(
            securityChallengeReads = mapOf(ArenaService.QWEN to fastTiming.maxConsecutiveReadErrors + 2),
        )
        val services = listOf(ArenaService.QWEN, ArenaService.YUANBAO)
        val controller = onMain { ArenaSessionController(gateway, fastTiming) }

        onMain { assertTrue(controller.startInitial("千问总结验证码测试", services, AnswerMode.PARALLEL)) }
        awaitHistorySize(controller, 1)
        gateway.readCounts[ArenaService.QWEN] = 0
        onMain { assertTrue(controller.startSummary(services)) }
        awaitSummaryComplete(controller)

        onMain {
            assertEquals(ArenaService.QWEN, controller.summary.judge)
            assertEquals(ParticipantPhase.COMPLETE, controller.summary.phase)
            assertTrue(gateway.readCounts.getValue(ArenaService.QWEN) > fastTiming.maxConsecutiveReadErrors)
            controller.destroy()
        }
    }

    @Test
    fun oneSlowProviderDoesNotBlockOtherCompletedResults() {
        val gateway = FakeGateway(waitReads = mapOf(ArenaService.DOUBAO to 8))
        val controller = onMain { ArenaSessionController(gateway, fastTiming) }

        onMain { controller.startInitial("慢回答测试", ArenaService.defaultMembers, AnswerMode.PARALLEL) }
        awaitHistorySize(controller, 1)
        onMain {
            assertEquals(3, controller.completedCount)
            assertTrue(gateway.readCounts.getValue(ArenaService.DOUBAO) >= 10)
            controller.destroy()
        }
    }

    @Test
    fun qwenSecurityChallengeKeepsPollingPastReadErrorLimitUntilAnswerAppears() {
        val gateway = FakeGateway(
            securityChallengeReads = mapOf(ArenaService.QWEN to fastTiming.maxConsecutiveReadErrors + 2),
        )
        val services = listOf(ArenaService.QWEN, ArenaService.YUANBAO)
        val controller = onMain { ArenaSessionController(gateway, fastTiming) }

        onMain { assertTrue(controller.startInitial("验证码恢复测试", services, AnswerMode.PARALLEL)) }
        awaitHistorySize(controller, 1)

        onMain {
            assertEquals(ParticipantPhase.COMPLETE, controller.runs.getValue(ArenaService.QWEN).phase)
            assertTrue(gateway.readCounts.getValue(ArenaService.QWEN) > fastTiming.maxConsecutiveReadErrors)
            assertEquals("QWEN-answer", controller.runs.getValue(ArenaService.QWEN).response)
            controller.destroy()
        }
    }

    @Test
    fun persistentQwenSecurityChallengeEndsWithActionableTimeout() {
        val gateway = FakeGateway(
            securityChallengeReads = mapOf(ArenaService.QWEN to Int.MAX_VALUE),
        )
        val services = listOf(ArenaService.QWEN, ArenaService.YUANBAO)
        val controller = onMain {
            ArenaSessionController(gateway, fastTiming.copy(responseTimeoutMillis = 180))
        }

        onMain { assertTrue(controller.startInitial("验证码超时测试", services, AnswerMode.PARALLEL)) }
        awaitHistorySize(controller, 1)

        onMain {
            val qwen = controller.runs.getValue(ArenaService.QWEN)
            assertEquals(ParticipantPhase.ERROR, qwen.phase)
            assertTrue(qwen.detail.contains("安全验证等待超时"))
            assertTrue(qwen.detail.contains("重新提取"))
            controller.destroy()
        }
    }

    @Test
    fun timedOutProviderLeavesTwoUsableAnswersAndExplicitError() {
        val gateway = FakeGateway(neverRespond = setOf(ArenaService.DOUBAO))
        val controller = onMain {
            ArenaSessionController(
                gateway,
                fastTiming.copy(responseTimeoutMillis = 180),
            )
        }

        onMain { controller.startInitial("超时测试", ArenaService.defaultMembers, AnswerMode.PARALLEL) }
        awaitHistorySize(controller, 1)
        onMain {
            assertEquals(2, controller.completedCount)
            assertEquals(ParticipantPhase.ERROR, controller.runs.getValue(ArenaService.DOUBAO).phase)
            assertTrue(controller.runs.getValue(ArenaService.DOUBAO).detail.contains("超时"))
            assertEquals(SessionStage.READY, controller.stage)
            controller.destroy()
        }
    }

    @Test
    fun cancelInvalidatesLateSendCallback() {
        val gateway = DeferredSendGateway()
        val controller = onMain { ArenaSessionController(gateway, fastTiming) }

        onMain {
            controller.startInitial("取消测试", ArenaService.defaultMembers, AnswerMode.PARALLEL)
            assertTrue(controller.isBusy)
            controller.cancelCurrentRound()
            assertEquals(SessionStage.READY, controller.stage)
            assertEquals(1, controller.history.size)
        }

        onMain { gateway.releaseAllCallbacks() }
        Thread.sleep(100)
        onMain {
            assertEquals(SessionStage.READY, controller.stage)
            assertEquals(1, controller.history.size)
            assertFalse(controller.isBusy)
            controller.destroy()
        }
    }

    @Test
    fun tenRoundIterationKeepsOnlyLatestBoundedHistory() {
        val gateway = FakeGateway()
        val controller = onMain { ArenaSessionController(gateway, fastTiming) }

        onMain { controller.startInitial("十轮压力", ArenaService.defaultMembers, AnswerMode.PARALLEL) }
        awaitRoundNumber(controller, 1)
        for (round in 2..10) {
            onMain { assertTrue(controller.startIteration(AnswerMode.PARALLEL, "第 $round 轮独立问题")) }
            awaitRoundNumber(controller, round)
        }

        onMain {
            assertEquals(10, controller.roundNumber)
            assertEquals(ArenaLimits.MAX_HISTORY_ROUNDS, controller.history.size)
            assertEquals(3, controller.history.first().number)
            assertEquals(10, controller.history.last().number)
            controller.destroy()
        }
    }

    @Test
    fun completedSessionPersistsAndRestoresWithRecentIndex() {
        val repository = FakeSessionRepository()
        val first = onMain { ArenaSessionController(FakeGateway(), fastTiming, repository) }

        onMain { first.startInitial("本地恢复测试", ArenaService.defaultMembers, AnswerMode.PARALLEL) }
        awaitHistorySize(first, 1)
        onMain { first.destroy() }

        val restored = onMain { ArenaSessionController(FakeGateway(), fastTiming, repository) }
        onMain {
            assertEquals("本地恢复测试", restored.originalQuestion)
            assertEquals(SessionStage.READY, restored.stage)
            assertEquals(3, restored.completedCount)
            assertEquals(1, restored.history.size)
            assertEquals(ArenaService.defaultMembers, restored.sessionServices)
            assertTrue(restored.recentSessions.any { it.title == "本地恢复测试" })
            restored.destroy()
        }
    }

    @Test
    fun interruptedSessionRestoresPartialTextAsExplicitError() {
        val repository = FakeSessionRepository()
        val id = repository.newSessionId()
        repository.save(
            ArenaSessionSnapshot(
                id = id,
                originalQuestion = "中断恢复",
                roundNumber = 1,
                currentRoundKind = RoundKind.INITIAL,
                currentAnswerMode = AnswerMode.PARALLEL,
                services = ArenaService.defaultMembers,
                runs = mapOf(
                    ArenaService.DEEPSEEK to ParticipantRun(
                        phase = ParticipantPhase.STREAMING,
                        requestId = "request",
                        response = "已保留的部分回答",
                    ),
                    ArenaService.DOUBAO to ParticipantRun(ParticipantPhase.COMPLETE, response = "豆包答案"),
                    ArenaService.KIMI to ParticipantRun(ParticipantPhase.COMPLETE, response = "Kimi答案"),
                ),
                history = emptyList(),
                summary = DiscussionSummary(phase = ParticipantPhase.STREAMING, text = "部分总结"),
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
        repository.setActiveSession(id)

        val restored = onMain { ArenaSessionController(FakeGateway(), fastTiming, repository) }
        onMain {
            assertEquals(ParticipantPhase.ERROR, restored.runs.getValue(ArenaService.DEEPSEEK).phase)
            assertEquals("已保留的部分回答", restored.runs.getValue(ArenaService.DEEPSEEK).response)
            assertEquals(ParticipantPhase.ERROR, restored.summary.phase)
            assertEquals(2, restored.completedCount)
            assertEquals(SessionStage.READY, restored.stage)
            restored.destroy()
        }
    }

    @Test
    fun sessionJsonRoundTripKeepsChineseResultsAndSummary() {
        val snapshot = ArenaSessionSnapshot(
            id = "session_roundtrip",
            originalQuestion = "中文问题",
            roundNumber = 1,
            currentRoundKind = RoundKind.INITIAL,
            currentAnswerMode = AnswerMode.SERIAL,
            services = listOf(ArenaService.DEEPSEEK, ArenaService.DOUBAO),
            runs = mapOf(
                ArenaService.DEEPSEEK to ParticipantRun(ParticipantPhase.COMPLETE, response = "中文回答"),
            ),
            history = emptyList(),
            summary = DiscussionSummary(
                phase = ParticipantPhase.COMPLETE,
                judge = ArenaService.DEEPSEEK,
                text = "总结内容",
            ),
            lastRoundPrompts = mapOf(ArenaService.DEEPSEEK to "重发 prompt"),
            updatedAtMillis = 123L,
        )

        val restored = ArenaSessionJson.decode(ArenaSessionJson.encode(snapshot))

        assertEquals(snapshot.originalQuestion, restored.originalQuestion)
        assertEquals("中文回答", restored.runs.getValue(ArenaService.DEEPSEEK).response)
        assertEquals("总结内容", restored.summary.text)
        assertEquals(AnswerMode.SERIAL, restored.currentAnswerMode)
        assertEquals("重发 prompt", restored.lastRoundPrompts.getValue(ArenaService.DEEPSEEK))
    }

    @Test
    fun fileStoreCapsHistoryAndRecoversFromCorruptIndex() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val suffix = System.nanoTime().toString()
        val directoryName = "arena_sessions_test_$suffix"
        val preferencesName = "arena_sessions_pointer_test_$suffix"
        val store = ArenaSessionStore(context, directoryName, preferencesName)
        val root = File(context.filesDir, directoryName)
        try {
            repeat(22) { index ->
                val id = "session_file_$index"
                store.save(
                    ArenaSessionSnapshot(
                        id = id,
                        originalQuestion = "历史问题$index",
                        roundNumber = index,
                        currentRoundKind = RoundKind.INITIAL,
                        currentAnswerMode = AnswerMode.PARALLEL,
                        services = ArenaService.defaultMembers,
                        runs = emptyMap(),
                        history = emptyList(),
                        summary = DiscussionSummary(),
                        updatedAtMillis = index.toLong(),
                    ),
                )
            }

            assertEquals(20, store.listRecent(limit = 99).size)
            assertEquals("历史问题21", store.listRecent().first().title)
            File(root, "index.json").writeText("{broken", Charsets.UTF_8)
            val recovered = store.listRecent(limit = 99)
            assertEquals(20, recovered.size)
            assertTrue(recovered.any { it.title == "历史问题21" })
        } finally {
            root.deleteRecursively()
            context.getSharedPreferences(preferencesName, android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun retrySendUpdatesOnlyFailedProviderAndLatestRound() {
        val repository = FakeSessionRepository()
        val snapshot = recoverySnapshot(lastPrompt = "原始重发 prompt")
        repository.save(snapshot)
        repository.setActiveSession(snapshot.id)
        val gateway = FakeGateway()
        val controller = onMain { ArenaSessionController(gateway, fastTiming, repository) }

        onMain { assertTrue(controller.retrySend(ArenaService.DEEPSEEK)) }
        awaitProviderPhase(controller, ArenaService.DEEPSEEK, ParticipantPhase.COMPLETE)

        onMain {
            assertTrue(gateway.prompts.contains("原始重发 prompt"))
            assertEquals("DEEPSEEK-answer", controller.runs.getValue(ArenaService.DEEPSEEK).response)
            assertEquals(
                ParticipantPhase.COMPLETE,
                controller.history.last().results.getValue(ArenaService.DEEPSEEK).phase,
            )
            assertEquals(1, controller.history.size)
            controller.destroy()
        }
    }

    @Test
    fun reextractDoesNotSendAndSkipRemainsExplicit() {
        val repository = FakeSessionRepository()
        val snapshot = recoverySnapshot(lastPrompt = "prompt")
        repository.save(snapshot)
        repository.setActiveSession(snapshot.id)
        val gateway = FakeGateway()
        val controller = onMain { ArenaSessionController(gateway, fastTiming, repository) }

        onMain { assertTrue(controller.retryExtraction(ArenaService.DEEPSEEK)) }
        awaitProviderPhase(controller, ArenaService.DEEPSEEK, ParticipantPhase.COMPLETE)
        onMain {
            assertTrue(gateway.sentServices.isEmpty())
            controller.runs[ArenaService.DEEPSEEK] = controller.runs.getValue(ArenaService.DEEPSEEK).copy(
                phase = ParticipantPhase.ERROR,
                detail = "模拟再次失败",
            )
            assertTrue(controller.skipService(ArenaService.DEEPSEEK))
            assertEquals("已跳过本轮", controller.runs.getValue(ArenaService.DEEPSEEK).detail)
            controller.destroy()
        }
    }

    @Test
    fun qwenReextractSurvivesChallengeAndRecoversExistingAnswerWithoutResend() {
        val repository = FakeSessionRepository()
        val snapshot = qwenRecoverySnapshot()
        repository.save(snapshot)
        repository.setActiveSession(snapshot.id)
        val gateway = FakeGateway(
            securityChallengeReads = mapOf(ArenaService.QWEN to fastTiming.maxConsecutiveReadErrors + 2),
        )
        val controller = onMain { ArenaSessionController(gateway, fastTiming, repository) }

        onMain { assertTrue(controller.retryExtraction(ArenaService.QWEN)) }
        awaitProviderPhase(controller, ArenaService.QWEN, ParticipantPhase.COMPLETE)

        onMain {
            assertTrue(gateway.sentServices.isEmpty())
            assertTrue(gateway.readCounts.getValue(ArenaService.QWEN) > fastTiming.maxConsecutiveReadErrors)
            assertEquals("QWEN-answer", controller.runs.getValue(ArenaService.QWEN).response)
            assertEquals(
                ParticipantPhase.COMPLETE,
                controller.history.last().results.getValue(ArenaService.QWEN).phase,
            )
            controller.destroy()
        }
    }

    @Test
    fun qwenLongDebateCompressesQuotedAnswersAndStaysWithinBudget() {
        val gateway = FakeGateway(responseTextLength = 6_000)
        val services = listOf(
            ArenaService.DEEPSEEK,
            ArenaService.DOUBAO,
            ArenaService.KIMI,
            ArenaService.QWEN,
        )
        val controller = onMain { ArenaSessionController(gateway, fastTiming) }

        onMain { assertTrue(controller.startInitial("原".repeat(5_000), services, AnswerMode.PARALLEL)) }
        awaitHistorySize(controller, 1)
        onMain { assertTrue(controller.startDebate(AnswerMode.PARALLEL)) }
        awaitHistorySize(controller, 2)

        val qwenDebatePrompt = gateway.sentRecords.last { (service, prompt) ->
            service == ArenaService.QWEN && prompt.contains("观点讨论")
        }.second
        onMain {
            assertTrue(qwenDebatePrompt.length <= PromptBudgetPolicy.QWEN_BUDGET)
            assertTrue(controller.sessionMessage.contains("已压缩"))
            controller.destroy()
        }
    }

    @Test
    fun qwenDebateBlocksWhenOriginalQuestionAloneExceedsBudget() {
        val gateway = FakeGateway(responseTextLength = 500)
        val services = listOf(ArenaService.DEEPSEEK, ArenaService.QWEN)
        val controller = onMain { ArenaSessionController(gateway, fastTiming) }

        onMain { assertTrue(controller.startInitial("原".repeat(8_400), services, AnswerMode.PARALLEL)) }
        awaitHistorySize(controller, 1)
        onMain {
            assertFalse(controller.startDebate(AnswerMode.PARALLEL))
            assertTrue(controller.sessionMessage.contains("上下文超过"))
            assertEquals(1, controller.history.size)
            controller.destroy()
        }
    }

    private fun awaitHistorySize(
        controller: ArenaSessionController,
        expectedSize: Int,
        timeoutMillis: Long = 5_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (onMain { controller.history.size >= expectedSize }) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for history size $expectedSize")
    }

    private fun awaitRoundNumber(
        controller: ArenaSessionController,
        expectedRound: Int,
        timeoutMillis: Long = 5_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (onMain { controller.roundNumber == expectedRound && controller.stage == SessionStage.READY }) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for completed round $expectedRound")
    }

    private fun awaitSummaryComplete(
        controller: ArenaSessionController,
        timeoutMillis: Long = 5_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (onMain { controller.summary.phase == ParticipantPhase.COMPLETE }) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for discussion summary")
    }

    private fun awaitProviderPhase(
        controller: ArenaSessionController,
        service: ArenaService,
        expected: ParticipantPhase,
        timeoutMillis: Long = 5_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (onMain { controller.runs.getValue(service).phase == expected }) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for $service phase $expected")
    }

    private fun recoverySnapshot(lastPrompt: String): ArenaSessionSnapshot {
        val error = ParticipantRun(
            phase = ParticipantPhase.ERROR,
            requestId = "failed_request",
            detail = "发送失败",
        )
        val results = mapOf(
            ArenaService.DEEPSEEK to error,
            ArenaService.DOUBAO to ParticipantRun(ParticipantPhase.COMPLETE, response = "豆包完成"),
            ArenaService.KIMI to ParticipantRun(ParticipantPhase.COMPLETE, response = "Kimi完成"),
        )
        return ArenaSessionSnapshot(
            id = "session_recovery_${System.nanoTime()}",
            originalQuestion = "单家补救测试",
            roundNumber = 1,
            currentRoundKind = RoundKind.INITIAL,
            currentAnswerMode = AnswerMode.PARALLEL,
            services = ArenaService.defaultMembers,
            runs = results,
            history = listOf(
                RoundRecord(
                    number = 1,
                    kind = RoundKind.INITIAL,
                    answerMode = AnswerMode.PARALLEL,
                    guidance = "",
                    results = results,
                    startedAtMillis = 1L,
                    finishedAtMillis = 2L,
                ),
            ),
            summary = DiscussionSummary(),
            lastRoundPrompts = mapOf(ArenaService.DEEPSEEK to lastPrompt),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun qwenRecoverySnapshot(): ArenaSessionSnapshot {
        val qwenError = ParticipantRun(
            phase = ParticipantPhase.ERROR,
            requestId = "qwen_failed_request",
            detail = "千问触发安全验证",
        )
        val results = mapOf(
            ArenaService.QWEN to qwenError,
            ArenaService.YUANBAO to ParticipantRun(
                phase = ParticipantPhase.COMPLETE,
                response = "元宝完成",
            ),
        )
        return ArenaSessionSnapshot(
            id = "session_qwen_recovery_${System.nanoTime()}",
            originalQuestion = "千问重新提取测试",
            roundNumber = 1,
            currentRoundKind = RoundKind.INITIAL,
            currentAnswerMode = AnswerMode.PARALLEL,
            services = listOf(ArenaService.QWEN, ArenaService.YUANBAO),
            runs = results,
            history = listOf(
                RoundRecord(
                    number = 1,
                    kind = RoundKind.INITIAL,
                    answerMode = AnswerMode.PARALLEL,
                    guidance = "",
                    results = results,
                    startedAtMillis = 1L,
                    finishedAtMillis = 2L,
                ),
            ),
            summary = DiscussionSummary(),
            lastRoundPrompts = mapOf(ArenaService.QWEN to "原始千问 Prompt"),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun <T> onMain(block: () -> T): T {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                result.set(block())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw AssertionError("Main-thread block failed", it) }
        return result.get()
    }

    private class FakeGateway(
        private val waitReads: Map<ArenaService, Int> = emptyMap(),
        private val neverRespond: Set<ArenaService> = emptySet(),
        private val responseTextLength: Int = 0,
        private val securityChallengeReads: Map<ArenaService, Int> = emptyMap(),
        private val attachmentFailures: Set<ArenaService> = emptySet(),
    ) : ArenaGateway {
        val sentServices = CopyOnWriteArrayList<ArenaService>()
        val prompts = CopyOnWriteArrayList<String>()
        val sentRecords = CopyOnWriteArrayList<Pair<ArenaService, String>>()
        val readCounts = ConcurrentHashMap<ArenaService, Int>()
        val attachmentRecords = CopyOnWriteArrayList<Triple<ArenaService, String, List<ArenaAttachment>>>()

        override fun sendPromptWithAttachments(service: ArenaService, prompt: String, requestId: String,
            attachments: List<ArenaAttachment>, callback: (SendOutcome) -> Unit) {
            attachmentRecords += Triple(service, prompt, attachments.toList())
            if (attachments.isNotEmpty() && service in attachmentFailures) {
                callback(SendOutcome(false, requestId, "附件上传未完成"))
            } else sendPrompt(service, prompt, requestId, callback)
        }


        override fun sendPrompt(
            service: ArenaService,
            prompt: String,
            requestId: String,
            callback: (SendOutcome) -> Unit,
        ) {
            sentServices += service
            prompts += prompt
            sentRecords += service to prompt
            callback(SendOutcome(true, requestId, "已发送"))
        }

        override fun readResponse(
            service: ArenaService,
            requestId: String,
            callback: (ResponseSnapshot) -> Unit,
        ) {
            val count = readCounts.merge(service, 1, Int::plus) ?: 1
            if (count <= securityChallengeReads.getOrDefault(service, 0)) {
                callback(
                    ResponseSnapshot(
                        found = false,
                        text = "",
                        streaming = false,
                        detail = "千问触发安全验证，请打开千问网页完成验证后重试",
                        securityChallenge = true,
                    ),
                )
            } else if (service in neverRespond || count <= waitReads.getOrDefault(service, 0)) {
                callback(ResponseSnapshot(found = false, text = "", streaming = false))
            } else {
                callback(
                    ResponseSnapshot(
                        found = true,
                        text = if (responseTextLength > 0) {
                            "${service.name}-" + "答".repeat(responseTextLength)
                        } else {
                            "${service.name}-answer"
                        },
                        streaming = false,
                    ),
                )
            }
        }
    }

    private class DeferredSendGateway : ArenaGateway {
        private val callbacks = mutableListOf<Pair<String, (SendOutcome) -> Unit>>()
        val sentServices = mutableListOf<ArenaService>()

        override fun sendPrompt(
            service: ArenaService,
            prompt: String,
            requestId: String,
            callback: (SendOutcome) -> Unit,
        ) {
            sentServices += service
            callbacks += requestId to callback
        }

        override fun readResponse(
            service: ArenaService,
            requestId: String,
            callback: (ResponseSnapshot) -> Unit,
        ) = Unit

        fun releaseAllCallbacks() {
            callbacks.toList().forEach { (requestId, callback) ->
                callback(SendOutcome(true, requestId, "late"))
            }
            callbacks.clear()
        }
    }

    private open class FakeSessionRepository : ArenaSessionRepository {
        private val snapshots = linkedMapOf<String, ArenaSessionSnapshot>()
        private var activeId: String? = null
        private var sequence = 0

        override fun newSessionId(): String = "session_test_${++sequence}"

        override fun save(snapshot: ArenaSessionSnapshot) {
            snapshots[snapshot.id] = snapshot
        }

        override fun load(id: String): ArenaSessionSnapshot? = snapshots[id]

        override fun loadActive(): ArenaSessionSnapshot? = activeId?.let(snapshots::get)

        override fun setActiveSession(id: String?) {
            activeId = id
        }

        override fun forget(id: String) {
            snapshots.remove(id)
            if (activeId == id) activeId = null
        }

        override fun listRecent(limit: Int): List<RecentArenaSession> = snapshots.values
            .sortedByDescending { it.updatedAtMillis }
            .take(limit)
            .map { snapshot ->
                RecentArenaSession(
                    id = snapshot.id,
                    title = snapshot.originalQuestion,
                    updatedAtMillis = snapshot.updatedAtMillis,
                    roundCount = snapshot.roundNumber,
                    serviceCount = snapshot.services.size,
                )
            }
    }
}
