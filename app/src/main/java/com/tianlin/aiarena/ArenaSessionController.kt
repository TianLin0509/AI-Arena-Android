package com.tianlin.aiarena

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class ArenaSessionController(
    private val pool: ArenaGateway,
    private val timing: ControllerTiming = ControllerTiming(),
    private val sessionRepository: ArenaSessionRepository? = null,
) {
    val runs = mutableStateMapOf<ArenaService, ParticipantRun>().apply {
        ArenaService.entries.forEach { service -> put(service, ParticipantRun()) }
    }
    val history = mutableStateListOf<RoundRecord>()
    val recentSessions = mutableStateListOf<RecentArenaSession>()

    var stage by mutableStateOf(SessionStage.IDLE)
        private set

    var originalQuestion by mutableStateOf("")
        private set

    var sessionMessage by mutableStateOf("等待开始")
        private set

    var currentRoundKind by mutableStateOf<RoundKind?>(null)
        private set

    var currentAnswerMode by mutableStateOf(AnswerMode.PARALLEL)
        private set

    var roundNumber by mutableIntStateOf(0)
        private set

    var summary by mutableStateOf(DiscussionSummary())
        private set

    var sessionServices by mutableStateOf(ArenaService.defaultMembers)
        private set

    var storageWarning by mutableStateOf<String?>(null)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val persistenceHandler = Handler(Looper.getMainLooper())
    private val pollStates = mutableMapOf<ArenaService, PollState>()
    private var activeExecution: RoundExecution? = null
    private var summaryExecution: SummaryExecution? = null
    private var recoveryExecution: RecoveryExecution? = null
    private var sessionEpoch = 0L
    private var requestSequence = 0L
    private var sessionId = ""
    private var lastRoundPrompts: Map<ArenaService, String> = emptyMap()
    private var currentRoundContextNotice = ""
    private val persistRunnable = Runnable { persistNow() }

    init {
        refreshRecentSessions()
        sessionRepository?.loadActive()?.let { snapshot -> applySnapshot(snapshot, recovered = true) }
    }

    val isBusy: Boolean
        get() = stage == SessionStage.INITIAL ||
            stage == SessionStage.ITERATION ||
            stage == SessionStage.DEBATE ||
            summary.phase == ParticipantPhase.SENDING ||
            summary.phase == ParticipantPhase.WAITING ||
            summary.phase == ParticipantPhase.STREAMING ||
            recoveryExecution != null

    val completedCount: Int
        get() = runs.values.count { it.phase == ParticipantPhase.COMPLETE }

    fun startInitial(
        question: String,
        services: List<ArenaService>,
        answerMode: AnswerMode = AnswerMode.PARALLEL,
    ): Boolean {
        val normalizedQuestion = question.trim()
        if (isBusy || stage != SessionStage.IDLE || services.distinct().size < 2) return false
        if (!QuestionPolicy.isValid(normalizedQuestion)) {
            sessionMessage = if (normalizedQuestion.isEmpty()) {
                "请输入问题"
            } else {
                "问题超过 ${ArenaLimits.MAX_QUESTION_CHARS} 字，请缩短后重试"
            }
            return false
        }

        val selected = ArenaService.entries.filter { it in services.distinct() }
        sessionId = sessionRepository?.newSessionId().orEmpty()
        sessionServices = selected
        sessionRepository?.setActiveSession(sessionId.ifBlank { null })
        storageWarning = null
        history.clear()
        summary = DiscussionSummary()
        lastRoundPrompts = emptyMap()
        currentRoundContextNotice = ""
        roundNumber = 0
        originalQuestion = normalizedQuestion
        return startRound(
            kind = RoundKind.INITIAL,
            services = selected,
            prompts = selected.associateWith { normalizedQuestion },
            answerMode = answerMode,
            guidance = "",
        )
    }

    fun startIteration(
        answerMode: AnswerMode = currentAnswerMode,
        guidance: String = "",
    ): Boolean {
        if (isBusy || stage != SessionStage.READY) return false
        val newPrompt = guidance.trim()
        if (newPrompt.isBlank()) {
            sessionMessage = "请输入本轮独立迭代的 Prompt"
            return false
        }
        if (newPrompt.length > ArenaLimits.MAX_GUIDANCE_CHARS) {
            sessionMessage = "本轮 Prompt 超过 ${ArenaLimits.MAX_GUIDANCE_CHARS} 字，请缩短后重试"
            return false
        }
        val completed = completedResponses()
        val services = ArenaService.entries.filter { it in completed.keys }
        if (services.size < ArenaService.MIN_MEMBERS) return false
        val prompts = services.associateWith { newPrompt }
        return startRound(RoundKind.ITERATION, services, prompts, answerMode, newPrompt)
    }

    fun startDebate(
        answerMode: AnswerMode = currentAnswerMode,
        guidance: String = "",
    ): Boolean {
        if (isBusy || stage != SessionStage.READY) return false
        val responses = completedResponses()
        if (responses.size < 2) return false
        val debateIndex = history.count { it.kind == RoundKind.DEBATE } + 1
        val services = ArenaService.entries.filter { it in responses.keys }
        val prompts = linkedMapOf<ArenaService, String>()
        var compressedCount = 0
        services.forEach { target ->
            val budgeted = PromptBudgetPolicy.fit(target) { quoteLimit ->
                DebatePromptBuilder.build(
                    originalQuestion = originalQuestion,
                    target = target,
                    responses = responses,
                    debateIndex = debateIndex,
                    guidance = guidance.take(ArenaLimits.MAX_GUIDANCE_CHARS),
                    quoteLimit = quoteLimit,
                )
            } ?: run {
                sessionMessage = "${target.displayName} 上下文超过 ${PromptBudgetPolicy.budgetFor(target)} 字，请缩短原问题或开始新问题"
                return false
            }
            prompts[target] = budgeted.text
            if (budgeted.compressed) compressedCount += 1
        }
        val started = startRound(RoundKind.DEBATE, services, prompts, answerMode, guidance)
        if (started && compressedCount > 0) {
            currentRoundContextNotice = "已压缩 $compressedCount 家的引用回答"
            sessionMessage += " · $currentRoundContextNotice"
        }
        return started
    }

    fun startSummary(
        preferredServices: List<ArenaService>,
        customInstruction: String = "",
    ): Boolean {
        if (isBusy || stage != SessionStage.READY) return false
        val responses = completedResponses()
        if (responses.size < 2) return false
        val judge = preferredServices.firstOrNull { it in responses.keys }
            ?: ArenaService.entries.firstOrNull { it in responses.keys }
            ?: return false
        val budgetedPrompt = PromptBudgetPolicy.fit(judge) { quoteLimit ->
            DiscussionSummaryPromptBuilder.build(
                originalQuestion = originalQuestion,
                history = history.toList(),
                responses = responses,
                customInstruction = customInstruction,
                quoteLimit = quoteLimit,
            )
        } ?: run {
            sessionMessage = "总结上下文超过 ${PromptBudgetPolicy.budgetFor(judge)} 字，请缩短原问题"
            return false
        }
        val prompt = budgetedPrompt.text

        sessionEpoch += 1
        handler.removeCallbacksAndMessages(null)
        val requestId = "summary_${++requestSequence}_${judge.name.lowercase()}_${System.currentTimeMillis()}"
        val execution = SummaryExecution(
            epoch = sessionEpoch,
            judge = judge,
            requestId = requestId,
            startedAtElapsedMillis = SystemClock.elapsedRealtime(),
        )
        summaryExecution = execution
        summary = DiscussionSummary(
            phase = ParticipantPhase.SENDING,
            judge = judge,
            requestId = requestId,
            detail = "正在请 ${judge.displayName} 总结",
        )
        sessionMessage = "正在生成讨论总结" + if (budgetedPrompt.compressed) " · 已压缩引用回答" else ""
        schedulePersist()
        pool.sendPrompt(judge, prompt, requestId) { outcome ->
            if (!isSummaryActive(execution)) return@sendPrompt
            if (outcome.success) {
                summary = summary.copy(phase = ParticipantPhase.WAITING, detail = "等待总结回答")
                schedulePersist()
                pollSummary(execution)
            } else {
                summary = summary.copy(phase = ParticipantPhase.ERROR, detail = outcome.detail)
                summaryExecution = null
                sessionMessage = "讨论总结失败"
                schedulePersist()
            }
        }
        return true
    }

    fun retrySend(service: ArenaService): Boolean {
        if (isBusy || stage != SessionStage.READY || service !in sessionServices) return false
        val prompt = lastRoundPrompts[service]
        if (prompt.isNullOrBlank()) {
            sessionMessage = "缺少 ${service.displayName} 的原始发送内容，可重新开始问题"
            return false
        }
        return startRecovery(service, prompt, resend = true)
    }

    fun retryExtraction(service: ArenaService): Boolean {
        if (isBusy || stage != SessionStage.READY || service !in sessionServices) return false
        val run = runs[service] ?: return false
        if (run.requestId.isBlank()) {
            sessionMessage = "${service.displayName} 没有可重新提取的请求"
            return false
        }
        return startRecovery(service, prompt = null, resend = false)
    }

    fun skipService(service: ArenaService): Boolean {
        if (isBusy || stage != SessionStage.READY || service !in sessionServices) return false
        val current = runs[service] ?: return false
        val skipped = current.copy(phase = ParticipantPhase.ERROR, detail = "已跳过本轮")
        runs[service] = skipped
        updateLatestRoundResult(service, skipped)
        sessionMessage = "已跳过 ${service.displayName}，其他结果仍保留"
        schedulePersist(immediate = true)
        return true
    }

    fun cancelCurrentRound() {
        val activeSummary = summaryExecution
        if (activeSummary != null && isSummaryActive(activeSummary)) {
            handler.removeCallbacksAndMessages(null)
            summary = summary.copy(phase = ParticipantPhase.ERROR, detail = "已停止总结")
            summaryExecution = null
            sessionEpoch += 1
            sessionMessage = "已停止讨论总结"
            schedulePersist()
            return
        }
        val activeRecovery = recoveryExecution
        if (activeRecovery != null && isRecoveryActive(activeRecovery)) {
            handler.removeCallbacksAndMessages(null)
            finishRecovery(
                activeRecovery,
                runs.getValue(activeRecovery.service).copy(
                    phase = ParticipantPhase.ERROR,
                    detail = "已停止单家补救；网页可能仍在生成",
                ),
            )
            sessionEpoch += 1
            return
        }
        val execution = activeExecution ?: return
        if (!isBusy) return
        handler.removeCallbacksAndMessages(null)
        pollStates.clear()
        execution.services.forEach { service ->
            val run = runs.getValue(service)
            if (!run.phase.isTerminal()) {
                runs[service] = run.copy(
                    phase = ParticipantPhase.ERROR,
                    detail = "已停止等待；网页可能仍在生成",
                )
            }
        }
        execution.dispatchComplete = true
        finishRound(execution, forcedMessage = "已停止本轮，已保留现有结果")
        sessionEpoch += 1
    }

    fun reset() {
        persistNow()
        persistenceHandler.removeCallbacksAndMessages(null)
        sessionRepository?.setActiveSession(null)
        sessionId = ""
        sessionEpoch += 1
        handler.removeCallbacksAndMessages(null)
        pollStates.clear()
        activeExecution = null
        summaryExecution = null
        recoveryExecution = null
        stage = SessionStage.IDLE
        currentRoundKind = null
        currentAnswerMode = AnswerMode.PARALLEL
        roundNumber = 0
        originalQuestion = ""
        sessionMessage = "等待开始"
        history.clear()
        summary = DiscussionSummary()
        lastRoundPrompts = emptyMap()
        currentRoundContextNotice = ""
        sessionServices = ArenaService.defaultMembers
        storageWarning = null
        ArenaService.entries.forEach { service -> runs[service] = ParticipantRun() }
        refreshRecentSessions()
    }

    fun destroy() {
        persistNow()
        persistenceHandler.removeCallbacksAndMessages(null)
        sessionEpoch += 1
        handler.removeCallbacksAndMessages(null)
        pollStates.clear()
        activeExecution = null
        summaryExecution = null
        recoveryExecution = null
    }

    fun restoreSession(id: String): Boolean {
        if (isBusy) return false
        val snapshot = sessionRepository?.load(id) ?: return false
        persistNow()
        applySnapshot(snapshot, recovered = false)
        sessionRepository.setActiveSession(snapshot.id)
        schedulePersist()
        return true
    }

    private fun startRound(
        kind: RoundKind,
        services: List<ArenaService>,
        prompts: Map<ArenaService, String>,
        answerMode: AnswerMode,
        guidance: String,
    ): Boolean {
        if (isBusy || services.size < 2 || services.any { prompts[it].isNullOrBlank() }) return false

        sessionEpoch += 1
        handler.removeCallbacksAndMessages(null)
        pollStates.clear()
        summary = DiscussionSummary()
        currentRoundContextNotice = ""
        roundNumber += 1
        currentRoundKind = kind
        currentAnswerMode = answerMode
        stage = when (kind) {
            RoundKind.INITIAL -> SessionStage.INITIAL
            RoundKind.ITERATION -> SessionStage.ITERATION
            RoundKind.DEBATE -> SessionStage.DEBATE
        }
        sessionMessage = when (answerMode) {
            AnswerMode.PARALLEL -> "正在快速发送第 $roundNumber 轮，${services.size} 家将并行生成"
            AnswerMode.SERIAL -> "正在串行执行第 $roundNumber 轮"
        }

        ArenaService.entries.forEach { service ->
            runs[service] = if (service in services) {
                ParticipantRun(ParticipantPhase.IDLE, detail = "等待发送")
            } else {
                ParticipantRun(ParticipantPhase.IDLE, detail = "本轮未参与")
            }
        }

        val dispatchOrder = when (answerMode) {
            AnswerMode.PARALLEL -> services.sortedBy { if (it == ArenaService.DOUBAO) 1 else 0 }
            AnswerMode.SERIAL -> services
        }
        val execution = RoundExecution(
            epoch = sessionEpoch,
            number = roundNumber,
            kind = kind,
            answerMode = answerMode,
            services = services,
            dispatchOrder = dispatchOrder,
            prompts = prompts,
            guidance = guidance.take(ArenaLimits.MAX_GUIDANCE_CHARS),
            startedAtMillis = System.currentTimeMillis(),
        )
        lastRoundPrompts = prompts.mapValues { (_, prompt) -> prompt.take(ArenaLimits.MAX_STORED_PROMPT_CHARS) }
        activeExecution = execution
        schedulePersist()
        when (answerMode) {
            AnswerMode.PARALLEL -> dispatchParallel(execution)
            AnswerMode.SERIAL -> dispatchSerialNext(execution)
        }
        return true
    }

    private fun startRecovery(
        service: ArenaService,
        prompt: String?,
        resend: Boolean,
    ): Boolean {
        sessionEpoch += 1
        handler.removeCallbacksAndMessages(null)
        val previous = runs[service] ?: ParticipantRun()
        val requestId = if (resend) {
            "retry_${++requestSequence}_${service.name.lowercase()}_${System.currentTimeMillis()}"
        } else {
            previous.requestId
        }
        val execution = RecoveryExecution(
            epoch = sessionEpoch,
            service = service,
            requestId = requestId,
            resend = resend,
            startedAtElapsedMillis = SystemClock.elapsedRealtime(),
        )
        recoveryExecution = execution
        runs[service] = previous.copy(
            phase = if (resend) ParticipantPhase.SENDING else ParticipantPhase.WAITING,
            requestId = requestId,
            response = if (resend) "" else previous.response,
            detail = if (resend) "正在重发" else "正在重新提取",
        )
        sessionMessage = if (resend) {
            "正在单独重发给 ${service.displayName}"
        } else {
            "正在重新提取 ${service.displayName} 的回答"
        }
        schedulePersist()
        if (!resend) {
            pollRecovery(execution)
            return true
        }
        pool.sendPrompt(service, prompt.orEmpty(), requestId) { outcome ->
            if (!isRecoveryActive(execution)) return@sendPrompt
            if (outcome.success) {
                runs[service] = runs.getValue(service).copy(
                    phase = ParticipantPhase.WAITING,
                    detail = "重发成功，等待回答",
                )
                schedulePersist()
                pollRecovery(execution)
            } else {
                finishRecovery(
                    execution,
                    runs.getValue(service).copy(
                        phase = ParticipantPhase.ERROR,
                        detail = "重发失败：${outcome.detail.take(100)}",
                    ),
                )
            }
        }
        return true
    }

    private fun pollRecovery(execution: RecoveryExecution) {
        if (!isRecoveryActive(execution)) return
        val elapsed = SystemClock.elapsedRealtime() - execution.startedAtElapsedMillis
        if (elapsed >= timing.responseTimeoutMillis) {
            val run = runs.getValue(execution.service)
            finishRecovery(
                execution,
                run.copy(
                    phase = ParticipantPhase.ERROR,
                    detail = when {
                        run.detail == QWEN_SECURITY_CHALLENGE_WAITING ->
                            "千问安全验证等待超时，请完成验证后再次点击重新提取"
                        run.response.isBlank() -> "单家补救等待超时"
                        else -> "单家补救超时，已保留部分内容"
                    },
                ),
            )
            return
        }
        val readToken = ++execution.readToken
        handler.postDelayed({
            if (!isRecoveryActive(execution) || execution.readToken != readToken) return@postDelayed
            execution.readToken += 1
            handleRecoveryReadFailure(execution, "读取网页超时")
        }, timing.readCallbackTimeoutMillis)
        pool.readResponse(execution.service, execution.requestId) { snapshot ->
            if (!isRecoveryActive(execution) || execution.readToken != readToken) return@readResponse
            execution.readToken += 1
            if (snapshot.isAwaitingSecurityChallenge()) {
                execution.consecutiveReadErrors = 0
                runs[execution.service] = runs.getValue(execution.service).copy(
                    phase = ParticipantPhase.WAITING,
                    detail = QWEN_SECURITY_CHALLENGE_WAITING,
                )
                schedulePersist()
                handler.postDelayed({ pollRecovery(execution) }, timing.pollIntervalMillis)
                return@readResponse
            }
            if (snapshot.detail.isNotBlank()) {
                handleRecoveryReadFailure(execution, snapshot.detail)
                return@readResponse
            }
            execution.consecutiveReadErrors = 0
            if (snapshot.found && snapshot.text.isNotBlank()) {
                if (snapshot.text == execution.lastText) execution.stableCount += 1 else {
                    execution.lastText = snapshot.text
                    execution.stableCount = 0
                }
                val updated = runs.getValue(execution.service).copy(
                    phase = if (snapshot.streaming) ParticipantPhase.STREAMING else ParticipantPhase.WAITING,
                    response = snapshot.text,
                    detail = if (snapshot.streaming) "正在补全回答" else "正在确认补救结果",
                    responseTruncated = snapshot.truncated,
                    originalResponseLength = snapshot.originalLength,
                )
                runs[execution.service] = updated
                schedulePersist()
                if (!snapshot.streaming && execution.stableCount >= timing.requiredStablePolls) {
                    finishRecovery(
                        execution,
                        updated.copy(
                            phase = ParticipantPhase.COMPLETE,
                            detail = if (execution.resend) "重答完成 · ${snapshot.text.length} 字" else "重新提取完成 · ${snapshot.text.length} 字",
                        ),
                    )
                    return@readResponse
                }
            } else {
                runs[execution.service] = runs.getValue(execution.service).copy(
                    phase = ParticipantPhase.WAITING,
                    detail = if (execution.resend) "等待重答" else "尚未找到新回答",
                )
                schedulePersist()
            }
            handler.postDelayed({ pollRecovery(execution) }, timing.pollIntervalMillis)
        }
    }

    private fun handleRecoveryReadFailure(execution: RecoveryExecution, detail: String) {
        if (!isRecoveryActive(execution)) return
        execution.consecutiveReadErrors += 1
        if (execution.consecutiveReadErrors >= timing.maxConsecutiveReadErrors) {
            finishRecovery(
                execution,
                runs.getValue(execution.service).copy(
                    phase = ParticipantPhase.ERROR,
                    detail = "重新提取失败：${detail.take(80)}",
                ),
            )
        } else {
            runs[execution.service] = runs.getValue(execution.service).copy(
                phase = ParticipantPhase.WAITING,
                detail = "补救读取重试 ${execution.consecutiveReadErrors}/${timing.maxConsecutiveReadErrors}",
            )
            schedulePersist()
            handler.postDelayed({ pollRecovery(execution) }, timing.pollIntervalMillis)
        }
    }

    private fun finishRecovery(execution: RecoveryExecution, result: ParticipantRun) {
        if (!isRecoveryActive(execution)) return
        runs[execution.service] = result
        recoveryExecution = null
        updateLatestRoundResult(execution.service, result)
        sessionMessage = when (result.phase) {
            ParticipantPhase.COMPLETE -> "${execution.service.displayName} 单家补救完成"
            else -> "${execution.service.displayName} 单家补救未完成，其他结果仍保留"
        }
        schedulePersist(immediate = true)
    }

    private fun updateLatestRoundResult(service: ArenaService, result: ParticipantRun) {
        if (history.isEmpty()) return
        val index = history.lastIndex
        val round = history[index]
        history[index] = round.copy(
            results = round.results + (service to result),
            finishedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun dispatchParallel(execution: RoundExecution) {
        if (!isActive(execution)) return
        val service = execution.dispatchOrder.getOrNull(execution.nextDispatchIndex)
        if (service == null) {
            execution.dispatchComplete = true
            sessionMessage = "第 ${execution.number} 轮已全部送达，正在并行等待回答"
            maybeFinishRound(execution)
            return
        }
        execution.nextDispatchIndex += 1
        sendService(execution, service) {
            dispatchParallel(execution)
        }
    }

    private fun dispatchSerialNext(execution: RoundExecution) {
        if (!isActive(execution)) return
        val service = execution.dispatchOrder.getOrNull(execution.nextDispatchIndex)
        if (service == null) {
            execution.dispatchComplete = true
            maybeFinishRound(execution)
            return
        }
        execution.nextDispatchIndex += 1
        sessionMessage = "串行模式：正在发送给 ${service.displayName}"
        sendService(execution, service) { sent ->
            if (!isActive(execution)) return@sendService
            if (sent) {
                sessionMessage = "串行模式：等待 ${service.displayName} 回答后再发送下一家"
            } else {
                dispatchSerialNext(execution)
            }
        }
    }

    private fun sendService(
        execution: RoundExecution,
        service: ArenaService,
        onSendFinished: (Boolean) -> Unit,
    ) {
        if (!isActive(execution)) return
        val requestId = buildRequestId(execution, service)
        runs[service] = ParticipantRun(
            phase = ParticipantPhase.SENDING,
            requestId = requestId,
            detail = "正在发送",
        )
        schedulePersist()
        pool.sendPrompt(
            service = service,
            prompt = execution.prompts.getValue(service),
            requestId = requestId,
        ) { outcome ->
            if (!isActive(execution) || runs[service]?.requestId != requestId) return@sendPrompt
            if (outcome.success) {
                runs[service] = ParticipantRun(
                    phase = ParticipantPhase.WAITING,
                    requestId = requestId,
                    detail = "等待回答",
                )
                startPolling(execution, service, requestId)
            } else {
                runs[service] = ParticipantRun(
                    phase = ParticipantPhase.ERROR,
                    requestId = requestId,
                    detail = outcome.detail,
                )
            }
            onSendFinished(outcome.success)
            schedulePersist()
            if (!outcome.success) maybeFinishRound(execution)
        }
    }

    private fun startPolling(execution: RoundExecution, service: ArenaService, requestId: String) {
        val pollState = PollState(
            requestId = requestId,
            startedAtElapsedMillis = SystemClock.elapsedRealtime(),
        )
        pollStates[service] = pollState
        poll(execution, service, pollState)
    }

    private fun poll(execution: RoundExecution, service: ArenaService, state: PollState) {
        if (!isActive(execution) || pollStates[service] !== state || runs[service]?.requestId != state.requestId) return
        val elapsed = SystemClock.elapsedRealtime() - state.startedAtElapsedMillis
        if (elapsed >= timing.responseTimeoutMillis) {
            val run = runs.getValue(service)
            val suffix = if (run.response.isNotBlank()) "，已保留 ${run.response.length} 字" else ""
            markTerminal(
                execution,
                service,
                run.copy(
                    phase = ParticipantPhase.ERROR,
                    detail = if (run.detail == QWEN_SECURITY_CHALLENGE_WAITING) {
                        "千问安全验证等待超时，请完成验证后点击重新提取"
                    } else {
                        "等待回答超时$suffix"
                    },
                ),
            )
            return
        }

        val readToken = ++state.readToken
        handler.postDelayed({
            if (!isActive(execution) || pollStates[service] !== state || state.readToken != readToken) return@postDelayed
            state.readToken += 1
            handleReadFailure(execution, service, state, "读取网页超时")
        }, timing.readCallbackTimeoutMillis)

        pool.readResponse(service, state.requestId) { snapshot ->
            if (!isActive(execution) || pollStates[service] !== state || state.readToken != readToken) return@readResponse
            state.readToken += 1
            if (snapshot.isAwaitingSecurityChallenge()) {
                state.consecutiveReadErrors = 0
                runs[service] = runs.getValue(service).copy(
                    phase = ParticipantPhase.WAITING,
                    detail = QWEN_SECURITY_CHALLENGE_WAITING,
                )
                schedulePersist()
                handler.postDelayed({ poll(execution, service, state) }, timing.pollIntervalMillis)
                return@readResponse
            }
            if (snapshot.detail.isNotBlank()) {
                handleReadFailure(execution, service, state, snapshot.detail)
                return@readResponse
            }
            state.consecutiveReadErrors = 0
            if (snapshot.found && snapshot.text.isNotBlank()) {
                if (snapshot.text == state.lastText) {
                    state.stableCount += 1
                } else {
                    state.lastText = snapshot.text
                    state.stableCount = 0
                }
                val lengthLabel = responseLengthLabel(snapshot)
                runs[service] = runs.getValue(service).copy(
                    phase = if (snapshot.streaming) ParticipantPhase.STREAMING else ParticipantPhase.WAITING,
                    response = snapshot.text,
                    detail = if (snapshot.streaming) "正在回答 · $lengthLabel" else "正在确认回答完成",
                    responseTruncated = snapshot.truncated,
                    originalResponseLength = snapshot.originalLength,
                )
                schedulePersist()
                if (!snapshot.streaming && state.stableCount >= timing.requiredStablePolls) {
                    markTerminal(
                        execution,
                        service,
                        runs.getValue(service).copy(
                            phase = ParticipantPhase.COMPLETE,
                            detail = "回答完成 · $lengthLabel",
                        ),
                    )
                    return@readResponse
                }
            } else {
                val waitedSeconds = (SystemClock.elapsedRealtime() - state.startedAtElapsedMillis) / 1_000
                runs[service] = runs.getValue(service).copy(
                    phase = ParticipantPhase.WAITING,
                    detail = "等待回答 · ${waitedSeconds}秒",
                )
                schedulePersist()
            }
            handler.postDelayed({ poll(execution, service, state) }, timing.pollIntervalMillis)
        }
    }

    private fun handleReadFailure(
        execution: RoundExecution,
        service: ArenaService,
        state: PollState,
        detail: String,
    ) {
        if (!isActive(execution) || pollStates[service] !== state) return
        state.consecutiveReadErrors += 1
        if (state.consecutiveReadErrors >= timing.maxConsecutiveReadErrors) {
            markTerminal(
                execution,
                service,
                runs.getValue(service).copy(
                    phase = ParticipantPhase.ERROR,
                    detail = "连续读取失败：${detail.take(80)}",
                ),
            )
        } else {
            runs[service] = runs.getValue(service).copy(
                phase = ParticipantPhase.WAITING,
                detail = "读取回答重试 ${state.consecutiveReadErrors}/${timing.maxConsecutiveReadErrors}",
            )
            schedulePersist()
            handler.postDelayed({ poll(execution, service, state) }, timing.pollIntervalMillis)
        }
    }

    private fun markTerminal(
        execution: RoundExecution,
        service: ArenaService,
        terminalRun: ParticipantRun,
    ) {
        if (!isActive(execution)) return
        runs[service] = terminalRun
        schedulePersist()
        pollStates.remove(service)
        if (execution.answerMode == AnswerMode.SERIAL) {
            dispatchSerialNext(execution)
        } else {
            maybeFinishRound(execution)
        }
    }

    private fun maybeFinishRound(execution: RoundExecution) {
        if (!isActive(execution) || !execution.dispatchComplete) return
        if (!execution.services.all { runs.getValue(it).phase.isTerminal() }) return
        finishRound(execution)
    }

    private fun finishRound(execution: RoundExecution, forcedMessage: String? = null) {
        if (activeExecution !== execution) return
        val results = execution.services.associateWith { runs.getValue(it) }
        history += RoundRecord(
            number = execution.number,
            kind = execution.kind,
            answerMode = execution.answerMode,
            guidance = execution.guidance,
            results = results,
            startedAtMillis = execution.startedAtMillis,
            finishedAtMillis = System.currentTimeMillis(),
        )
        while (history.size > ArenaLimits.MAX_HISTORY_ROUNDS) history.removeAt(0)
        val completed = results.values.count { it.phase == ParticipantPhase.COMPLETE }
        val failed = results.size - completed
        stage = SessionStage.READY
        currentRoundKind = execution.kind
        sessionMessage = forcedMessage ?: buildString {
            append("第 ${execution.number} 轮${execution.kind.displayName}完成：$completed 位成功")
            if (failed > 0) append("，$failed 位失败")
            if (currentRoundContextNotice.isNotBlank()) append(" · $currentRoundContextNotice")
        }
        activeExecution = null
        schedulePersist(immediate = true)
    }

    private fun pollSummary(execution: SummaryExecution) {
        if (!isSummaryActive(execution)) return
        val elapsed = SystemClock.elapsedRealtime() - execution.startedAtElapsedMillis
        if (elapsed >= timing.responseTimeoutMillis) {
            summary = summary.copy(
                phase = ParticipantPhase.ERROR,
                detail = when {
                    summary.detail == QWEN_SECURITY_CHALLENGE_WAITING ->
                        "千问安全验证等待超时，请完成验证后重新总结"
                    summary.text.isBlank() -> "等待总结超时"
                    else -> "等待总结超时，已保留部分内容"
                },
            )
            summaryExecution = null
            sessionMessage = "讨论总结超时"
            schedulePersist()
            return
        }

        val readToken = ++execution.readToken
        handler.postDelayed({
            if (!isSummaryActive(execution) || execution.readToken != readToken) return@postDelayed
            execution.readToken += 1
            handleSummaryReadFailure(execution, "读取总结网页超时")
        }, timing.readCallbackTimeoutMillis)

        pool.readResponse(execution.judge, execution.requestId) { snapshot ->
            if (!isSummaryActive(execution) || execution.readToken != readToken) return@readResponse
            execution.readToken += 1
            if (snapshot.isAwaitingSecurityChallenge()) {
                execution.consecutiveReadErrors = 0
                summary = summary.copy(
                    phase = ParticipantPhase.WAITING,
                    detail = QWEN_SECURITY_CHALLENGE_WAITING,
                )
                schedulePersist()
                handler.postDelayed({ pollSummary(execution) }, timing.pollIntervalMillis)
                return@readResponse
            }
            if (snapshot.detail.isNotBlank()) {
                handleSummaryReadFailure(execution, snapshot.detail)
                return@readResponse
            }
            execution.consecutiveReadErrors = 0
            if (snapshot.found && snapshot.text.isNotBlank()) {
                if (snapshot.text == execution.lastText) execution.stableCount += 1 else {
                    execution.lastText = snapshot.text
                    execution.stableCount = 0
                }
                summary = summary.copy(
                    phase = if (snapshot.streaming) ParticipantPhase.STREAMING else ParticipantPhase.WAITING,
                    text = snapshot.text,
                    detail = if (snapshot.streaming) "正在总结 · ${snapshot.text.length} 字" else "正在确认总结完成",
                )
                schedulePersist()
                if (!snapshot.streaming && execution.stableCount >= timing.requiredStablePolls) {
                    summary = summary.copy(
                        phase = ParticipantPhase.COMPLETE,
                        text = snapshot.text,
                        detail = "总结完成 · ${snapshot.originalLength} 字",
                    )
                    summaryExecution = null
                    sessionMessage = "讨论总结完成 · ${execution.judge.displayName}"
                    schedulePersist(immediate = true)
                    return@readResponse
                }
            } else {
                val waitedSeconds = elapsed / 1_000
                summary = summary.copy(phase = ParticipantPhase.WAITING, detail = "等待总结 · ${waitedSeconds}秒")
                schedulePersist()
            }
            handler.postDelayed({ pollSummary(execution) }, timing.pollIntervalMillis)
        }
    }

    private fun handleSummaryReadFailure(execution: SummaryExecution, detail: String) {
        if (!isSummaryActive(execution)) return
        execution.consecutiveReadErrors += 1
        if (execution.consecutiveReadErrors >= timing.maxConsecutiveReadErrors) {
            summary = summary.copy(
                phase = ParticipantPhase.ERROR,
                detail = "连续读取总结失败：${detail.take(80)}",
            )
            summaryExecution = null
            sessionMessage = "讨论总结失败"
            schedulePersist()
        } else {
            summary = summary.copy(
                phase = ParticipantPhase.WAITING,
                detail = "读取总结重试 ${execution.consecutiveReadErrors}/${timing.maxConsecutiveReadErrors}",
            )
            schedulePersist()
            handler.postDelayed({ pollSummary(execution) }, timing.pollIntervalMillis)
        }
    }

    private fun schedulePersist(immediate: Boolean = false) {
        if (sessionRepository == null || sessionId.isBlank() || originalQuestion.isBlank()) return
        persistenceHandler.removeCallbacks(persistRunnable)
        if (immediate) persistNow() else persistenceHandler.postDelayed(persistRunnable, PERSIST_DEBOUNCE_MILLIS)
    }

    private fun persistNow() {
        val repository = sessionRepository ?: return
        if (sessionId.isBlank() || originalQuestion.isBlank()) return
        val snapshot = ArenaSessionSnapshot(
            id = sessionId,
            originalQuestion = originalQuestion,
            roundNumber = roundNumber,
            currentRoundKind = currentRoundKind,
            currentAnswerMode = currentAnswerMode,
            services = sessionServices,
            runs = runs.toMap(),
            history = history.toList(),
            summary = summary,
            lastRoundPrompts = lastRoundPrompts,
            updatedAtMillis = System.currentTimeMillis(),
        )
        runCatching {
            repository.save(snapshot)
            repository.setActiveSession(sessionId)
        }.onSuccess {
            storageWarning = null
            refreshRecentSessions()
        }.onFailure {
            storageWarning = "本地保存失败，当前讨论仍可继续"
        }
    }

    private fun applySnapshot(snapshot: ArenaSessionSnapshot, recovered: Boolean) {
        sessionEpoch += 1
        handler.removeCallbacksAndMessages(null)
        persistenceHandler.removeCallbacksAndMessages(null)
        pollStates.clear()
        activeExecution = null
        summaryExecution = null
        recoveryExecution = null
        sessionId = snapshot.id
        originalQuestion = snapshot.originalQuestion
        roundNumber = maxOf(snapshot.roundNumber, snapshot.history.maxOfOrNull { it.number } ?: 0)
        currentRoundKind = snapshot.currentRoundKind ?: snapshot.history.lastOrNull()?.kind
        currentAnswerMode = snapshot.currentAnswerMode
        sessionServices = snapshot.services.distinct().let { services ->
            if (services.size in ArenaService.MIN_MEMBERS..ArenaService.MAX_MEMBERS) services else ArenaService.defaultMembers
        }
        history.clear()
        history.addAll(snapshot.history.takeLast(ArenaLimits.MAX_HISTORY_ROUNDS))
        ArenaService.entries.forEach { service ->
            runs[service] = recoverRun(snapshot.runs[service] ?: ParticipantRun())
        }
        summary = recoverSummary(snapshot.summary)
        lastRoundPrompts = snapshot.lastRoundPrompts
        currentRoundContextNotice = ""
        stage = if (originalQuestion.isBlank()) SessionStage.IDLE else SessionStage.READY
        sessionMessage = if (recovered) {
            "已恢复上次本地讨论 · $roundNumber 轮"
        } else {
            "已打开历史讨论 · $roundNumber 轮"
        }
        storageWarning = null
        refreshRecentSessions()
        if (recovered) schedulePersist(immediate = true)
    }

    private fun recoverRun(run: ParticipantRun): ParticipantRun =
        if (run.phase == ParticipantPhase.SENDING ||
            run.phase == ParticipantPhase.WAITING ||
            run.phase == ParticipantPhase.STREAMING
        ) {
            run.copy(phase = ParticipantPhase.ERROR, detail = "应用重启，已停止等待；已保留现有内容")
        } else {
            run
        }

    private fun recoverSummary(value: DiscussionSummary): DiscussionSummary =
        if (value.phase == ParticipantPhase.SENDING ||
            value.phase == ParticipantPhase.WAITING ||
            value.phase == ParticipantPhase.STREAMING
        ) {
            value.copy(phase = ParticipantPhase.ERROR, detail = "应用重启，已停止总结等待")
        } else {
            value
        }

    private fun refreshRecentSessions() {
        val repository = sessionRepository ?: return
        runCatching { repository.listRecent() }
            .onSuccess { sessions ->
                recentSessions.clear()
                recentSessions.addAll(sessions)
            }
            .onFailure { storageWarning = "无法读取最近问题" }
    }

    private fun completedResponses(): Map<ArenaService, String> = ArenaService.entries
        .mapNotNull { service ->
            val run = runs[service]
            if (run?.phase == ParticipantPhase.COMPLETE && run.response.isNotBlank()) {
                service to run.response
            } else {
                null
            }
        }
        .toMap()

    private fun buildRequestId(execution: RoundExecution, service: ArenaService): String {
        requestSequence += 1
        return "${execution.kind.name.lowercase()}_${execution.number}_${requestSequence}_${service.name.lowercase()}_${System.currentTimeMillis()}"
    }

    private fun isActive(execution: RoundExecution): Boolean =
        activeExecution === execution && execution.epoch == sessionEpoch

    private fun isSummaryActive(execution: SummaryExecution): Boolean =
        summaryExecution === execution && execution.epoch == sessionEpoch

    private fun isRecoveryActive(execution: RecoveryExecution): Boolean =
        recoveryExecution === execution && execution.epoch == sessionEpoch

    private fun responseLengthLabel(snapshot: ResponseSnapshot): String =
        if (snapshot.truncated) {
            "${snapshot.originalLength} 字（保留前 ${snapshot.text.length} 字）"
        } else {
            "${snapshot.text.length} 字"
        }

    private data class RoundExecution(
        val epoch: Long,
        val number: Int,
        val kind: RoundKind,
        val answerMode: AnswerMode,
        val services: List<ArenaService>,
        val dispatchOrder: List<ArenaService>,
        val prompts: Map<ArenaService, String>,
        val guidance: String,
        val startedAtMillis: Long,
        var nextDispatchIndex: Int = 0,
        var dispatchComplete: Boolean = false,
    )

    private data class PollState(
        val requestId: String,
        val startedAtElapsedMillis: Long,
        var lastText: String = "",
        var stableCount: Int = 0,
        var consecutiveReadErrors: Int = 0,
        var readToken: Long = 0,
    )

    private data class SummaryExecution(
        val epoch: Long,
        val judge: ArenaService,
        val requestId: String,
        val startedAtElapsedMillis: Long,
        var lastText: String = "",
        var stableCount: Int = 0,
        var consecutiveReadErrors: Int = 0,
        var readToken: Long = 0,
    )

    private data class RecoveryExecution(
        val epoch: Long,
        val service: ArenaService,
        val requestId: String,
        val resend: Boolean,
        val startedAtElapsedMillis: Long,
        var lastText: String = "",
        var stableCount: Int = 0,
        var consecutiveReadErrors: Int = 0,
        var readToken: Long = 0,
    )

    private companion object {
        const val PERSIST_DEBOUNCE_MILLIS = 250L
    }

}

private fun ParticipantPhase.isTerminal(): Boolean =
    this == ParticipantPhase.COMPLETE || this == ParticipantPhase.ERROR

private const val QWEN_SECURITY_CHALLENGE_WAITING = "千问安全验证处理中；完成后将自动继续提取"

private fun ResponseSnapshot.isAwaitingSecurityChallenge(): Boolean =
    securityChallenge && !found

object QuestionPolicy {
    fun isValid(question: String): Boolean =
        question.isNotBlank() && question.length <= ArenaLimits.MAX_QUESTION_CHARS
}

object DebatePromptBuilder {
    fun build(
        originalQuestion: String,
        target: ArenaService,
        responses: Map<ArenaService, String>,
        debateIndex: Int = 1,
        guidance: String = "",
        quoteLimit: Int = ArenaLimits.MAX_QUOTED_RESPONSE_CHARS,
    ): String {
        val others = PromptSections.otherResponses(target, responses, quoteLimit)
        val guidanceSection = guidance.trim().take(ArenaLimits.MAX_GUIDANCE_CHARS).let {
            if (it.isBlank()) "" else "\n\n用户本轮补充要求：\n$it"
        }
        return """
            这是观点讨论第 $debateIndex 轮。

            原始问题：
            $originalQuestion

            以下是其他 AI 的最新回答：
            $others$guidanceSection

            请逐一讨论这些观点：明确指出你认同和不认同的部分，给出理由，修正可能的错误或遗漏，并形成你这一轮更可靠的结论。不要只复述其他回答。总长度控制在 200 个汉字以内，最后单独给出一句综合结论。
        """.trimIndent()
    }
}

object DiscussionSummaryPromptBuilder {
    fun build(
        originalQuestion: String,
        history: List<RoundRecord>,
        responses: Map<ArenaService, String>,
        customInstruction: String = "",
        quoteLimit: Int = ArenaLimits.MAX_QUOTED_RESPONSE_CHARS,
    ): String {
        val viewpoints = responses.entries.joinToString("\n\n") { (service, response) ->
            "【${service.displayName} 的最新观点】\n${response.take(quoteLimit.coerceAtLeast(0))}"
        }
        val roundOutline = history.joinToString("\n") { round ->
            buildString {
                append("- 第 ${round.number} 轮：${round.kind.displayName}")
                if (round.guidance.isNotBlank()) {
                    val label = if (round.kind == RoundKind.ITERATION) "本轮问题" else "用户补充"
                    append("；$label：${round.guidance.take(240)}")
                }
            }
        }
        val extra = customInstruction.trim().take(ArenaLimits.MAX_GUIDANCE_CHARS).let {
            if (it.isBlank()) "" else "\n\n用户对总结的额外要求：\n$it"
        }
        return """
            你是这场多 AI 讨论的主持人，请替普通用户做一份简明总结。

            原始问题：
            $originalQuestion

            讨论轮次：
            $roundOutline

            各 AI 最新观点：
            $viewpoints$extra

            请按以下结构输出，总长度控制在 350 个汉字以内：
            1. 一句话结论
            2. 已形成的共识
            3. 仍有分歧或需要核验的地方
            4. 给用户的 2-4 条可执行建议
            不要声称未被上述材料支持的事实。
        """.trimIndent()
    }
}

private object PromptSections {
    fun otherResponses(
        target: ArenaService,
        responses: Map<ArenaService, String>,
        quoteLimit: Int = ArenaLimits.MAX_QUOTED_RESPONSE_CHARS,
    ): String = responses
        .filterKeys { it != target }
        .entries
        .joinToString("\n\n") { (service, response) ->
            "【${service.displayName} 的回答】\n${response.take(quoteLimit.coerceAtLeast(0))}"
        }
}
