package com.tianlin.aiarena

import android.os.Handler
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

internal class ArenaAttachmentTransport(private val handler: Handler, private val fileBroker: ArenaFileChooserBroker) {
    fun upload(
        webView: WebView,
        service: ArenaService,
        requestId: String,
        files: List<Pair<ArenaAttachment, File>>,
        isCurrent: () -> Boolean,
        callback: (String?) -> Unit,
    ) {
        val originalUrl = webView.url
        val generation = fileBroker.generation(webView)
        val deadline = SystemClock.elapsedRealtime() + 120_000L
        var delivered = false
        var settled = false
        fun finish(error: String?) {
            if (settled) return
            settled = true
            callback(error)
        }
        fun current() = isCurrent() && !settled
        if (!ArenaFileChooserBroker.trusted(service, originalUrl)) return finish("网页已离开 ${service.displayName} 官网，未上传附件")
        fileBroker.prepare(webView, service, requestId, files) { error ->
            if (current()) {
                if (error != null) finish(error) else delivered = true
            }
        }
        fun poll() {
            if (!current()) return
            if (fileBroker.generation(webView) != generation || webView.url != originalUrl || !ArenaFileChooserBroker.trusted(service, webView.url)) {
                return finish("网页已切换，附件上传已取消，请重新发送")
            }
            if (SystemClock.elapsedRealtime() >= deadline) return finish(
                if (delivered) "${service.displayName} 附件上传或解析未确认完成，未发送问题；请到原网页查看后重试"
                else "${service.displayName} 未提供可用的附件上传入口，未发送问题；请登录并检查网页是否支持附件",
            )
            val script = if (delivered) ArenaAttachmentScript.readiness(requestId, service) else ArenaAttachmentScript.nextControl(requestId, service)
            webView.evaluateJavascript(script) { raw ->
                if (!current()) return@evaluateJavascript
                val result = try { JSONObject(decode(raw)) } catch (_: Exception) { return@evaluateJavascript finish("网页附件状态无法读取，未发送问题") }
                if (result.has("error")) return@evaluateJavascript finish(result.optString("error"))
                if (delivered && result.optBoolean("ready")) return@evaluateJavascript finish(null)
                if (!delivered && result.has("x")) {
                    val ratio = webView.width / result.optDouble("width", 1.0)
                    val x = (result.getDouble("x") * ratio).toFloat()
                    val y = (result.getDouble("y") * ratio).toFloat()
                    if (x < 0 || y < 0 || x > webView.width || y > webView.height) return@evaluateJavascript finish("附件入口超出网页显示区域，请打开原网页重试")
                    val downAt = SystemClock.uptimeMillis()
                    MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0).let { event -> webView.dispatchTouchEvent(event); event.recycle() }
                    handler.postDelayed({
                        if (!current()) return@postDelayed
                        MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).let { event -> webView.dispatchTouchEvent(event); event.recycle() }
                    }, 100L)
                }
                handler.postDelayed({ poll() }, 650L)
            }
        }
        webView.evaluateJavascript(ArenaAttachmentScript.prepare(requestId, files.map { it.first }, service)) { raw ->
            if (current()) {
                if (raw == "true") poll()
                else {
                    val error = try { JSONObject(decode(raw)).optString("error") } catch (_: Exception) { "附件上传准备失败" }
                    finish(error.ifBlank { "附件上传准备失败" })
                }
            }
        }
    }

    private fun decode(raw: String): String = when(val value = JSONTokener(raw).nextValue()) {
        is String -> value
        else -> value.toString()
    }
}
