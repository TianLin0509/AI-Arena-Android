package com.tianlin.aiarena

/**
 * 每位成员「该收到什么」的纯逻辑：开轮、重新发送、换人接手共用同一套组装，避免三处各写一份而走样。
 * 不碰网页、不碰 Handler，可以直接做 JVM 单测。
 */
object RoundMaterials {
    /** 本轮被采用的回答：已完成、没被跳过、正文不为空；顺序固定按成员枚举，和观点讨论一致。 */
    fun adopted(runs: Map<ArenaService, ParticipantRun>): Map<ArenaService, String> =
        ArenaService.entries.mapNotNull { service ->
            runs[service]?.takeIf { it.phase == ParticipantPhase.COMPLETE && !it.skipped && it.response.isNotBlank() }
                ?.let { service to it.response }
        }.toMap()

    /** 工作流里排在 [target] 前面、已经被采用的回答（按接力顺序）。 */
    fun earlierAnswers(order: List<ArenaService>, target: ArenaService, runs: Map<ArenaService, ParticipantRun>): LinkedHashMap<ArenaService, String> {
        val earlier = LinkedHashMap<ArenaService, String>()
        val adopted = adopted(runs)
        order.takeWhile { it != target }.forEach { previous -> adopted[previous]?.let { earlier[previous] = it } }
        return earlier
    }

    /**
     * 中途加入的新成员网页里没有这场讨论的上下文：本轮材料里没写原问题时，在最前面补一句原问题。
     * 材料里已经有原问题（例如观点讨论预设、首轮提问本身）就原样发送。
     */
    fun forNewcomer(prompt: String, originalQuestion: String): String =
        if (originalQuestion.isBlank() || prompt.contains(originalQuestion)) prompt
        else "（你是中途加入这场讨论的成员。原始问题：\n$originalQuestion）\n\n$prompt"

    /** 「重新发送」前把手上的回答存进「之前的回答」，然后清空正文等新回答。 */
    fun keepPrevious(run: ParticipantRun): ParticipantRun =
        if (run.response.isBlank()) run
        else run.copy(previousResponses = run.previousResponses + run.response, response = "", responseTruncated = false, originalResponseLength = 0)

    /**
     * 下一轮的参与者：成员表按待换人替换后，本轮回答被采用的成员 + 新换上来的成员（新成员没有回答，也要参加）。
     * 顺序按成员枚举，与旧版一致。
     */
    fun nextParticipants(
        members: List<ArenaService>,
        runs: Map<ArenaService, ParticipantRun>,
        pendingSwaps: Map<ArenaService, ArenaService>,
    ): List<ArenaService> {
        val adopted = adopted(runs).keys
        val newcomers = pendingSwaps.filterKeys { it in members }.values.toSet()
        val roster = members.map { pendingSwaps[it] ?: it }
        return ArenaService.entries.filter { it in roster && (it in adopted || it in newcomers) }
    }

    /** 成员表应用待换人后的样子（位置不变）。 */
    fun swappedRoster(members: List<ArenaService>, pendingSwaps: Map<ArenaService, ArenaService>): List<ArenaService> =
        members.map { pendingSwaps[it] ?: it }.distinct()
}

/** 「换人」会怎样生效：本轮马上接手，还是只从下一轮起。 */
enum class SwapTiming { NOW, NEXT_ROUND }

/**
 * 成员卡片上四个逃生动作的规则（与 AI圆桌lite 一致）。
 */
object MemberActionPolicy {
    private val ACTIVE = setOf(ParticipantPhase.QUEUED, ParticipantPhase.SENDING, ParticipantPhase.WAITING, ParticipantPhase.STREAMING)

    /** 本轮这位成员有没有任务；没有任务（例如刚换上来的新成员）时只给「换人」。 */
    fun hasTask(run: ParticipantRun): Boolean = run.requestId.isNotBlank()

