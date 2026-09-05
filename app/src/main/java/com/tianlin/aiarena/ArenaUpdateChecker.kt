package com.tianlin.aiarena

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新检查。
 *
 * 这个 App 走不了应用商店（核心机制是驱动六家网页版，商店审核过不去），家人靠 APK 直装。
 * 于是"怎么知道有新版"成了真正的痛点。做法是自建站上放一份 `version.json`：
 *
 * ```
 * { "versionCode": 8, "versionName": "0.6.0", "apkUrl": "https://…/ai-arena-v0.6.0.apk",
 *   "apkSizeBytes": 1465044, "sha256": "…", "releasedAt": "2026-09-10", "notes": "…" }
 * ```
 *
 * 比对 `versionCode` 就够了 —— 它单调递增，不需要解析版本号字符串。
 * 站点是备案域名 + 国内 ECS，微信里也能打开；GitHub Releases 在国内时好时坏，不拿它当主渠道。
 *
 * 只用 `HttpURLConnection` + `org.json`，不为这一个请求引 OkHttp / 序列化库把 APK 撑大。
 * 网络请求必须在后台线程调用（[fetch] 是阻塞的）。
 */
data class ArenaUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val sha256: String,
    val releasedAt: String,
    val notes: String,
) {
    fun isNewerThan(installedVersionCode: Int): Boolean = versionCode > installedVersionCode

    /** "1.4 MB" 这种给人看的体积；没有体积信息时返回空串。 */
    val sizeLabel: String
        get() = when {
            apkSizeBytes <= 0 -> ""
            apkSizeBytes < 1024 * 1024 -> "${apkSizeBytes / 1024} KB"
            else -> String.format(java.util.Locale.ROOT, "%.1f MB", apkSizeBytes / 1024.0 / 1024.0)
        }
}

sealed interface ArenaUpdateResult {
    data class Available(val info: ArenaUpdateInfo) : ArenaUpdateResult
    data class UpToDate(val info: ArenaUpdateInfo) : ArenaUpdateResult
    data class Failed(val reason: String) : ArenaUpdateResult
}

object ArenaUpdateChecker {
    const val MANIFEST_URL = "https://ai.lt-stockpartner.tech/android/version.json"
    const val DOWNLOAD_PAGE_URL = "https://ai.lt-stockpartner.tech/android/"

    /** 自动检查最多一天一次；手动点「检查更新」不受限。 */
    private const val AUTO_CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    private const val CONNECT_TIMEOUT_MILLIS = 8_000
    private const val READ_TIMEOUT_MILLIS = 8_000
    private const val MAX_BODY_BYTES = 64 * 1024

    private const val PREFERENCES = "arena_update"
    private const val KEY_LAST_AUTO_CHECK = "last_auto_check_millis"
    private const val KEY_DISMISSED_CODE = "dismissed_version_code"
    private const val KEY_CACHED_MANIFEST = "cached_manifest_json"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun shouldAutoCheck(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val last = prefs(context).getLong(KEY_LAST_AUTO_CHECK, 0L)
        return nowMillis - last >= AUTO_CHECK_INTERVAL_MILLIS
    }

    fun markAutoChecked(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(KEY_LAST_AUTO_CHECK, nowMillis) }
    }

    /** 用户点了「暂不更新」：这个版本号不再在首页提示，但设置页仍然能看到、能手动装。 */
    fun dismiss(context: Context, versionCode: Int) {
        prefs(context).edit { putInt(KEY_DISMISSED_CODE, versionCode) }
    }

    fun dismissedVersionCode(context: Context): Int = prefs(context).getInt(KEY_DISMISSED_CODE, -1)

    /**
     * 最近一次成功拿到的清单。结果只放内存的话，用户看到横幅、退出再进就没了，要等明天
     * 自动检查才会再出现 —— 所以成功一次就缓存，启动时先按缓存显示，再按节奏去刷新。
     */
    fun cachedResult(context: Context, installedVersionCode: Int = BuildConfig.VERSION_CODE): ArenaUpdateResult? {
        val json = prefs(context).getString(KEY_CACHED_MANIFEST, null) ?: return null
        val info = runCatching { parse(json) }.getOrNull() ?: return null
        return if (info.isNewerThan(installedVersionCode)) ArenaUpdateResult.Available(info) else ArenaUpdateResult.UpToDate(info)
    }

    private fun cache(context: Context?, json: String) {
        context ?: return
        prefs(context).edit { putString(KEY_CACHED_MANIFEST, json) }
    }

    fun isDismissed(context: Context, versionCode: Int): Boolean =
        prefs(context).getInt(KEY_DISMISSED_CODE, -1) == versionCode

    /**
     * 拉取并比对。**阻塞，必须在后台线程调用。**
     * 任何失败都收成 [ArenaUpdateResult.Failed] 带一句能给用户看的原因，不抛异常。
     */
    fun fetch(
        installedVersionCode: Int = BuildConfig.VERSION_CODE,
        url: String = MANIFEST_URL,
        cacheInto: Context? = null,
    ): ArenaUpdateResult {
        val body = try {
            download(url)
        } catch (error: IOException) {
            return ArenaUpdateResult.Failed("连不上更新服务器：${error.message ?: error.javaClass.simpleName}")
        } catch (error: Exception) {
            return ArenaUpdateResult.Failed("检查更新失败：${error.message ?: error.javaClass.simpleName}")
        }
        val info = try {
            parse(body)
        } catch (error: Exception) {
            return ArenaUpdateResult.Failed("更新信息格式不对，可能是服务器还没发布新版本")
        }
        cache(cacheInto, body)
        return if (info.isNewerThan(installedVersionCode)) {
            ArenaUpdateResult.Available(info)
        } else {
            ArenaUpdateResult.UpToDate(info)
        }
    }

    /** 纯解析，方便单测。缺 versionCode / apkUrl 直接抛，其余字段允许缺省。 */
    fun parse(json: String): ArenaUpdateInfo {
        val root = JSONObject(json)
        val versionCode = root.getInt("versionCode")
        val apkUrl = root.getString("apkUrl").trim()
        require(versionCode > 0) { "versionCode 必须为正整数" }
        require(apkUrl.startsWith("https://")) { "apkUrl 必须是 https 链接" }
        return ArenaUpdateInfo(
            versionCode = versionCode,
            versionName = root.optString("versionName").ifBlank { "版本代码 $versionCode" },
            apkUrl = apkUrl,
            apkSizeBytes = root.optLong("apkSizeBytes", 0L),
            sha256 = root.optString("sha256"),
            releasedAt = root.optString("releasedAt"),
            notes = root.optString("notes"),
        )
    }

    private fun download(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AI-Arena-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
            connection.inputStream.use { stream ->
                val bytes = stream.readNBytesCompat(MAX_BODY_BYTES)
                return String(bytes, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** minSdk 26 没有 InputStream.readNBytes(int)（API 33+），自己读到上限为止。 */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - total))
            if (read < 0) break
            buffer.write(chunk, 0, read)
            total += read
        }
        return buffer.toByteArray()
    }
}
