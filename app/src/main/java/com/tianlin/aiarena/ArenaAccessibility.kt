package com.tianlin.aiarena

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.util.Locale

sealed interface VoiceInputOutcome {
    data class Success(val transcript: String) : VoiceInputOutcome
    data object Cancelled : VoiceInputOutcome
    data class Error(val message: String) : VoiceInputOutcome
}

typealias VoiceInputRequest = () -> Boolean

data class VoiceInputEvent(
    val id: Long,
    val outcome: VoiceInputOutcome,
)

class VoiceInputState : ViewModel() {
    var active by mutableStateOf(false)
        private set

    var event by mutableStateOf<VoiceInputEvent?>(null)
        private set

    private var eventSequence = 0L

    fun begin(): Boolean {
        if (active) return false
        active = true
        return true
    }

    fun finish(outcome: VoiceInputOutcome) {
        active = false
        event = VoiceInputEvent(++eventSequence, outcome)
    }

    fun take(eventId: Long): VoiceInputOutcome? {
        val current = event ?: return null
        if (current.id != eventId) return null
        event = null
        return current.outcome
    }
}

data class VoiceMergeResult(
    val text: String,
    val addedCharacters: Int,
    val truncated: Boolean,
)

object VoiceInputPolicy {
    fun merge(
        existing: String,
        transcript: String,
        maxCharacters: Int = ArenaLimits.MAX_QUESTION_CHARS,
    ): VoiceMergeResult {
        val spoken = transcript.trim()
        if (spoken.isEmpty() || maxCharacters <= 0) {
            return VoiceMergeResult(existing.take(maxCharacters.coerceAtLeast(0)), 0, existing.length > maxCharacters)
        }

        val prefix = when {
            existing.isEmpty() -> ""
            existing.endsWith('\n') -> ""
            else -> "\n"
        }
        val available = (maxCharacters - existing.length - prefix.length).coerceAtLeast(0)
        val accepted = spoken.take(available)
        val merged = if (accepted.isEmpty()) {
            existing.take(maxCharacters)
        } else {
            existing + prefix + accepted
        }
        return VoiceMergeResult(
            text = merged,
            addedCharacters = accepted.length,
            truncated = accepted.length < spoken.length,
        )
    }
}

class AccessibilityPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "arena_accessibility",
        Context.MODE_PRIVATE,
    )

    fun isLargeTextEnabled(): Boolean = preferences.getBoolean(KEY_LARGE_TEXT, false)

    fun setLargeTextEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_LARGE_TEXT, enabled) }
    }

    private companion object {
        const val KEY_LARGE_TEXT = "large_text"
    }
}

object TextScalePolicy {
    private const val LARGE_TEXT_MULTIPLIER = 1.25f
    private const val MAX_FONT_SCALE = 1.75f

    fun composeFontScale(systemFontScale: Float, largeTextEnabled: Boolean): Float =
        if (largeTextEnabled) {
            (systemFontScale * LARGE_TEXT_MULTIPLIER).coerceAtMost(MAX_FONT_SCALE)
        } else {
            systemFontScale
        }

    fun webViewTextZoom(largeTextEnabled: Boolean): Int = if (largeTextEnabled) 125 else 100
}

class SpeechPlaybackState {
    var ready by mutableStateOf(false)
        private set

    var activeKey by mutableStateOf<String?>(null)
        private set

    var detail by mutableStateOf("朗读：准备中")
        private set

    internal fun markReady() {
        ready = true
        if (activeKey == null) detail = "朗读：可用"
    }

    internal fun markActive(key: String, segmentCount: Int = 1) {
        activeKey = key
        detail = if (segmentCount > 1) {
            "朗读：正在播放 · ${segmentCount}段"
        } else {
            "朗读：正在播放"
        }
    }

    internal fun markStopped() {
        activeKey = null
        detail = if (ready) "朗读：可用" else "朗读：准备中"
    }

    internal fun markReconnecting(key: String) {
        activeKey = key
        detail = "朗读：正在重新连接"
    }

    internal fun markError(message: String, engineUnavailable: Boolean = false) {
        if (engineUnavailable) ready = false
        activeKey = null
        detail = message
    }
}

typealias SpeechPlaybackRequest = (key: String, text: String) -> Unit

object SpeechTextPolicy {
    const val DEFAULT_CHUNK_CHARACTERS = 3_000

    fun normalize(raw: String): String = raw
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("[#*_`>]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun chunks(raw: String, maxCharacters: Int = DEFAULT_CHUNK_CHARACTERS): List<String> {
        val text = normalize(raw)
        if (text.isBlank() || maxCharacters <= 0) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxCharacters).coerceAtMost(text.length)
            if (end < text.length && end > start && Character.isHighSurrogate(text[end - 1])) end -= 1
            if (end < text.length) {
                val minimumBreak = start + (maxCharacters * 3 / 5)
                val punctuation = text.substring(start, end).indexOfLast { it in "。！？；，.!?;," }
                val candidate = if (punctuation >= 0) start + punctuation + 1 else -1
                if (candidate >= minimumBreak) end = candidate
            }
            if (end <= start) end = (start + 1).coerceAtMost(text.length)
            result += text.substring(start, end).trim()
            start = end
            while (start < text.length && text[start].isWhitespace()) start += 1
        }
        return result.filter { it.isNotEmpty() }
    }
}

