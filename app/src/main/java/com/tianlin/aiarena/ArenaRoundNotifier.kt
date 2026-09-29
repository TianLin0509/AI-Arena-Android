package com.tianlin.aiarena

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 一轮进行中时的前台服务：本身不做任何事，只让系统知道「这个 App 正在替用户干活」，
 * 用户切到别的应用后进程和网页不容易被回收。通知栏显示进度，点一下回到圆桌。
 */
class ArenaRoundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = ArenaRoundNotifier.progressNotification(this, intent?.getStringExtra(EXTRA_TEXT).orEmpty())
        // startForegroundService 之后必须先 startForeground，哪怕这一轮已经结束，否则系统会让 App 崩溃。
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ArenaRoundNotifier.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ArenaRoundNotifier.PROGRESS_ID, notification)
            }
        }.isSuccess
        inForeground = started
        if (!started || stopRequested) {
            stopRequested = false
            finish()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        inForeground = false
        super.onDestroy()
    }

    private fun finish() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        inForeground = false
        stopSelf()
    }

    companion object {
        const val EXTRA_TEXT = "text"
        /** 服务已进入前台；之前请求停止只能先记下，等它进入前台后再停（2026-09-29 设备测试发现的崩溃）。 */
        var inForeground = false
            private set
        var stopRequested = false
    }
}

/** 轮次进度 → 前台服务与「答完了」通知。只由界面层调用，不参与发送与读取。 */
class ArenaRoundNotifier(private val context: Context) {
    private var running = false
    private var lastText = ""

    /** [text] 为 null 表示当前没有进行中的一轮。[doneText] 是这一轮结束时要告诉用户的话。 */
    fun update(text: String?, doneText: String) {
        if (text != null) {
            if (!running) {
                // 前台服务只能在 App 可见时启动；一轮总是用户在前台点发送开始的。
                if (!ArenaForeground.visible) return
                ArenaRoundService.stopRequested = false
                running = runCatching {
                    context.startForegroundService(Intent(context, ArenaRoundService::class.java).putExtra(ArenaRoundService.EXTRA_TEXT, text))
                }.isSuccess
                lastText = text
            } else if (text != lastText) {
                lastText = text
                runCatching { manager().notify(PROGRESS_ID, progressNotification(context, text)) }
            }
            return
        }
        if (!running) return
        stop()
        if (!ArenaForeground.visible && doneText.isNotBlank()) {
            runCatching { manager().notify(DONE_ID, doneNotification(context, doneText)) }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        lastText = ""
        // 一轮刚开就结束（点发送后马上停止）时，服务可能还没进入前台：记下停止请求，由服务进入前台后自己停。
        if (ArenaRoundService.inForeground) runCatching { context.stopService(Intent(context, ArenaRoundService::class.java)) }
        else ArenaRoundService.stopRequested = true
    }

    private fun manager() = context.getSystemService(NotificationManager::class.java)

    companion object {
        const val PROGRESS_ID = 3101
        const val DONE_ID = 3102
        private const val PROGRESS_CHANNEL = "arena_round_progress"
        private const val DONE_CHANNEL = "arena_round_done"

        fun clearDone(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(DONE_ID) }
        }

        fun progressNotification(context: Context, text: String): Notification {
            ensureChannels(context)
            return Notification.Builder(context, PROGRESS_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("AI 圆桌正在讨论")
                .setContentText(text.ifBlank { "可以先去做别的，答完会通知你" })
                .setContentIntent(openApp(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        private fun doneNotification(context: Context, text: String): Notification {
            ensureChannels(context)
            return Notification.Builder(context, DONE_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("AI 圆桌：回答好了")
                .setContentText(text)
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(PROGRESS_CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, "讨论进行中", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "切到别的应用时，显示圆桌讨论的进度" })
            }
            if (manager.getNotificationChannel(DONE_CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "回答完成", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "你不在圆桌界面时，各家答完后提醒你回来看" })
            }
        }

        /** 通知栏里的一句进度。 */
        fun progressText(controller: ArenaSessionController): String? {
            if (!controller.isBusy) return null
            val summary = controller.summary
            if (summary.phase in ACTIVE) return "${summary.judge?.displayName ?: "队长"} 正在写总结"
            val members = controller.sessionServices
            val done = members.count { controller.runs[it]?.phase == ParticipantPhase.COMPLETE || controller.runs[it]?.phase == ParticipantPhase.ERROR }
            val sending = members.count { controller.runs[it]?.phase == ParticipantPhase.QUEUED || controller.runs[it]?.phase == ParticipantPhase.SENDING }
            return when {
                sending > 0 && !ArenaForeground.visible -> "还有 $sending 家没发出，回到圆桌后继续发送"
                sending > 0 -> "正在发送问题（$done/${members.size}）"
                else -> "已回答 $done/${members.size} 家，可以先去做别的"
            }
        }

        /** 本轮问题都已送达（没有还在准备或发送的成员），此时切走不影响收回答。 */
        fun delivered(controller: ArenaSessionController): Boolean {
            val summary = controller.summary
            if (summary.phase == ParticipantPhase.SENDING) return false
            if (summary.phase == ParticipantPhase.WAITING || summary.phase == ParticipantPhase.STREAMING) return true
            val phases = controller.sessionServices.mapNotNull { controller.runs[it]?.phase }
            return phases.none { it == ParticipantPhase.QUEUED || it == ParticipantPhase.SENDING } &&
                phases.any { it == ParticipantPhase.WAITING || it == ParticipantPhase.STREAMING }
        }

        /** 一轮结束时的一句话。 */
        fun doneText(controller: ArenaSessionController): String {
            val summary = controller.summary
            if (summary.phase == ParticipantPhase.COMPLETE && summary.judge != null) return "${summary.judge.displayName} 的队长总结写好了"
            val members = controller.sessionServices
            val ok = members.count { controller.runs[it]?.phase == ParticipantPhase.COMPLETE }
            return if (ok == members.size) "${members.size} 家都答完了，点开查看" else "$ok/${members.size} 家答完，点开查看"
        }

        private val ACTIVE = setOf(ParticipantPhase.SENDING, ParticipantPhase.WAITING, ParticipantPhase.STREAMING)
    }
}
