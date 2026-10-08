package com.tianlin.aiarena

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 打开历史会话的结果。分开报，界面才能说清楚到底发生了什么。 */
enum class RestoreOutcome {
    OK,
    /** 打开成功，但为此中止了正在进行的一轮。 */
    OK_AFTER_STOP,
    /** 记录文件缺失或损坏，已从列表移除。 */
    UNREADABLE,
    /** 本地存储不可用。 */
    NO_STORAGE,
    SAVE_FAILED,
}

class ArenaSessionController(
    private val pool: ArenaGateway,
    private val timing: ControllerTiming = ControllerTiming(),
    private val sessionRepository: ArenaSessionRepository? = null,
    private val progressTracker: ArenaProgressTracker = ArenaProgressTracker(),
    /** 观点讨论、工作流、队长总结用的预设提示词；界面编辑后由 [ArenaPresetStore] 提供。 */
    private val presets: PresetSource = DefaultPresets,
) {
    private val runStates = mutableStateMapOf<ArenaService, ParticipantRun>().apply {
        ArenaService.entries.forEach { service -> put(service, ParticipantRun()) }
    }
    val runs: MutableMap<ArenaService, ParticipantRun> = object : MutableMap<ArenaService, ParticipantRun> by runStates {
        override fun put(key: ArenaService, value: ParticipantRun): ParticipantRun? {
            progressTracker.observe(key, value, SystemClock.elapsedRealtime(), lastRoundAttachments.isNotEmpty())
            if (runStates[key]?.phase != value.phase) ArenaTrace.log(key, "phase ${value.phase} ${value.detail.take(40)}")
            return runStates.put(key, value)
        }
    }

    fun waitingProgress(service: ArenaService, nowElapsedMillis: Long): ArenaWaitProgress? =
        runs[service]?.let { run ->
            val answerStarted = recoveries[service]?.takeIf { it.requestId == run.requestId }?.startedAtElapsedMillis
                ?: pollStates[service]?.takeIf { it.requestId == run.requestId }?.startedAtElapsedMillis
            progressTracker.describe(service, run, nowElapsedMillis, timing, pool.sendProgress(service, run.requestId),
                answerStarted?.plus(timing.responseTimeoutMillis))
        }
    val history = mutableStateListOf<RoundRecord>()
    val recentSessions = mutableStateListOf<RecentArenaSession>()

    var stage by mutableStateOf(SessionStage.IDLE)
        private set

    var originalQuestion by mutableStateOf("")
        private set

    /** 这个问题是什么时候提的；家里长辈想一眼知道每个问题的时间（用户反馈 2026-09-06）。 */
    var askedAtMillis by mutableStateOf(0L)
        private set

    var sessionMessage by mutableStateOf("等待开始")
        private set

    var currentRoundKind by mutableStateOf<RoundKind?>(null)
        private set

    /** 当前轮是不是工作流（按顺序接力），以及观点讨论用的方式；标签与时光机据此显示。 */
    var currentRoundRelay by mutableStateOf(false)
        private set
    var currentRoundStyle by mutableStateOf<DebateStyle?>(null)
        private set

    /** 本轮工作流的接力顺序；重发还没轮到或被跳过的成员时，据此重新组装「问题 + 前面各位的回答」。 */
    private var relayOrder: List<ArenaService> = emptyList()

    var currentAnswerMode by mutableStateOf(AnswerMode.PARALLEL)
        private set

    var roundNumber by mutableIntStateOf(0)
        private set

    private val summaryState = mutableStateOf(DiscussionSummary())
    var summary: DiscussionSummary
        get() = summaryState.value
        private set(value) {
            value.judge?.let { judge -> progressTracker.observe(judge, value.progressRun(), SystemClock.elapsedRealtime(), value.attachments.isNotEmpty()) }
            summaryState.value = value
        }

    private fun DiscussionSummary.progressRun() = ParticipantRun(phase = phase, requestId = requestId, response = text, detail = detail)

    fun summaryWaitingProgress(nowElapsedMillis: Long): ArenaWaitProgress? = summary.judge?.let { judge ->
        progressTracker.describe(judge, summary.progressRun(), nowElapsedMillis, timing, pool.sendProgress(judge, summary.requestId),
            summaryExecution?.takeIf { it.requestId == summary.requestId }?.startedAtElapsedMillis?.plus(timing.responseTimeoutMillis))
    }

    var sessionServices by mutableStateOf(ArenaService.defaultMembers)
        private set

    var storageWarning by mutableStateOf<String?>(null)
        private set

    /** 发送与开新对话的期限（ArenaDeadline）只按前台时间计；读回答的轮询在后台照常进行。 */
    private val handler: Handler = ArenaForegroundHandler(Looper.getMainLooper(), freezeAll = false)
    private val persistenceHandler = Handler(Looper.getMainLooper())

    /**
     * 落盘专用单线程。一次 save() 要做全量 JSON 编码 + 两次 fsync，
     * 而流式回答期间每家 AI 每 1.5 秒轮询一次都会触发它——放在主线程上必然 ANR。
     */
    @Volatile private var persistThread: Thread? = null
    private val persistExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "arena-session-persist").apply { isDaemon = true }.also { persistThread = it }
    }
    private var persistShutdown = false

    /**
     * 每次 reset / restoreSession / applySnapshot 都会递增。
     *
     * 落盘搬到后台线程之后出现过一个真实回归：reset() 先触发一次 persist（后台任务里
     * 会 setActiveSession(旧 id)），再同步把活动会话清成 null；后台任务晚一步执行，
     * 又把旧会话设了回去 —— 用户点了"开始新问题"，冷启动却仍然恢复上一轮。
     * 这个代号让迟到的任务知道自己已经过期。
     */
    private var persistGeneration = 0L
    private val pollStates = mutableMapOf<ArenaService, PollState>()
    private var activeExecution: RoundExecution? = null
    private var summaryExecution: SummaryExecution? = null
    /** 轮次结束后的单家补救（重新提取 / 重新发送 / 换人接手）；每家一条，彼此独立，可以同时进行。 */
    private val recoveries = mutableMapOf<ArenaService, RecoveryExecution>()

    /**
     * 「换人」只从下一轮起生效时记在这里：旧成员 → 新成员。开下一轮时替换成员表并清空。
     * 旧成员本轮已完成 / 已跳过 / 整轮被停止时走这条，本轮不自动发送。
     */
    private val pendingSwapState = mutableStateMapOf<ArenaService, ArenaService>()
    val pendingSwaps: Map<ArenaService, ArenaService> get() = pendingSwapState

    /** 本轮中途换上来的新成员：网页里没有这场讨论的上下文，重发也要开新对话。 */
    private var roundNewcomers: Set<ArenaService> = emptySet()
    private var sessionEpoch = 0L
    private var requestSequence = 0L
    private var sessionId = ""
    var lastRoundAttachments by mutableStateOf<List<ArenaAttachment>>(emptyList())
        private set
    private val recoveryQueue = ArrayDeque<Pair<ArenaService, Boolean>>()
    private var lastRoundPrompts: Map<ArenaService, String> = emptyMap()
    /** 各家网页里这条讨论对应的对话地址；打开历史会话时用它把网页切回去。 */
    private val conversationUrls = mutableMapOf<ArenaService, String>()
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
            stage == SessionStage.INSPIRE ||
            summary.phase == ParticipantPhase.SENDING ||
            summary.phase == ParticipantPhase.WAITING ||
            summary.phase == ParticipantPhase.STREAMING ||
            recoveries.isNotEmpty()

    val completedCount: Int
        get() = runs.values.count { it.phase == ParticipantPhase.COMPLETE }

    /** User-facing question paired with the current member answers, never an internal debate prompt. */
    val currentQuestion: String
        get() {
            val number = roundNumber
            val guidance = activeExecution?.takeIf { it.number == number }?.guidance?.takeIf { it.isNotBlank() }
                ?: history.lastOrNull { it.number == number }?.guidance?.takeIf { it.isNotBlank() }
            return when (currentRoundKind) {
                RoundKind.ITERATION -> guidance
                    ?: lastRoundPrompts.values.firstOrNull { it.isNotBlank() }
                    ?: "本轮问题未保存"
                RoundKind.DEBATE -> originalQuestion + (guidance?.let { "\n\n本轮讨论要求：$it" } ?: "")
                RoundKind.INSPIRE -> originalQuestion + (guidance?.let { "\n\n本轮激发要求：$it" } ?: "")
                else -> originalQuestion
            }
        }

    fun startInitial(
        question: String,
        services: List<ArenaService>,
        answerMode: AnswerMode = AnswerMode.PARALLEL,
        attachments: List<ArenaAttachment> = emptyList(),
        relayOrder: List<ArenaService>? = null,
    ): Boolean {
        ArenaAttachmentPolicy.validate(attachments)?.let { sessionMessage = it; return false }
        val normalizedQuestion = AttachmentPromptPolicy.withDefault(question, attachments).trim()
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
        lastRoundAttachments = emptyList()
        lastRoundPrompts = emptyMap()
        conversationUrls.clear()
        pendingSwapState.clear()
        roundNewcomers = emptySet()
        currentRoundContextNotice = ""
        roundNumber = 0
        originalQuestion = normalizedQuestion
        askedAtMillis = System.currentTimeMillis()
        return startRound(
            kind = RoundKind.INITIAL,
            services = selected,
            prompts = selected.associateWith { normalizedQuestion },
            answerMode = answerMode,
            guidance = "",
            attachments = attachments,
            relayOrder = relayOrder,
            question = normalizedQuestion,
        )
    }

    fun startIteration(
        answerMode: AnswerMode = currentAnswerMode,
        guidance: String = "",
        attachments: List<ArenaAttachment> = emptyList(),
        relayOrder: List<ArenaService>? = null,
    ): Boolean {
        if (isBusy || stage != SessionStage.READY) return false
        val newPrompt = AttachmentPromptPolicy.withDefault(guidance, attachments).trim()
        if (newPrompt.isBlank()) {
            sessionMessage = "请输入本轮独立迭代的 Prompt"
            return false
        }
        if (newPrompt.length > ArenaLimits.MAX_GUIDANCE_CHARS) {
            sessionMessage = "本轮 Prompt 超过 ${ArenaLimits.MAX_GUIDANCE_CHARS} 字，请缩短后重试"
            return false
        }
        val services = nextRoundMembers()
        val newcomers = nextRoundNewcomers(services)
        // 逃生通道：其他几家都被跳过时，剩下的一家仍可继续独立追问；接力至少要两家。
        val needed = if (relayOrder != null) ArenaService.MIN_MEMBERS else 1
        if (services.size < needed) {
            sessionMessage = if (services.isEmpty()) "本轮没有答完的成员，请先重发或重新读取" else "工作流至少需要 ${ArenaService.MIN_MEMBERS} 家答完"
            return false
        }
        val prompts = services.associateWith { if (it in newcomers) RoundMaterials.forNewcomer(newPrompt, originalQuestion) else newPrompt }
        return startRound(RoundKind.ITERATION, services, prompts, answerMode, newPrompt, attachments,
            relayOrder = relayOrder?.filter { it in services }, newcomers = newcomers, question = newPrompt)
    }

    /**
     * 回答页的成员标签：成员表，再把本轮被换下、仍有记录的旧成员排在接替它的人前面，旧回答还能看。
     */
    val roundTabs: List<ArenaService>
        get() {
            val out = mutableListOf<ArenaService>()
            fun predecessors(member: ArenaService, seen: Set<ArenaService>): List<ArenaService> =
                ArenaService.entries.filter { old ->
                    old !in sessionServices && old !in seen && runs[old]?.let { it.replacedBy == member && it.requestId.isNotBlank() } == true
                }.flatMap { old -> predecessors(old, seen + old) + old }
            sessionServices.forEach { member ->
                predecessors(member, setOf(member)).forEach { if (it !in out) out += it }
                if (member !in out) out += member
            }
            return out
        }

    /** 下一轮会参加的成员：本轮回答被采用的成员（待换人已替换）+ 新换上来的成员。界面的人数、接力顺序也用它。 */
    fun nextRoundMembers(): List<ArenaService> = RoundMaterials.nextParticipants(sessionServices, runs, pendingSwapState)

    private fun nextRoundNewcomers(participants: List<ArenaService>): Set<ArenaService> =
        pendingSwapState.filterKeys { it in sessionServices }.values.filter { it in participants }.toSet()

    /** 观点讨论 / 互相激发给某一位组装材料；新成员在材料缺原问题时补一句。超出上下文预算返回 null。 */
    private fun composeExchangePrompt(
        kind: RoundKind,
        style: DebateStyle?,
        target: ArenaService,
        base: Map<ArenaService, String>,
        index: Int,
        instruction: String,
        newcomer: Boolean,
    ): BudgetedPrompt? {
        val inspire = kind == RoundKind.INSPIRE
        return PromptBudgetPolicy.fit(
            target,
            initialQuoteLimit = if (inspire) ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS else ArenaLimits.MAX_QUOTED_RESPONSE_CHARS,
        ) { quoteLimit ->
            val prompt = if (inspire) {
                InspirePromptBuilder.build(target, base, index, instruction, quoteLimit, presets, originalQuestion)
            } else {
                DebatePromptBuilder.build(originalQuestion, target, base, index, instruction, quoteLimit, style ?: DebateStyle.DEBATE, presets)
            }
            if (newcomer) RoundMaterials.forNewcomer(prompt, originalQuestion) else prompt
        }
    }

    /** 同一种讨论方式 / 互相激发，在 [beforeRound] 之前已经做过几轮。 */
    private fun exchangeIndex(kind: RoundKind, style: DebateStyle?, beforeRound: Int): Int =
        history.count { round ->
            round.number < beforeRound && round.kind == kind &&
                (kind != RoundKind.DEBATE || (round.style ?: DebateStyle.DEBATE) == (style ?: DebateStyle.DEBATE))
        } + 1

    /** 观点讨论与互相激发共用：每位收到别人的回答（不含自己的），新成员收到全部。 */
    private fun startExchange(
        kind: RoundKind,
        answerMode: AnswerMode,
        guidance: String,
        attachments: List<ArenaAttachment>,
        style: DebateStyle?,
    ): Boolean {
        if (isBusy || stage != SessionStage.READY) return false
        val base = completedResponses()
        if (base.size < 2) {
            sessionMessage = "至少要有 ${ArenaService.MIN_MEMBERS} 份完整回答才能${kind.displayName}；先在成员卡片上重新提取或重新发送，或跳过不完整的"
            return false
        }
        val index = exchangeIndex(kind, style, roundNumber + 1)
        val instruction = AttachmentPromptPolicy.withDefault(guidance, attachments).take(ArenaLimits.MAX_GUIDANCE_CHARS)
        val services = nextRoundMembers()
        val newcomers = nextRoundNewcomers(services)
        val prompts = linkedMapOf<ArenaService, String>()
        var compressedCount = 0
        services.forEach { target ->
            val budgeted = composeExchangePrompt(kind, style, target, base, index, instruction, target in newcomers) ?: run {
                sessionMessage = "${target.displayName} 上下文超过 ${PromptBudgetPolicy.budgetFor(target)} 字，请缩短原问题或开始新问题"
                return false
            }
            prompts[target] = budgeted.text
            if (budgeted.compressed) compressedCount += 1
        }
        val started = startRound(kind, services, prompts, answerMode, instruction, attachments, style = style,
            newcomers = newcomers, question = instruction, baseResponses = base, roundIndex = index)
        if (started && compressedCount > 0) {
            currentRoundContextNotice = "已压缩 $compressedCount 家的引用回答"
            sessionMessage += " · $currentRoundContextNotice"
        }
        return started
    }

    /**
     * 互相激发：每位收到其他成员上一轮的完整回答当灵感，各自产出更好的独立答案，不追求一致。
     * 规则同观点讨论：至少两份完整回答，独立计轮，用户补充附在预设之后。
     */
    fun startInspire(
        answerMode: AnswerMode = AnswerMode.PARALLEL,
        guidance: String = "",
        attachments: List<ArenaAttachment> = emptyList(),
    ): Boolean = startExchange(RoundKind.INSPIRE, answerMode, guidance, attachments, style = null)

    /** 观点讨论：把其他 AI 的回答转给每一家让它们互相评论。各家平等；把大家收拢成一条的活交给「队长总结」。 */
    fun startDebate(
        answerMode: AnswerMode = currentAnswerMode,
        guidance: String = "",
        attachments: List<ArenaAttachment> = emptyList(),
        style: DebateStyle = DebateStyle.DEBATE,
    ): Boolean = startExchange(RoundKind.DEBATE, answerMode, guidance, attachments, style)

    /**
     * 「队长总结」：[preferredServices] 里第一位答完了的成员当队长（界面按用户选的队长排在最前），
     * 按 [depth] 选 prompt。喂给队长的是几家的**完整回答**（上限 [ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS]），
     * 只有超出该站上下文预算时才逐步压缩引用——家人反馈旧总结"浅"，一半原因就是只引用了片段。
     */
    fun startSummary(
        preferredServices: List<ArenaService>,
        customInstruction: String = "",
        depth: SummaryDepth = SummaryDepth.STANDARD,
        attachments: List<ArenaAttachment> = emptyList(),
    ): Boolean {
        if (isBusy || stage != SessionStage.READY) return false
        val responses = completedResponses()
        if (responses.size < 2) return false
        val judge = preferredServices.firstOrNull { it in responses.keys }
            ?: ArenaService.entries.firstOrNull { it in responses.keys }
            ?: return false
        val budgetedPrompt = PromptBudgetPolicy.fit(
            judge,
            initialQuoteLimit = ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS,
        ) { quoteLimit ->
            DiscussionSummaryPromptBuilder.build(
                originalQuestion = originalQuestion,
                history = history.toList(),
                responses = responses,
                customInstruction = AttachmentPromptPolicy.withDefault(customInstruction, attachments),
                quoteLimit = quoteLimit,
                depth = depth,
                presets = presets,
            )
        } ?: run {
            sessionMessage = "总结上下文超过 ${PromptBudgetPolicy.budgetFor(judge)} 字，请缩短原问题"
            return false
        }
        return sendSummary(judge, budgetedPrompt.text, depth, attachments, budgetedPrompt.compressed)
    }

    fun retrySummary(): Boolean {
        val previous = summary
        val judge = previous.judge ?: return false
        if (isBusy || previous.prompt.isBlank()) return false
        return sendSummary(judge, previous.prompt, previous.depth, previous.attachments, false)
    }

    private fun sendSummary(judge: ArenaService, prompt: String, depth: SummaryDepth, attachments: List<ArenaAttachment>, compressed: Boolean): Boolean {
        ArenaAttachmentPolicy.validate(attachments)?.let { sessionMessage = it; return false }
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
            detail = "正在请 ${judge.displayName} 做${depth.displayName}总结",
            depth = depth,
            prompt = prompt,
            attachments = attachments.toList(),
            roundNumber = history.lastOrNull()?.number ?: roundNumber,
        )
        sessionMessage = "正在请 ${judge.displayName} 做${depth.displayName}总结" +
            if (compressed) " · 已压缩引用回答" else ""
        schedulePersist()
        val sendTimeout = ArenaDeadline {
            if (isSummaryActive(execution) && summary.phase == ParticipantPhase.SENDING) {
                pool.cancelAutomation(judge)
                summary = summary.copy(phase = ParticipantPhase.ERROR, detail = "总结发送超时，已停止；可打开原网页确认")
                summaryExecution = null
                schedulePersist()
            }
        }
        handler.postDelayed(sendTimeout, if (attachments.isEmpty()) timing.sendTimeoutMillis else timing.attachmentSendTimeoutMillis)
        pool.sendPromptWithAttachments(judge, prompt, requestId, attachments) { outcome ->
            if (!isSummaryActive(execution)) return@sendPromptWithAttachments
            handler.removeCallbacks(sendTimeout)
            if (outcome.success) {
                summary = summary.copy(phase = ParticipantPhase.WAITING, detail = "等待总结回答")
                schedulePersist()
                pollSummary(execution)
            } else {
                summary = summary.copy(phase = ParticipantPhase.ERROR, detail = outcome.detail)
                summaryExecution = null
                sessionMessage = "队长总结失败"
                schedulePersist()
            }
        }
        return true
    }

    /** 用户明确触发后逐家处理失败项；已完成成员绝不重发，取消后队列立即作废。 */
    fun retryFailed(resend: Boolean): Boolean {
        if (isBusy || stage != SessionStage.READY) return false
        recoveryQueue.clear()
        sessionServices.filter { runs[it]?.phase == ParticipantPhase.ERROR }.forEach { service ->
            if (if (resend) !lastRoundPrompts[service].isNullOrBlank() else !runs[service]?.requestId.isNullOrBlank()) {
                recoveryQueue.addLast(service to resend)
            }
        }
        if (recoveryQueue.isEmpty()) {
            sessionMessage = if (resend) "旧记录缺少原轮发送内容，请保留历史开新会话"
            else "没有可定位的原回答，请打开 AI 网页查看，或保留历史开新会话"
            return false
        }
        return startNextFailedRecovery()
    }

    private fun startNextFailedRecovery(): Boolean {
        while (recoveryQueue.isNotEmpty() && runs[recoveryQueue.first().first]?.skipped == true) recoveryQueue.removeFirst()
        if (isBusy || recoveryQueue.isEmpty()) return false
        val (service, resend) = recoveryQueue.removeFirst()
        return startRecovery(service, if (resend) lastRoundPrompts[service] else null, resend)
    }

    /** 旧名字：等同于 [resend]。 */
    fun retrySend(service: ArenaService): Boolean = resend(service)

    /**
     * 逃生动作「重新发送」：把本轮这位成员该收到的内容再发一次。任何时候都可以点：
     * - 本轮进行中：只重发这一家，其他成员照常；工作流里还在排队的成员立即用当下已有的前面回答组装并发送。
     * - 本轮结束后：单家补救，可以和别家的补救、队长总结同时进行（队长本人正在总结时除外）。
     * 已收到的回答存进「之前的回答」，不会丢。是否先弹确认由界面按 [MemberActionPolicy.resendNeedsConfirm] 决定。
     */
    fun resend(service: ArenaService): Boolean {
        if (summaryOccupies(service)) return false
        activeRound()?.let { execution ->
            if (service !in execution.services) {
                sessionMessage = "${service.displayName} 本轮没有任务；要让它参加请用「换人」"
                return false
            }
            return resendInRound(execution, service)
        }
        if (stage != SessionStage.READY || (service !in sessionServices && runs[service]?.requestId.isNullOrBlank())) return false
        if (currentRoundRelay && service in relayOrder && service != relayOrder.first()) {
            // A member stopped or skipped before its turn still gets the question plus the earlier answers.
            val question = currentRelayQuestion()
            if (!question.isNullOrBlank()) {
                val earlier = RoundMaterials.earlierAnswers(relayOrder, service, runs)
                val budgeted = PromptBudgetPolicy.fit(service, initialQuoteLimit = ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS) { limit ->
                    RelayPromptBuilder.build(question, earlier, limit, presets).let {
                        if (service in roundNewcomers) RoundMaterials.forNewcomer(it, originalQuestion) else it
                    }
                } ?: run {
                    sessionMessage = "工作流材料超过 ${service.displayName} 的上下文预算（${PromptBudgetPolicy.budgetFor(service)} 字），没有发送"
                    return false
                }
                lastRoundPrompts = lastRoundPrompts + (service to budgeted.text.take(ArenaLimits.MAX_STORED_PROMPT_CHARS))
            }
        }
        val prompt = lastRoundPrompts[service]
        if (prompt.isNullOrBlank()) {
            sessionMessage = "缺少 ${service.displayName} 的原始发送内容，可重新开始问题"
            return false
        }
        stopRecovery(service)
        return startRecovery(service, prompt, resend = true)
    }

    /** 工作流这一轮的问题本身（不含新成员补的原问题）。 */
    private fun currentRelayQuestion(): String? = when (currentRoundKind) {
        RoundKind.INITIAL -> originalQuestion
        else -> history.lastOrNull { it.number == roundNumber }?.guidance?.takeIf { it.isNotBlank() }
    } ?: relayOrder.firstOrNull()?.let { lastRoundPrompts[it] }

    /** 正在进行、且仍有效的一轮；没有则 null。 */
    private fun activeRound(): RoundExecution? = activeExecution?.takeIf { isActive(it) }

    /** 队长正在用这家网页写总结：同一网页上不能再跑别的自动化。 */
    private fun summaryOccupies(service: ArenaService): Boolean {
        val active = summaryExecution?.takeIf { isSummaryActive(it) } ?: return false
        if (active.judge != service) return false
        sessionMessage = "${service.displayName} 正在当队长写总结，等总结完成或停止后再操作"
        return true
    }

    /** 停掉这一家正在进行的单家补救（不影响别家）。 */
    private fun stopRecovery(service: ArenaService) {
        if (recoveries.remove(service) != null) pool.cancelAutomation(service)
    }

    /** 新对话因网页输入框里的草稿停下时的草稿原文，供界面原样展示给用户确认。 */
    fun blockingDraft(service: ArenaService): String? =
        pool.freshConversationDraft(service)?.takeIf {
            currentRoundKind == RoundKind.INITIAL && runs[service]?.phase == ParticipantPhase.ERROR
        }

    /** 用户确认过这段草稿可以被本轮问题替换后重发；网页草稿若已变化，新对话准备仍会停下。 */
    fun retrySendReplacingDraft(service: ArenaService, draft: String): Boolean {
        if (draft.isBlank() || blockingDraft(service) != draft) return false
        if (isBusy || stage != SessionStage.READY || service !in sessionServices) return false
        // Grant the one-shot permission only when the resend can actually start.
        if (lastRoundPrompts[service].isNullOrBlank()) return retrySend(service)
        pool.allowFreshDraftReplacement(service, draft)
        return retrySend(service)
    }

    /** 当前这一轮是什么时候开始的；时光机给本轮标时间用。 */
    val currentRoundStartedAtMillis: Long
        get() = activeExecution?.takeIf { it.number == roundNumber }?.startedAtMillis
            ?: history.lastOrNull { it.number == roundNumber }?.startedAtMillis ?: 0L

    /**
     * 开始新一轮前把已完成的综合答案归档到它依据的那一轮，免得追问后就再也看不到。
     * 老文件的综合没有轮次记录时，归到最近一轮。
     */
    private fun archiveSummary() {
        val done = summary.takeIf { it.phase == ParticipantPhase.COMPLETE && it.text.isNotBlank() } ?: return
        val index = history.indexOfLast { it.number == done.roundNumber }
            .takeIf { it >= 0 } ?: history.lastIndex.takeIf { done.roundNumber == 0 } ?: return
        if (index < 0) return
        history[index] = history[index].copy(summary = done)
    }

    /** 旧名字：等同于 [reextract]。 */
    fun retryExtraction(service: ArenaService): Boolean = reextract(service)

    /**
     * 逃生动作「重新提取」：只读，从这家网页重新读本轮回答，绝不重发。正在回答时也能点：重新开始读取，
     * 不打断网页生成。网页最新提问对不上时由网页池按本轮请求号核对，读不到就明确报错，不会读别的问题。
     */
    fun reextract(service: ArenaService): Boolean {
        if (summaryOccupies(service)) return false
        val run = runs[service] ?: return false
        activeRound()?.let { execution ->
            if (service !in execution.services) {
                sessionMessage = "${service.displayName} 本轮没有任务，没有可提取的回答"
                return false
            }
            return reextractInRound(execution, service, run)
        }
        if (stage != SessionStage.READY) return false
        if (run.requestId.isBlank()) {
            sessionMessage = "${service.displayName} 没有可重新提取的请求"
            return false
        }
        if (!MemberActionPolicy.canReextract(run)) {
            sessionMessage = "${service.displayName} 本轮问题没有发出去，没有可提取的回答；可以点「重新发送」"
            return false
        }
        recoveries[service]?.let { running ->
            if (running.resend && runs[service]?.phase in setOf(ParticipantPhase.QUEUED, ParticipantPhase.SENDING)) {
                sessionMessage = "${service.displayName} 正在重新发送，发出后会自动读取回答"
                return false
            }
            recoveries.remove(service)
        }
        return startRecovery(service, prompt = null, resend = false)
    }

    private fun reextractInRound(execution: RoundExecution, service: ArenaService, run: ParticipantRun): Boolean {
        if (run.phase == ParticipantPhase.QUEUED || run.phase == ParticipantPhase.SENDING) {
            sessionMessage = "${service.displayName} 还在${if (run.phase == ParticipantPhase.QUEUED) "准备" else "发送"}，发出后会自动读取回答"
            return false
        }
        if (!MemberActionPolicy.canReextract(run)) {
            sessionMessage = "${service.displayName} 本轮问题没有发出去，没有可提取的回答；可以点「重新发送」"
            return false
        }
        execution.skipped -= service
        execution.dispatchedServices += service
        if (run.phase == ParticipantPhase.COMPLETE && run.response.isNotBlank()) execution.reextractBackups[service] = run
        runs[service] = run.copy(phase = ParticipantPhase.WAITING, skipped = false, stopped = false, detail = "正在重新提取")
        sessionMessage = "正在重新提取 ${service.displayName} 的回答，其他成员照常进行"
        startPolling(execution, service, run.requestId)
        schedulePersist()
        return true
    }

    /** 旧名字：等同于 [skip]。 */
    fun skipService(service: ArenaService): Boolean = skip(service)

    /** 旧名字：等同于 [skip]。 */
    fun skipRunning(service: ArenaService): Boolean = skip(service)

    /**
     * 逃生动作「跳过」：本轮不再等它，之后的讨论 / 激发 / 总结 / 工作流都不带它。任何状态都可点：
     * 正在回答时只停这一家，其他照常；已完成的回答也可跳过（本轮不采用，文字保留）。之后点重新提取 / 重新发送可恢复。
     */
    fun skip(service: ArenaService): Boolean {
        val run = runs[service] ?: return false
        if (run.skipped) {
            sessionMessage = "${service.displayName} 本轮已经跳过；要恢复请点「重新提取」或「重新发送」"
            return false
        }
        activeRound()?.let { execution ->
            if (service !in execution.services) return false
            return skipInRound(execution, service, run)
        }
        if (stage != SessionStage.READY || run.requestId.isBlank()) return false
        stopRecovery(service)
        val skipped = run.copy(phase = ParticipantPhase.ERROR, skipped = true, detail = "已跳过本轮")
        runs[service] = skipped
        updateLatestRoundResult(service, skipped)
        sessionMessage = "已跳过 ${service.displayName}，其他结果仍保留"
        schedulePersist(immediate = true)
        return true
    }

    /**
     * 本轮进行中跳过一家：只停掉这一家，其他成员照常进行、照常收尾。
     * 停掉的是 App 这边的等待和网页自动化；网页里可能仍在生成，之后可「重新提取」或「重新发送」把它拉回来。
     */
    private fun skipInRound(execution: RoundExecution, service: ArenaService, run: ParticipantRun): Boolean {
        execution.skipped += service
        execution.reextractBackups.remove(service)
        pollStates.remove(service)
        if (run.phase.isTerminal()) {
            runs[service] = run.copy(phase = ParticipantPhase.ERROR, skipped = true, detail = "已跳过本轮")
            sessionMessage = "已跳过 ${service.displayName}，本轮不采用它的回答"
            schedulePersist()
            return true
        }
        pool.cancelAutomation(service)
        execution.dispatchedServices += service
        if (execution.answerMode == AnswerMode.PARALLEL) {
            execution.dispatchComplete = execution.dispatchedServices.containsAll(execution.services)
        }
        sessionMessage = "已跳过 ${service.displayName}，其他成员照常进行"
        markTerminal(execution, service, run.copy(
            phase = ParticipantPhase.ERROR,
            skipped = true,
            detail = if (run.phase == ParticipantPhase.QUEUED) "已跳过，这一家本轮没有发送"
            else "已跳过本轮；网页可能仍在生成，可稍后重新读取",
        ))
        return true
    }

    /** 换人对话框用：这位成员现在换人，是本轮马上接手还是只从下一轮起。 */
    fun swapTiming(service: ArenaService): SwapTiming = MemberActionPolicy.swapTiming(runs[service] ?: ParticipantRun())

    /** 可以换上来的 AI：不在成员表里、也没有被别的位置预约。 */
    fun swapCandidates(from: ArenaService): List<ArenaService> = ArenaService.entries.filter { candidate ->
        candidate !in sessionServices && pendingSwapState.none { (key, value) -> value == candidate && key != from } &&
            // 本轮刚被换下的成员本轮不再换回来（它这一轮的任务已作废），下一轮起可以。
            runs[candidate]?.let { it.replacedBy != null && it.requestId.isNotBlank() } != true
    }

    /**
     * 逃生动作「换人」：选另一家 AI 接替 [from] 的位置，成员数不变。
     * - [from] 本轮还在排队 / 进行中 / 出错：[to] 立刻接手本轮，收到与该位置相同的材料（讨论、激发按新成员重新组装，
     *   别人的回答全给它；工作流在同一位置接力），[from] 记为「已跳过 · 已换成 X」。
     * - [from] 本轮已完成、已跳过、或整轮被停止：只从下一轮起换人，本轮不自动发送。
     * [to] 等于 [from] 表示取消已预约的下一轮换人。
     */
    fun swap(from: ArenaService, to: ArenaService): Boolean {
        if (from !in sessionServices) {
            sessionMessage = "${from.displayName} 已经不在成员里"
            return false
        }
        if (to == from) {
            val cancelled = pendingSwapState.remove(from) != null
            if (cancelled) { sessionMessage = "已取消换人，下一轮仍由 ${from.displayName} 参加"; schedulePersist(immediate = true) }
            return cancelled
        }
        if (to !in swapCandidates(from)) {
            sessionMessage = "${to.displayName} 已经在成员里，或已被安排接替别的位置"
            return false
        }
        if (stage == SessionStage.IDLE) return false
        if (summaryOccupies(from)) return false
        val run = runs[from] ?: ParticipantRun()
        if (MemberActionPolicy.swapTiming(run) == SwapTiming.NEXT_ROUND) {
            pendingSwapState[from] = to
            sessionMessage = "已安排：从下一轮起由 ${to.displayName} 接替 ${from.displayName}"
            schedulePersist(immediate = true)
            return true
        }
        pendingSwapState.remove(from)
        activeRound()?.let { execution ->
            if (from in execution.services) return swapInRound(execution, from, to)
        }
        if (stage != SessionStage.READY) return false
        return swapAfterRound(from, to, run)
    }

    /** 成员表里把 [from] 换成 [to]，位置不变。 */
    private fun replaceMember(from: ArenaService, to: ArenaService) {
        sessionServices = sessionServices.map { if (it == from) to else it }.distinct()
        if (relayOrder.isNotEmpty()) relayOrder = relayOrder.map { if (it == from) to else it }
    }

    private fun swapInRound(execution: RoundExecution, from: ArenaService, to: ArenaService): Boolean {
        val run = runs.getValue(from)
        // 先把新成员的材料组装好；放不下就不换，旧成员原样继续。
        val prompt: String? = when {
            execution.relay -> "" // 轮到这个位置时再按前面各位的回答组装
            execution.kind == RoundKind.DEBATE || execution.kind == RoundKind.INSPIRE ->
                composeExchangePrompt(execution.kind, execution.style, to, execution.baseResponses, execution.roundIndex, execution.guidance, newcomer = true)?.text
            else -> RoundMaterials.forNewcomer(execution.question, originalQuestion)
        }
        if (prompt == null) {
            sessionMessage = "${to.displayName} 上下文预算不够放下本轮材料（${PromptBudgetPolicy.budgetFor(to)} 字），没有换人"
            return false
        }
        val fromWasDispatched = from in execution.dispatchedServices
        // 先登记「已换下」再取消网页自动化：取消可能同步回调旧的「新对话未就绪」，必须被当作过期结果丢掉。
        execution.skipped += from
        execution.dispatchedServices += from
        execution.freshGeneration[from] = (execution.freshGeneration[from] ?: 0) + 1
        execution.reextractBackups.remove(from)
        pollStates.remove(from)
        runs[from] = run.copy(phase = ParticipantPhase.ERROR, skipped = true, replacedBy = to,
            detail = "已跳过 · 已换成 ${to.displayName}")
        pool.cancelAutomation(from)
        replaceMember(from, to)
        execution.services.add(execution.services.indexOf(from) + 1, to)
        val position = execution.dispatchOrder.indexOf(from)
        if (position >= 0) execution.dispatchOrder[position] = to else execution.dispatchOrder += to
        execution.newcomers += to
        roundNewcomers = roundNewcomers + to
        val requestId = buildRequestId(execution.kind, execution.number, to)
        execution.requestIds[to] = requestId
        if (!execution.relay) {
            execution.prompts[to] = prompt
            lastRoundPrompts = lastRoundPrompts + (to to prompt.take(ArenaLimits.MAX_STORED_PROMPT_CHARS))
        }
        runs[to] = ParticipantRun(phase = ParticipantPhase.QUEUED, requestId = requestId,
            detail = "接替 ${from.shortName}，正在打开新对话…")
        pool.setProtectedServices(execution.services.toSet())
        sessionMessage = "${to.displayName} 接替 ${from.displayName}，马上接手本轮"
        if (execution.answerMode == AnswerMode.PARALLEL) {
            execution.dispatchComplete = false
            prepareFresh(execution, to) { dispatchParallelService(execution, to) }
        } else if (fromWasDispatched) {
            // 工作流已经轮过这个位置：新成员在同一位置立刻接力，后面的成员等它答完再继续。
            execution.dispatchedServices += to
            prepareFresh(execution, to) { sendOutOfLine(execution, to) }
        } else {
            prepareFresh(execution, to) { dispatchSerialNext(execution) }
        }
        schedulePersist()
        // 被换下的那位如果正占着串行队列，队列由新成员接着走。
        if (execution.answerMode == AnswerMode.SERIAL) dispatchSerialNext(execution) else maybeFinishRound(execution)
        return true
    }

    /** 串行 / 工作流里不按队列顺序立刻发出某一位（换人接手、排队成员重新发送）。 */
    private fun sendOutOfLine(execution: RoundExecution, service: ArenaService) {
        if (!isActive(execution) || service in execution.skipped) return
        if (execution.relay && !composeRelayPrompt(execution, service)) {
            dispatchSerialNext(execution)
            return
        }
        sendService(execution, service) { sent -> if (!sent) dispatchSerialNext(execution) }
    }

    /** 本轮已结束、旧成员出错：新成员单独接手这一轮，结果并入本轮记录。 */
    private fun swapAfterRound(from: ArenaService, to: ArenaService, run: ParticipantRun): Boolean {
        val kind = currentRoundKind ?: return false
        val round = history.lastOrNull { it.number == roundNumber }
        val guidance = round?.guidance.orEmpty()
        val order = relayOrder.map { if (it == from) to else it }
        val budgeted: BudgetedPrompt? = when {
            currentRoundRelay -> {
                val question = currentRelayQuestion().orEmpty()
                val earlier = RoundMaterials.earlierAnswers(order, to, runs)
                PromptBudgetPolicy.fit(to, initialQuoteLimit = ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS) { limit ->
                    RoundMaterials.forNewcomer(RelayPromptBuilder.build(question, earlier, limit, presets), originalQuestion)
                }
            }
            kind == RoundKind.DEBATE || kind == RoundKind.INSPIRE -> {
                val base = RoundMaterials.adopted(history.lastOrNull { it.number < roundNumber }?.results.orEmpty())
                if (base.isEmpty()) null
                else composeExchangePrompt(kind, currentRoundStyle, to, base, exchangeIndex(kind, currentRoundStyle, roundNumber), guidance, newcomer = true)
            }
            kind == RoundKind.INITIAL -> BudgetedPrompt(originalQuestion, false, originalQuestion.length, PromptBudgetPolicy.budgetFor(to), 0)
            else -> (guidance.ifBlank { lastRoundPrompts[from].orEmpty() }).takeIf { it.isNotBlank() }?.let {
                val text = RoundMaterials.forNewcomer(it, originalQuestion)
                BudgetedPrompt(text, false, text.length, PromptBudgetPolicy.budgetFor(to), 0)
            }
        }
        if (budgeted == null || budgeted.text.isBlank()) {
            sessionMessage = "没能为 ${to.displayName} 组装本轮材料，没有换人；可以先「重新发送」${from.displayName}"
            return false
        }
        stopRecovery(from)
        val replaced = run.copy(phase = ParticipantPhase.ERROR, skipped = true, replacedBy = to, detail = "已跳过 · 已换成 ${to.displayName}")
        runs[from] = replaced
        updateLatestRoundResult(from, replaced)
        replaceMember(from, to)
        roundNewcomers = roundNewcomers + to
        lastRoundPrompts = lastRoundPrompts + (to to budgeted.text.take(ArenaLimits.MAX_STORED_PROMPT_CHARS))
        runs[to] = ParticipantRun(detail = "接替 ${from.shortName}")
        val started = startRecovery(to, budgeted.text, resend = true)
        if (started) sessionMessage = "${to.displayName} 接替 ${from.displayName}，马上接手本轮"
        return started
    }

    fun cancelCurrentRound() {
        recoveryQueue.clear()
        val activeSummary = summaryExecution
        if (activeSummary != null && isSummaryActive(activeSummary)) {
            // 只停队长这一家；别家可能正在单家补救，不能一起打断。
            pool.cancelAutomation(activeSummary.judge)
            summary = summary.copy(phase = ParticipantPhase.ERROR, detail = "已停止总结")
            summaryExecution = null
            sessionMessage = "已停止讨论总结"
            schedulePersist()
            return
        }
        if (recoveries.isNotEmpty()) {
            handler.removeCallbacksAndMessages(null)
            pool.cancelAutomation()
            recoveries.values.toList().forEach { active ->
                finishRecovery(
                    active,
                    runs.getValue(active.service).copy(
                        phase = ParticipantPhase.ERROR,
                        stopped = true,
                        detail = "已停止单家补救；网页可能仍在生成",
                    ),
                )
            }
            sessionEpoch += 1
            return
        }
        val execution = activeExecution ?: return
        if (!isBusy) return
        handler.removeCallbacksAndMessages(null)
        pool.cancelAutomation()
        pollStates.clear()
        execution.services.forEach { service ->
            val run = runs.getValue(service)
            // 整轮被用户停止：没答完的成员都记一笔，之后「换人」只从下一轮起生效，不自动发送。
            if (run.phase == ParticipantPhase.ERROR) runs[service] = run.copy(stopped = true)
            if (!run.phase.isTerminal()) {
                runs[service] = run.copy(
                    phase = ParticipantPhase.ERROR,
                    stopped = true,
                    detail = if (run.phase == ParticipantPhase.QUEUED) {
                        "已停止，这一家还没来得及发送"
                    } else {
                        "已停止等待；网页可能仍在生成"
                    },
                )
            }
        }
        execution.dispatchComplete = true
        finishRound(execution, forcedMessage = "已停止本轮，已保留现有结果")
        sessionEpoch += 1
    }

    fun reset(): Boolean {
        recoveryQueue.clear()
        // 必须同步写完：后面紧接着要把活动会话清空，异步落盘会把它又设回去。
        if (!persistNow(synchronous = true)) {
            sessionMessage = "未能保存当前讨论，请稍后再试；当前内容仍保留"
            return false
        }
        persistGeneration += 1
        persistenceHandler.removeCallbacksAndMessages(null)
        pool.setProtectedServices(emptySet())
        pool.cancelAutomation()
        sessionRepository?.setActiveSession(null)
        sessionId = ""
        sessionEpoch += 1
        handler.removeCallbacksAndMessages(null)
        pollStates.clear()
        activeExecution = null
        summaryExecution = null
        recoveries.clear()
        pendingSwapState.clear()
        roundNewcomers = emptySet()
        stage = SessionStage.IDLE
        askedAtMillis = 0L
        currentRoundKind = null
        currentAnswerMode = AnswerMode.PARALLEL
        roundNumber = 0
        originalQuestion = ""
        sessionMessage = "等待开始"
        history.clear()
        summary = DiscussionSummary()
        lastRoundAttachments = emptyList()
        lastRoundPrompts = emptyMap()
        conversationUrls.clear()
        currentRoundContextNotice = ""
        sessionServices = ArenaService.defaultMembers
        storageWarning = null
        ArenaService.entries.forEach { service -> runs[service] = ParticipantRun() }
        refreshRecentSessions()
        return true
    }

    fun destroy() {
        recoveryQueue.clear()
        pool.cancelAutomation()
        pool.setProtectedServices(emptySet())
        // 进程随时可能被回收，最后这一次必须同步写完，不能交给后台线程。
        persistShutdown = true
        persistNow()
        persistExecutor.shutdown()
        persistenceHandler.removeCallbacksAndMessages(null)
        sessionEpoch += 1
        handler.removeCallbacksAndMessages(null)
        pollStates.clear()
        activeExecution = null
        summaryExecution = null
        recoveries.clear()
    }

    /**
     * 打开一条历史会话。
     *
     * 原来这里是 `if (isBusy) return false`，调用方只能笼统提示"该历史记录无法恢复" ——
     * 用户刚问完一轮、总结还在跑的时候点历史，必然撞上这条，而且看不出是为什么。
     * 现在忙的时候先把本轮停掉再打开（这正是用户点"继续"想表达的意思），
     * 真失败时也回具体原因，让界面能说人话。
     */
    fun restoreSession(id: String): RestoreOutcome {
        val repository = sessionRepository ?: return RestoreOutcome.NO_STORAGE
        val interrupted = isBusy
        if (interrupted) cancelCurrentRound()
        val snapshot = repository.load(id)
        if (snapshot == null) {
            // 索引里有、文件读不出来：留着它只会让用户每次点每次失败。
            repository.forget(id)
            refreshRecentSessions()
            return RestoreOutcome.UNREADABLE
        }
        if (!persistNow(synchronous = true)) return RestoreOutcome.SAVE_FAILED
        persistGeneration += 1
        applySnapshot(snapshot, recovered = false)
        repository.setActiveSession(snapshot.id)
        reopenConversations()
        schedulePersist()
        return if (interrupted) RestoreOutcome.OK_AFTER_STOP else RestoreOutcome.OK
    }

    private fun startRound(
        kind: RoundKind,
        services: List<ArenaService>,
        prompts: Map<ArenaService, String>,
        answerMode: AnswerMode,
        guidance: String,
        attachments: List<ArenaAttachment> = emptyList(),
        relayOrder: List<ArenaService>? = null,
        style: DebateStyle? = null,
        /** 中途换上来的新成员：先开新对话再发送。 */
        newcomers: Set<ArenaService> = emptySet(),
        /** 本轮的问题本身（首轮 = 原问题，独立迭代 = 本轮问题，讨论 / 激发 = 用户补充）；换人和工作流组装用。 */
        question: String = guidance,
        baseResponses: Map<ArenaService, String> = emptyMap(),
        roundIndex: Int = 1,
    ): Boolean {
        // 只有独立迭代允许单独一家（其他成员被跳过后的逃生通道）；提问、讨论、工作流都要至少两家。
        val minimum = if (kind == RoundKind.ITERATION && relayOrder == null) 1 else 2
        if (isBusy || services.size < minimum || services.any { prompts[it].isNullOrBlank() }) return false
        // 工作流：顺序必须恰好是本轮成员；第 1 位直接收到问题，之后每位轮到时再组装「问题 + 前面各位的回答」。
        val relay = relayOrder != null && relayOrder.size == services.size && relayOrder.toSet() == services.toSet()
        if (relayOrder != null && !relay) {
            sessionMessage = "工作流顺序与本轮成员不一致，请重新排序"
            return false
        }
        @Suppress("NAME_SHADOWING")
        val answerMode = if (relay) AnswerMode.SERIAL else answerMode
        ArenaAttachmentPolicy.validate(attachments)?.let { sessionMessage = it; return false }

        sessionEpoch += 1
        handler.removeCallbacksAndMessages(null)
        pollStates.clear()
        archiveSummary()
        summary = DiscussionSummary()
        currentRoundContextNotice = ""
        roundNumber += 1
        currentRoundKind = kind
        currentRoundRelay = relay
        currentRoundStyle = style
        this.relayOrder = if (relay) relayOrder!!.toList() else emptyList()
        currentAnswerMode = answerMode
        // 预约的换人从这一轮起生效：成员表位置不变地换掉旧成员。
        if (pendingSwapState.isNotEmpty()) {
            sessionServices = RoundMaterials.swappedRoster(sessionServices, pendingSwapState)
            pendingSwapState.clear()
        }
        roundNewcomers = newcomers
        recoveries.clear()
        stage = when (kind) {
            RoundKind.INITIAL -> SessionStage.INITIAL
            RoundKind.ITERATION -> SessionStage.ITERATION
            RoundKind.DEBATE -> SessionStage.DEBATE
            RoundKind.INSPIRE -> SessionStage.INSPIRE
        }
        sessionMessage = when {
            relay -> "第 $roundNumber 轮工作流：${services.size} 家按顺序接力回答"
            answerMode == AnswerMode.PARALLEL -> "第 $roundNumber 轮：${services.size} 家正在独立发送和回答"
            else -> "正在串行执行第 $roundNumber 轮"
        }

        val dispatchOrder = if (relay) relayOrder!! else services
        val execution = RoundExecution(
            epoch = sessionEpoch,
            number = roundNumber,
            kind = kind,
            answerMode = answerMode,
            services = services.toMutableList(),
            dispatchOrder = dispatchOrder.toMutableList(),
            prompts = prompts.toMutableMap(),
            attachments = attachments.toList(),
            guidance = guidance.take(ArenaLimits.MAX_GUIDANCE_CHARS),
            relay = relay,
            style = style,
            startedAtMillis = System.currentTimeMillis(),
            requestIds = services.associateWith { service -> buildRequestId(kind, roundNumber, service) }.toMutableMap(),
            newcomers = newcomers.toMutableSet(),
            question = question,
            baseResponses = baseResponses,
            roundIndex = roundIndex,
        )
        lastRoundAttachments = attachments.toList()
        // 所有参与者立即有请求号和准备状态；并行发送不等其他成员的发送回调。
        ArenaService.entries.forEach { service ->
            runs[service] = if (service in services) {
                ParticipantRun(
                    phase = ParticipantPhase.QUEUED,
                    requestId = execution.requestIds.getValue(service),
                    detail = queuedDetail(execution, service),
                )
            } else {
                ParticipantRun(ParticipantPhase.IDLE, detail = "本轮未参与")
            }
        }
        lastRoundPrompts = prompts.mapValues { (_, prompt) -> prompt.take(ArenaLimits.MAX_STORED_PROMPT_CHARS) }
        activeExecution = execution
        pool.setProtectedServices(services.toSet())
        schedulePersist()
        if (kind == RoundKind.INITIAL) {
            // 新问题必须发进干净的新对话，否则 AI 带着上一题的上下文作答
            prepareFreshConversations(execution, execution.services)
        } else {
            // 新换上来的成员网页里没有这场讨论：先开新对话，其余成员照常在原对话里继续。
            if (newcomers.isNotEmpty()) prepareFreshConversations(execution, execution.services.filter { it in newcomers })
            when (answerMode) {
                AnswerMode.PARALLEL -> execution.dispatchOrder.filter { it !in newcomers }.forEach { dispatchParallelService(execution, it) }
                AnswerMode.SERIAL -> dispatchSerialNext(execution)
            }
        }
        return true
    }

    /** 这一位发送前要不要先开新对话：首轮全体；之后只有中途换上来的新成员。 */
    private fun needsFresh(execution: RoundExecution, service: ArenaService): Boolean =
        execution.kind == RoundKind.INITIAL || service in execution.newcomers

    /**
     * 本轮进行中重新发送一位：旧回答存进「之前的回答」，换新请求号重发；工作流按当下已有的前面回答重新组装。
     * 串行 / 工作流里还在排队的成员立即发送，不再等前面的人；其他成员照常进行。
     */
    private fun resendInRound(execution: RoundExecution, service: ArenaService): Boolean {
        val previous = runs.getValue(service)
        // 先换新请求号、作废旧的开新对话结果，再取消网页自动化：取消时同步回来的旧失败一律对不上号而被丢弃。
        val requestId = buildRequestId(execution.kind, execution.number, service)
        execution.requestIds[service] = requestId
        execution.freshGeneration[service] = (execution.freshGeneration[service] ?: 0) + 1
        execution.reextractBackups.remove(service)
        pollStates.remove(service)
        execution.skipped -= service
        execution.dispatchedServices += service
        if (execution.answerMode == AnswerMode.PARALLEL) {
            execution.dispatchComplete = execution.dispatchedServices.containsAll(execution.services)
        }
        runs[service] = RoundMaterials.keepPrevious(previous).copy(
            phase = ParticipantPhase.QUEUED,
            requestId = requestId,
            skipped = false,
            stopped = false,
            replacedBy = null,
            detail = if (needsFresh(execution, service)) "重新发送前正在打开新对话…" else "准备重新发送",
        )
        pool.cancelAutomation(service)
        sessionMessage = "正在单独重新发送给 ${service.displayName}，其他成员照常进行"
        schedulePersist()
        val send = {
            if (execution.answerMode == AnswerMode.SERIAL) sendOutOfLine(execution, service)
            else sendService(execution, service) { maybeFinishRound(execution) }
        }
        if (needsFresh(execution, service)) {
            execution.freshReadiness.remove(service)
            prepareFresh(execution, service) { send() }
        } else {
            send()
        }
        return true
    }

    /** 准备网页与串行等待是两种状态，不再给并行成员标发送排位。 */
    private fun queuedDetail(execution: RoundExecution, service: ArenaService): String {
        val position = execution.dispatchOrder.indexOf(service) + 1
        return when {
            execution.answerMode == AnswerMode.PARALLEL -> "已收到，准备独立发送"
            position <= 1 -> "已收到，马上发送"
            else -> "已收到，等上一家答完再发"
        }
    }

    /**
     * 每家独立开新对话，就绪的一家可立即发送；失败与丢失回调只影响本家。
     * 不在新对话尚未确认时冒险发送，否则可能串入上一个问题。
     */
    private fun prepareFreshConversations(execution: RoundExecution, services: List<ArenaService>) {
        services.forEach { service ->
            if (execution.kind == RoundKind.INITIAL) runs[service] = runs.getValue(service).copy(detail = "已收到，正在打开新对话…")
            prepareFresh(execution, service) {
                when (execution.answerMode) {
                    AnswerMode.PARALLEL -> dispatchParallelService(execution, service)
                    AnswerMode.SERIAL -> {
                        runs[service] = runs.getValue(service).copy(detail = queuedDetail(execution, service))
                        dispatchSerialNext(execution)
                    }
                }
            }
        }
    }

    /**
     * 给一位成员开新对话，结果记入 freshReadiness 后调用 [onSettled]（成功或失败都调用，发送时再按结果决定）。
     * 同一位重新准备（重新发送、换人）后，旧的迟到结果一律作废。
     */
    private fun prepareFresh(execution: RoundExecution, service: ArenaService, onSettled: () -> Unit) {
        val generation = (execution.freshGeneration[service] ?: 0) + 1
        execution.freshGeneration[service] = generation
        var settled = false
        fun ready(ok: Boolean) {
            if (settled || !isActive(execution) || execution.freshGeneration[service] != generation) return
            settled = true
            execution.freshReadiness[service] = ok
            // Skipping already moved the round on (markTerminal); a late page result changes nothing.
            if (service in execution.skipped) return
            onSettled()
        }
        val timeout = ArenaDeadline { ready(false) }
        handler.postDelayed(timeout, timing.freshConversationTimeoutMillis)
        pool.openFreshConversation(service) { ok ->
            handler.removeCallbacks(timeout)
            ready(ok)
        }
    }

    private fun rememberConversationUrl(service: ArenaService) {
        val url = pool.conversationUrl(service)
        if (url.isNotBlank()) conversationUrls[service] = url
    }

    /** 把各家网页切回这条讨论当时的对话。返回切了几家。 */
    private fun reopenConversations(): Int {
        val targets = conversationUrls.filterKeys { it in sessionServices }
        targets.forEach { (service, url) -> pool.openConversation(service, url) { } }
        return targets.size
    }

    private fun startRecovery(
        service: ArenaService,
        prompt: String?,
        resend: Boolean,
    ): Boolean {
        // 每家补救彼此独立：不清别家的计时器，也不作废正在进行的队长总结。
        recoveries.remove(service)
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
            previousResponse = previous.response,
            previousTruncated = previous.responseTruncated,
            previousOriginalLength = previous.originalResponseLength,
            previousRun = previous,
        )
        recoveries[service] = execution
        val fresh = resend && (currentRoundKind == RoundKind.INITIAL || service in roundNewcomers)
        // 重新发送：已收到的回答存进「之前的回答」，不丢。
        val base = if (resend) RoundMaterials.keepPrevious(previous) else previous
        runs[service] = base.copy(
            phase = if (!resend) ParticipantPhase.WAITING else if (fresh) ParticipantPhase.QUEUED else ParticipantPhase.SENDING,
            requestId = requestId,
            skipped = false,
            stopped = false,
            replacedBy = null,
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
        var sendSettled = false
        val sendTimeout = ArenaDeadline {
            if (isRecoveryActive(execution) && runs[service]?.phase == ParticipantPhase.SENDING) {
                sendSettled = true
                pool.cancelAutomation(service)
                finishRecovery(execution, runs.getValue(service).copy(phase = ParticipantPhase.ERROR, detail = "重发超时，已停止；请打开原网页确认"))
            }
        }
        val send = send@{
            if (sendSettled || !isRecoveryActive(execution)) return@send
            runs[service] = runs.getValue(service).copy(phase = ParticipantPhase.SENDING, detail = "正在重发")
            handler.postDelayed(sendTimeout, if (lastRoundAttachments.isEmpty()) timing.sendTimeoutMillis else timing.attachmentSendTimeoutMillis)
            pool.sendPromptWithAttachments(service, prompt.orEmpty(), requestId, lastRoundAttachments) { outcome ->
                if (sendSettled || !isRecoveryActive(execution)) return@sendPromptWithAttachments
                sendSettled = true
                handler.removeCallbacks(sendTimeout)
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
        }
        if (fresh) {
            runs[service] = runs.getValue(service).copy(detail = "重发前正在确认新对话")
            var freshSettled = false
            fun ready(ok: Boolean) {
                if (freshSettled || sendSettled || !isRecoveryActive(execution)) return
                freshSettled = true
                if (ok) send() else {
                    sendSettled = true
                    handler.removeCallbacks(sendTimeout)
                    finishRecovery(execution, runs.getValue(service).copy(
                        phase = ParticipantPhase.ERROR,
                        detail = pool.freshConversationFailure(service)?.let { "重发前：$it" } ?: "重发前新对话未能就绪，未发送；请打开原网页确认",
                    ))
                }
            }
            val freshTimeout = ArenaDeadline { ready(false) }
            handler.postDelayed(freshTimeout, timing.freshConversationTimeoutMillis)
            pool.openFreshConversation(service) { ok ->
                handler.removeCallbacks(freshTimeout)
                ready(ok)
            }
        } else send()
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
                val shownText = if (snapshot.streaming) snapshot.text else snapshot.settledText
                if (shownText == execution.lastText) execution.stableCount += 1 else {
                    execution.lastText = shownText
                    execution.stableCount = 0
                }
                val current = runs.getValue(execution.service)
                val updated = current.copy(
                    phase = if (snapshot.streaming) ParticipantPhase.STREAMING else ParticipantPhase.WAITING,
                    response = shownText,
                    detail = if (snapshot.streaming) "正在补全回答" else "正在确认补救结果",
                    responseTruncated = snapshot.truncated,
                    originalResponseLength = snapshot.originalLength,
                    modeLabel = snapshot.modeLabel.ifBlank { current.modeLabel },
                    thinkingUsed = current.thinkingUsed || snapshot.thinkingUsed,
                )
                runs[execution.service] = updated
                schedulePersist()
                if (!snapshot.streaming && execution.stableCount >= requiredStablePolls(snapshot)) {
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
        // 重发前已把旧回答存进「之前的回答」；这次没拿到内容时说明一句，旧回答仍可在卡片下方展开查看。
        val backup = execution.previousRun
        val restored = if (
            !execution.resend && result.phase == ParticipantPhase.ERROR &&
            backup != null && backup.phase == ParticipantPhase.COMPLETE && backup.response.isNotBlank()
        ) {
            // 重新提取一份已完成的回答却没读到：原回答原样保留，不降成失败。
            backup.copy(detail = "${backup.detail}（重新提取没读到新内容，保留原回答）")
        } else if (
            execution.resend &&
            result.phase == ParticipantPhase.ERROR &&
            result.response.isBlank() &&
            execution.previousResponse.isNotBlank()
        ) {
            result.copy(detail = "${result.detail}；之前的回答已保留")
        } else {
            result
        }
        runs[execution.service] = restored
        recoveries.remove(execution.service)
        updateLatestRoundResult(execution.service, restored)
        sessionMessage = when (restored.phase) {
            ParticipantPhase.COMPLETE -> "${execution.service.displayName} 单家补救完成"
            else -> "${execution.service.displayName} 单家补救未完成，其他结果仍保留"
        }
        schedulePersist(immediate = true)
        startNextFailedRecovery()
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
        execution.dispatchOrder.forEach { dispatchParallelService(execution, it) }
    }

    private fun dispatchParallelService(execution: RoundExecution, service: ArenaService) {
        if (!isActive(execution) || !execution.dispatchedServices.add(service)) return
        execution.dispatchComplete = execution.dispatchedServices.containsAll(execution.services)
        sendService(execution, service) {
            maybeFinishRound(execution)
        }
    }

    private fun dispatchSerialNext(execution: RoundExecution) {
        if (!isActive(execution)) return
        // 其他成员的网页可能先加载好，但串行模式仍须等待当前成员回答结束；
        // 不按队列提前发出的成员（换人接手、排队时重新发送）也要等它答完，后面的人才接着接力。
        if (execution.services.any { service ->
                val phase = runs.getValue(service).phase
                phase in setOf(ParticipantPhase.SENDING, ParticipantPhase.WAITING, ParticipantPhase.STREAMING) ||
                    (phase == ParticipantPhase.QUEUED && service in execution.dispatchedServices && service !in execution.skipped)
            }) return
        val service = execution.dispatchOrder.getOrNull(execution.nextDispatchIndex)
        if (service == null) {
            execution.dispatchComplete = true
            maybeFinishRound(execution)
            return
        }
        if (service in execution.skipped || service in execution.dispatchedServices) {
            execution.nextDispatchIndex += 1
            return dispatchSerialNext(execution)
        }
        if (needsFresh(execution, service) && service !in execution.freshReadiness) return
        execution.nextDispatchIndex += 1
        execution.dispatchedServices += service
        if (execution.relay && !composeRelayPrompt(execution, service)) return dispatchSerialNext(execution)
        sessionMessage = if (execution.relay) "工作流：轮到 ${service.displayName}" else "串行模式：正在发送给 ${service.displayName}"
        sendService(execution, service) { sent ->
            if (!isActive(execution)) return@sendService
            if (sent) {
                sessionMessage = if (execution.relay) "工作流：等待 ${service.displayName} 回答后接力给下一位" else "串行模式：等待 ${service.displayName} 回答后再发送下一家"
            } else {
                dispatchSerialNext(execution)
            }
        }
    }

    /**
     * 工作流轮到某位时组装它的提示：第 1 位原样收到问题；之后每位收到「问题 + 前面各位已完成的完整回答」。
     * 前面没成功的成员不提供材料。超出这家网页的上下文预算时逐步压缩引用，仍放不下就标明失败并跳到下一位。
     */
    private fun composeRelayPrompt(execution: RoundExecution, service: ArenaService): Boolean {
        val newcomer = service in execution.newcomers
        val question = execution.question
        if (service == execution.dispatchOrder.first()) {
            val text = if (newcomer) RoundMaterials.forNewcomer(question, originalQuestion) else execution.prompts[service] ?: question
            execution.prompts[service] = text
            lastRoundPrompts = lastRoundPrompts + (service to text.take(ArenaLimits.MAX_STORED_PROMPT_CHARS))
            return true
        }
        val earlier = RoundMaterials.earlierAnswers(execution.dispatchOrder, service, runs)
        val budgeted = PromptBudgetPolicy.fit(service, initialQuoteLimit = ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS) { limit ->
            RelayPromptBuilder.build(question, earlier, limit, presets).let {
                if (newcomer) RoundMaterials.forNewcomer(it, originalQuestion) else it
            }
        }
        if (budgeted == null) {
            runs[service] = runs.getValue(service).copy(
                phase = ParticipantPhase.ERROR,
                detail = "工作流材料超过 ${service.displayName} 的上下文预算（${PromptBudgetPolicy.budgetFor(service)} 字），没有发送",
            )
            schedulePersist()
            return false
        }
        execution.prompts[service] = budgeted.text
        lastRoundPrompts = lastRoundPrompts + (service to budgeted.text.take(ArenaLimits.MAX_STORED_PROMPT_CHARS))
        if (budgeted.compressed) currentRoundContextNotice = "已压缩部分接力材料"
        return true
    }

    private fun sendService(
        execution: RoundExecution,
        service: ArenaService,
        onSendFinished: (Boolean) -> Unit,
    ) {
        if (!isActive(execution)) return
        val requestId = execution.requestIds.getValue(service)
        val kept = runs[service]?.previousResponses.orEmpty()
        if (needsFresh(execution, service) && execution.freshReadiness[service] != true) {
            runs[service] = ParticipantRun(
                phase = ParticipantPhase.ERROR,
                requestId = requestId,
                detail = pool.freshConversationFailure(service) ?: "新对话未能就绪，未发送；请打开原网页确认后重试",
                previousResponses = kept,
            )
            onSendFinished(false)
            schedulePersist()
            maybeFinishRound(execution)
            return
        }
        runs[service] = ParticipantRun(
            phase = ParticipantPhase.SENDING,
            requestId = requestId,
            detail = "正在发送",
            previousResponses = kept,
        )
        schedulePersist()
        // 每家独立兜底，不能因一家丢失回调而取消其他成员的在途上传和发送。
        var sendSettled = false
        val sendTimeout = ArenaDeadline {
            if (sendSettled) return@ArenaDeadline
            sendSettled = true
            if (!isActive(execution) || runs[service]?.requestId != requestId) return@ArenaDeadline
            if (runs[service]?.phase != ParticipantPhase.SENDING) return@ArenaDeadline
            runs[service] = ParticipantRun(
                phase = ParticipantPhase.ERROR,
                requestId = requestId,
                detail = "发送无响应，已停止等待",
                previousResponses = kept,
            )
            pool.cancelAutomation(service)
            onSendFinished(false)
            schedulePersist()
            maybeFinishRound(execution)
        }
        handler.postDelayed(sendTimeout, if (execution.attachments.isEmpty()) timing.sendTimeoutMillis else timing.attachmentSendTimeoutMillis)
        pool.sendPromptWithAttachments(
            service = service,
            prompt = execution.prompts.getValue(service),
            attachments = execution.attachments,
            requestId = requestId,
        ) { outcome ->
            if (sendSettled) return@sendPromptWithAttachments
            sendSettled = true
            handler.removeCallbacks(sendTimeout)
            if (!isActive(execution) || runs[service]?.requestId != requestId || service in execution.skipped) return@sendPromptWithAttachments
            if (outcome.success) {
                runs[service] = ParticipantRun(
                    phase = ParticipantPhase.WAITING,
                    requestId = requestId,
                    detail = "等待回答",
                    previousResponses = kept,
                )
                rememberConversationUrl(service)
                startPolling(execution, service, requestId)
            } else {
                runs[service] = ParticipantRun(
                    phase = ParticipantPhase.ERROR,
                    requestId = requestId,
                    detail = outcome.detail,
                    previousResponses = kept,
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
                // 流式期间显示宽松抓到的进度文本（可能混着思考过程，只是让人看到 AI 在动）；
                // 站点说"已结束"之后改看严格抓取的正式回答，稳定两次才算完成，存下的也是它。
                // 用户反馈：DeepSeek 一直把思考过程当回答存下来，真正的回答反而没抓到。
                val shownText = if (snapshot.streaming) snapshot.text else snapshot.settledText
                if (shownText == state.lastText) {
                    state.stableCount += 1
                } else {
                    state.lastText = shownText
                    state.stableCount = 0
                }
                val lengthLabel = responseLengthLabel(snapshot)
                val current = runs.getValue(service)
                runs[service] = current.copy(
                    phase = if (snapshot.streaming) ParticipantPhase.STREAMING else ParticipantPhase.WAITING,
                    response = shownText,
                    detail = if (snapshot.streaming) "正在回答 · $lengthLabel" else "正在确认回答完成",
                    responseTruncated = snapshot.truncated,
                    originalResponseLength = snapshot.originalLength,
                    // 模式小字：读到就更新，没读到沿用；思考痕迹一旦出现过就记住
                    modeLabel = snapshot.modeLabel.ifBlank { current.modeLabel },
                    thinkingUsed = current.thinkingUsed || snapshot.thinkingUsed,
                )
                schedulePersist()
                if (!snapshot.streaming && state.stableCount >= requiredStablePolls(snapshot)) {
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
                    detail = stalledWaitDetail(waitedSeconds),
                )
                schedulePersist()
            }
            handler.postDelayed({ poll(execution, service, state) }, timing.pollIntervalMillis)
        }
    }

    /**
     * 一直没读到任何文字时的文案。
     *
     * 超时上限是 5 分钟，但真实原因通常在头一分钟就已经确定：网页掉登录了、
     * 弹了验证码、或者发送根本没进去。让用户干等 5 分钟再看到"超时"是最差的体验，
     * 所以过了阈值就把文案换成可操作的提示，而不是继续只报秒数。
     */
    /**
     * 判"回答完成"需要文本连续稳定几轮。站点能给出明确的结束信号（消息操作栏展开）时用默认值；
     * 只有"停止按钮"这种弱信号的站点多等两轮（共 6 秒），免得 AI 中途停顿思考时把半截当成整条。
     */
    private fun requiredStablePolls(snapshot: ResponseSnapshot): Int =
        if (snapshot.weakDoneSignal) timing.requiredStablePolls + 2 else timing.requiredStablePolls

    private fun stalledWaitDetail(waitedSeconds: Long): String = when {
        waitedSeconds < STALL_HINT_SECONDS -> "等待回答 · ${waitedSeconds}秒"
        else -> "等待回答 · ${waitedSeconds}秒 · 迟迟没有回应，可点「跳转网页」看看是否需要登录或完成验证"
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
        // 重新提取一份已完成的回答却没读到：原回答原样保留，不降成失败。
        val backup = execution.reextractBackups.remove(service)
        runs[service] = if (backup != null && terminalRun.phase == ParticipantPhase.ERROR && !terminalRun.skipped) {
            backup.copy(detail = "${backup.detail}（重新提取没读到新内容，保留原回答）")
        } else terminalRun
        if (runs[service]?.phase == ParticipantPhase.COMPLETE) rememberConversationUrl(service)
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
        pool.setProtectedServices(emptySet())
        val results = execution.services.associateWith { runs.getValue(it) }
        history += RoundRecord(
            number = execution.number,
            kind = execution.kind,
            answerMode = execution.answerMode,
            guidance = execution.guidance,
            results = results,
            startedAtMillis = execution.startedAtMillis,
            finishedAtMillis = System.currentTimeMillis(),
            attachments = execution.attachments,
            relay = execution.relay,
            style = execution.style,
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
        // 被停止的一轮不核实：那是用户自己的决定。
        if (forcedMessage == null) verifyUncertainFailures()
    }

    /**
     * 本轮结束后，对「问题可能已经送达、只是没确认到」的失败悄悄核实一次（2026-09-29 实测：
     * DeepSeek 网页上已有 618 字回答，App 却显示「未检测到」）。只读网页、不重发、不占用界面；
     * 读到稳定的回答就改为完成，读不到就原样保留失败说明。用户开始下一步操作即放弃核实。
     */
    private fun verifyUncertainFailures() {
        val epoch = sessionEpoch
        sessionServices.forEach { service ->
            val run = runs[service] ?: return@forEach
            if (run.phase == ParticipantPhase.ERROR && run.requestId.isNotBlank() && ArenaErrorHelp.mayHaveAnswered(run.detail)) {
                autoVerify(service, run, epoch, attempt = 0, lastText = "", stable = 0)
            }
        }
    }

    private fun autoVerify(service: ArenaService, original: ParticipantRun, epoch: Long, attempt: Int, lastText: String, stable: Int) {
        fun untouched() = epoch == sessionEpoch && !isBusy && stage == SessionStage.READY && runs[service] == original
        if (!untouched() || attempt >= timing.autoVerifyReads) return
        pool.readResponse(service, original.requestId) { snapshot ->
            if (!untouched()) return@readResponse
            val text = if (snapshot.found && snapshot.detail.isBlank() && !snapshot.isAwaitingSecurityChallenge()) {
                if (snapshot.streaming) snapshot.text else snapshot.settledText
            } else ""
            val nextStable = if (text.isNotBlank() && text == lastText && !snapshot.streaming) stable + 1 else 0
            if (text.isNotBlank() && nextStable >= requiredStablePolls(snapshot)) {
                val verified = original.copy(
                    phase = ParticipantPhase.COMPLETE,
                    response = text,
                    responseTruncated = snapshot.truncated,
                    originalResponseLength = snapshot.originalLength,
                    modeLabel = snapshot.modeLabel.ifBlank { original.modeLabel },
                    thinkingUsed = original.thinkingUsed || snapshot.thinkingUsed,
                    detail = "回答完成 · ${text.length} 字（已从网页核实）",
                )
                runs[service] = verified
                updateLatestRoundResult(service, verified)
                sessionMessage = "${service.displayName} 其实已经回答，已从网页补上"
                schedulePersist(immediate = true)
                return@readResponse
            }
            handler.postDelayed({ autoVerify(service, original, epoch, attempt + 1, text, nextStable) }, timing.autoVerifyIntervalMillis)
        }
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
            sessionMessage = "队长总结超时"
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
                val shownText = if (snapshot.streaming) snapshot.text else snapshot.settledText
                if (shownText == execution.lastText) execution.stableCount += 1 else {
                    execution.lastText = shownText
                    execution.stableCount = 0
                }
                summary = summary.copy(
                    phase = if (snapshot.streaming) ParticipantPhase.STREAMING else ParticipantPhase.WAITING,
                    text = shownText,
                    detail = if (snapshot.streaming) "正在总结 · ${shownText.length} 字" else "正在确认总结完成",
                )
                schedulePersist()
                if (!snapshot.streaming && execution.stableCount >= requiredStablePolls(snapshot)) {
                    summary = summary.copy(
                        phase = ParticipantPhase.COMPLETE,
                        // 与各家回答一致：结束后以严格抓取的正式回答为准，别把思考过程当总结存下来
                        text = snapshot.settledText,
                        detail = "总结完成 · ${snapshot.settledText.length} 字",
                    )
                    summaryExecution = null
                    sessionMessage = "队长总结完成 · ${execution.judge.displayName}"
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

    /**
     * 每次会话状态变化都会通知（与落盘同一个时机）。App 在后台时界面不重组，
     * 通知栏进度和「答完了」提醒靠它驱动。
     */
    var onStateChanged: (() -> Unit)? = null

    private fun schedulePersist(immediate: Boolean = false) {
        onStateChanged?.invoke()
        if (sessionRepository == null || sessionId.isBlank() || originalQuestion.isBlank()) return
        persistenceHandler.removeCallbacks(persistRunnable)
        if (immediate) persistNow() else persistenceHandler.postDelayed(persistRunnable, PERSIST_DEBOUNCE_MILLIS)
    }

    /** 在调用线程（主线程）从 Compose 状态取一份不可变快照。 */
    private fun buildSnapshot(): ArenaSessionSnapshot? {
        if (sessionId.isBlank() || originalQuestion.isBlank()) return null
        return ArenaSessionSnapshot(
            id = sessionId,
            originalQuestion = originalQuestion,
            askedAtMillis = askedAtMillis,
            roundNumber = roundNumber,
            currentRoundKind = currentRoundKind,
            currentRoundRelay = currentRoundRelay,
            currentRoundStyle = currentRoundStyle,
            currentRelayOrder = relayOrder,
            currentAnswerMode = currentAnswerMode,
            services = sessionServices,
            runs = runs.toMap(),
            history = history.toList(),
            summary = summary,
            lastRoundPrompts = lastRoundPrompts,
            lastRoundAttachments = lastRoundAttachments,
            conversationUrls = conversationUrls.toMap(),
            updatedAtMillis = System.currentTimeMillis(),
            pendingSwaps = pendingSwapState.toMap(),
            roundNewcomers = roundNewcomers,
        )
    }

    private fun persistNow(synchronous: Boolean = false): Boolean {
        val repository = sessionRepository ?: return true
        val snapshot = buildSnapshot() ?: return true
        if (synchronous || persistShutdown) {
            return writeSnapshot(repository, snapshot)
        }
        val generation = persistGeneration
        // 写盘和读取最近列表都在后台线程完成，只把结果回投到主线程更新 UI 状态。
        persistExecutor.execute {
            val outcome = runCatching {
                repository.save(snapshot)
                repository.listRecent()
            }
            persistenceHandler.post {
                outcome
                    .onSuccess { sessions ->
                        storageWarning = null
                        // 期间用户可能已经开了新问题或恢复了别的会话，此时绝不能把
                        // 这个旧 id 重新设成活动会话。
                        if (generation == persistGeneration && sessionId == snapshot.id) {
                            runCatching { repository.setActiveSession(snapshot.id) }
                        }
                        recentSessions.clear()
                        recentSessions.addAll(sessions)
                    }
                    .onFailure { storageWarning = "本地保存失败，当前讨论仍可继续" }
            }
        }
        return true
    }

    /** Queue barrier: an older async save must finish before a newer reset/restore/destroy snapshot. */
    private fun writeSnapshot(repository: ArenaSessionRepository, snapshot: ArenaSessionSnapshot): Boolean {
        return try {
            val write = {
                repository.save(snapshot)
                repository.setActiveSession(snapshot.id)
            }
            if (Thread.currentThread() === persistThread) write()
            else persistExecutor.submit(java.util.concurrent.Callable { write() }).get()
            storageWarning = null
            true
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            storageWarning = "本地保存被中断，当前讨论仍保留，请稍后重试"
            false
        } catch (_: Exception) {
            storageWarning = "本地保存失败，当前讨论仍保留，请稍后重试"
            false
        }
    }

    private fun applySnapshot(snapshot: ArenaSessionSnapshot, recovered: Boolean) {
        recoveryQueue.clear()
        pool.cancelAutomation()
        sessionEpoch += 1
        persistGeneration += 1
        handler.removeCallbacksAndMessages(null)
        persistenceHandler.removeCallbacksAndMessages(null)
        pollStates.clear()
        activeExecution = null
        summaryExecution = null
        recoveries.clear()
        sessionId = snapshot.id
        originalQuestion = snapshot.originalQuestion
        askedAtMillis = snapshot.askedAtMillis
        roundNumber = maxOf(snapshot.roundNumber, snapshot.history.maxOfOrNull { it.number } ?: 0)
        currentRoundKind = snapshot.currentRoundKind ?: snapshot.history.lastOrNull()?.kind
        currentRoundRelay = snapshot.currentRoundRelay || snapshot.history.lastOrNull { it.number == snapshot.roundNumber }?.relay == true
        currentRoundStyle = snapshot.currentRoundStyle ?: snapshot.history.lastOrNull { it.number == snapshot.roundNumber }?.style
        relayOrder = snapshot.currentRelayOrder.takeIf { currentRoundRelay }.orEmpty()
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
        lastRoundAttachments = snapshot.lastRoundAttachments
        lastRoundPrompts = snapshot.lastRoundPrompts
        conversationUrls.clear()
        conversationUrls.putAll(snapshot.conversationUrls)
        pendingSwapState.clear()
        pendingSwapState.putAll(snapshot.pendingSwaps.filterKeys { it in sessionServices }.filterValues { it !in sessionServices })
        roundNewcomers = snapshot.roundNewcomers
        currentRoundContextNotice = ""
        stage = if (originalQuestion.isBlank()) SessionStage.IDLE else SessionStage.READY
        sessionMessage = if (recovered) {
            "已恢复上次本地讨论 · $roundNumber 轮"
        } else {
            "已打开历史讨论 · $roundNumber 轮"
        }
        storageWarning = null
        refreshRecentSessions()
        if (recovered) {
            // 冷启动恢复：网页此时还是站点首页，接着讨论会发进一条空对话里；切回当时那条
            reopenConversations()
            schedulePersist(immediate = true)
        }
    }

    private fun recoverRun(run: ParticipantRun): ParticipantRun =
        if (run.phase == ParticipantPhase.QUEUED ||
            run.phase == ParticipantPhase.SENDING ||
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

    /** 本轮被采用的回答（已完成且没被跳过）。 */
    private fun completedResponses(): Map<ArenaService, String> = RoundMaterials.adopted(runs)

    private fun buildRequestId(kind: RoundKind, number: Int, service: ArenaService): String {
        requestSequence += 1
        return "${kind.name.lowercase()}_${number}_${requestSequence}_${service.name.lowercase()}_${System.currentTimeMillis()}"
    }

    private fun isActive(execution: RoundExecution): Boolean =
        activeExecution === execution && execution.epoch == sessionEpoch

    private fun isSummaryActive(execution: SummaryExecution): Boolean =
        summaryExecution === execution && execution.epoch == sessionEpoch

    private fun isRecoveryActive(execution: RecoveryExecution): Boolean =
        recoveries[execution.service] === execution && execution.epoch == sessionEpoch

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
        /** 本轮成员；换人接手时会追加新成员。 */
        val services: MutableList<ArenaService>,
        val dispatchOrder: MutableList<ArenaService>,
        val prompts: MutableMap<ArenaService, String>,
        val attachments: List<ArenaAttachment>,
        val guidance: String,
        val startedAtMillis: Long,
        /** 开轮时就给每家分配好请求号，卡片从第一秒起就能显示准备进度。 */
        val requestIds: MutableMap<ArenaService, String>,
        val freshReadiness: MutableMap<ArenaService, Boolean> = mutableMapOf(),
        val dispatchedServices: MutableSet<ArenaService> = mutableSetOf(),
        var nextDispatchIndex: Int = 0,
        var dispatchComplete: Boolean = false,
        val relay: Boolean = false,
        val style: DebateStyle? = null,
        /** 用户在本轮中途跳过的成员：迟到的回调一律不再改动它们。 */
        val skipped: MutableSet<ArenaService> = mutableSetOf(),
        /** 中途换上来的新成员：先开新对话，材料里补原问题。 */
        val newcomers: MutableSet<ArenaService> = mutableSetOf(),
        /** 本轮的问题本身（首轮 = 原问题，独立迭代 = 本轮问题，讨论 / 激发 = 用户补充）。 */
        val question: String = "",
        /** 观点讨论 / 互相激发开轮时用的材料（上一轮被采用的回答），换人时给新成员重新组装。 */
        val baseResponses: Map<ArenaService, String> = emptyMap(),
        /** 观点讨论 / 互相激发是同类第几轮。 */
        val roundIndex: Int = 1,
        /** 每位成员开新对话的代次：重新准备后旧的迟到结果作废。 */
        val freshGeneration: MutableMap<ArenaService, Int> = mutableMapOf(),
        /** 本轮进行中重新提取已完成回答时的原状态；没读到就恢复它。 */
        val reextractBackups: MutableMap<ArenaService, ParticipantRun> = mutableMapOf(),
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
        /** 重发前已经拿到的部分回答。重发失败时用它回填，不能让用户白白丢掉。 */
        val previousResponse: String = "",
        val previousTruncated: Boolean = false,
        val previousOriginalLength: Int = 0,
        /** 动手前的完整状态：重新提取失败时据此恢复已完成的回答。 */
        val previousRun: ParticipantRun? = null,
        var lastText: String = "",
        var stableCount: Int = 0,
        var consecutiveReadErrors: Int = 0,
        var readToken: Long = 0,
    )

    private companion object {
        // 流式回答期间事件间隔约 500ms，250ms 的去抖等于没有去抖。
        const val PERSIST_DEBOUNCE_MILLIS = 1_200L

        /** 超过这个秒数还一个字都没读到，就把文案换成可操作的排查提示。 */
        const val STALL_HINT_SECONDS = 75L
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
