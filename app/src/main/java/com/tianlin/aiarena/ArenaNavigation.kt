package com.tianlin.aiarena

import android.content.Context
import androidx.core.content.edit

class ArenaNavigationPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "arena_navigation",
        Context.MODE_PRIVATE,
    )

    fun hasOpenedRoundtable(): Boolean = preferences.getBoolean(KEY_OPENED_ROUNDTABLE, false)

    fun markRoundtableOpened() {
        preferences.edit { putBoolean(KEY_OPENED_ROUNDTABLE, true) }
    }

    private companion object {
        const val KEY_OPENED_ROUNDTABLE = "opened_roundtable"
    }
}

object RoundtableNavigationPolicy {
    fun showConnectionGuide(
        usableCount: Int,
        connectionManagerRequested: Boolean,
        roundtableUnlocked: Boolean,
    ): Boolean = connectionManagerRequested ||
        (usableCount < ArenaService.MIN_MEMBERS && !roundtableUnlocked)
}
