package com.tianlin.aiarena

typealias TextCopyRequest = (label: String, text: String) -> Boolean
typealias TextShareRequest = (title: String, text: String) -> Boolean

data class PreparedShareText(
    val text: String,
    val truncated: Boolean,
)

object ShareTextPolicy {
    const val MAX_SHARE_CHARACTERS = 20_000

    fun discussionSummary(question: String, summary: String): PreparedShareText {
        val content = buildString {
            append("AI 圆桌讨论总结\n\n")
            append("原问题：\n")
            append(question.trim())
            append("\n\n总结：\n")
            append(summary.trim())
        }
        if (content.length <= MAX_SHARE_CHARACTERS) return PreparedShareText(content, truncated = false)
        val suffix = "\n\n[内容过长，已保留前 ${MAX_SHARE_CHARACTERS} 字]"
        return PreparedShareText(
            text = content.take((MAX_SHARE_CHARACTERS - suffix.length).coerceAtLeast(0)) + suffix,
            truncated = true,
        )
    }
}
