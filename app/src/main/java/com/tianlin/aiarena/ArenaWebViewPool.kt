package com.tianlin.aiarena

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.view.inputmethod.InputMethodManager
import androidx.core.content.edit
import androidx.compose.runtime.mutableStateMapOf
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener

class ArenaWebViewPool(private val activity: MainActivity) : ArenaGateway {
    val statuses = mutableStateMapOf<ArenaService, ServiceStatus>().apply {
        ArenaService.entries.forEach { service -> put(service, ServiceStatus()) }
    }

    val container: FrameLayout = FrameLayout(activity).apply {
        setBackgroundColor(Color.WHITE)
        visibility = View.GONE
        isClickable = false
        isFocusable = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val webViews = linkedMapOf<ArenaService, WebView>()
    private val pendingBackgroundProbes = linkedSetOf<ArenaService>()
    private val confirmedSignedIn = mutableSetOf<ArenaService>()
    private val explicitLoginProbeCounts = mutableMapOf<ArenaService, Int>()
    private var uiSelectedService: ArenaService? = null
    private var automationService: ArenaService? = null
    private var backgroundProbeInProgress = false
    private var destroyed = false
    private var textZoomPercent = 100
    private var preloadGeneration = 0L
    private var desiredServices: Set<ArenaService> = emptySet()
    @SuppressLint("MissingOnRenderProcessGone")
    private val destroyedWebViewClient = object : WebViewClient() {
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = true
    }
    private val memberPreferences = activity.getSharedPreferences("arena_members", android.content.Context.MODE_PRIVATE)

    fun preload(services: List<ArenaService>) {
        val normalized = services.distinct().toSet()
        desiredServices = normalized
        val generation = ++preloadGeneration
        trimUndesiredWebViews()
        normalized.forEachIndexed { index, service ->
            handler.postDelayed({
                if (!destroyed && generation == preloadGeneration && service in desiredServices) {
                    ensureWebView(service)
                }
            }, index * 700L)
        }
    }

    fun loadSelectedServices(): List<ArenaService> {
        val stored = memberPreferences.getString("selected", null)
            ?.split(',')
            ?.mapNotNull { name -> ArenaService.entries.firstOrNull { it.name == name } }
            ?.distinct()
            .orEmpty()
        return if (stored.size in ArenaService.MIN_MEMBERS..ArenaService.MAX_MEMBERS) {
            stored
        } else {
            ArenaService.defaultMembers
        }
    }

    fun saveSelectedServices(services: List<ArenaService>) {
        val normalized = services.distinct().take(ArenaService.MAX_MEMBERS)
        if (normalized.size < ArenaService.MIN_MEMBERS) return
        memberPreferences.edit { putString("selected", normalized.joinToString(",") { it.name }) }
        preload(normalized)
    }

    fun setTextZoomPercent(percent: Int) {
        textZoomPercent = percent.coerceIn(100, 150)
        webViews.values.forEach { webView -> webView.settings.textZoom = textZoomPercent }
    }

    fun show(service: ArenaService?) {
        uiSelectedService = service
        if (automationService != null) return
        applyVisibility(service, hiddenAutomation = false)
        if (service != null) handler.postDelayed({ probe(service) }, 600)
        else handler.post { drainBackgroundProbes() }
    }

    private fun applyVisibility(service: ArenaService?, hiddenAutomation: Boolean) {
        container.visibility = if (service == null) View.GONE else View.VISIBLE
        container.alpha = if (hiddenAutomation) 0.01f else 1f
        container.isClickable = service != null && !hiddenAutomation
        container.isFocusable = service != null && !hiddenAutomation
        ArenaService.entries.forEach { candidate ->
            val webView = webViews[candidate]
            if (candidate == service) {
                ensureWebView(candidate).visibility = View.VISIBLE
            } else {
                webView?.visibility = View.GONE
            }
        }
    }

    fun open(service: ArenaService) {
        ensureWebView(service)
        show(service)
    }

    fun reload(service: ArenaService) {
        ensureWebView(service).reload()
    }

    fun canGoBack(service: ArenaService): Boolean = webViews[service]?.canGoBack() == true

    fun goBack(service: ArenaService): Boolean {
        val webView = webViews[service] ?: return false
        if (!webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    fun probeAll() {
        webViews.keys.toList().forEach(::probe)
    }

    override fun sendPrompt(
        service: ArenaService,
        prompt: String,
        requestId: String,
        callback: (SendOutcome) -> Unit,
    ) {
        if (statuses[service]?.state != ConnectionState.SIGNED_IN && service !in confirmedSignedIn) {
            callback(SendOutcome(false, requestId, "${service.displayName} 尚未登录"))
            return
        }
        val fullPrompt = prompt.trim()
        activateForAutomation(
            service = service,
            onTimeout = {
                finishSend(SendOutcome(false, requestId, "${service.displayName} 网页输入框加载超时"), callback)
            },
        ) { webView ->
            webView.evaluateJavascript(ArenaWebCursorScript.prepare(service, requestId)) {
                sendStandard(webView, service, fullPrompt, requestId, callback)
            }
        }
    }

    override fun readResponse(
        service: ArenaService,
        requestId: String,
        callback: (ResponseSnapshot) -> Unit,
    ) {
        val webView = webViews[service]
        if (webView == null) {
            callback(ResponseSnapshot(false, "", false, "网页尚未加载"))
            return
        }
        webView.evaluateJavascript(ArenaWebResponseScript.build(service, requestId)) { raw ->
            try {
                val payload = JSONObject(decodeJsValue(raw))
                val rawText = payload.optString("text", "")
                val originalLength = payload.optInt("originalLength", rawText.length)
                callback(
                    ResponseSnapshot(
                        found = payload.optBoolean("found", false),
                        text = rawText.take(ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS),
                        streaming = payload.optBoolean("streaming", false),
                        detail = payload.optString("error", ""),
                        truncated = payload.optBoolean("truncated", false) ||
                            originalLength > ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS,
                        originalLength = originalLength,
                        securityChallenge = payload.optBoolean("securityChallenge", false),
                    ),
                )
            } catch (error: Exception) {
                callback(ResponseSnapshot(false, "", false, error.message ?: "解析回答失败"))
            }
        }
    }

    private fun activateForAutomation(
        service: ArenaService,
        onTimeout: () -> Unit,
        block: (WebView) -> Unit,
    ) {
        if (backgroundProbeInProgress) {
            handler.postDelayed({ activateForAutomation(service, onTimeout, block) }, 200)
            return
        }
        automationService = service
        val webView = ensureWebView(service)
        applyVisibility(service, hiddenAutomation = true)
        webView.onResume()
        handler.postDelayed({
            waitForPromptInput(webView, service, attempt = 0, onTimeout = onTimeout, onReady = block)
        }, 650)
    }

    private fun waitForPromptInput(
        webView: WebView,
        service: ArenaService,
        attempt: Int,
        onTimeout: () -> Unit,
        onReady: (WebView) -> Unit,
    ) {
        val selector = promptInputSelector(service)
        webView.evaluateJavascript("!!document.querySelector(${JSONObject.quote(selector)})") { raw ->
            if (raw == "true") {
                onReady(webView)
            } else if (attempt + 1 < AUTOMATION_READY_ATTEMPTS) {
                handler.postDelayed({
                    waitForPromptInput(webView, service, attempt + 1, onTimeout, onReady)
                }, AUTOMATION_READY_INTERVAL_MS)
            } else {
                onTimeout()
            }
        }
    }

    private fun finishAutomation() {
        automationService?.let { service -> webViews[service]?.let(::hideAutomationKeyboard) }
        automationService = null
        applyVisibility(uiSelectedService, hiddenAutomation = false)
        trimUndesiredWebViews()
        handler.post { drainBackgroundProbes() }
    }

    private fun finishSend(outcome: SendOutcome, callback: (SendOutcome) -> Unit) {
        finishAutomation()
        callback(outcome)
    }

    private fun finishSuccessfulSend(
        webView: WebView,
        service: ArenaService,
        requestId: String,
        callback: (SendOutcome) -> Unit,
    ) {
        webView.evaluateJavascript(ArenaWebCursorScript.bind(service, requestId), null)
        finishSend(SendOutcome(true, requestId, "已发送"), callback)
    }

    private fun sendStandard(
        webView: WebView,
        service: ArenaService,
        fullPrompt: String,
        requestId: String,
        callback: (SendOutcome) -> Unit,
    ) {
        var scriptCallbackConsumed = false
        val scriptCallbackTimeout = Runnable {
            if (scriptCallbackConsumed) return@Runnable
            scriptCallbackConsumed = true
            finishSend(SendOutcome(false, requestId, "网页发送脚本响应超时"), callback)
        }
        handler.postDelayed(scriptCallbackTimeout, SEND_SCRIPT_CALLBACK_TIMEOUT_MS)
        webView.evaluateJavascript(sendScript(service, JSONObject.quote(fullPrompt), requestId)) { raw ->
            if (scriptCallbackConsumed) return@evaluateJavascript
            scriptCallbackConsumed = true
            handler.removeCallbacks(scriptCallbackTimeout)
            val result = decodeJsValue(raw)
            if (!result.startsWith("sent")) {
                finishSend(SendOutcome(false, requestId, result.ifBlank { "注入失败" }), callback)
                return@evaluateJavascript
            }
            if (service == ArenaService.DOUBAO) {
                // Doubao's current mobile web build accepts the same programmatic click
                // once the host WebView is no longer the visible automation surface.
                // Keep the page alive, but hide the Android view before issuing clicks.
                applyVisibility(null, hiddenAutomation = false)
                verifyDoubaoSend(webView, requestId, callback)
                return@evaluateJavascript
            }
            // Rich editors (especially Doubao's ProseMirror) update their framework state
            // asynchronously. Give the scheduled, guarded click/retry enough time to run
            // before deciding that a send failed.
            val verifyDelayMs = 2_500L
            handler.postDelayed({
                var verifyCallbackConsumed = false
                val verifyCallbackTimeout = Runnable {
                    if (verifyCallbackConsumed) return@Runnable
                    verifyCallbackConsumed = true
                    finishSend(SendOutcome(false, requestId, "网页发送确认超时"), callback)
                }
                handler.postDelayed(verifyCallbackTimeout, SEND_VERIFY_CALLBACK_TIMEOUT_MS)
                webView.evaluateJavascript(verifySendScript(service, requestId)) { verifyRaw ->
                    if (verifyCallbackConsumed) return@evaluateJavascript
                    verifyCallbackConsumed = true
                    handler.removeCallbacks(verifyCallbackTimeout)
                    val sent = verifyRaw == "true"
                    if (sent) {
                        finishSuccessfulSend(webView, service, requestId, callback)
                    } else {
                        finishSend(SendOutcome(false, requestId, "发送后未检测到新消息"), callback)
                    }
                }
            }, verifyDelayMs)
        }
    }

    private fun hideAutomationKeyboard(webView: WebView) {
        webView.clearFocus()
        val inputMethodManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputMethodManager?.hideSoftInputFromWindow(webView.windowToken, 0)
    }

    private fun verifyDoubaoSend(
        webView: WebView,
        requestId: String,
        callback: (SendOutcome) -> Unit,
        attempt: Int = 0,
    ) {
        val clickDelayMs = if (attempt == 0) 900L else 1_400L
        handler.postDelayed({
            webView.evaluateJavascript(clickSendScript(ArenaService.DOUBAO, requestId)) {
                handler.postDelayed({
                    webView.evaluateJavascript(verifySendScript(ArenaService.DOUBAO, requestId)) { raw ->
                        if (raw == "true") {
                            finishSuccessfulSend(webView, ArenaService.DOUBAO, requestId, callback)
                        } else if (attempt + 1 < DOUBAO_SEND_ATTEMPTS) {
                            verifyDoubaoSend(webView, requestId, callback, attempt + 1)
                        } else {
                            finishSend(
                                SendOutcome(false, requestId, "豆包发送按钮未响应，请稍后重试"),
                                callback,
                            )
                        }
                    }
                }, 600L)
            }
        }, clickDelayMs)
    }

    private fun decodeJsValue(raw: String): String {
        return try {
            when (val value = JSONTokener(raw).nextValue()) {
                JSONObject.NULL -> ""
                is String -> value
                else -> value.toString()
            }
        } catch (_: Exception) {
            raw.trim('"').replace("\\\"", "\"")
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        preloadGeneration += 1
        handler.removeCallbacksAndMessages(null)
        webViews.keys.toList().forEach(::disposeWebView)
    }

    private fun trimUndesiredWebViews() {
        webViews.keys
            .filter { service ->
                service !in desiredServices && service != uiSelectedService && service != automationService
            }
            .toList()
            .forEach(::disposeWebView)
    }

    private fun disposeWebView(service: ArenaService) {
        val webView = webViews.remove(service) ?: return
        pendingBackgroundProbes.remove(service)
        confirmedSignedIn.remove(service)
        explicitLoginProbeCounts.remove(service)
        statuses[service] = ServiceStatus()
        container.removeView(webView)
        webView.stopLoading()
        webView.webViewClient = destroyedWebViewClient
        webView.webChromeClient = null
        webView.removeAllViews()
        webView.destroy()
    }

    // The anonymous client below does implement onRenderProcessGone; lint cannot follow this factory shape.
    @SuppressLint("SetJavaScriptEnabled", "MissingOnRenderProcessGone")
    private fun ensureWebView(service: ArenaService): WebView {
        webViews[service]?.let { return it }

        statuses[service] = ServiceStatus(ConnectionState.LOADING, "正在打开网页")
        val webView = WebView(activity).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            visibility = if (uiSelectedService == service || automationService == service) View.VISIBLE else View.GONE

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.textZoom = textZoomPercent

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            if (service == ArenaService.QWEN &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            ) {
                WebViewCompat.addDocumentStartJavaScript(
                    this,
                    QWEN_DOCUMENT_START_CAPTURE,
                    setOf("https://*.qianwen.com"),
                )
            }
            if (service == ArenaService.ZHIPU &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            ) {
                WebViewCompat.addDocumentStartJavaScript(
                    this,
                    ZHIPU_DOCUMENT_START_CAPTURE,
                    setOf("https://chatglm.cn", "https://*.chatglm.cn"),
                )
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams,
                ): Boolean = activity.openImageFileChooser(filePathCallback)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    if (destroyed || webViews[service] !== view) return
                    val decision = LoginTrustPolicy.duringNavigation(service in confirmedSignedIn)
                    statuses[service] = ServiceStatus(
                        state = decision.state,
                        detail = if (decision.confirmedSignedIn) "网页可用 · 页面加载中" else "正在加载 ${service.displayName}",
                        url = url.orEmpty(),
                    )
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (destroyed || webViews[service] !== view) return
                    val decision = LoginTrustPolicy.duringNavigation(service in confirmedSignedIn)
                    statuses[service] = ServiceStatus(
                        state = decision.state,
                        detail = if (decision.confirmedSignedIn) "网页可用 · 正在复核" else "检查登录状态中",
                        url = url.orEmpty(),
                    )
                    this@ArenaWebViewPool.handler.postDelayed({ probe(service) }, 500)
                    this@ArenaWebViewPool.handler.postDelayed({ probe(service) }, 1_800)
                    this@ArenaWebViewPool.handler.postDelayed({ probe(service) }, 3_500)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return openExternalIfNeeded(request.url)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (destroyed || webViews[service] !== view) return
                    if (!request.isForMainFrame) return
                    statuses[service] = ServiceStatus(
                        state = ConnectionState.ERROR,
                        detail = error.description?.toString().orEmpty().ifBlank { "页面加载失败" },
                        url = request.url.toString(),
                    )
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (destroyed) return true
                    statuses[service] = ServiceStatus(
                        state = ConnectionState.ERROR,
                        detail = "网页进程已退出，点重新加载恢复",
                        url = view.url.orEmpty(),
                    )
                    webViews.remove(service)
                    container.removeView(view)
                    view.destroy()
                    return true
                }
            }
        }

        webViews[service] = webView
        container.addView(webView)
        webView.loadUrl(service.url)
        return webView
    }

    private fun openExternalIfNeeded(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme == "http" || scheme == "https" || scheme == "about") return false
        return try {
            val intent = if (scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun probe(service: ArenaService) {
        val webView = webViews[service] ?: return
        if (webView.url.isNullOrBlank() || webView.url == "about:blank") return
        if (uiSelectedService != service || automationService != null) {
            pendingBackgroundProbes += service
            drainBackgroundProbes()
            return
        }
        evaluateLoginState(service)
    }

    private fun drainBackgroundProbes() {
        if (backgroundProbeInProgress || automationService != null || uiSelectedService != null) return
        val service = pendingBackgroundProbes.firstOrNull() ?: return
        pendingBackgroundProbes.remove(service)
        val webView = webViews[service] ?: return
        if (webView.url.isNullOrBlank() || webView.url == "about:blank") return

        backgroundProbeInProgress = true
        automationService = service
        applyVisibility(service, hiddenAutomation = true)
        webView.onResume()
        handler.postDelayed({
            evaluateLoginState(service) {
                backgroundProbeInProgress = false
                finishAutomation()
            }
        }, 650)
    }

    private fun evaluateLoginState(service: ArenaService, onComplete: () -> Unit = {}) {
        val webView = webViews[service]
        if (webView == null) {
            onComplete()
            return
        }
        webView.evaluateJavascript(loginProbeScript(service)) { rawResult ->
            if (destroyed || webViews[service] !== webView) {
                onComplete()
                return@evaluateJavascript
            }
            val result = rawResult.trim('"').lowercase()
            val explicitLoginVisible = result == "explicit_login"
            val explicitLoginCount = if (explicitLoginVisible) {
                (explicitLoginProbeCounts[service] ?: 0) + 1
            } else {
                0
            }
            if (explicitLoginCount > 0) {
                explicitLoginProbeCounts[service] = explicitLoginCount
            } else {
                explicitLoginProbeCounts.remove(service)
            }
            val decision = LoginTrustPolicy.afterProbe(
                probeSignedIn = result == "signed_in",
                pageVisibleToUser = uiSelectedService == service,
                previouslyConfirmed = service in confirmedSignedIn,
                explicitLoginVisible = explicitLoginVisible,
                consecutiveExplicitLoginProbes = explicitLoginCount,
            )
            if (decision.confirmedSignedIn) confirmedSignedIn += service else confirmedSignedIn -= service
            val state = decision.state
            statuses[service] = ServiceStatus(
                state = state,
                detail = when (state) {
                    ConnectionState.SIGNED_IN -> "网页可用"
                    ConnectionState.NEEDS_LOGIN -> "需要在网页中登录"
                    else -> "后台检查中，打开网页可确认"
                },
                url = webView.url.orEmpty(),
            )
            onComplete()
        }
    }

    private fun loginProbeScript(service: ArenaService): String {
        val selectors = when (service) {
            ArenaService.DEEPSEEK -> listOf(
                "#chat-input",
                "textarea[placeholder]",
                "[contenteditable='true']",
            )
            ArenaService.DOUBAO -> listOf(
                "[contenteditable='true']",
                "textarea",
                "[class*='input'][class*='editor']",
            )
            ArenaService.KIMI -> listOf(
                "[role='textbox']",
                "[contenteditable='true']",
                "textarea",
            )
            ArenaService.QWEN -> listOf(
                "[role='textbox']",
                "[contenteditable='true']",
                "textarea",
            )
            ArenaService.YUANBAO -> listOf(
                "[contenteditable='true']",
                "textarea",
                "#chat-input",
            )
            ArenaService.ZHIPU -> listOf(
                "[contenteditable='true']",
                "[role='textbox']",
                "textarea",
            )
        }
        val selectorJson = selectors.joinToString(",") { JSONObject.quote(it) }
        return """
            (function() {
              const selectors = [$selectorJson];
              const hasInput = selectors.some(function(selector) {
                try {
                  const el = document.querySelector(selector);
                  if (!el) return false;
                  const rect = el.getBoundingClientRect();
                  const style = window.getComputedStyle(el);
                  return rect.width > 40 && rect.height > 12 && style.display !== 'none' && style.visibility !== 'hidden';
                } catch (_) { return false; }
              });
              const loginPattern = /^(登录(?:\s*[\/\|·]\s*注册)?|微信登录|抖音登录|手机号登录|扫码登录|sign in|log in|log in to sync chat history|phone number login)$/i;
              const hasVisibleLogin = Array.from(document.querySelectorAll('button,a,[role=button],[class~=button],[class*=login]')).some(function(el) {
                try {
                  const rect = el.getBoundingClientRect();
                  const style = window.getComputedStyle(el);
                  const visible = rect.width > 1 && rect.height > 1 && style.display !== 'none' && style.visibility !== 'hidden';
                  return visible && loginPattern.test((el.innerText || el.textContent || '').trim());
                } catch (_) { return false; }
              });
              if (hasVisibleLogin) return 'explicit_login';
              if (hasInput) return 'signed_in';
              return 'unknown';
            })();
        """.trimIndent()
    }

    private fun verifySendScript(service: ArenaService, requestId: String): String {
        val inputSelector = promptInputSelector(service)
        val stateBootstrap = ArenaWebCursorScript.stateBootstrap(requestId)
        val conversationAdvanced = ArenaWebCursorScript.conversationAdvancedExpression(service)
        return """
            (function() {
              $stateBootstrap
              const input = document.querySelector(${JSONObject.quote(inputSelector)});
              const inputText = input ? (input.value || input.innerText || input.textContent || '') : '';
              const clicked = !!(window.__aiArenaSendClicks && window.__aiArenaSendClicks[requestId]);
              return ($conversationAdvanced) || (inputText.trim().length === 0 && clicked);
            })();
        """.trimIndent()
    }

    private fun clickSendScript(service: ArenaService, requestId: String): String {
        val inputSelector = promptInputSelector(service)
        val sendSelector = sendButtonSelector(service)
        val stateBootstrap = ArenaWebCursorScript.stateBootstrap(requestId)
        val conversationAdvanced = ArenaWebCursorScript.conversationAdvancedExpression(service)
        return """
            (function() {
              $stateBootstrap
              if ($conversationAdvanced) return 'already_sent';
              const input = document.querySelector(${JSONObject.quote(inputSelector)});
              const inputText = input ? (input.value || input.innerText || input.textContent || '') : '';
              if (!inputText.trim()) return 'already_sent_or_missing';
              const send = document.querySelector(${JSONObject.quote(sendSelector)});
              if (!send || send.disabled || send.getAttribute('aria-disabled') === 'true') return 'not_ready';
              window.__aiArenaSendClicks = window.__aiArenaSendClicks || {};
              window.__aiArenaSendClicks[requestId] = Date.now();
              send.click();
              return 'clicked';
            })();
        """.trimIndent()
    }

    private fun sendScript(service: ArenaService, quotedPrompt: String, requestId: String): String {
        val inputSelectors = promptInputSelector(service)
        val sendSelectors = sendButtonSelector(service)
        val firstClickDelayMs = if (service == ArenaService.DOUBAO) 850 else 400
        val retryClickDelayMs = if (service == ArenaService.DOUBAO) 2_400 else 1_400
        val stateBootstrap = ArenaWebCursorScript.stateBootstrap(requestId)
        val conversationAdvanced = ArenaWebCursorScript.conversationAdvancedExpression(service)
        val qwenFetchHook = if (service == ArenaService.QWEN) {
            """
                window.__aiArenaQwenResponses = window.__aiArenaQwenResponses || {};
                window.__aiArenaQwenCaptureChunk = function(chunk, explicitRequestId, forceDone) {
                  const captureRequestId = explicitRequestId || window.__aiArenaQwenPendingRequestId;
                  if (!captureRequestId || !chunk) return;
                  let record = window.__aiArenaQwenResponses[captureRequestId];
                  if (!record && !chunk.includes('data:')) return;
                  if (!record) {
                    record = { done: false, answer: '', error: '', rawBuffer: '', startedAt: Date.now() };
                    window.__aiArenaQwenResponses[captureRequestId] = record;
                  }
                  record.rawBuffer = (record.rawBuffer + chunk).slice(-2000000);
                  let latest = record.answer || '';
                  for (const line of record.rawBuffer.split(/\r?\n/)) {
                    if (!line.startsWith('data:')) continue;
                    try {
                      const payload = JSON.parse(line.slice(5));
                      const messages = payload && payload.data && payload.data.messages || [];
                      for (const message of messages) {
                        if (typeof message.content === 'string' && message.content.trim()) {
                          latest = message.content;
                        }
                      }
                    } catch (_) {}
                  }
                  record.answer = latest;
                  if (forceDone || record.rawBuffer.includes('event:complete')) {
                    record.done = true;
                    record.rawLength = record.rawBuffer.length;
                    record.rawBuffer = '';
                    if (window.__aiArenaQwenPendingRequestId === captureRequestId) {
                      window.__aiArenaQwenPendingRequestId = null;
                    }
                    try {
                      sessionStorage.setItem(
                        '__ai_arena_qwen_response_' + captureRequestId,
                        JSON.stringify({ done: true, answer: latest, error: record.error || '' })
                      );
                    } catch (_) {}
                  }
                };
                const decoderPrototype = window.TextDecoder && window.TextDecoder.prototype;
                if (decoderPrototype && !decoderPrototype.decode.__aiArenaQwenWrapped) {
                  const originalDecode = decoderPrototype.decode;
                  const wrappedDecode = function() {
                    const decoded = originalDecode.apply(this, arguments);
                    try { window.__aiArenaQwenCaptureChunk(decoded, null, false); } catch (_) {}
                    return decoded;
                  };
                  wrappedDecode.__aiArenaQwenWrapped = true;
                  decoderPrototype.decode = wrappedDecode;
                }
                if (!window.fetch.__aiArenaQwenWrapped) {
                  const originalFetch = window.fetch;
                  const wrappedFetch = async function() {
                    const args = arguments;
                    const response = await originalFetch.apply(this, args);
                    try {
                      const url = String((args[0] && args[0].url) || args[0] || response.url || '');
                      const pendingRequestId = window.__aiArenaQwenPendingRequestId;
                      if (pendingRequestId && url.includes('/api/v2/chat')) {
                        response.clone().text().then(function(raw) {
                          window.__aiArenaQwenCaptureChunk(raw, pendingRequestId, true);
                        }).catch(function(error) {
                          const record = window.__aiArenaQwenResponses[pendingRequestId] || {
                            done: false, answer: '', error: '', rawBuffer: '', startedAt: Date.now()
                          };
                          window.__aiArenaQwenResponses[pendingRequestId] = record;
                          record.error = String(error && error.message || error);
                          record.done = true;
                        });
                      }
                    } catch (_) {}
                    return response;
                  };
                  wrappedFetch.__aiArenaQwenWrapped = true;
                  window.fetch = wrappedFetch;
                }
                window.__aiArenaQwenPendingRequestId = requestId;
            """.trimIndent()
        } else {
            ""
        }
        val zhipuMessageDispatch = if (service == ArenaService.ZHIPU) {
            """
                window.postMessage({
                  channel: '__ai_arena_zhipu_send_v1',
                  requestId: requestId,
                  text: text
                }, location.origin);
                return 'sent_pending';
            """.trimIndent()
        } else {
            ""
        }
        val scheduleClicks = if (service == ArenaService.ZHIPU) {
            ""
        } else {
            """
                setTimeout(attemptSend, $firstClickDelayMs);
                setTimeout(attemptSend, $retryClickDelayMs);
            """.trimIndent()
        }
        val focusInput = "input.focus();"
        val dispatchChangeEvent = "input.dispatchEvent(new Event('change', { bubbles: true }));"
        return """
            (function() {
              try {
                const text = $quotedPrompt;
                $stateBootstrap
                $qwenFetchHook
                $zhipuMessageDispatch
                const input = document.querySelector(${JSONObject.quote(inputSelectors)});
                if (!input) return 'no_input';
                $focusInput
                let needsSyntheticInput = true;
                if (input.editor && input.editor.commands && typeof input.editor.commands.setContent === 'function') {
                  const paragraphs = text.split(/\r?\n/).map(function(line) {
                    return { type: 'paragraph', content: line ? [{ type: 'text', text: line }] : [] };
                  });
                  const updated = input.editor.commands.setContent({ type: 'doc', content: paragraphs }, { emitUpdate: true });
                  if (updated) {
                    if (typeof input.editor.commands.focus === 'function') input.editor.commands.focus('end');
                    needsSyntheticInput = false;
                  } else {
                    input.textContent = text;
                  }
                } else if (input.getAttribute('data-slate-editor') === 'true') {
                  const selection = window.getSelection();
                  const clearRange = document.createRange();
                  clearRange.selectNodeContents(input);
                  selection.removeAllRanges();
                  selection.addRange(clearRange);
                  input.dispatchEvent(new InputEvent('beforeinput', {
                    bubbles: true,
                    cancelable: true,
                    composed: true,
                    inputType: 'deleteContentBackward',
                    data: null
                  }));
                  setTimeout(function() {
                    input.focus();
                    const currentSelection = window.getSelection();
                    const insertRange = document.createRange();
                    insertRange.selectNodeContents(input);
                    insertRange.collapse(false);
                    currentSelection.removeAllRanges();
                    currentSelection.addRange(insertRange);
                    input.dispatchEvent(new InputEvent('beforeinput', {
                      bubbles: true,
                      cancelable: true,
                      composed: true,
                      inputType: 'insertText',
                      data: text
                    }));
                  }, 80);
                  needsSyntheticInput = false;
                } else if (input.tagName === 'TEXTAREA' || input.tagName === 'INPUT') {
                  const proto = input.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                  const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                  setter.call(input, text);
                } else {
                  document.execCommand('selectAll', false, null);
                  const inserted = document.execCommand('insertText', false, text);
                  const normalizedInput = String(input.innerText || input.textContent || '').replace(/\s+/g, ' ').trim();
                  const transportProbe = String(text || '').replace(/\s+/g, ' ').trim().slice(-48);
                  if (inserted && normalizedInput.length > 0 && (!transportProbe || normalizedInput.includes(transportProbe))) {
                    // execCommand already emitted the editor's native input event.
                    needsSyntheticInput = false;
                  } else {
                    input.textContent = text;
                  }
                }
                if (needsSyntheticInput) {
                  input.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
                }
                $dispatchChangeEvent
                const currentInputText = function() {
                  return input.value || input.innerText || input.textContent || '';
                };
                const attemptSend = function() {
                  if ($conversationAdvanced) return;
                  if (!currentInputText().trim()) return;
                  const send = document.querySelector(${JSONObject.quote(sendSelectors)});
                  window.__aiArenaSendClicks = window.__aiArenaSendClicks || {};
                  if (send && !send.disabled && send.getAttribute('aria-disabled') !== 'true') {
                    window.__aiArenaSendClicks[requestId] = Date.now();
                    send.click();
                  } else {
                    window.__aiArenaSendClicks[requestId] = Date.now();
                    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true }));
                    input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', bubbles: true }));
                  }
                };
                $scheduleClicks
                return 'sent_pending';
              } catch (error) {
                return 'error:' + (error && error.message ? error.message : String(error));
              }
            })();
        """.trimIndent()
    }

    private fun promptInputSelector(service: ArenaService): String = when (service) {
        ArenaService.DEEPSEEK -> "#chat-input, textarea[placeholder], [contenteditable='true']"
        ArenaService.DOUBAO -> ".tiptap.ProseMirror[contenteditable='true'], [contenteditable='true'], textarea"
        ArenaService.KIMI -> ".chat-input-editor, [role='textbox'], [contenteditable='true'], textarea"
        ArenaService.QWEN -> "[role='textbox'], [contenteditable='true'], [contenteditable], textarea"
        ArenaService.YUANBAO -> "[contenteditable='true'], textarea, #chat-input"
        ArenaService.ZHIPU -> "[contenteditable='true'], [role='textbox'], textarea"
    }

    companion object {
        private val ZHIPU_DOCUMENT_START_CAPTURE = """
            (function() {
              try {
                const eventTargetPrototype = window.EventTarget && window.EventTarget.prototype;
                if (!eventTargetPrototype || eventTargetPrototype.addEventListener.__aiArenaWrapped) return;
                const originalAddEventListener = eventTargetPrototype.addEventListener;
                const wrappedAddEventListener = function(type, listener, options) {
                  if (type === 'input' && listener) {
                    this.__aiArenaInputListeners = this.__aiArenaInputListeners || [];
                    if (!this.__aiArenaInputListeners.includes(listener)) {
                      this.__aiArenaInputListeners.push(listener);
                    }
                  }
                  return originalAddEventListener.call(this, type, listener, options);
                };
                wrappedAddEventListener.__aiArenaWrapped = true;
                eventTargetPrototype.addEventListener = wrappedAddEventListener;
                originalAddEventListener.call(window, 'message', function(event) {
                  const payload = event && event.data;
                  if (event.source !== window || event.origin !== location.origin ||
                    !payload || payload.channel !== '__ai_arena_zhipu_send_v1') return;
                  const requestId = String(payload.requestId || '');
                  const text = String(payload.text || '');
                  window.__aiArenaZhipuDispatchResults = window.__aiArenaZhipuDispatchResults || {};
                  try {
                    const input = document.querySelector("[contenteditable='true'],[role='textbox'],textarea");
                    if (!input) throw new Error('no_input');
                    const inputPrototype = input.tagName === 'TEXTAREA'
                      ? HTMLTextAreaElement.prototype
                      : input.tagName === 'INPUT'
                        ? HTMLInputElement.prototype
                        : null;
                    if (inputPrototype) {
                      const setter = Object.getOwnPropertyDescriptor(inputPrototype, 'value').set;
                      setter.call(input, text);
                    } else {
                      input.textContent = text;
                    }
                    const listeners = Array.isArray(input.__aiArenaInputListeners)
                      ? input.__aiArenaInputListeners.slice()
                      : [];
                    if (!listeners.length) throw new Error('no_input_listener');
                    const inputEvent = new InputEvent('input', {
                      bubbles: true,
                      cancelable: false,
                      inputType: 'insertText',
                      data: text
                    });
                    try {
                      Object.defineProperty(inputEvent, 'target', { value: input });
                      Object.defineProperty(inputEvent, 'currentTarget', { value: input });
                    } catch (_) {}
                    for (const listener of listeners) {
                      if (typeof listener === 'function') listener.call(input, inputEvent);
                      else if (listener && typeof listener.handleEvent === 'function') listener.handleEvent(inputEvent);
                    }
                    const send = document.querySelector('.button-right-inner');
                    if (!send) throw new Error('no_send_button');
                    const rect = send.getBoundingClientRect();
                    const eventOptions = {
                      bubbles: true,
                      cancelable: true,
                      view: window,
                      button: 0,
                      buttons: 1,
                      clientX: rect.left + rect.width / 2,
                      clientY: rect.top + rect.height / 2
                    };
                    window.__aiArenaSendClicks = window.__aiArenaSendClicks || {};
                    window.__aiArenaSendClicks[requestId] = Date.now();
                    send.dispatchEvent(new MouseEvent('mousedown', eventOptions));
                    send.dispatchEvent(new MouseEvent(
                      'mouseup',
                      Object.assign({}, eventOptions, { buttons: 0 })
                    ));
                    window.__aiArenaZhipuDispatchResults[requestId] = 'dispatched';
                  } catch (error) {
                    window.__aiArenaZhipuDispatchResults[requestId] =
                      'error:' + String(error && error.message || error);
                  }
                });
              } catch (_) {}
            })();
        """.trimIndent()

        private val QWEN_DOCUMENT_START_CAPTURE = """
            (function() {
              try {
                const decoderPrototype = window.TextDecoder && window.TextDecoder.prototype;
                if (!decoderPrototype || decoderPrototype.decode.__aiArenaQwenWrapped) return;
                const originalDecode = decoderPrototype.decode;
                const wrappedDecode = function() {
                  const decoded = originalDecode.apply(this, arguments);
                  try {
                    if (typeof window.__aiArenaQwenCaptureChunk === 'function') {
                      window.__aiArenaQwenCaptureChunk(decoded, null, false);
                    }
                  } catch (_) {}
                  return decoded;
                };
                wrappedDecode.__aiArenaQwenWrapped = true;
                decoderPrototype.decode = wrappedDecode;
              } catch (_) {}
            })();
        """.trimIndent()

        internal fun sendButtonSelector(service: ArenaService): String = when (service) {
            ArenaService.DEEPSEEK -> "[role='button'].ds-button--primary.ds-button--circle, button.ds-button--primary.ds-button--circle, [data-testid='send-button'], button[aria-label*='Send'], button[aria-label*='发送']"
            ArenaService.DOUBAO -> "#input-engine-container button[class*='bg-dbx-fill-highlight'], button[class*='send-msg-btn'], button[class*='g-send-msg'], button[class*='send'], button[class*='send-btn'], button[aria-label*='发送']"
            ArenaService.KIMI -> "button[class*='send'], button[aria-label*='发送'], button[type='submit']"
            ArenaService.QWEN -> "button[aria-label='发送消息'], button[aria-label*='发送'], button[aria-label*='Send'], button[class*='send'], button[class*='submit']"
            ArenaService.YUANBAO -> "button[aria-label*='发送'], button[aria-label*='Send'], button[aria-label='提交'], button[class*='send'], button[class*='submit']"
            ArenaService.ZHIPU -> ".button-right-inner, .send-button-right, button[aria-label*='发送'], button[aria-label*='Send'], button[type='submit'], button[class*='send']"
        }

        private const val DOUBAO_SEND_ATTEMPTS = 7
        private const val AUTOMATION_READY_ATTEMPTS = 15
        private const val AUTOMATION_READY_INTERVAL_MS = 800L
        private const val SEND_SCRIPT_CALLBACK_TIMEOUT_MS = 12_000L
        private const val SEND_VERIFY_CALLBACK_TIMEOUT_MS = 10_000L
    }
}
