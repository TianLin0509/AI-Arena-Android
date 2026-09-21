package com.tianlin.aiarena

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
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
import java.io.File
import java.util.concurrent.Executors

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
    private class Automation(val requestId: String, val onTimeout: () -> Unit, val onInterrupted: (String) -> Unit) {
        var watchdog: Runnable? = null
        var parked = false
        var sending = false
        var cancelNativeTouch: (() -> Unit)? = null
        var nativeUpPending = false
    }
    private val automations = mutableMapOf<ArenaService, Automation>()
    private val serviceEpochs = mutableMapOf<ArenaService, Long>()
    private var cancellationEpoch = 0L
    private val fileBroker = ArenaFileChooserBroker(activity)
    private val manualFileCallbacks = mutableMapOf<WebView, ValueCallback<Array<Uri>>>()
    private val attachmentExecutor = Executors.newSingleThreadExecutor()
    private var backgroundProbeService: ArenaService? = null
    private var backgroundProbeGeneration = 0L
    private class FocusAction(val service: ArenaService, val requestId: String, val block: (() -> Unit) -> Unit)
    private val focusQueue = ArrayDeque<FocusAction>()
    private var focusAction: FocusAction? = null
    private var focusWatchdog: Runnable? = null
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
        if (service == uiSelectedService) return
        uiSelectedService = service
        refreshVisibility()
        if (service != null) handler.postDelayed({ probe(service) }, 600)
        else handler.post { drainBackgroundProbes() }
    }

    /** Keep each uploading page laid out; only a short input/touch action borrows the front surface. */
    private fun refreshVisibility() {
        if (destroyed) return
        val front = focusAction?.service ?: uiSelectedService ?: backgroundProbeService ?: automations.keys.firstOrNull()
        val hidden = focusAction != null || uiSelectedService == null
        container.visibility = if (front == null) View.GONE else View.VISIBLE
        container.alpha = if (hidden) 0.01f else 1f
        container.isClickable = front != null && !hidden
        container.isFocusable = front != null && !hidden
        ArenaService.entries.forEach { candidate ->
            val webView = webViews[candidate]
            val active = candidate == front || candidate == uiSelectedService || candidate in automations || candidate == backgroundProbeService
            val visible = active && automations[candidate]?.parked != true
            if (webView != null) updateWebViewInteraction(candidate, webView, visible)
            webView?.visibility = if (visible) View.VISIBLE else View.GONE
            webView?.alpha = if (candidate == front) 1f else 0.01f
        }
        webViews[front]?.bringToFront()
    }

    private fun updateWebViewInteraction(service: ArenaService, webView: WebView, visible: Boolean) {
        val userPage = visible && focusAction == null && uiSelectedService == service
        val acceptsFocus = visible && (userPage || focusAction?.service == service)
        // Alpha only hides drawing: a laid-out background page can still request Android focus.
        // Keep probes/upload waits non-focusable without hiding a user's Compose keyboard.
        webView.isFocusableInTouchMode = acceptsFocus
        webView.isFocusable = acceptsFocus
        webView.importantForAccessibility = if (userPage) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
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
        if (destroyed || automations.isNotEmpty()) return 0
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
            service !in automations && statuses[service]?.state == ConnectionState.ERROR
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
        if (service in automations) return callback(false)
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

    override fun sendPromptWithAttachments(
        service: ArenaService,
        prompt: String,
        requestId: String,
        attachments: List<ArenaAttachment>,
        callback: (SendOutcome) -> Unit,
    ) {
        if (attachments.isEmpty()) return sendPrompt(service, prompt, requestId, callback)
        val error = ArenaAttachmentPolicy.validate(attachments)
        if (error != null) return callback(SendOutcome(false, requestId, error))
        ArenaAttachmentSupport.sendError(service, attachments)?.let { return callback(SendOutcome(false, requestId, it)) }
        val epoch = cancellationEpoch
        val serviceEpoch = serviceEpochs[service] ?: 0L
        if (destroyed) return callback(SendOutcome(false, requestId, "网页已关闭"))
        attachmentExecutor.execute {
            val checked = runCatching {
                val store = ArenaAttachmentStore(activity.applicationContext)
                attachments.map { it to store.verify(it) }
            }
            handler.post {
                if (!submissionCurrent(service, epoch, serviceEpoch)) return@post
                checked.fold(
                    onSuccess = { sendPromptInternal(service, prompt, requestId, callback, false, it, epoch, serviceEpoch) },
                    onFailure = { callback(SendOutcome(false, requestId, it.message ?: "附件无法读取，请重新选择")) },
                )
            }
        }
    }

    private fun sendPromptInternal(
        service: ArenaService,
        prompt: String,
        requestId: String,
        callback: (SendOutcome) -> Unit,
        reloadedOnce: Boolean,
        attachmentFiles: List<Pair<ArenaAttachment, File>> = emptyList(),
        epoch: Long = cancellationEpoch,
        serviceEpoch: Long = serviceEpochs[service] ?: 0L,
    ) {
        if (!submissionCurrent(service, epoch, serviceEpoch)) return
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
                    sendPromptInternal(service, prompt, requestId, callback, true, attachmentFiles, epoch, serviceEpoch)
                } else if (submissionCurrent(service, epoch, serviceEpoch)) {
                    callback(SendOutcome(false, requestId, "${service.displayName} 网页打不开，请确认网络正常后再试"))
                }
            }
            return
        }
        val fullPrompt = prompt.trim()
        activateForAutomation(
            service = service,
            requestId = requestId,
            timeoutMillis = if (attachmentFiles.isEmpty()) AUTOMATION_HARD_TIMEOUT_MS else 180_000L,
            onTimeout = {
                callback(SendOutcome(false, requestId, "${service.displayName} 网页发送超时，请检查原网页后重试"))
            },
            onBusy = { callback(SendOutcome(false, requestId, "${service.displayName} 仍有发送任务，请等待或停止后重试")) },
            onInterrupted = { detail -> callback(SendOutcome(false, requestId, detail)) },
        ) { webView ->
            val token = automations[service]
            webView.evaluateJavascript(ArenaWebCursorScript.prepare(service, requestId)) {
                if (!isCurrent(service, token)) return@evaluateJavascript
                if (attachmentFiles.isEmpty()) sendStandard(webView, service, fullPrompt, requestId, callback)
                else ArenaAttachmentTransport(handler, fileBroker) { action -> withFocus(service, requestId, action) }
                    .upload(webView, service, requestId, attachmentFiles, { isCurrent(service, token) }) { error ->
                    if (!isCurrent(service, token)) return@upload
                    if (error != null) finishSend(service, SendOutcome(false, requestId, error), callback)
                    else sendStandard(webView, service, fullPrompt, requestId, callback, nativeAttachmentSend = service == ArenaService.DOUBAO)
                }
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
                        modeLabel = AiModePolicy.label(AiModePolicy.parse(payload.optJSONObject("mode"))),
                        thinkingUsed = payload.optBoolean("thinkingUsed", false),
                    ),
                )
            } catch (error: Exception) {
                callback(ResponseSnapshot(false, "", false, error.message ?: "解析回答失败"))
            }
        }
    }

    private fun submissionCurrent(service: ArenaService, epoch: Long, serviceEpoch: Long) =
        !destroyed && epoch == cancellationEpoch && serviceEpoch == (serviceEpochs[service] ?: 0L)

    private fun isCurrent(service: ArenaService, token: Automation?) =
        !destroyed && token != null && automations[service] === token

    override fun cancelAutomation() {
        cancellationEpoch++
        automations.keys.toList().forEach { service -> finishAutomation(service) }
        focusQueue.clear()
        fileBroker.cancelAll()
        manualFileCallbacks.keys.toList().forEach(::cancelManualChooser)
        backgroundProbeGeneration++
        backgroundProbeService = null
        refreshVisibility()
    }

    override fun cancelAutomation(service: ArenaService) {
        serviceEpochs[service] = (serviceEpochs[service] ?: 0L) + 1L
        finishAutomation(service)
        webViews[service]?.let { fileBroker.cancel(it) }
    }

    private fun activateForAutomation(
        service: ArenaService,
        requestId: String,
        onTimeout: () -> Unit,
        onBusy: () -> Unit,
        onInterrupted: (String) -> Unit,
        timeoutMillis: Long = AUTOMATION_HARD_TIMEOUT_MS,
        block: (WebView) -> Unit,
    ) {
        if (destroyed) return onTimeout()
        if (service in automations) return onBusy()
        val webView = ensureWebView(service) ?: return onTimeout()
        val token = Automation(requestId, onTimeout, onInterrupted)
        automations[service] = token
        val watchdog = Runnable {
            if (!isCurrent(service, token)) return@Runnable
            finishAutomation(service)
            onTimeout()
        }
        token.watchdog = watchdog
        handler.postDelayed(watchdog, timeoutMillis)
        refreshVisibility()
        webView.onResume()
        handler.postDelayed({
            if (isCurrent(service, token)) waitForPromptInput(webView, service, token, 0, block)
        }, 650L)
    }

    private fun waitForPromptInput(
        webView: WebView,
        service: ArenaService,
        token: Automation,
        attempt: Int,
        onReady: (WebView) -> Unit,
    ) {
        val selectors = ArenaJs.quoteArray(promptInputSelectors(service))
        val probe = "(function() { ${selectorHelperScript()} return !!arenaFirstMatch($selectors); })();"
        webView.evaluateJavascript(probe) { raw ->
            if (!isCurrent(service, token)) return@evaluateJavascript
            if (raw == "true") onReady(webView)
            else if (attempt + 1 < AUTOMATION_READY_ATTEMPTS) handler.postDelayed({
                if (isCurrent(service, token)) waitForPromptInput(webView, service, token, attempt + 1, onReady)
            }, AUTOMATION_READY_INTERVAL_MS)
            else {
                finishAutomation(service)
                token.onTimeout()
            }
        }
    }

    /** A lease ends after input injection or one native tap, never after upload/parse/send acknowledgement. */
    private fun withFocus(service: ArenaService, requestId: String, block: (() -> Unit) -> Unit) {
        if (automations[service]?.requestId != requestId || destroyed) return
        focusQueue.addLast(FocusAction(service, requestId, block))
        drainFocusQueue()
    }

    private fun drainFocusQueue() {
        if (destroyed || focusAction != null) return
        while (focusQueue.isNotEmpty()) {
            val action = focusQueue.removeFirst()
            val token = automations[action.service]
            if (token == null || token.requestId != action.requestId) continue
            val webView = webViews[action.service] ?: continue
            focusAction = action
            refreshVisibility()
            webView.requestFocus()
            val watchdog = Runnable {
                if (focusAction !== action) return@Runnable
                releaseFocus(action)
                if (isCurrent(action.service, token)) {
                    finishAutomation(action.service)
                    token.onInterrupted("${action.service.displayName} 网页输入操作响应超时，请检查原网页后重试")
                }
            }
            focusWatchdog = watchdog
            handler.postDelayed(watchdog, FOCUS_ACTION_TIMEOUT_MS)
            // Let Android apply the front view's layout before querying native touch coordinates.
            handler.postDelayed({
                if (focusAction !== action || !isCurrent(action.service, token)) return@postDelayed
                action.block { releaseFocus(action) }
            }, 80L)
            return
        }
    }

    private fun releaseFocus(action: FocusAction) {
        if (focusAction !== action) return
        focusWatchdog?.let(handler::removeCallbacks)
        focusWatchdog = null
        webViews[action.service]?.let(::hideAutomationKeyboard)
        focusAction = null
        refreshVisibility()
        handler.post { drainFocusQueue() }
    }

    private fun finishAutomation(service: ArenaService) {
        val token = automations.remove(service) ?: return
        val cancelTouch = token.cancelNativeTouch
        token.cancelNativeTouch = null
        cancelTouch?.invoke()
        token.watchdog?.let(handler::removeCallbacks)
        webViews[service]?.let { view ->
            view.evaluateJavascript(
                "window.__aiArenaCancelledRequests=window.__aiArenaCancelledRequests||{};window.__aiArenaCancelledRequests[${ArenaJs.quote(token.requestId)}]=true;" + ArenaAttachmentScript.cancel(token.requestId) + nativeDoubaoCleanupScript(token.requestId, token.nativeUpPending), null,
            )
            fileBroker.cancel(view, token.requestId)
        }
        focusQueue.removeAll { it.service == service && it.requestId == token.requestId }
        focusAction?.takeIf { it.service == service && it.requestId == token.requestId }?.let(::releaseFocus)
        if (destroyed) return
        refreshVisibility()
        trimUndesiredWebViews()
        handler.post { drainBackgroundProbes() }
    }

    private fun finishSend(service: ArenaService, outcome: SendOutcome, callback: (SendOutcome) -> Unit) {
        if (automations[service]?.requestId != outcome.requestId) return
        finishAutomation(service)
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
        finishSend(service, SendOutcome(true, requestId, "已发送"), callback)
    }

    private fun sendStandard(
        webView: WebView,
        service: ArenaService,
        fullPrompt: String,
        requestId: String,
        callback: (SendOutcome) -> Unit,
        nativeAttachmentSend: Boolean = false,
    ) {
        if (nativeAttachmentSend && service == ArenaService.DOUBAO) {
            sendDoubaoAttachmentNative(webView, fullPrompt, requestId, callback)
            return
        }
        val token = automations[service]
        var scriptCallbackConsumed = false
        val scriptCallbackTimeout = Runnable {
            if (!isCurrent(service, token)) return@Runnable
            if (scriptCallbackConsumed) return@Runnable
            scriptCallbackConsumed = true
            finishSend(service, SendOutcome(false, requestId, "网页发送脚本响应超时"), callback)
        }
        withFocus(service, requestId) { release ->
            handler.postDelayed(scriptCallbackTimeout, SEND_SCRIPT_CALLBACK_TIMEOUT_MS)
            token?.sending = true
            webView.evaluateJavascript(sendScript(service, ArenaJs.quote(fullPrompt), requestId)) { raw ->
                // Rich-editor insertText can commit on a later task; release before the guarded send click.
                handler.postDelayed({ release() }, 220L)
                if (!isCurrent(service, token)) return@evaluateJavascript
                if (scriptCallbackConsumed) return@evaluateJavascript
                scriptCallbackConsumed = true
                handler.removeCallbacks(scriptCallbackTimeout)
                val result = decodeJsValue(raw)
                if (!result.startsWith("sent")) {
                    finishSend(service, SendOutcome(false, requestId, result.ifBlank { "注入失败" }), callback)
                    return@evaluateJavascript
                }
                if (service == ArenaService.DOUBAO) {
                    // Doubao's current mobile web build accepts the same programmatic click
                    // once the host WebView is no longer the visible automation surface.
                    // Keep the page alive, but hide the Android view before issuing clicks.
                    token?.parked = true
                    refreshVisibility()
                    verifyDoubaoSend(webView, requestId, callback)
                    return@evaluateJavascript
                }
                // Rich editors (especially Doubao's ProseMirror) update their framework state
                // asynchronously. Give the scheduled, guarded click/retry enough time to run
                // before deciding that a send failed.
                val verifyDelayMs = 2_500L
                handler.postDelayed({
                    if (!isCurrent(service, token)) return@postDelayed
                    var verifyCallbackConsumed = false
                    val verifyCallbackTimeout = Runnable {
                        if (!isCurrent(service, token)) return@Runnable
                        if (verifyCallbackConsumed) return@Runnable
                        verifyCallbackConsumed = true
                        finishSend(service, SendOutcome(false, requestId, "网页发送确认超时"), callback)
                    }
                    handler.postDelayed(verifyCallbackTimeout, SEND_VERIFY_CALLBACK_TIMEOUT_MS)
                    webView.evaluateJavascript(verifySendScript(service, requestId)) { verifyRaw ->
                        if (!isCurrent(service, token)) return@evaluateJavascript
                        if (verifyCallbackConsumed) return@evaluateJavascript
                        verifyCallbackConsumed = true
                        handler.removeCallbacks(verifyCallbackTimeout)
                        val sent = verifyRaw == "true"
                        if (sent) {
                            finishSuccessfulSend(webView, service, requestId, callback)
                        } else if (service == ArenaService.KIMI) {
                            // A members-only Kimi model answers the send click with an upgrade modal (2026-09-15).
                            webView.evaluateJavascript(KIMI_UPGRADE_MODAL_SCRIPT) { modal ->
                                if (!isCurrent(service, token)) return@evaluateJavascript
                                val detail = if (modal == "true") KIMI_UPGRADE_DETAIL else "发送后未检测到新消息"
                                finishSend(service, SendOutcome(false, requestId, detail), callback)
                            }
                        } else {
                            finishSend(service, SendOutcome(false, requestId, "发送后未检测到新消息"), callback)
                        }
                    }
                }, verifyDelayMs)
            }
        }
    }

    private fun hideAutomationKeyboard(webView: WebView) {
        webView.clearFocus()
        val inputMethodManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputMethodManager?.hideSoftInputFromWindow(webView.windowToken, 0)
    }

    /** Doubao attachments use one trusted foreground gesture; delivery checks never click again. */
    private fun sendDoubaoAttachmentNative(webView: WebView, prompt: String, requestId: String, callback: (SendOutcome) -> Unit) {
        val service = ArenaService.DOUBAO
        val token = automations[service] ?: return
        val origin = webView.url
        val generation = fileBroker.generation(webView)
        var issued = false
        var geometryFailures = 0
        var preparationDeadline = 0L
        fun current() = isCurrent(service, token)
        fun sameDocument() = current() && fileBroker.generation(webView) == generation && webView.url == origin && ArenaFileChooserBroker.trusted(service, webView.url)
        fun fail(detail: String) { if (current()) finishSend(service, SendOutcome(false, requestId, detail), callback) }
        fun read(raw: String): JSONObject? = try { JSONObject(decodeJsValue(raw)) } catch (_: Exception) { null }
        fun verify(deadline: Long) {
            if (!current()) return
            if (!ArenaFileChooserBroker.trusted(service, webView.url)) return fail("豆包网页已切换，发送已停止")
            webView.evaluateJavascript(verifySendScript(service, requestId)) { raw ->
                if (!current()) return@evaluateJavascript
                if (raw == "true") finishSuccessfulSend(webView, service, requestId, callback)
                else if (SystemClock.elapsedRealtime() >= deadline) fail("豆包发送后未检测到新消息，请查看原网页；未重复点击发送")
                else handler.postDelayed({ verify(deadline) }, 250L)
            }
        }
        fun awaitClick(release: () -> Unit) {
            var settled = false
            lateinit var timeout: Runnable
            fun settle() {
                if (settled) return
                settled = true
                handler.removeCallbacks(timeout)
                release()
                if (current()) {
                    val deadline = SystemClock.elapsedRealtime() + SEND_VERIFY_CALLBACK_TIMEOUT_MS
                    handler.postDelayed({ fail("豆包发送确认响应超时，请查看原网页；未重复点击发送") }, SEND_VERIFY_CALLBACK_TIMEOUT_MS)
                    verify(deadline)
                }
            }
            timeout = Runnable { settle() }
            handler.postDelayed(timeout, 1_500L)
            fun poll() {
                if (settled) return
                if (!current() || !sameDocument()) return settle()
                webView.evaluateJavascript("!!(window.__aiArenaNativeSend?.id===${ArenaJs.quote(requestId)}&&window.__aiArenaNativeSend.completed)") { raw ->
                    if (settled) return@evaluateJavascript
                    if (raw == "true") token.nativeUpPending = false
                    if (!current() || raw == "true") settle() else handler.postDelayed({ poll() }, 50L)
                }
            }
            handler.postDelayed({ poll() }, 25L)
        }
        fun prepareTouch() {
            if (!current() || issued) return
            if (!sameDocument()) return fail("豆包网页已切换，未发送问题")
            if (SystemClock.elapsedRealtime() >= preparationDeadline) return fail("豆包发送按钮或当前正文尚未就绪，未发送问题")
            webView.evaluateJavascript(nativeDoubaoControlScript(requestId, arm = false)) probe@{ raw ->
                if (!current() || issued) return@probe
                val ready = read(raw) ?: return@probe fail("豆包发送控件状态无法读取，未发送问题")
                if (ready.has("error")) return@probe fail(ready.optString("error"))
                if (!ready.optBoolean("ready")) {
                    handler.postDelayed({ prepareTouch() }, 250L)
                    return@probe
                }
                withFocus(service, requestId) { release ->
                    if (!sameDocument()) { release(); fail("豆包网页已切换，未发送问题"); return@withFocus }
                    if (issued) { release(); return@withFocus }
                    val widthBefore = webView.width
                    val heightBefore = webView.height
                    webView.evaluateJavascript(nativeDoubaoControlScript(requestId, arm = true)) control@{ rawControl ->
                        if (!current()) { release(); return@control }
                        if (!sameDocument()) { release(); return@control fail("豆包网页已切换，未发送问题") }
                        val control = read(rawControl)
                        if (control == null || control.has("error")) { release(); return@control fail(control?.optString("error") ?: "豆包发送控件状态无法读取，未发送问题") }
                        if (!control.optBoolean("ready")) { release(); handler.postDelayed({ prepareTouch() }, 250L); return@control }
                        val cssWidth = control.optDouble("width", Double.NaN)
                        val cssHeight = control.optDouble("height", Double.NaN)
                        val scale = widthBefore / cssWidth
                        val x = (control.optDouble("x", Double.NaN) * scale).toFloat()
                        val y = (control.optDouble("y", Double.NaN) * scale).toFloat()
                        if (!cssWidth.isFinite() || !cssHeight.isFinite() || cssWidth <= 0 || cssHeight <= 0 || !scale.isFinite() || !x.isFinite() || !y.isFinite() ||
                            widthBefore <= 0 || heightBefore <= 0 || widthBefore != webView.width || heightBefore != webView.height || x < 0 || y < 0 || x > webView.width || y > webView.height) {
                            release()
                            if (++geometryFailures >= 3) fail("豆包发送按钮布局持续变化，未发送问题")
                            else handler.postDelayed({ prepareTouch() }, 250L)
                            return@control
                        }
                        if (issued || webView.visibility != View.VISIBLE || !webView.hasFocus()) { release(); return@control fail("豆包发送页面尚未获得焦点，未发送问题") }
                        issued = true // Never return the send budget after a native DOWN.
                        var down = true
                        val downAt = SystemClock.uptimeMillis()
                        fun touch(action: Int) { MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0).let { event -> webView.dispatchTouchEvent(event); event.recycle() } }
                        token.cancelNativeTouch = {
                            if (down) { down = false; if (!destroyed && webViews[service] === webView) touch(MotionEvent.ACTION_CANCEL) }
                        }
                        touch(MotionEvent.ACTION_DOWN)
                        handler.postDelayed({
                            if (!current()) { release(); return@postDelayed }
                            if (!sameDocument()) { fail("豆包网页已切换，未发送问题"); release(); return@postDelayed }
                            if (!down) { release(); return@postDelayed }
                            down = false
                            token.cancelNativeTouch = null
                            token.nativeUpPending = true
                            touch(MotionEvent.ACTION_UP)
                            if (current()) awaitClick(release) else release()
                        }, 100L)
                    }
                }
            }
        }
        var injectionFinished = false
        val injectionTimeout = Runnable { if (!injectionFinished) { injectionFinished = true; fail("豆包正文注入响应超时，未发送问题") } }
        withFocus(service, requestId) { release ->
            token.sending = true
            handler.postDelayed(injectionTimeout, SEND_SCRIPT_CALLBACK_TIMEOUT_MS)
            webView.evaluateJavascript(nativeDoubaoSetupScript(requestId, prompt) + sendScript(service, ArenaJs.quote(prompt), requestId, scheduleSubmit = false, browserInputOnly = true)) { raw ->
                handler.postDelayed({ release() }, 220L)
                if (!current() || injectionFinished) return@evaluateJavascript
                injectionFinished = true
                handler.removeCallbacks(injectionTimeout)
                val result = decodeJsValue(raw)
                if (!result.startsWith("sent")) return@evaluateJavascript fail(result.ifBlank { "豆包正文注入失败，未发送问题" })
                preparationDeadline = SystemClock.elapsedRealtime() + SEND_VERIFY_CALLBACK_TIMEOUT_MS
                handler.postDelayed({ if (!issued) fail("豆包发送控件响应超时，未发送问题") }, SEND_VERIFY_CALLBACK_TIMEOUT_MS)
                handler.postDelayed({ prepareTouch() }, 220L)
            }
        }
    }

    private fun nativeDoubaoSetupScript(requestId: String, prompt: String): String = """
        (()=>{
          ${sendControlHelperScript()}
          const previous=window.__aiArenaNativeSend;if(previous?.cleanup)previous.cleanup();
          window.__aiArenaNativeSendRequests=window.__aiArenaNativeSendRequests||{};
          window.__aiArenaNativeSendRequests[${ArenaJs.quote(requestId)}]=true;
          const normalize=text=>String(text||'').replace(/\s+/g,' ').trim();
          const native={id:${ArenaJs.quote(requestId)},expected:normalize(${ArenaJs.quote(prompt)}),target:null,input:null,armedAt:Infinity,down:null,up:null,completed:false};
          const alive=()=>window.__aiArenaNativeSend===native&&!window.__aiArenaCancelledRequests?.[native.id];
          const buttonFor=e=>e.target?.closest?.('button#flow-end-msg-send');
          native.pointer=e=>{
            if(!alive()){
              // Preserve the guard for the queued old UP/click, but an actual new user gesture is allowed.
              if(native.cancelledAt!==undefined&&e.type==='pointerdown'&&e.isTrusted&&e.timeStamp>=native.cancelledAt){native.cleanup();if(window.__aiArenaNativeSend===native)window.__aiArenaNativeSend=null;}
              return;
            }
            // MotionEvent uses millisecond uptime; Chromium's time-origin conversion can place
            // a newly dispatched event a fraction of a millisecond before performance.now().
            // Allow only that sub-millisecond precision gap; older events remain rejected.
            if(!e.isTrusted||buttonFor(e)!==native.target||native.armedAt-e.timeStamp>=1)return;
            if(e.type==='pointerdown'){native.down=e.timeStamp;native.up=null;}
            else if(native.down!==null)native.up=e.timeStamp;
          };
          native.click=e=>{
            const button=buttonFor(e);if(!button||!button.closest('.guidance-input-surface,#input-engine-container'))return;
            if(native.cancelledAt!==undefined&&!alive()){
              // This branch has no scheduled JS send; do not intercept a later ordinary text-only JS send.
              if(!e.isTrusted)return;
              e.preventDefault();e.stopImmediatePropagation();native.cleanup();if(window.__aiArenaNativeSend===native)window.__aiArenaNativeSend=null;return;
            }
            const valid=alive()&&e.isTrusted&&button===native.target&&arenaSendEnabled(button)&&native.down!==null&&native.up!==null&&e.timeStamp>=native.down&&
              native.input?.isConnected&&normalize(native.input.value||native.input.innerText||native.input.textContent)===native.expected;
            if(!valid){e.preventDefault();e.stopImmediatePropagation();return;}
            window.__aiArenaSendClicks=window.__aiArenaSendClicks||{};
            if(window.__aiArenaSendClicks[native.id]){e.preventDefault();e.stopImmediatePropagation();return;}
            window.__aiArenaSendClicks[native.id]=Date.now();
            setTimeout(()=>{if(alive())native.completed=true;},0);
          };
          native.cleanup=()=>{document.removeEventListener('pointerdown',native.pointer,true);document.removeEventListener('pointerup',native.pointer,true);document.removeEventListener('click',native.click,true);};
          document.addEventListener('pointerdown',native.pointer,true);document.addEventListener('pointerup',native.pointer,true);document.addEventListener('click',native.click,true);
          window.__aiArenaNativeSend=native;
        })();
    """.trimIndent()

    private fun nativeDoubaoControlScript(requestId: String, arm: Boolean): String = """
        (()=>{
          ${sendControlHelperScript()}
          const native=window.__aiArenaNativeSend;
          if(!native||native.id!==${ArenaJs.quote(requestId)}||window.__aiArenaCancelledRequests?.[native.id])return JSON.stringify({error:'豆包发送请求已取消'});
          if(window.__aiArenaSendClicks?.[native.id])return JSON.stringify({error:'豆包已点击发送，未重复提交'});
          const shown=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>2&&r.height>2&&s.display!=='none'&&s.visibility!=='hidden'};
          const buttons=Array.from(document.querySelectorAll('button#flow-end-msg-send')).filter(shown);
          if(buttons.length>1)return JSON.stringify({error:'无法确认豆包唯一发送按钮，未发送问题'});
          const target=buttons[0],scope=target?.closest('.guidance-input-surface,#input-engine-container');
          if(!scope||!arenaSendEnabled(target))return JSON.stringify({ready:false});
          const inputs=Array.from(scope.querySelectorAll('textarea,[contenteditable=true]')).filter(shown);
          if(inputs.length!==1)return JSON.stringify({ready:false});
          const input=inputs[0],text=String(input.value||input.innerText||input.textContent||'').replace(/\s+/g,' ').trim();
          if(!text||text!==native.expected)return JSON.stringify({ready:false});
          const rect=target.getBoundingClientRect(),x=rect.left+rect.width/2,y=rect.top+rect.height/2,hit=document.elementFromPoint(x,y);
          if(!hit||!(hit===target||target.contains(hit)))return JSON.stringify({ready:false});
          if($arm){native.target=target;native.input=input;native.armedAt=performance.now();native.down=null;native.up=null;}
          return JSON.stringify({ready:true,x,y,width:innerWidth,height:innerHeight});
        })();
    """.trimIndent()

    private fun nativeDoubaoCleanupScript(requestId: String, pendingUp: Boolean): String = """
        (()=>{const native=window.__aiArenaNativeSend;if(native?.id===${ArenaJs.quote(requestId)}){
          if($pendingUp&&!window.__aiArenaSendClicks?.[native.id])native.cancelledAt=performance.now();
          else {native.cleanup();window.__aiArenaNativeSend=null;}
        }})();
    """.trimIndent()

    private fun verifyDoubaoSend(
        webView: WebView,
        requestId: String,
        callback: (SendOutcome) -> Unit,
        attempt: Int = 0,
    ) {
        val service = ArenaService.DOUBAO
        val token = automations[service]
        val clickDelayMs = if (attempt == 0) 900L else 1_400L
        handler.postDelayed({
            if (!isCurrent(service, token)) return@postDelayed
            webView.evaluateJavascript(clickSendScript(ArenaService.DOUBAO, requestId)) {
                if (!isCurrent(service, token)) return@evaluateJavascript
                handler.postDelayed({
                    if (!isCurrent(service, token)) return@postDelayed
                    webView.evaluateJavascript(verifySendScript(ArenaService.DOUBAO, requestId)) { raw ->
                        if (!isCurrent(service, token)) return@evaluateJavascript
                        if (raw == "true") {
                            finishSuccessfulSend(webView, ArenaService.DOUBAO, requestId, callback)
                        } else if (attempt + 1 < DOUBAO_SEND_ATTEMPTS) {
                            verifyDoubaoSend(webView, requestId, callback, attempt + 1)
                        } else {
                            finishSend(
                                service, SendOutcome(false, requestId, "豆包发送按钮未响应，请稍后重试"),
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
        cancelAutomation()
        destroyed = true
        attachmentExecutor.shutdown()
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
                    service !in automations
            }
            .toList()
            .forEach(::disposeWebView)
    }

    private fun disposeWebView(service: ArenaService) {
        val webView = webViews.remove(service) ?: return
        cancelManualChooser(webView)
        fileBroker.destroyed(webView)
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
            visibility = if (uiSelectedService == service || service in automations) View.VISIBLE else View.GONE

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
                ): Boolean {
                    if (fileBroker.handle(webView, filePathCallback, fileChooserParams)) return true
                    if (destroyed || service in automations || uiSelectedService != service || !ArenaFileChooserBroker.trusted(service, webView.url)) {
                        filePathCallback.onReceiveValue(null)
                        return true
                    }
                    val generation = fileBroker.generation(webView)
                    val origin = webView.url
                    cancelManualChooser(webView)
                    manualFileCallbacks[webView] = filePathCallback
                    fun deliver(uris: Array<Uri>?) {
                        if (manualFileCallbacks[webView] === filePathCallback) {
                            manualFileCallbacks.remove(webView)
                            filePathCallback.onReceiveValue(uris)
                        }
                    }
                    // Manual provider uploads cannot know the Compose draft references: preserve all copies.
                    activity.chooseAttachments(ArenaAttachmentLeases.retainedIds() + ArenaAttachmentStore(activity).storedIds()) { result ->
                        if (destroyed || webViews[service] !== webView || generation != fileBroker.generation(webView) || origin != webView.url) {
                            deliver(null)
                            return@chooseAttachments
                        }
                        val files = result.getOrNull().orEmpty()
                        if (files.isEmpty()) {
                            deliver(null)
                            result.exceptionOrNull()?.let { android.widget.Toast.makeText(activity, it.message, android.widget.Toast.LENGTH_LONG).show() }
                        } else {
                            attachmentExecutor.execute {
                                val checked = runCatching { files.map { it to ArenaAttachmentStore(activity).verify(it) } }
                                handler.post {
                                    if (destroyed || generation != fileBroker.generation(webView) || origin != webView.url) {
                                        deliver(null)
                                    } else checked.fold(onSuccess = { verified ->
                                        fileBroker.prepare(webView, service, "manual-${SystemClock.elapsedRealtime()}", verified) { error ->
                                            if (error != null) android.widget.Toast.makeText(activity, error, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                        fileBroker.handle(webView, ValueCallback(::deliver), fileChooserParams)
                                    }, onFailure = { error ->
                                        deliver(null)
                                        android.widget.Toast.makeText(activity, error.message ?: "附件读取失败", android.widget.Toast.LENGTH_LONG).show()
                                    })
                                }
                            }
                        }
                    }
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    if (destroyed || webViews[service] !== view) return
                    val active = automations[service]
                    // A successful first send may navigate within the provider. Uploads must stay in
                    // their original document; a send may continue its cursor verification on-site.
                    if (active != null && (!active.sending || !ArenaFileChooserBroker.trusted(service, url))) {
                        finishAutomation(service)
                        active.onInterrupted("${service.displayName} 网页已切换，当前发送已停止，请检查原网页后重试")
                    }
                    fileBroker.navigated(view)
                    cancelManualChooser(view)
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
                    if (destroyed || webViews[service] !== view) return true
                    fileBroker.destroyed(view)
                    cancelManualChooser(view)
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
                    val pending = automations[service]?.onInterrupted
                    finishAutomation(service)
                    pending?.invoke("${service.displayName} 网页进程已退出，请重新加载后重试")
                    if (backgroundProbeService == service) {
                        backgroundProbeGeneration++
                        backgroundProbeService = null
                        refreshVisibility()
                        handler.post { drainBackgroundProbes() }
                    }
                    return true
                }
            }
        }

        updateWebViewInteraction(service, webView, webView.visibility == View.VISIBLE)
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

    private fun cancelManualChooser(view: WebView) {
        manualFileCallbacks.remove(view)?.onReceiveValue(null)
    }

    private fun probe(service: ArenaService) {
        val webView = webViews[service] ?: return
        if (webView.url.isNullOrBlank() || webView.url == "about:blank") return
        if (uiSelectedService != service || service in automations) {
            pendingBackgroundProbes += service
            drainBackgroundProbes()
            return
        }
        evaluateLoginState(service)
    }

    private fun drainBackgroundProbes() {
        if (destroyed || backgroundProbeService != null || automations.isNotEmpty() || uiSelectedService != null) return
        val service = pendingBackgroundProbes.firstOrNull() ?: return
        pendingBackgroundProbes.remove(service)
        val webView = webViews[service] ?: return
        if (webView.url.isNullOrBlank() || webView.url == "about:blank") return
        backgroundProbeService = service
        val generation = ++backgroundProbeGeneration
        refreshVisibility()
        webView.onResume()
        handler.postDelayed({
            if (destroyed || generation != backgroundProbeGeneration) return@postDelayed
            evaluateLoginState(service) {
                if (generation == backgroundProbeGeneration) {
                    backgroundProbeService = null
                    refreshVisibility()
                    handler.post { drainBackgroundProbes() }
                }
            }
        }, 650L)
    }

    private fun evaluateLoginState(service: ArenaService, onComplete: () -> Unit = {}) {
        val webView = webViews[service]
        if (webView == null) {
            onComplete()
            return
        }
        // 与自动化链同理：探针的 JS 回调也会随渲染进程一起消失。
        // 探针超时只结束自身；发送任务不再等待探针，也不借用它的标识。
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
            val guest = result == "guest"
            val decision = LoginTrustPolicy.afterProbe(
                probeSignedIn = result == "signed_in" || guest,
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
                    ConnectionState.SIGNED_IN -> if (guest) "未登录也可提问" else "网页可用"
                    ConnectionState.NEEDS_LOGIN -> "需要在网页中登录"
                    else -> "后台检查中，打开网页可确认"
                },
                url = webView.url.orEmpty(),
                // 模式小字由下面的探针单独更新；这里先沿用上一次的，免得每次登录探测都闪成"未知"
                modeReading = statuses[service]?.modeReading ?: AiModeReading(),
                guest = guest && state == ConnectionState.SIGNED_IN,
            )
            if (state == ConnectionState.SIGNED_IN) evaluateMode(service, webView, onComplete) else onComplete()
        }
    }

    /**
     * 读一次网页上的"当前模型 / 思考模式"（只读，不点任何开关），写进状态栏小字。
     * 各站页面改版这些文字就会变，所以它是锦上添花：读不到不影响提问，也绝不猜。
     */
    private fun evaluateMode(service: ArenaService, webView: WebView, onComplete: () -> Unit) {
        var consumed = false
        val timeout = Runnable {
            if (consumed) return@Runnable
            consumed = true
            onComplete()
        }
        handler.postDelayed(timeout, MODE_PROBE_TIMEOUT_MS)
        webView.evaluateJavascript(ArenaWebModeScript.build(service)) { raw ->
            if (consumed) return@evaluateJavascript
            consumed = true
            handler.removeCallbacks(timeout)
            if (!destroyed && webViews[service] === webView) {
                val reading = try {
                    AiModePolicy.parse(JSONObject(decodeJsValue(raw)))
                } catch (_: Exception) {
                    AiModeReading()
                }
                statuses[service] = (statuses[service] ?: ServiceStatus()).copy(modeReading = reading)
            }
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
            ArenaService.CLAUDE, ArenaService.CHATGPT, ArenaService.GEMINI -> promptInputSelectors(service)
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
              const loginPattern = /^(登录(?:\s*[\/\|·]\s*注册)?|微信登录|抖音登录|手机号登录|扫码登录|sign in|log in|log in to sync chat history|phone number login|continue with email|continue with google)$/i;
              const hasVisibleLogin = Array.from(document.querySelectorAll('button,a,[role=button],[class~=button],[class*=login]')).some(function(el) {
                try {
                  const rect = el.getBoundingClientRect();
                  const style = window.getComputedStyle(el);
                  const visible = rect.width > 1 && rect.height > 1 && style.display !== 'none' && style.visibility !== 'hidden';
                  return visible && loginPattern.test((el.innerText || el.textContent || '').trim());
                } catch (_) { return false; }
              });
              // 境外站点未登录时会跳到 /login、/auth 或停在 Cloudflare 人机验证页（2026-09-15 实测 Claude 先闪出 /new 输入框再跳走）。
              // 这些页面不能沿用"之前确认过已登录"的结论，按明确的登录页处理。
              const verificationWall = /^just a moment/i.test(document.title || '') || !!document.querySelector('input[name=cf-turnstile-response], #challenge-form');
              if (${service.overseas} && (verificationWall || /^\/(login|auth|log-in)(\/|$)/i.test(location.pathname))) return 'explicit_login';
              if (${service.guestUsable} && hasInput) return hasVisibleLogin ? 'guest' : 'signed_in';
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
              ${sendControlHelperScript()}
              if ($conversationAdvanced) return 'already_sent';
              if (window.__aiArenaCancelledRequests && window.__aiArenaCancelledRequests[requestId]) return 'cancelled';
              if (window.__aiArenaNativeSendRequests?.[requestId]) return 'native_managed';
              if (window.__aiArenaSendClicks && window.__aiArenaSendClicks[requestId]) return 'awaiting_confirmation';
              const input = arenaFirstMatch($inputSelectors);
              const inputText = input ? (input.value || input.innerText || input.textContent || '') : '';
              if (!inputText.trim()) return 'already_sent_or_missing';
              const send = arenaFirstMatch($sendSelectors);
              if (!arenaSendEnabled(send)) return 'not_ready';
              window.__aiArenaSendClicks = window.__aiArenaSendClicks || {};
              window.__aiArenaSendClicks[requestId] = Date.now();
              send.click();
              return 'clicked';
            })();
        """.trimIndent()
    }

    private fun sendScript(service: ArenaService, quotedPrompt: String, requestId: String): String = sendScript(service, quotedPrompt, requestId, scheduleSubmit = true)

    private fun sendScript(service: ArenaService, quotedPrompt: String, requestId: String, scheduleSubmit: Boolean, browserInputOnly: Boolean = false): String {
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
        val scheduleClicks = if (service == ArenaService.ZHIPU || !scheduleSubmit) {
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
                const cancelled = () => !!(window.__aiArenaCancelledRequests && window.__aiArenaCancelledRequests[requestId]);
                if (cancelled()) return 'cancelled';
                $selectorHelper
                ${sendControlHelperScript()}
                $qwenFetchHook
                $zhipuMessageDispatch
                const input = arenaFirstMatch($inputSelectors);
                if (!input) return 'no_input';
                $focusInput
                let needsSyntheticInput = true;
                if ($browserInputOnly) {
                  // Doubao attachments must pass through the browser's editing/input path.
                  // Do not substitute its private setter or a synthetic input event here.
                  document.execCommand('selectAll', false, null);
                  if (!document.execCommand('insertText', false, text)) return '豆包正文浏览器输入失败，未发送问题';
                  needsSyntheticInput = false;
                } else if (input.editor && input.editor.commands && typeof input.editor.commands.setContent === 'function') {
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
                    if (cancelled()) return;
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
                      if (cancelled()) return;
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
                  if (cancelled()) return;
                  if (window.__aiArenaNativeSendRequests?.[requestId]) return;
                  if ($conversationAdvanced) return;
                  if (window.__aiArenaSendClicks && window.__aiArenaSendClicks[requestId]) return;
                  if (!currentInputText().trim()) return;
                  const send = arenaFirstMatch($sendSelectors);
                  window.__aiArenaSendClicks = window.__aiArenaSendClicks || {};
                  if (send) {
                    if (!arenaSendEnabled(send)) return;
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

    /** Div-based send controls use CSS/data/ARIA disabling; a disabled control must not fall back to Enter. */
    private fun sendControlHelperScript(): String = """
        const arenaSendEnabled = function(send) {
          if (!send) return false;
          const rect=send.getBoundingClientRect(),style=getComputedStyle(send);
          if(rect.width<=0||rect.height<=0||style.display==='none'||style.visibility==='hidden'||style.pointerEvents==='none')return false;
          for(let node=send;node&&node!==document.body;node=node.parentElement){
            if(node.disabled||node.hasAttribute('disabled')||node.getAttribute('aria-disabled')==='true'||['','true'].includes(node.getAttribute('data-disabled'))||node.classList.contains('disabled'))return false;
            // CSS-module disabled states such as Yuanbao's SendButton_disabled__hash.
            if(Array.from(node.classList).some(name=>/(^|_)disabled(_|${'$'})/i.test(name)))return false;
          }
          return true;
        };
    """.trimIndent()

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
        ArenaService.CLAUDE -> listOf("div.ProseMirror[contenteditable='true']", "[contenteditable='true']", "textarea")
        // 2026-09 的 ChatGPT 手机网页：输入框是无 id 的普通 textarea（旧版为 #prompt-textarea 富文本）
        ArenaService.CHATGPT -> listOf("#prompt-textarea", "form textarea", "textarea")
        ArenaService.GEMINI -> listOf("rich-textarea .ql-editor[contenteditable='true']", ".ql-editor[contenteditable='true']", "[contenteditable='true']", "textarea")
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
                ".chat-editor .send-button-container",
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
                // 2026-09-15: the send control is a div; none of the button selectors matched any more.
                "#yuanbao-send-btn",
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
            ArenaService.CLAUDE -> listOf(
                "button[aria-label='Send message']",
                "button[aria-label*='Send']",
                "button[aria-label*='发送']",
            )
            ArenaService.CHATGPT -> listOf(
                "button[aria-label='Send message']",
                "#composer-submit-button",
                "button[data-testid='send-button']",
                "button[aria-label*='Send']",
                "button[aria-label*='发送']",
            )
            ArenaService.GEMINI -> listOf(
                "button.send-button",
                "button[aria-label='Send message']",
                "button[aria-label*='Send']",
                "button[aria-label*='发送']",
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
        private const val KIMI_UPGRADE_DETAIL = "Kimi 网页提示当前模型或功能需要会员，问题没有发出；请打开原网页换用可用模型后重试"
        private const val KIMI_UPGRADE_MODAL_SCRIPT = "(function(){return Array.from(document.querySelectorAll('.modal-mask')).some(function(mask){var r=mask.getBoundingClientRect();return r.width>2&&r.height>2&&/Upgrade your membership|higher-tier members|members only|升级会员|开通会员|会员专享|仅.{0,8}会员/i.test(String(mask.innerText||''));});})()"

        /** 整条自动化链（等输入框 + 注入 + 校验）的硬上限，超过即认定回调已丢失。 */
        private const val AUTOMATION_HARD_TIMEOUT_MS = 45_000L
        private const val FOCUS_ACTION_TIMEOUT_MS = 12_000L
        private const val LOGIN_PROBE_TIMEOUT_MS = 8_000L
        private const val MODE_PROBE_TIMEOUT_MS = 4_000L
        /** 开新对话 / 切历史对话的整页加载上限；超时向调用方报告失败。 */
        private const val NAVIGATION_TIMEOUT_MS = 20_000L
        /** onPageFinished 之后再等一会儿，让单页应用把输入框画出来。 */
        private const val NAVIGATION_SETTLE_MS = 900L
    }
}
