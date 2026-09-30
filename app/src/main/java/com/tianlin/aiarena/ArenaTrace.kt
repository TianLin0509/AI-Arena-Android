package com.tianlin.aiarena

import android.os.SystemClock
import android.util.Log

/** 调试版专用的时序记录（logcat 标签 ArenaTiming）：每家每个阶段发生在第几毫秒。正式版不输出。 */
internal object ArenaTrace {
    fun log(service: ArenaService?, event: String) {
        if (BuildConfig.DEBUG) Log.i("ArenaTiming", "${SystemClock.elapsedRealtime()} ${service?.name ?: "-"} $event")
    }
}
