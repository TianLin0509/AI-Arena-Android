package com.tianlin.aiarena

import android.content.Context
import android.os.Build
import android.webkit.WebView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「导出诊断信息」：用户遇到问题时一键分享给开发者的一段纯文本。
 * 只含版本、设备、各家网页状态和每轮各家的结果与字数；不含问题、回答正文和账号信息，
 * 网址只保留域名和第一段路径，用户分享前可以看到全部内容。
 */
object ArenaDiagnostics {
    fun report(context: Context, pool: ArenaWebViewPool, controller: ArenaSessionController): String = buildString {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        appendLine("AI 圆桌诊断信息 · $time")
        appendLine("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("WebView ${runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull() ?: "未知"} · 前台 ${ArenaForeground.visible}")
        appendLine()
        appendLine("【各家网页】")
        ArenaService.entries.forEach { service ->
            val status = pool.statuses[service] ?: return@forEach
            append("${service.displayName}: ${status.state}")
            if (status.detail.isNotBlank()) append(" · ${status.detail.take(80)}")
            val path = pagePath(status.url)
            if (path.isNotBlank()) append(" · $path")
            pool.healthIssues[service]?.let { append(" · 体检：$it") }
            pool.freshConversationFailure(service)?.let { append(" · 新对话：${it.take(60)}") }
            appendLine()
        }
        appendLine()
        appendLine("【当前讨论】第 ${controller.roundNumber} 轮 · ${controller.stage} · 进行中 ${controller.isBusy}")
        controller.sessionServices.forEach { service ->
            val run = controller.runs[service] ?: return@forEach
            appendLine("${service.displayName}: ${run.phase} · ${run.response.length} 字 · ${run.detail.take(100)}")
        }
        val summary = controller.summary
        if (summary.phase != ParticipantPhase.IDLE) {
            appendLine("队长总结: ${summary.judge?.displayName ?: "-"} · ${summary.phase} · ${summary.text.length} 字 · ${summary.detail.take(100)}")
        }
        val rounds = controller.history.takeLast(6)
        if (rounds.isNotEmpty()) {
            appendLine()
            appendLine("【最近几轮】")
            rounds.forEach { round ->
                append("第 ${round.number} 轮 ${ArenaTimeline.kindLabel(round)}：")
                appendLine(round.results.entries.joinToString("；") { (service, run) ->
                    "${service.shortName} ${run.phase} ${run.response.length} 字" + if (run.phase == ParticipantPhase.ERROR) "（${run.detail.take(60)}）" else ""
                })
            }
        }
        runCatching { ArenaCrashReporter.latest(context) }.getOrNull()?.let {
            appendLine()
            appendLine("【最近一次崩溃】${it.fileName}")
        }
    }

    /** 只留域名和第一段路径（看得出是首页还是某个对话页），不带对话编号和参数。 */
    private fun pagePath(url: String): String {
        val bare = url.substringBefore('?').substringBefore('#').removePrefix("https://")
        val parts = bare.split('/').filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> ""
            parts.size == 1 -> parts[0]
            else -> parts[0] + "/" + parts[1] + if (parts.size > 2) "/…" else ""
        }
    }
}
