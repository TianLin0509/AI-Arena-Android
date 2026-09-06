package com.tianlin.aiarena

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
