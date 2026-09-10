package com.tianlin.aiarena

import android.os.Handler
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

internal class ArenaAttachmentTransport(
    private val handler: Handler,
    private val fileBroker: ArenaFileChooserBroker,
    private val withFocus: (((() -> Unit) -> Unit) -> Unit) = { action -> action {} },
) {
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
        var geometryFailures = 0
        var geometryDeadline = 0L
        fun finish(error: String?) {
            if (settled) return
            settled = true
            callback(error)
        }
        fun current() = isCurrent() && !settled
        fun sameDocument() = fileBroker.generation(webView) == generation && webView.url == originalUrl && ArenaFileChooserBroker.trusted(service, webView.url)
        val geometryError = "${service.displayName} 附件入口布局持续变化，未发送问题；请打开原网页检查后重试"
        if (!ArenaFileChooserBroker.trusted(service, originalUrl)) return finish("网页已离开 ${service.displayName} 官网，未上传附件")
        fileBroker.prepare(webView, service, requestId, files) { error ->
            if (current()) {
                if (error != null) finish(error) else delivered = true
            }
        }
        fun poll() {
            if (!current()) return
            if (!sameDocument()) {
                return finish("网页已切换，附件上传已取消，请重新发送")
            }
            if (!delivered && geometryDeadline > 0L && SystemClock.elapsedRealtime() >= geometryDeadline) return finish(geometryError)
            if (SystemClock.elapsedRealtime() >= deadline) return finish(
                if (delivered) "${service.displayName} 附件上传或解析未确认完成，未发送问题；请到原网页查看后重试"
                else "${service.displayName} 未提供可用的附件上传入口，未发送问题；请打开原网页检查上传入口后重试",
            )
            fun inspect(release: () -> Unit) {
                if (!current()) { release(); return }
                if (!delivered && geometryDeadline > 0L && SystemClock.elapsedRealtime() >= geometryDeadline) { release(); return finish(geometryError) }
                val script = if (delivered) ArenaAttachmentScript.readiness(requestId, service) else ArenaAttachmentScript.nextControl(requestId, service)
                webView.evaluateJavascript(script) { raw ->
                    if (!current()) { release(); return@evaluateJavascript }
                    val result = try { JSONObject(decode(raw)) } catch (_: Exception) { release(); return@evaluateJavascript finish("网页附件状态无法读取，未发送问题") }
                    if (result.has("error")) { release(); return@evaluateJavascript finish(result.optString("error")) }
                    if (delivered && result.optBoolean("ready")) { release(); return@evaluateJavascript finish(null) }
                    if (!delivered && result.has("x") && fileBroker.canDeliver(webView, requestId)) {
                        val cssWidth = result.optDouble("width", Double.NaN)
                        val cssHeight = result.optDouble("height", Double.NaN)
                        val nativeWidth = webView.width
                        val nativeHeight = webView.height
                        val ratio = nativeWidth / cssWidth
                        val x = (result.optDouble("x", Double.NaN) * ratio).toFloat()
                        val y = (result.optDouble("y", Double.NaN) * ratio).toFloat()
                        if (!cssWidth.isFinite() || !cssHeight.isFinite() || cssWidth <= 0 || cssHeight <= 0 || !ratio.isFinite() || !x.isFinite() || !y.isFinite() || nativeWidth <= 0 || nativeHeight <= 0 || x < 0 || y < 0 || x > nativeWidth || y > nativeHeight) {
                            if (BuildConfig.DEBUG) android.util.Log.i("ArenaAttachmentGeometry", "nativeWidth=$nativeWidth nativeHeight=$nativeHeight cssWidth=$cssWidth cssHeight=$cssHeight x=$x y=$y scale=$ratio")
                            if (geometryDeadline == 0L) geometryDeadline = SystemClock.elapsedRealtime() + 4_000L
                            geometryFailures++
                            if (geometryFailures >= 3 || SystemClock.elapsedRealtime() >= geometryDeadline) { release(); return@evaluateJavascript finish(geometryError) }
                            val controlId = result.optLong("controlId", 0L)
                            if (controlId <= 0L || !sameDocument() || !fileBroker.canDeliver(webView, requestId)) { release(); return@evaluateJavascript finish("附件入口状态已变化，未发送问题") }
                            // No DOWN has occurred. Return only this reservation, then remeasure and hit-test.
                            webView.evaluateJavascript(ArenaAttachmentScript.releaseUnsentControl(requestId, controlId)) restore@{ returned ->
                                release()
                                if (!current()) return@restore
                                if (!sameDocument()) return@restore finish("网页已切换，附件上传已取消，请重新发送")
                                if (delivered) {
                                    geometryFailures = 0
                                    geometryDeadline = 0L
                                    handler.post { poll() }
                                    return@restore
                                }
                                if (returned != "true") return@restore finish("附件入口状态已变化，未发送问题")
                                handler.postDelayed({ poll() }, 250L)
                            }
                            return@evaluateJavascript
                        }
                        geometryFailures = 0
                        geometryDeadline = 0L
                        val controlId = result.optLong("controlId", 0L)
                        if (controlId <= 0L) { release(); return@evaluateJavascript finish("附件入口状态已变化，未发送问题") }
                        val downAt = SystemClock.uptimeMillis()
                        MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0).let { event -> webView.dispatchTouchEvent(event); event.recycle() }
                        handler.postDelayed({
                            // A cancelled lease may already have been replaced on this same WebView.
                            // Never deliver an old UP/CANCEL to the newer gesture (or a destroyed view).
                            if (!current()) { release(); return@postDelayed }
                            // Some controls request the chooser on DOWN. Do not activate them again on UP.
                            val action = if (delivered || !fileBroker.canDeliver(webView, requestId)) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                            MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0).let { event -> webView.dispatchTouchEvent(event); event.recycle() }
                            if (action == MotionEvent.ACTION_CANCEL) {
                                release()
                                handler.postDelayed({ poll() }, 650L)
                                return@postDelayed
                            }
                            // Native UP is queued to Chromium; its click/default file activation can happen later.
                            // Hold only this interaction, never file change, network upload, parsing or the answer.
                            var clickSettled = false
                            lateinit var clickTimeout: Runnable
                            fun settleClick(error: String? = null) {
                                if (clickSettled) return
                                clickSettled = true
                                handler.removeCallbacks(clickTimeout)
                                release()
                                if (!current()) return
                                if (error != null) finish(error) else handler.postDelayed({ poll() }, 650L)
                            }
                            clickTimeout = Runnable {
                                if (!current()) settleClick()
                                else if (delivered || result.optBoolean("retryableTap")) settleClick()
                                else settleClick("${service.displayName} 附件入口点击未确认，未发送问题；请打开原网页检查后重试")
                            }
                            handler.postDelayed(clickTimeout, 1_500L)
                            fun awaitBrowserClick() {
                                if (clickSettled) return
                                if (!current()) return settleClick()
                                if (!sameDocument()) return settleClick("网页已切换，附件上传已取消，请重新发送")
                                if (delivered) return settleClick()
                                webView.evaluateJavascript(ArenaAttachmentScript.clickCompleted(requestId, controlId)) { completed ->
                                    if (clickSettled) return@evaluateJavascript
                                    if (!current()) return@evaluateJavascript settleClick()
                                    if (!sameDocument()) return@evaluateJavascript settleClick("网页已切换，附件上传已取消，请重新发送")
                                    if (delivered || completed == "true") settleClick()
                                    else handler.postDelayed({ awaitBrowserClick() }, 50L)
                                }
                            }
                            handler.postDelayed({ awaitBrowserClick() }, 25L)
                        }, 100L)
                        return@evaluateJavascript
                    } else release()
                    handler.postDelayed({ poll() }, 650L)
                }
            }
            if (delivered) inspect {} else withFocus(::inspect)
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
