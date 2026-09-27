package com.tianlin.aiarena

import android.content.Context
import android.util.Log

class ArenaDurationStore(context: Context) : ArenaDurationSamples {
    private val preferences = context.applicationContext.getSharedPreferences("arena_duration_samples_v1", Context.MODE_PRIVATE)
    override fun read(key: String): List<Long> = try {
        preferences.getString(key, "").orEmpty().split(',').mapNotNull(String::toLongOrNull)
            .filter { it in 1..600_000L }.takeLast(20)
    } catch (error: RuntimeException) {
        Log.w("ArenaDurationStore", "Local timing reference unavailable", error)
        emptyList()
    }

    override fun add(key: String, milliseconds: Long) {
        if (milliseconds !in 1..600_000L) return
        try {
            preferences.edit().putString(key, (read(key) + milliseconds).takeLast(20).joinToString(",")).apply()
        } catch (error: RuntimeException) {
            Log.w("ArenaDurationStore", "Could not save optional timing reference", error)
        }
    }
}
