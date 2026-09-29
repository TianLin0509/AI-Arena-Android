package com.tianlin.aiarena

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock

/**
 * 前台时钟：只在 App 显示在屏幕上时才走的时间。
 *
 * 切到别的应用后，网页不再绘制、输入框拿不到焦点，「打开新对话 → 填问题 → 点发送 → 确认送达」
 * 这一段本来就走不动；如果超时照常计时，用户回来只会看到一排「超时」或「未检测到」
 * （2026-09-29 真实账号实测：离开 3 分钟，四家里三家被误判失败，其中 DeepSeek 其实已经答完）。
 * 所以发送相关的期限都按前台时间计，回到前台后从暂停处接着走。
 * 等回答的轮询不受影响：问题送达之后，网页在后台照样收得到回答。
 */
object ArenaForeground {
    private val listeners = mutableListOf<(Boolean) -> Unit>()
    private var backgroundTotal = 0L
    private var backgroundSince = -1L

    /** 没有 Activity 的场景（单元与仪器测试）视为一直在前台，行为与真实时间一致。 */
    var visible: Boolean = true
        private set

    fun elapsed(): Long {
        val now = SystemClock.elapsedRealtime()
        return now - backgroundTotal - if (backgroundSince >= 0) now - backgroundSince else 0L
    }

    fun setVisible(value: Boolean) {
        if (value == visible) return
        val now = SystemClock.elapsedRealtime()
        if (value) {
            if (backgroundSince >= 0) backgroundTotal += now - backgroundSince
            backgroundSince = -1L
        } else {
            backgroundSince = now
        }
        visible = value
        listeners.toList().forEach { it(value) }
    }

    fun addListener(listener: (Boolean) -> Unit) { listeners += listener }

    fun removeListener(listener: (Boolean) -> Unit) { listeners -= listener }
}

/** 只按前台时间计的期限；交给 [ArenaForegroundHandler] 时，App 在后台期间不会触发。 */
class ArenaDeadline(private val block: () -> Unit) : Runnable {
    override fun run() = block()
}

/**
 * 延时只按前台时间计的 Handler。
 *
 * [freezeAll] 为真时（网页池），后台期间所有排队的动作都原地等待：页面自动化在后台走不动，
 * 在隐藏的豆包页面上点发送还会让消息卡在「待发送」（2026-09-23 根因）。为假时（会话控制器），
 * 只有 [ArenaDeadline] 等待，读回答的轮询照常进行。
 *
 * 被推迟的动作始终留在消息队列里（重新投递同一个 Runnable），所以 removeCallbacks 照常有效。
 */
class ArenaForegroundHandler(looper: Looper, private val freezeAll: Boolean) : Handler(looper) {
    private class Due(val at: Long)

    override fun sendMessageAtTime(msg: Message, uptimeMillis: Long): Boolean {
        val callback = msg.callback
        if (callback != null && msg.obj == null && (freezeAll || callback is ArenaDeadline)) {
            msg.obj = Due(ArenaForeground.elapsed() + (uptimeMillis - SystemClock.uptimeMillis()).coerceAtLeast(0L))
        }
        return super.sendMessageAtTime(msg, uptimeMillis)
    }

    override fun dispatchMessage(msg: Message) {
        val due = msg.obj as? Due
        val callback = msg.callback
        if (due != null && callback != null) {
            val wait = if (!ArenaForeground.visible) BACKGROUND_RECHECK_MS else due.at - ArenaForeground.elapsed()
            if (wait > 0L) {
                val again = Message.obtain(this, callback)
                again.obj = due
                sendMessageAtTime(again, SystemClock.uptimeMillis() + wait)
                return
            }
        }
        super.dispatchMessage(msg)
    }

    private companion object {
        const val BACKGROUND_RECHECK_MS = 1_000L
    }
}
