package com.tianlin.aiarena

import android.content.Context
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import java.io.File
import java.util.UUID

/** Every callback is scoped to an exact WebView, document generation and send request. */
internal class ArenaFileChooserBroker(private val context: Context) {
    private data class Pending(
        val service: ArenaService, val requestId: String, val generation: Long, val url: String,
        val files: List<Pair<ArenaAttachment, File>>, val leaseOwner: String = UUID.randomUUID().toString(),
        val delivered: (String?) -> Unit, var consumed: Boolean = false,
    )
    private val generations = mutableMapOf<WebView, Long>()
    private val requests = mutableMapOf<WebView, Pending>()

    fun prepare(view: WebView, service: ArenaService, requestId: String, files: List<Pair<ArenaAttachment, File>>, delivered: (String?) -> Unit) {
        cancel(view)
        require(trusted(service, view.url)) { "网页已离开 ${service.displayName} 官网，未交付附件" }
        require(files.isNotEmpty())
        requests[view] = Pending(service, requestId, generation(view), view.url.orEmpty(), files, delivered = delivered)
    }

    fun handle(view: WebView, callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        val request = requests[view] ?: return false
        val error = when {
            request.consumed -> "网页重复请求附件，已阻止重复上传"
            request.generation != generation(view) || request.url != view.url || !trusted(request.service, view.url) -> "网页已切换，已取消附件上传"
            params.mode != WebChromeClient.FileChooserParams.MODE_OPEN && params.mode != WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE -> "网页文件选择模式暂不支持"
            params.mode == WebChromeClient.FileChooserParams.MODE_OPEN && request.files.size > 1 -> "${request.service.displayName} 当前入口一次只接受一个文件，请减少附件后重试"
            request.files.any { !ArenaAttachmentPolicy.accepts(it.first, params.acceptTypes.toList()) } -> "${request.service.displayName} 当前入口不支持所选附件类型，请换文件或成员"
            else -> null
        }
        request.consumed = true
        if (error != null) {
            callback.onReceiveValue(null)
            request.delivered(error)
        } else {
            val uris = request.files.map { (attachment, file) -> ArenaAttachmentLeases.issue(context, request.leaseOwner, attachment, file) }.toTypedArray()
            callback.onReceiveValue(uris)
            request.delivered(null)
        }
        return true
    }

    fun generation(view: WebView): Long = generations[view] ?: 0L
    fun navigated(view: WebView) { generations[view] = generation(view) + 1; cancel(view) }
    fun cancel(view: WebView) { requests.remove(view)?.let { ArenaAttachmentLeases.revoke(it.leaseOwner) } }
    fun cancel(view: WebView, requestId: String) {
        if (requests[view]?.requestId == requestId) cancel(view)
    }
    fun cancelAll() { requests.keys.toList().forEach(::cancel) }
    fun destroyed(view: WebView) { navigated(view); generations.remove(view) }

    companion object {
        fun trusted(service: ArenaService, url: String?): Boolean {
            val uri = try { Uri.parse(url.orEmpty()) } catch (_: Exception) { return false }
            if (uri.scheme != "https" || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return false
            val host = uri.host?.lowercase() ?: return false
            return when (service) {
                ArenaService.DEEPSEEK -> host == "chat.deepseek.com"
                ArenaService.DOUBAO -> host == "www.doubao.com" || host == "doubao.com"
                ArenaService.KIMI -> host == "www.kimi.com" || host == "kimi.com" || host == "kimi.moonshot.cn"
                ArenaService.QWEN -> host == "www.qianwen.com" || host == "qianwen.com"
                ArenaService.YUANBAO -> host == "yuanbao.tencent.com"
                ArenaService.ZHIPU -> host == "chatglm.cn" || host == "www.chatglm.cn"
            }
        }
    }
}
