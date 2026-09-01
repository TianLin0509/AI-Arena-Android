package com.tianlin.aiarena

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Base64
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var webViewPool: ArenaWebViewPool
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private val voiceInputState: VoiceInputState by viewModels()
    private lateinit var speechController: ArenaSpeechController
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        callback?.onReceiveValue(uri?.let { arrayOf(it) })
    }
    private val voiceRecognizer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!voiceInputState.active) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) {
            voiceInputState.finish(VoiceInputOutcome.Cancelled)
            return@registerForActivityResult
        }
        val transcript = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        if (transcript.isBlank()) {
            voiceInputState.finish(VoiceInputOutcome.Error("没有识别到语音，请再试一次"))
        } else {
            voiceInputState.finish(VoiceInputOutcome.Success(transcript))
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
        speechController = ArenaSpeechController(applicationContext)
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
            var skin by remember { mutableStateOf(skinPreferences.loadSkin()) }
            ArenaTheme(skin = skin) {
                ArenaApp(
                    pool = webViewPool,
                    debugInitialQuestion = debugInitialQuestion,
                    voiceInputState = voiceInputState,
                    voiceInputRequest = ::requestVoiceInput,
                    speechState = speechController.state,
                    speechPlaybackRequest = speechController::toggle,
                    stopSpeech = speechController::stop,
                    copyText = ::copyText,
                    shareText = ::shareText,
                    skin = skin,
                    onSkinChange = { next ->
                        skin = next
                        skinPreferences.saveSkin(next)
                    },
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
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
        }
        if (::webViewPool.isInitialized) webViewPool.destroy()
        if (::speechController.isInitialized) speechController.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!BuildConfig.DEBUG) return
        val encoded = intent.getStringExtra(DEBUG_VOICE_RESULT_BASE64_EXTRA)
        val transcript = if (encoded.isNullOrBlank()) {
            intent.getStringExtra(DEBUG_VOICE_RESULT_EXTRA)
        } else {
            runCatching {
                String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrNull()
        }
        if (!transcript.isNullOrBlank() && voiceInputState.active) {
            voiceInputState.finish(VoiceInputOutcome.Success(transcript))
        }
        val ttsEncoded = intent.getStringExtra(DEBUG_TTS_TEXT_BASE64_EXTRA)
        val ttsText = if (ttsEncoded.isNullOrBlank()) {
            intent.getStringExtra(DEBUG_TTS_TEXT_EXTRA)
        } else {
            runCatching {
                String(Base64.decode(ttsEncoded, Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrNull()
        }
        if (!ttsText.isNullOrBlank() && ::speechController.isInitialized) {
            speechController.toggle("debug:tts", ttsText)
        }
    }

    fun requestVoiceInput(): Boolean {
        if (!voiceInputState.begin()) return false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出你想问的问题")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        return try {
            voiceRecognizer.launch(intent)
            true
        } catch (_: ActivityNotFoundException) {
            voiceInputState.finish(VoiceInputOutcome.Error("当前设备没有可用的语音识别服务"))
            false
        } catch (_: Exception) {
            voiceInputState.finish(VoiceInputOutcome.Error("语音输入暂时无法启动"))
            false
        }
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

    fun shareText(title: String, text: String): Boolean {
        if (text.isBlank()) return false
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return try {
            startActivity(Intent.createChooser(sendIntent, "分享讨论总结"))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun openImageFileChooser(callback: ValueCallback<Array<Uri>>): Boolean {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = callback
        return try {
            imagePicker.launch(arrayOf("image/*"))
            true
        } catch (_: Exception) {
            pendingFileCallback = null
            callback.onReceiveValue(null)
            false
        }
    }

    companion object {
        const val DEBUG_PREFILL_EXTRA = "com.tianlin.aiarena.DEBUG_PREFILL_QUESTION"
        const val DEBUG_PREFILL_BASE64_EXTRA = "com.tianlin.aiarena.DEBUG_PREFILL_QUESTION_BASE64"
        const val DEBUG_VOICE_RESULT_EXTRA = "com.tianlin.aiarena.DEBUG_VOICE_RESULT"
        const val DEBUG_VOICE_RESULT_BASE64_EXTRA = "com.tianlin.aiarena.DEBUG_VOICE_RESULT_BASE64"
        const val DEBUG_TTS_TEXT_EXTRA = "com.tianlin.aiarena.DEBUG_TTS_TEXT"
        const val DEBUG_TTS_TEXT_BASE64_EXTRA = "com.tianlin.aiarena.DEBUG_TTS_TEXT_BASE64"
    }
}
