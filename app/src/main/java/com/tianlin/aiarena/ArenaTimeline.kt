package com.tianlin.aiarena

/**
 * 本次会话的「时光机」：把已经结束的轮次按时间排好，给回答页各 Tab 回看用。
 *
 * 只读：回看旧轮次不改变下一轮的发送基准，也不能在旧轮次上重试或重新提取。
 */
object ArenaTimeline {
    /** 用户视角的轮次名称，与输入框上的动作对应（提问 / 追问 / 互相讨论）。 */
    fun kindLabel(kind: RoundKind, relay: Boolean = false, style: DebateStyle? = null): String = when {
        relay -> "工作流"
        kind == RoundKind.INITIAL -> "提问"
        kind == RoundKind.ITERATION -> "独立迭代"
        kind == RoundKind.INSPIRE -> "互相激发"
        style == DebateStyle.COLLAB -> "观点讨论 · 取长补短"
        else -> "观点讨论"
    }

    fun kindLabel(round: RoundRecord): String = kindLabel(round.kind, round.relay, round.style)

    /** 这一轮用户问了什么；互相讨论没有额外要求时说明是在讨论原问题。 */
    fun roundQuestion(round: RoundRecord, originalQuestion: String): String = when (round.kind) {
        RoundKind.INITIAL -> originalQuestion
        RoundKind.ITERATION -> round.guidance.ifBlank { "本轮问题未保存" }
        RoundKind.DEBATE -> round.guidance.takeIf { it.isNotBlank() }?.let { "讨论要求：$it" } ?: "让 AI 互相讨论：$originalQuestion"
        RoundKind.INSPIRE -> round.guidance.takeIf { it.isNotBlank() }?.let { "激发要求：$it" } ?: "让 AI 互相激发：$originalQuestion"
    }

    /** 当前轮之前、已经结束的轮次，按先后排列；当前轮仍在正文区显示，不重复出现在时光机里。 */
    fun pastRounds(history: List<RoundRecord>, currentRound: Int): List<RoundRecord> =
        history.filter { it.number in 1 until currentRound }.sortedBy { it.number }

    /** 某位成员参与过的往轮；没参与的轮次在它的 Tab 里不出现。 */
    fun pastRoundsFor(service: ArenaService, history: List<RoundRecord>, currentRound: Int): List<RoundRecord> =
        pastRounds(history, currentRound).filter { round ->
            round.results[service]?.let { it.phase != ParticipantPhase.IDLE || it.response.isNotBlank() } == true
        }

    /** 往轮里做过的综合答案（开始下一轮时归档），给「综合」Tab 回看。 */
    fun pastSummaries(history: List<RoundRecord>, currentRound: Int): List<RoundRecord> =
        pastRounds(history, currentRound).filter { it.summary?.text?.isNotBlank() == true }

    /** 节点标题，例如「第 2 轮 · 追问 · 今天 21:05」。 */
    fun nodeTitle(round: RoundRecord, now: Long = System.currentTimeMillis()): String =
        "第 ${round.number} 轮 · ${kindLabel(round)}" +
            (round.startedAtMillis.takeIf { it > 0L }?.let { " · ${formatAskedTime(it, now)}" } ?: "")

    /** 某成员在某轮的一句话状态；成功时为空，由正文说话。 */
    fun runNote(run: ParticipantRun?): String = when {
        run == null -> "这一轮没有参与"
        run.replacedBy != null && run.skipped -> "这一轮已跳过，由 ${run.replacedBy.shortName} 接手"
        run.skipped -> "这一轮已跳过，回答不采用"
        run.phase == ParticipantPhase.COMPLETE && run.response.isNotBlank() -> ""
        run.phase == ParticipantPhase.ERROR -> "这一轮没有成功：" + run.detail.take(60)
        run.response.isNotBlank() -> "这一轮只收到部分回答"
        else -> "这一轮没有回答"
    }
}
