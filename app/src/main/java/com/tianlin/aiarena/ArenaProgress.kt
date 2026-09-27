package com.tianlin.aiarena

import kotlin.math.ceil

/** Only timings are stored, never questions, files, account information or web content. */
interface ArenaDurationSamples {
    fun read(key: String): List<Long>
    fun add(key: String, milliseconds: Long)
}

class ArenaMemoryDurationSamples : ArenaDurationSamples {
    private val values = mutableMapOf<String, List<Long>>()
    override fun read(key: String): List<Long> = values[key].orEmpty()
    override fun add(key: String, milliseconds: Long) {
        values[key] = (read(key) + milliseconds).takeLast(20)
    }
}

data class ArenaSendProgress(val requestId: String, val description: String)

data class ArenaWaitProgress(
    val title: String,
    val elapsed: String,
    val expectation: String,
    val limit: String,
    val needsAttention: Boolean,
)

/** Observes transitions; never schedules, resends, changes deadlines, or relies on wall-clock time. */
class ArenaProgressTracker(private val samples: ArenaDurationSamples = ArenaMemoryDurationSamples()) {
    private data class Attempt(
        val requestId: String,
        val started: Long,
        var phaseStarted: Long,
        var phase: String,
        val hasAttachments: Boolean,
        val learn: Boolean,
        var serialWait: Boolean = false,
    )
    private val attempts = mutableMapOf<ArenaService, Attempt>()

    private fun phase(run: ParticipantRun): String? = when (run.phase) {
        ParticipantPhase.QUEUED -> "prepare"
        ParticipantPhase.SENDING -> "send"
        ParticipantPhase.WAITING, ParticipantPhase.STREAMING -> "answer"
        else -> null
    }

    private fun key(service: ArenaService, attempt: Attempt) =
        "${service.name}.${if (attempt.hasAttachments) "files" else "text"}.${attempt.phase}"

    fun observe(service: ArenaService, run: ParticipantRun, now: Long, hasAttachments: Boolean) {
        val next = phase(run)
        if (run.requestId.isBlank()) { attempts.remove(service); return }
        var attempt = attempts[service]
        if (attempt == null || attempt.requestId != run.requestId) {
            if (next == null) { attempts.remove(service); return }
            attempt = Attempt(run.requestId, now, now, next, hasAttachments,
                run.phase == ParticipantPhase.QUEUED || run.phase == ParticipantPhase.SENDING)
            attempts[service] = attempt
        }
        if (run.detail.contains("等上一家")) attempt.serialWait = true
        if (next != attempt.phase) {
            // Failed/stopped/restored attempts must not become successful timing samples.
            if (attempt.learn && (next != null || run.phase == ParticipantPhase.COMPLETE) && !attempt.serialWait) {
                val duration = now - attempt.phaseStarted
                if (duration in 1..600_000L) samples.add(key(service, attempt), duration)
            }
            if (next == null) { attempts.remove(service); return }
            attempt.phase = next
            attempt.phaseStarted = now
            attempt.serialWait = false
        }
    }

    fun describe(service: ArenaService, run: ParticipantRun, now: Long, timing: ControllerTiming,
                 sending: ArenaSendProgress? = null, answerDeadlineElapsedMillis: Long? = null): ArenaWaitProgress? {
        val current = attempts[service]?.takeIf { it.requestId == run.requestId } ?: return null
        if (phase(run) == null) return null
        val elapsed = (now - current.started).coerceAtLeast(0L)
        val stageElapsed = (now - current.phaseStarted).coerceAtLeast(0L)
        val serial = run.phase == ParticipantPhase.QUEUED && run.detail.contains("等上一家")
        val challenge = run.detail.contains("安全验证")
        val title = when {
            challenge -> "需要完成网页验证"
            serial -> "等待上一家回答"
            run.phase == ParticipantPhase.QUEUED -> "准备新对话"
            run.phase == ParticipantPhase.SENDING -> sending?.takeIf { it.requestId == run.requestId }?.description
                ?: if (current.hasAttachments) "上传附件并发送" else "正在发送问题"
            run.phase == ParticipantPhase.STREAMING -> "正在生成回答"
            else -> "已送达，等待回答"
        }
        val budget = when (current.phase) {
            "prepare" -> timing.freshConversationTimeoutMillis
            "send" -> if (current.hasAttachments) timing.attachmentSendTimeoutMillis else timing.sendTimeoutMillis
            else -> timing.responseTimeoutMillis
        }
        val remaining = (if (current.phase == "answer" && answerDeadlineElapsedMillis != null)
            answerDeadlineElapsedMillis - now else budget - stageElapsed).coerceIn(0L, budget.coerceAtLeast(0L))
        val recent = samples.read(key(service, current)).filter { it in 1..600_000L }.takeLast(20).sorted()
        val estimate = when {
            challenge -> "请打开网页完成验证；无法预估完成时间"
            serial -> "上一家完成后会自动继续；暂无可靠剩余时间"
            recent.size < 3 -> "预计用时：暂无足够记录，完成后会积累本机参考"
            else -> {
                val lower = seconds(recent[(recent.size - 1) / 5])
                val upperMs = recent[((recent.size - 1) * 4 + 4) / 5]
                val upper = seconds(upperMs)
                val range = if (lower == upper) "约 ${upper}秒" else "${lower}–${upper}秒"
                "本阶段近期 $range（近 ${recent.size} 次）" + if (stageElapsed > upperMs) "；本次已超过参考，仍在等待官网" else "，仅供参考"
            }
        }
        val limit = when {
            serial -> "各家网站速度不同，不代表圆桌卡住"
            remaining == 0L -> "已到本阶段等待上限，正在结束；可停止等待或打开网页核对"
            else -> "本阶段超时保护：最多再等 ${seconds(remaining)}秒（不是完成倒计时）"
        }
        return ArenaWaitProgress(title, "已等待 ${seconds(elapsed)}秒", estimate, limit,
            challenge || remaining == 0L || (stageElapsed >= 30_000L && run.phase != ParticipantPhase.STREAMING))
    }

    private fun seconds(milliseconds: Long): Long = ceil(milliseconds / 1000.0).toLong().coerceAtLeast(0)
}
