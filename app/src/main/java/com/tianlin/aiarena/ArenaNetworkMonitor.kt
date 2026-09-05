package com.tianlin.aiarena

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 有没有网。
 *
 * 没网的时候六家网页会各自报一串 `net::ERR_…`，家人看到的是"六个红色失败"，
 * 而真正的原因只有一个。这里在最上层判断一次，用一句话说清，网络恢复后提示自动消失。
 * 只用系统回调，不发探测请求。
 *
 * 判定标准是"有一条能上网的默认网络"（`NET_CAPABILITY_INTERNET`），**故意不看
 * `NET_CAPABILITY_VALIDATED`**：那一位要靠系统去连 Google 的连通性检测服务器才会置上，
 * 在国内很多网络下永远不会成功，看它就会把正常联网的手机误报成"没有网"（实测模拟器上
 * 状态栏带着感叹号、网页却能打开）。家人真正会遇到的是 Wi-Fi 关了、飞行模式开了，
 * 这两种情况默认网络直接消失，INTERNET 这一位足够判断。
 */
class ArenaNetworkMonitor(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    var isOnline by mutableStateOf(currentlyOnline())
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            isOnline = true
        }

        override fun onLost(network: Network) {
            // 默认网络没了就是没网：这里不能再去问 activeNetwork——系统在 onLost 那一刻往往还把
            // 刚断掉的那条网络当成"当前网络"报回来，问出来永远是"有网"，提示条就永远不出现
            // （2026-09-05 模拟器 svc data disable 实测）。若有新的默认网络，onAvailable 会再置回 true。
            isOnline = false
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            isOnline = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private var registered = false

    fun start() {
        if (registered) return
        val manager = manager ?: return
        registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        isOnline = currentlyOnline()
    }

    fun stop() {
        if (!registered) return
        runCatching { manager?.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun currentlyOnline(): Boolean {
        val manager = manager ?: return true
        return runCatching {
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }
}