class ArenaSpeechController(
    context: Context,
    val state: SpeechPlaybackState = SpeechPlaybackState(),
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var initialized = false
    private var destroyed = false
    private var session = 0L
    private var engineGeneration = 0L
    private var pending: PendingSpeech? = null

    init {
        initializeEngine()
    }

    fun toggle(key: String, text: String) {
        if (destroyed) return
        if (state.activeKey == key) {
            stop()
            return
        }
        val normalized = SpeechTextPolicy.normalize(text)
        if (normalized.isBlank()) {
            state.markError("朗读：没有可播放文字")
            return
        }
        if (!initialized) {
            pending = PendingSpeech(key, normalized, allowReconnect = true)
            state.markActive(key)
            return
        }
        speak(key, normalized)
    }

    fun stop() {
        pending = null
        session += 1
        engine?.stop()
        state.markStopped()
    }

    fun shutdown() {
        if (destroyed) return
        destroyed = true
        stop()
        engineGeneration += 1
        engine?.shutdown()
        engine = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun onInitialized(status: Int) {
        if (destroyed) return
        val current = engine
        if (status != TextToSpeech.SUCCESS || current == null) {
            pending = null
            state.markError("朗读：系统引擎不可用", engineUnavailable = true)
            return
        }
        val languageStatus = current.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (languageStatus == TextToSpeech.LANG_MISSING_DATA || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            pending = null
            state.markError("朗读：缺少中文语音包", engineUnavailable = true)
            return
        }
        current.setSpeechRate(0.88f)
        current.setPitch(1.0f)
        current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                val parsed = SpeechUtteranceId.parse(utteranceId) ?: return
                if (!parsed.last) return
                mainHandler.post {
                    if (parsed.session == session) state.markStopped()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handleSpeechError(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleSpeechError(utteranceId)
            }
        })
        initialized = true
        state.markReady()
        pending?.also { pending = null }?.let { request ->
            speak(request.key, request.text, allowReconnect = request.allowReconnect)
        }
    }

    private fun speak(key: String, text: String, allowReconnect: Boolean = true) {
        val current = engine ?: return
        val maxCharacters = TextToSpeech.getMaxSpeechInputLength()
            .coerceAtMost(SpeechTextPolicy.DEFAULT_CHUNK_CHARACTERS)
        val chunks = SpeechTextPolicy.chunks(text, maxCharacters)
        if (chunks.isEmpty()) {
            state.markError("朗读：没有可播放文字")
            return
        }
        current.stop()
        session += 1
        val currentSession = session
        state.markActive(key, chunks.size)
        var failed = false
        for ((index, chunk) in chunks.withIndex()) {
            val utteranceId = SpeechUtteranceId(currentSession, index == chunks.lastIndex).encode()
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = current.speak(chunk, queueMode, Bundle(), utteranceId)
            if (result == TextToSpeech.ERROR) {
                failed = true
                break
            }
        }
        if (!failed) return
        current.stop()
        if (!allowReconnect) {
            state.markError("朗读：播放失败")
            return
        }
        reconnectEngine(key, text, currentSession)
    }

    private fun handleSpeechError(utteranceId: String?) {
        val parsed = SpeechUtteranceId.parse(utteranceId) ?: return
        mainHandler.post {
            if (parsed.session == session) state.markError("朗读：播放失败")
        }
    }

    private fun initializeEngine() {
        if (destroyed) return
        val generation = ++engineGeneration
        engine = TextToSpeech(applicationContext) { status ->
            if (!destroyed && generation == engineGeneration) onInitialized(status)
        }
    }

    private fun reconnectEngine(key: String, text: String, failedSession: Long) {
        initialized = false
        pending = PendingSpeech(key, text, allowReconnect = false)
        state.markReconnecting(key)
        engineGeneration += 1
        engine?.shutdown()
        engine = null
        mainHandler.postDelayed({
            if (
                !destroyed &&
                session == failedSession &&
                state.activeKey == key &&
                pending?.key == key
            ) {
                initializeEngine()
            }
        }, TTS_RECONNECT_DELAY_MILLIS)
    }

    private data class PendingSpeech(
        val key: String,
        val text: String,
        val allowReconnect: Boolean,
    )

    private companion object {
        const val TTS_RECONNECT_DELAY_MILLIS = 600L
    }
}

private data class SpeechUtteranceId(val session: Long, val last: Boolean) {
    fun encode(): String = "arena_tts_${session}_${if (last) 1 else 0}"

    companion object {
        fun parse(value: String?): SpeechUtteranceId? {
            val parts = value?.split('_') ?: return null
            if (parts.size != 4 || parts[0] != "arena" || parts[1] != "tts") return null
            return SpeechUtteranceId(
                session = parts[2].toLongOrNull() ?: return null,
                last = parts[3] == "1",
            )
        }
    }
}
