package com.tianlin.aiarena

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ArenaCrashReport(
    val fileName: String,
    val recordedAtMillis: Long,
    val text: String,
)

/**
 * 本地崩溃记录。
 *
 * 这个 App 没有后端、不做任何遥测，所以不能靠云端崩溃平台看线上问题；但"看不见崩溃"
 * 对一个依赖 6 个第三方网站 DOM 的应用是致命的——最可能的故障恰恰只在真实设备上出现。
 * 折中方案：崩溃时在本机写一份带版本号和机型的报告，用户在设置页可以主动导出发给我。
 * 不上传、不联网，与"登录信息只保存在本机"的承诺一致。
 */
object ArenaCrashReporter {
    private const val DIRECTORY = "crashes"
    private const val MAX_REPORTS = 3
    private const val MAX_REPORT_CHARS = 16_000

    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 整段包在 runCatching 里：崩溃处理器自己再抛异常会把原始堆栈彻底吞掉。
            runCatching { write(appContext, thread, error) }
            // 必须把异常交回系统默认处理器，否则进程不会正常结束，
            // 用户会看到一个卡住不动的界面而不是"应用已停止"。
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val stack = StringWriter().also { writer ->
            PrintWriter(writer).use(error::printStackTrace)
        }.toString()
        val report = buildString {
            appendLine("AI 圆桌崩溃记录")
            appendLine("时间：${formatTime(now)}")
            appendLine("版本：${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）")
            appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统：Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
            appendLine("线程：${thread.name}")
            appendLine()
            append(stack)
        }.take(MAX_REPORT_CHARS)
        File(directory, "crash_$now.txt").writeText(report, Charsets.UTF_8)
        trim(directory)
    }

    private fun trim(directory: File) {
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("crash_") }
            .sortedByDescending { it.lastModified() }
            .drop(MAX_REPORTS)
            .forEach { it.delete() }
    }

    /** 最近一次崩溃记录；没有则返回 null。读文件，调用方不要放在主线程的热路径上。 */
    fun latest(context: Context): ArenaCrashReport? {
        val directory = File(context.applicationContext.filesDir, DIRECTORY)
        val file = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("crash_") }
            .maxByOrNull { it.lastModified() }
            ?: return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return ArenaCrashReport(
            fileName = file.name,
            recordedAtMillis = file.lastModified(),
            text = text.take(MAX_REPORT_CHARS),
        )
    }

    fun clear(context: Context) {
        val directory = File(context.applicationContext.filesDir, DIRECTORY)
        directory.listFiles().orEmpty().forEach { it.delete() }
    }

    fun formatTime(value: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(value))
}
