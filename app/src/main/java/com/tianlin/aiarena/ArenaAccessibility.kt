package com.tianlin.aiarena

import android.content.Context
import androidx.core.content.edit

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