    /**
     * 换人的时机：旧成员本轮还在排队 / 进行中 / 出错（还在等它或它卡住了）→ 新成员立刻接手本轮；
     * 本轮已完成、已跳过、或整轮被用户停止 → 只从下一轮起换人，本轮不自动发送。
     */
    fun swapTiming(run: ParticipantRun): SwapTiming = when {
        !hasTask(run) || run.skipped || run.stopped -> SwapTiming.NEXT_ROUND
        run.phase in ACTIVE || run.phase == ParticipantPhase.ERROR -> SwapTiming.NOW
        else -> SwapTiming.NEXT_ROUND
    }

    /** 换人对话框里的那一句话。 */
    fun swapExplanation(from: ArenaService, run: ParticipantRun): String = when (swapTiming(run)) {
        SwapTiming.NOW -> "${from.shortName} 本轮还没答完，选中的 AI 会马上接手本轮，收到同样的材料；${from.shortName} 记为已跳过。"
        SwapTiming.NEXT_ROUND -> when {
            run.skipped -> "${from.shortName} 本轮已跳过，从下一轮起由选中的 AI 接替，本轮不会自动发送。"
            run.stopped -> "这一轮已被停止，从下一轮起由选中的 AI 接替，本轮不会自动发送。"
            run.phase == ParticipantPhase.COMPLETE -> "${from.shortName} 本轮已答完，回答保留；从下一轮起由选中的 AI 接替。"
            else -> "从下一轮起由选中的 AI 接替。"
        }
    }

    /**
     * 「重新发送」前要不要先确认：正在回答、已完成、或发送结果不确定时可能在网页里产生重复提问，要先问一句；
     * 还在排队、或明确没发出去的直接发。
     */
    fun resendNeedsConfirm(run: ParticipantRun): Boolean = when {
        !hasTask(run) -> false
        run.phase == ParticipantPhase.QUEUED -> false
        run.phase == ParticipantPhase.ERROR -> !definitelyNotSent(run.detail) || run.response.isNotBlank()
        else -> true
    }

    /** 明确没有发出去：重新提取没有东西可读。 */
    fun definitelyNotSent(detail: String): Boolean =
        listOf("还没来得及发送", "本轮没有发送", "没有发送", "未发送", "没有发出", "尚未登录", "注入失败").any { detail.contains(it) } &&
            !ArenaErrorHelp.mayHaveAnswered(detail)

    /** 「重新提取」有没有东西可读；不能读时控制器说明原因，按钮仍然可见。 */
    fun canReextract(run: ParticipantRun): Boolean =
        hasTask(run) && run.phase != ParticipantPhase.QUEUED && run.phase != ParticipantPhase.SENDING &&
            !(run.phase == ParticipantPhase.ERROR && run.response.isBlank() && definitelyNotSent(run.detail))

    /** 状态词：卡片和标签共用，颜色按 [tone]。 */
    fun statusWord(run: ParticipantRun): String = when {
        run.replacedBy != null -> "已换成${run.replacedBy.shortName}"
        run.skipped -> "已跳过"
        run.stopped && run.phase == ParticipantPhase.ERROR && run.detail.startsWith("已停止") -> "已停止"
        else -> when (run.phase) {
            ParticipantPhase.IDLE -> if (run.detail == "本轮未参与") "本轮未参与" else "等待"
            ParticipantPhase.QUEUED -> if (run.detail.contains("等上一家")) "排队中" else "准备中"
            ParticipantPhase.SENDING -> "发送中"
            ParticipantPhase.WAITING -> "等待回答"
            ParticipantPhase.STREAMING -> "回答中"
            ParticipantPhase.COMPLETE -> "已完成"
            ParticipantPhase.ERROR -> "没成功"
        }
    }

    enum class Tone { RUNNING, DONE, PROBLEM, NEUTRAL }

    fun tone(run: ParticipantRun): Tone = when {
        run.skipped || run.replacedBy != null || (run.stopped && run.detail.startsWith("已停止")) -> Tone.NEUTRAL
        run.phase in ACTIVE -> Tone.RUNNING
        run.phase == ParticipantPhase.COMPLETE -> Tone.DONE
        run.phase == ParticipantPhase.ERROR -> Tone.PROBLEM
        else -> Tone.NEUTRAL
    }
}
