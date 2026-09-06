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
    /**
     * 每次进入自动化都会递增。看门狗和各级回调靠它判断"我还是当前这一次吗"，
     * 避免迟到的回调结束掉后来的自动化。
     */
    private var automationToken = 0L
    private var automationWatchdog: Runnable? = null
    private var automationOnTimeout: (() -> Unit)? = null
    private var backgroundProbeInProgress = false
    private var destroyed = false
    private var textZoomPercent = 100
    private var preloadGeneration = 0L
    private var desiredServices: Set<ArenaService> = emptySet()
    /**
     * 本轮正在收发的成员。它们的 WebView 不能因为用户改了成员选择就被销毁——
     * 销毁会让已经生成一半的回答直接丢失，而且清掉登录确认后连"重发"都会失败。
     */
    private var protectedServices: Set<ArenaService> = emptySet()
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
        if (destroyed) return
        // Compose 的 AndroidView.update 每次重组都会重跑，show() 因此被高频调用。
        // 没有真正切换时直接返回；否则每次重组都排一个 600ms 的登录探针，
        // 探针写回 statuses 又触发重组，形成自激循环。
        if (service == uiSelectedService && automationService == null) return
        uiSelectedService = service
        if (automationService != null) return
        applyVisibility(service, hiddenAutomation = false)
        if (service != null) handler.postDelayed({ probe(service) }, 600)
        else handler.post { drainBackgroundProbes() }
    }

    private fun applyVisibility(service: ArenaService?, hiddenAutomation: Boolean) {
        if (destroyed) return
        container.visibility = if (service == null) View.GONE else View.VISIBLE
        container.alpha = if (hiddenAutomation) 0.01f else 1f
        container.isClickable = service != null && !hiddenAutomation
        container.isFocusable = service != null && !hiddenAutomation
        ArenaService.entries.forEach { candidate ->
            val webView = webViews[candidate]
            if (candidate == service) {
                ensureWebView(candidate)?.visibility = View.VISIBLE
            } else {
                webView?.visibility = View.GONE
            }
        }
    }

    fun open(service: ArenaService) {
        if (destroyed) return
        ensureWebView(service)
        show(service)
    }

    fun reload(service: ArenaService) {
        ensureWebView(service)?.reload()
    }

    /**
     * 「重新加载 AI 网页」：设置页的第一道修复手段。
     * 已经打开的网页原地刷新；渲染进程死掉后被移除的（见 onRenderProcessGone）重新建一个。
     * 不碰 Cookie，登录态不会丢。轮次进行中不允许，否则会打断正在收的回答。
     */
    fun reloadAll(): Int {
        if (destroyed || automationService != null) return 0
        val targets = (desiredServices + webViews.keys).toList()
        targets.forEach { service ->
            val existing = webViews[service]
            if (existing != null) existing.reload() else ensureWebView(service)
        }
        return targets.size
    }

    /**
     * 只重载"上次没加载出来"的网页（多半是当时没网）。网络恢复时由界面层调用，
     * 不动正在自动化的那一页，也不动加载正常的页面。返回重载了几家。
     */
    fun reloadFailed(): Int {
        if (destroyed) return 0
        val failed = webViews.keys.filter { service ->
            service != automationService && statuses[service]?.state == ConnectionState.ERROR
        }
        failed.forEach { service -> webViews[service]?.reload() }
        return failed.size
    }

    fun canGoBack(service: ArenaService): Boolean = webViews[service]?.canGoBack() == true

    fun goBack(service: ArenaService): Boolean {
        val webView = webViews[service] ?: return false
        if (!webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    /** 由控制器在开轮/收轮时告知，哪些成员的 WebView 现在不能回收。 */
    override fun setProtectedServices(services: Set<ArenaService>) {
        protectedServices = services
    }

    // -----------------------------------------------------------------------
    // 对话级导航：新问题开新对话 / 历史会话切回当时的对话
    // -----------------------------------------------------------------------

    /**
     * 自上次由我们发起的页面加载以来，这个网页里有没有发出过消息。
     * 站点在第一条消息后用 pushState 换地址，不会触发 onPageStarted，所以只能自己记。
     */
    private val sentSinceLoad = mutableSetOf<ArenaService>()

    /** 等某个 WebView 完成一次由我们发起的加载；onPageFinished 时兑现。 */
    private val pendingLoads = mutableMapOf<ArenaService, (Boolean) -> Unit>()

    override fun conversationUrl(service: ArenaService): String =
        webViews[service]?.url.orEmpty().takeIf { it.startsWith("https://") }.orEmpty()

    override fun openFreshConversation(service: ArenaService, callback: (Boolean) -> Unit) {
        if (destroyed) return callback(false)
        val webView = ensureWebView(service) ?: return callback(false)
        val current = webView.url.orEmpty()
        // 还没发过消息、且就停在站点根地址：已经是干净的新对话，别再白等一次加载
        if (service !in sentSinceLoad && isRootUrl(service, current)) return callback(true)
        navigate(service, webView, service.url, callback)
    }

    override fun openConversation(service: ArenaService, url: String, callback: (Boolean) -> Unit) {
        if (destroyed || !url.startsWith("https://")) return callback(false)
        val webView = ensureWebView(service) ?: return callback(false)
        if (webView.url == url) return callback(true)
        navigate(service, webView, url, callback)
    }

    private fun navigate(service: ArenaService, webView: WebView, url: String, callback: (Boolean) -> Unit) {
        // 自动化进行中不能换页面：会把正在收的回答和在途的 JS 回调一起弄丢
        if (automationService == service) return callback(false)
        pendingLoads.remove(service)?.invoke(false)
        var settled = false
        val timeout = Runnable {
            if (settled) return@Runnable
            settled = true
            pendingLoads.remove(service)
            callback(false)
        }
        pendingLoads[service] = { ok ->
            if (!settled) {
                settled = true
                handler.removeCallbacks(timeout)
                callback(ok)
            }
        }
        handler.postDelayed(timeout, NAVIGATION_TIMEOUT_MS)
        sentSinceLoad.remove(service)
        webView.loadUrl(url)
    }

    /** 站点根地址（含尾斜杠差异）就算"新对话"页。 */
    private fun isRootUrl(service: ArenaService, url: String): Boolean {
        fun norm(value: String) = value.substringBefore('?').substringBefore('#').trimEnd('/')
        return norm(url) == norm(service.url)
    }

    private fun settlePendingLoad(service: ArenaService, ok: Boolean) {
        val pending = pendingLoads.remove(service) ?: return
        // 单页应用在 onPageFinished 之后还要跑一会儿脚本才会把输入框画出来；留一点余量
        handler.postDelayed({ pending(ok) }, NAVIGATION_SETTLE_MS)
    }

    fun probeAll() {
        if (destroyed) return
        webViews.keys.toList().forEach(::probe)
    }

    /** App 退到后台时挂起全部 WebView，避免 3-4 个聊天页在后台继续跑定时器和动画。 */
    fun pauseAll() {
        if (destroyed) return
        webViews.values.forEach { webView -> webView.onPause() }
    }

    fun resumeAll() {
        if (destroyed) return
        webViews.values.forEach { webView -> webView.onResume() }
    }

    override fun sendPrompt(
        service: ArenaService,
        prompt: String,
        requestId: String,
        callback: (SendOutcome) -> Unit,
    ) = sendPromptInternal(service, prompt, requestId, callback, reloadedOnce = false)

    private fun sendPromptInternal(
        service: ArenaService,
        prompt: String,
        requestId: String,
        callback: (SendOutcome) -> Unit,
        reloadedOnce: Boolean,
    ) {
        if (statuses[service]?.state != ConnectionState.SIGNED_IN && service !in confirmedSignedIn) {
            callback(SendOutcome(false, requestId, "${service.displayName} 尚未登录"))
            return
        }
        // 页面上次没加载出来（多半是当时没网）：先重新加载再发。往错误页里注入脚本必然
        // "找不到输入框"，用户联网后点「重发」会白点一次（2026-09-05 断网实测）。只重试一次，
        // 重载后还是错误页就如实报"网页打不开"。
        val failedPage = webViews[service]?.takeIf { statuses[service]?.state == ConnectionState.ERROR }
        if (failedPage != null && !reloadedOnce) {
            val target = failedPage.url?.takeIf { it.startsWith("https://") } ?: service.url
            navigate(service, failedPage, target) { ok ->
                if (ok) {
                    sendPromptInternal(service, prompt, requestId, callback, reloadedOnce = true)
                } else {
                    callback(SendOutcome(false, requestId, "${service.displayName} 网页打不开，请确认网络正常后再试"))
                }
            }
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
                val finalText = payload.optString("finalText", rawText)
                callback(
                    ResponseSnapshot(
                        found = payload.optBoolean("found", false),
                        text = rawText.take(ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS),
                        finalText = finalText.take(ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS),
                        weakDoneSignal = payload.optBoolean("weakDoneSignal", false),
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

    override fun cancelAutomation() {
        if (destroyed || automationService == null) return
        // 让所有在途回调因 token 失配而失效，并把界面可见性立刻复位。
        finishAutomation()
    }

    private fun activateForAutomation(
        service: ArenaService,
        onTimeout: () -> Unit,
        waited: Long = 0L,
        block: (WebView) -> Unit,
    ) {
        if (destroyed) {
            onTimeout()
            return
        }
        // 后台探针或上一次自动化还没结束时排队等待，而不是直接覆盖 automationService。
        // 覆盖会让先前那条链在结束时把后来这条的可见性和键盘状态一并复位。
        if (backgroundProbeInProgress || automationService != null) {
            if (waited >= AUTOMATION_QUEUE_TIMEOUT_MS) {
                onTimeout()
                return
            }
            handler.postDelayed(
                {
                    activateForAutomation(
                        service = service,
                        onTimeout = onTimeout,
                        waited = waited + AUTOMATION_QUEUE_INTERVAL_MS,
                        block = block,
                    )
                },
                AUTOMATION_QUEUE_INTERVAL_MS,
            )
            return
        }
        val webView = ensureWebView(service)
        if (webView == null) {
            onTimeout()
            return
        }
        automationService = service
        val token = ++automationToken
        automationOnTimeout = onTimeout
        // 兜底看门狗：WebView 渲染进程被杀时，已投递的 evaluateJavascript 回调会被
        // Chromium 静默丢弃，既不抛异常也不回调。没有这个超时，automationService
        // 会永远非空，之后所有发送和网页显示全部死锁，只能杀掉进程。
        val watchdog = Runnable {
            if (token != automationToken) return@Runnable
            val pending = automationOnTimeout
            finishAutomation()
            pending?.invoke()
        }
        automationWatchdog = watchdog
        handler.postDelayed(watchdog, AUTOMATION_HARD_TIMEOUT_MS)
        applyVisibility(service, hiddenAutomation = true)
        webView.onResume()
        handler.postDelayed({
            if (token != automationToken) return@postDelayed
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
        val selectors = ArenaJs.quoteArray(promptInputSelectors(service))
        val probe = "(function() { ${selectorHelperScript()} return !!arenaFirstMatch($selectors); })();"
        webView.evaluateJavascript(probe) { raw ->
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
        automationWatchdog?.let(handler::removeCallbacks)
        automationWatchdog = null
        automationOnTimeout = null
        automationToken += 1
        val service = automationService ?: return
        webViews[service]?.let(::hideAutomationKeyboard)
        automationService = null
        if (destroyed) return
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
        sentSinceLoad += service
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
        webView.evaluateJavascript(sendScript(service, ArenaJs.quote(fullPrompt), requestId)) { raw ->
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
                service !in desiredServices &&
                    service !in protectedServices &&
                    service != uiSelectedService &&
                    service != automationService
            }
            .toList()
            .forEach(::disposeWebView)
    }

    private fun disposeWebView(service: ArenaService) {
        val webView = webViews.remove(service) ?: return
        pendingBackgroundProbes.remove(service)
        confirmedSignedIn.remove(service)
        explicitLoginProbeCounts.remove(service)
        sentSinceLoad.remove(service)
        pendingLoads.remove(service)?.invoke(false)
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
    private fun ensureWebView(service: ArenaService): WebView? {
        webViews[service]?.let { return it }
        // destroy() 之后仍可能有迟到的 JS 回调走到这里；再建一个 WebView
        // 会把已经销毁的 Activity 一起泄漏掉。
        if (destroyed) return null

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
                    settlePendingLoad(service, ok = true)
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
                    pendingLoads.remove(service)?.invoke(false)
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (destroyed) return true
                    statuses[service] = ServiceStatus(
                        state = ConnectionState.ERROR,
                        detail = "网页进程已退出，点重新加载恢复",
                        url = view.url.orEmpty(),
                    )
                    webViews.remove(service)
                    confirmedSignedIn.remove(service)
                    sentSinceLoad.remove(service)
                    pendingLoads.remove(service)?.invoke(false)
                    container.removeView(view)
                    view.destroy()
                    // 这个 WebView 上所有在途的 JS 回调都不会再回来。
                    // 立刻结束自动化并让调用方失败，不必等 45 秒看门狗。
                    if (automationService == service) {
                        val pending = automationOnTimeout
                        backgroundProbeInProgress = false
                        finishAutomation()
                        pending?.invoke()
                    }
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
        // 与自动化链同理：探针的 JS 回调也会随渲染进程一起消失。
        // 没有兜底的话 backgroundProbeInProgress 会永久为 true，后续自动化全部排队饿死。
        var consumed = false
        val timeout = Runnable {
            if (consumed) return@Runnable
            consumed = true
            onComplete()
        }
        handler.postDelayed(timeout, LOGIN_PROBE_TIMEOUT_MS)
        webView.evaluateJavascript(loginProbeScript(service)) { rawResult ->
            if (consumed) return@evaluateJavascript
            consumed = true
            handler.removeCallbacks(timeout)
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
        val selectorJson = selectors.joinToString(",") { ArenaJs.quote(it) }
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
        val inputSelectors = ArenaJs.quoteArray(promptInputSelectors(service))
        val selectorHelper = selectorHelperScript()
        val stateBootstrap = ArenaWebCursorScript.stateBootstrap(requestId)
        val conversationAdvanced = ArenaWebCursorScript.conversationAdvancedExpression(service)
        return """
            (function() {
              $stateBootstrap
              $selectorHelper
              const input = arenaFirstMatch($inputSelectors);
              const inputText = input ? (input.value || input.innerText || input.textContent || '') : '';
              const clicked = !!(window.__aiArenaSendClicks && window.__aiArenaSendClicks[requestId]);
              return ($conversationAdvanced) || (inputText.trim().length === 0 && clicked);
            })();
        """.trimIndent()
    }

    private fun clickSendScript(service: ArenaService, requestId: String): String {
        val inputSelectors = ArenaJs.quoteArray(promptInputSelectors(service))
        val sendSelectors = ArenaJs.quoteArray(sendButtonSelectors(service))
        val selectorHelper = selectorHelperScript()
        val stateBootstrap = ArenaWebCursorScript.stateBootstrap(requestId)
        val conversationAdvanced = ArenaWebCursorScript.conversationAdvancedExpression(service)
        return """
            (function() {
              $stateBootstrap
              $selectorHelper
              if ($conversationAdvanced) return 'already_sent';
              const input = arenaFirstMatch($inputSelectors);
              const inputText = input ? (input.value || input.innerText || input.textContent || '') : '';
              if (!inputText.trim()) return 'already_sent_or_missing';
              const send = arenaFirstMatch($sendSelectors);
              if (!send || send.disabled || send.getAttribute('aria-disabled') === 'true') return 'not_ready';
              window.__aiArenaSendClicks = window.__aiArenaSendClicks || {};
              window.__aiArenaSendClicks[requestId] = Date.now();
              send.click();
              return 'clicked';
            })();
        """.trimIndent()
    }

    private fun sendScript(service: ArenaService, quotedPrompt: String, requestId: String): String {
        val inputSelectors = ArenaJs.quoteArray(promptInputSelectors(service))
        val sendSelectors = ArenaJs.quoteArray(sendButtonSelectors(service))
        val selectorHelper = selectorHelperScript()
        val firstClickDelayMs = if (service == ArenaService.DOUBAO) 850 else 400
        val retryClickDelayMs = if (service == ArenaService.DOUBAO) 2_400 else 1_400
        val stateBootstrap = ArenaWebCursorScript.stateBootstrap(requestId)
        val conversationAdvanced = ArenaWebCursorScript.conversationAdvancedExpression(service)
        val qwenFetchHook = if (service == ArenaService.QWEN) {
            qwenCaptureScript()
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
                $selectorHelper
                $qwenFetchHook
                $zhipuMessageDispatch
                const input = arenaFirstMatch($inputSelectors);
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
                  } else if (inserted) {
                    // Lexical（Kimi 新版输入框）之类的编辑器把 insertText 异步落到 DOM：这一刻读出来是空的
                    // 不代表没插进去。以前这里立刻用 textContent 硬塞一份再派发 input 事件，结果用户气泡里
                    // 同一个问题出现两遍。改成稍后核对，真没有再兜底。
                    needsSyntheticInput = false;
                    setTimeout(function() {
                      const nowText = String(input.innerText || input.textContent || '').replace(/\s+/g, ' ').trim();
                      if (nowText.length > 0) return;
                      input.textContent = text;
                      input.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
                    }, 180);
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
                  const send = arenaFirstMatch($sendSelectors);
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

    /**
     * 输入框候选，**按优先级从精确到兜底排列**。
     *
     * 不能直接把它们逗号拼成一个选择器交给 querySelector：CSS 选择器列表返回的是
     * "文档中第一个匹配任一选择器的元素"，而不是"第一个能匹配上的选择器"。页面里
     * 只要在真正输入框之前存在任何一个 [contenteditable='true']（隐藏占位、搜索框、
     * 富文本编辑器的量算节点），提示词就会被写进错误的元素。
     */
    private fun promptInputSelectors(service: ArenaService): List<String> = when (service) {
        ArenaService.DEEPSEEK -> listOf("#chat-input", "textarea[placeholder]", "[contenteditable='true']")
        ArenaService.DOUBAO -> listOf(".tiptap.ProseMirror[contenteditable='true']", "[contenteditable='true']", "textarea")
        ArenaService.KIMI -> listOf(".chat-input-editor", "[role='textbox']", "[contenteditable='true']", "textarea")
        ArenaService.QWEN -> listOf("[role='textbox']", "[contenteditable='true']", "[contenteditable]", "textarea")
        ArenaService.YUANBAO -> listOf("[contenteditable='true']", "textarea", "#chat-input")
        ArenaService.ZHIPU -> listOf("[contenteditable='true']", "[role='textbox']", "textarea")
    }

    private fun promptInputSelector(service: ArenaService): String =
        promptInputSelectors(service).joinToString(", ")

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

        /** 发送按钮候选，同样按优先级排列，理由见 [promptInputSelectors]。 */
        internal fun sendButtonSelectors(service: ArenaService): List<String> = when (service) {
            ArenaService.DEEPSEEK -> listOf(
                "[role='button'].ds-button--primary.ds-button--circle",
                "button.ds-button--primary.ds-button--circle",
                "[data-testid='send-button']",
                "button[aria-label*='Send']",
                "button[aria-label*='发送']",
            )
            ArenaService.DOUBAO -> listOf(
                "#input-engine-container button[class*='bg-dbx-fill-highlight']",
                "button[class*='send-msg-btn']",
                "button[class*='g-send-msg']",
                "button[class*='send']",
                "button[class*='send-btn']",
                "button[aria-label*='发送']",
            )
            ArenaService.KIMI -> listOf(
                "button[class*='send']",
                "button[aria-label*='发送']",
                "button[type='submit']",
            )
            ArenaService.QWEN -> listOf(
                "button[aria-label='发送消息']",
                "button[aria-label*='发送']",
                "button[aria-label*='Send']",
                "button[class*='send']",
                "button[class*='submit']",
            )
            ArenaService.YUANBAO -> listOf(
                "button[aria-label*='发送']",
                "button[aria-label*='Send']",
                "button[aria-label='提交']",
                "button[class*='send']",
                "button[class*='submit']",
            )
            ArenaService.ZHIPU -> listOf(
                ".button-right-inner",
                ".send-button-right",
                "button[aria-label*='发送']",
                "button[aria-label*='Send']",
                "button[type='submit']",
                "button[class*='send']",
            )
        }

        internal fun sendButtonSelector(service: ArenaService): String =
            sendButtonSelectors(service).joinToString(", ")

        /**
         * 千问的 SSE 抓取钩子。抽成独立函数是为了能在单元测试里断言它的
         * 缓冲策略，不必启动 WebView。
         */
        internal fun qwenCaptureScript(): String =
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
                  // 只解析新到达的部分：之前是把最多 2MB 的整段缓冲每次重新 split + JSON.parse，
                  // SSE 每秒几十个分片时会变成每秒上亿字符操作，渲染进程直接卡死。
                  record.rawBuffer = (record.rawBuffer || '') + chunk;
                  const lastBreak = record.rawBuffer.lastIndexOf('\n');
                  const consumable = lastBreak >= 0 ? record.rawBuffer.slice(0, lastBreak) : '';
                  record.rawBuffer = lastBreak >= 0 ? record.rawBuffer.slice(lastBreak + 1) : record.rawBuffer;
                  if (record.rawBuffer.length > 200000) record.rawBuffer = record.rawBuffer.slice(-200000);
                  let latest = record.answer || '';
                  for (const line of consumable.split(/\r?\n/)) {
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

        /** 注入到页面的按优先级查找辅助函数。 */
        internal fun selectorHelperScript(): String = """
            const arenaFirstMatch = function(selectors) {
              for (const selector of selectors) {
                try {
                  const found = document.querySelector(selector);
                  if (found) return found;
                } catch (_) {}
              }
              return null;
            };
        """.trimIndent()

        private const val DOUBAO_SEND_ATTEMPTS = 7
        private const val AUTOMATION_READY_ATTEMPTS = 15
        private const val AUTOMATION_READY_INTERVAL_MS = 800L
        private const val SEND_SCRIPT_CALLBACK_TIMEOUT_MS = 12_000L
        private const val SEND_VERIFY_CALLBACK_TIMEOUT_MS = 10_000L

        /** 整条自动化链（等输入框 + 注入 + 校验）的硬上限，超过即认定回调已丢失。 */
        private const val AUTOMATION_HARD_TIMEOUT_MS = 45_000L
        private const val AUTOMATION_QUEUE_INTERVAL_MS = 200L
        private const val AUTOMATION_QUEUE_TIMEOUT_MS = 20_000L
        private const val LOGIN_PROBE_TIMEOUT_MS = 8_000L
        /** 开新对话 / 切历史对话的整页加载上限；超过就放弃等待，直接尝试发送。 */
        private const val NAVIGATION_TIMEOUT_MS = 20_000L
        /** onPageFinished 之后再等一会儿，让单页应用把输入框画出来。 */
        private const val NAVIGATION_SETTLE_MS = 900L
    }
}
