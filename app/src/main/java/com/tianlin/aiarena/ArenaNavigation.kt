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

    /** 回答页底部的模式与输入面板是否收起（用户自己点的，记住到下次）。 */
    fun isComposerCollapsed(): Boolean = preferences.getBoolean(KEY_COMPOSER_COLLAPSED, false)

    fun setComposerCollapsed(collapsed: Boolean) {
        preferences.edit { putBoolean(KEY_COMPOSER_COLLAPSED, collapsed) }
    }

    private companion object {
        const val KEY_OPENED_ROUNDTABLE = "opened_roundtable"
        const val KEY_COMPOSER_COLLAPSED = "composer_collapsed"
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
