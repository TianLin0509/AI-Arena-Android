package com.tianlin.aiarena

typealias TextCopyRequest = (label: String, text: String) -> Boolean
typealias TextShareRequest = (title: String, text: String) -> Boolean

data class PreparedShareText(
    val text: String,
    val truncated: Boolean,
)

object ShareTextPolicy {
    const val MAX_SHARE_CHARACTERS = 20_000

    /** 整场讨论可能有多轮、多家长回答；系统分享面板对超长文本不稳定，因此单独设一个较宽的上限。 */
    const val MAX_SESSION_SHARE_CHARACTERS = 60_000

    fun discussionSummary(question: String, summary: String): PreparedShareText {
        val content = buildString {
            append("AI 圆桌讨论总结\n\n")
            append("原问题：\n")
            append(question.trim())
            append("\n\n总结：\n")
            append(summary.trim())
        }
        return limit(content, MAX_SHARE_CHARACTERS)
    }

    /**
     * 整场讨论按时间顺序导出成 Markdown：每一轮的问题、各家回答（没成功的写明原因），
     * 以及归档在该轮的综合答案；当前的综合答案附在它依据的那一轮后面。
     */
    fun fullSession(
        originalQuestion: String,
        askedAtMillis: Long,
        history: List<RoundRecord>,
        currentSummary: DiscussionSummary,
    ): PreparedShareText {
        val rounds = history.sortedBy { it.number }
        val latestSummary = currentSummary.takeIf { it.phase == ParticipantPhase.COMPLETE && it.text.isNotBlank() }
        val content = buildString {
            append("# AI 圆桌 · 整场讨论\n\n")
            append("**问题**：").append(originalQuestion.trim()).append("\n\n")
            if (askedAtMillis > 0L) append("提问时间：").append(formatAskedTime(askedAtMillis)).append("\n\n")
            rounds.forEach { round ->
                append("## 第 ${round.number} 轮 · ${ArenaTimeline.kindLabel(round.kind)}\n\n")
                if (round.kind != RoundKind.INITIAL) {
                    append("> ").append(ArenaTimeline.roundQuestion(round, originalQuestion).trim().replace("\n", "\n> ")).append("\n\n")
                }
                ArenaService.entries.filter { it in round.results.keys }.forEach { service ->
                    val run = round.results.getValue(service)
                    if (run.phase == ParticipantPhase.IDLE && run.response.isBlank()) return@forEach
                    append("### ").append(service.displayName).append("\n\n")
                    val note = ArenaTimeline.runNote(run)
                    if (note.isNotBlank()) append("（").append(note).append("）\n\n")
                    if (run.response.isNotBlank()) append(run.response.trim()).append("\n\n")
                }
                val summary = round.summary
                    ?: latestSummary?.takeIf { it.roundNumber == round.number || (it.roundNumber == 0 && round === rounds.last()) }
                summary?.let {
                    append("### 综合答案").append(it.judge?.let { judge -> "（由 ${judge.displayName} 整理）" }.orEmpty()).append("\n\n")
                    append(it.text.trim()).append("\n\n")
                }
            }
            append("—— 由 AI 圆桌整理，回答来自各家 AI 官网，重要信息请核实。")
        }
        return limit(content, MAX_SESSION_SHARE_CHARACTERS)
    }

    private fun limit(content: String, max: Int): PreparedShareText {
        if (content.length <= max) return PreparedShareText(content, truncated = false)
        val suffix = "\n\n[内容过长，已保留前 $max 字]"
        return PreparedShareText(
            text = content.take((max - suffix.length).coerceAtLeast(0)) + suffix,
            truncated = true,
        )
    }
}
