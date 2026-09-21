package com.tianlin.aiarena

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private lateinit var webViewPool: ArenaWebViewPool
    private val attachmentExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var attachmentPickCallback: ((Result<List<ArenaAttachment>>) -> Unit)? = null
    private var attachmentPickGeneration = 0L
    private var attachmentActivityDestroyed = false
    private var attachmentRetainedIds: Set<String> = emptySet()
    private val attachmentPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val callback = attachmentPickCallback ?: return@registerForActivityResult
        val generation = attachmentPickGeneration
        if (uris.isEmpty()) {
            attachmentPickCallback = null
            callback(Result.success(emptyList()))
        } else {
            attachmentExecutor.execute {
                val store = ArenaAttachmentStore(applicationContext)
                val result = runCatching { store.importDocuments(uris, attachmentRetainedIds) }
                mainHandler.post {
                    if (attachmentActivityDestroyed || generation != attachmentPickGeneration) {
                        result.getOrNull()?.let(store::discardImported)
                    } else {
                        attachmentPickCallback = null
                        callback(result)
                    }
                }
            }
        }
    }

    fun chooseAttachments(retainedIds: Set<String>, callback: (Result<List<ArenaAttachment>>) -> Unit) {
        if (attachmentPickCallback != null || attachmentActivityDestroyed) {
            callback(Result.failure(IllegalStateException("附件选择正在进行，请稍后重试")))
            return
        }
        attachmentPickGeneration++
        attachmentRetainedIds = retainedIds.toSet()
        attachmentPickCallback = callback
        try {
            attachmentPicker.launch(ArenaAttachmentPolicy.pickerMimeTypes)
        } catch (error: Exception) {
            attachmentPickCallback = null
            callback(Result.failure(IllegalStateException("无法打开系统文件选择器", error)))
        }
    }

    private lateinit var skinPreferences: ArenaSkinPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须在建 WebView / Compose 之前装，才能覆盖启动期的崩溃。
        ArenaCrashReporter.install(this)
        enableEdgeToEdge()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webViewPool = ArenaWebViewPool(this)
        skinPreferences = ArenaSkinPreferences(applicationContext)
        val debugInitialQuestion = if (BuildConfig.DEBUG) {
            val encoded = intent.getStringExtra(DEBUG_PREFILL_BASE64_EXTRA)
            if (encoded.isNullOrBlank()) {
                intent.getStringExtra(DEBUG_PREFILL_EXTRA).orEmpty()
            } else {
                runCatching {
                    String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
                }.getOrDefault("")
            }
        } else {
            ""
        }
        setContent {
            val skin = ArenaSkin.PURE
            ArenaTheme(skin = skin) {
                ArenaApp(
                    pool = webViewPool,
                    debugInitialQuestion = debugInitialQuestion,
                    copyText = ::copyText,
                    shareText = ::shareText,
                    openExternalUrl = ::openExternalUrl,
                    restartApp = { ArenaRestart.trigger(this) },
                    skin = skin,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webViewPool.isInitialized) {
            webViewPool.resumeAll()
            webViewPool.probeAll()
        }
    }

    override fun onPause() {
        super.onPause()
        // 退到后台时挂起全部 WebView：3-4 个聊天页各自的定时器、动画和轮询
        // 会持续耗电，也会加剧渲染进程被系统回收的概率。
        if (::webViewPool.isInitialized && !isChangingConfigurations) webViewPool.pauseAll()
        // WebView 的 Cookie 是异步落盘的：刚登录完就被系统杀进程，登录态可能还没写到磁盘。
        // 退后台这一刻强制刷一次，登录态就不会"莫名其妙没了"。
        runCatching { CookieManager.getInstance().flush() }
    }

    override fun onDestroy() {
        attachmentActivityDestroyed = true
        attachmentPickGeneration++
        attachmentPickCallback = null
        attachmentExecutor.shutdown()
        if (::webViewPool.isInitialized) webViewPool.destroy()
        super.onDestroy()
    }

    fun copyText(label: String, text: String): Boolean {
        if (text.isBlank()) return false
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 用系统浏览器打开外链（下载新版 APK 用）。没有浏览器时返回 false，由界面自己提示。 */
    fun openExternalUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun shareText(title: String, text: String): Boolean {
        if (text.isBlank()) return false
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return try {
            startActivity(Intent.createChooser(sendIntent, "分享总结"))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val DEBUG_PREFILL_EXTRA = "com.tianlin.aiarena.DEBUG_PREFILL_QUESTION"
        const val DEBUG_PREFILL_BASE64_EXTRA = "com.tianlin.aiarena.DEBUG_PREFILL_QUESTION_BASE64"
    }
}
