package com.tianlin.aiarena

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArenaWebResponseScriptInstrumentedTest {
    @Test
    fun deepSeekExtractionIgnoresImageNodes() {
        val payload = evaluate(
            ArenaService.DEEPSEEK,
            "photo_deepseek",
            """
                <div class="ds-virtual-list-visible-items">
                  <div class="user">用户问题<img alt="照片隐私文字" /></div>
                  <div class="assistant"><div class="ds-markdown"><p>DeepSeek 最终文本</p><img alt="不应进入结果" /></div></div>
                </div>
            """.trimIndent(),
        )

        assertTrue(payload.getBoolean("found"))
        assertFalse(payload.getBoolean("streaming"))
        assertEquals("DeepSeek 最终文本", payload.getString("text"))
    }

    @Test
    fun doubaoExtractionFindsAnswerAfterPhotoUserRow() {
        val payload = evaluate(
            ArenaService.DOUBAO,
            "photo_doubao",
            """
                <div class="v_list_row" data-observe-row>
                  <div class="bg-g-send">用户问题</div><img alt="用户照片" />
                </div>
                <div class="v_list_row" data-observe-row>
                  <div class="md-box-root">豆包最终文本</div><div class="message-action-bar"></div>
                </div>
            """.trimIndent(),
        )

        assertTrue(payload.getBoolean("found"))
        assertFalse(payload.getBoolean("streaming"))
        assertEquals("豆包最终文本", payload.getString("text"))
        assertFalse(payload.getBoolean("requestIdVisible"))
        assertTrue(payload.getBoolean("localTagBound"))
    }

    @Test
    fun kimiExtractionExcludesThinkingAndImageAltText() {
        val payload = evaluate(
            ArenaService.KIMI,
            "photo_kimi",
            """
                <div class="chat-content-item-user">用户问题<img alt="用户图片" /></div>
                <div class="chat-content-item-assistant">
                  <div class="toolcall"><div class="markdown-container toolcall-content-text">内部思考过程</div></div>
                  <div class="segment-content"><div class="markdown-container">Kimi 最终文本</div></div>
                  <div class="segment-assistant-actions"></div>
                </div>
            """.trimIndent(),
        )

        assertTrue(payload.getBoolean("found"))
        assertFalse(payload.getBoolean("streaming"))
        assertEquals("Kimi 最终文本", payload.getString("text"))
        assertFalse(payload.getString("text").contains("内部思考"))
    }

    @Test
    fun longAnswerIsExplicitlyTruncatedBeforeCrossingWebViewBoundary() {
        val longText = "长".repeat(ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS + 73)
        val payload = evaluate(
            ArenaService.DEEPSEEK,
            "long_answer",
            """
                <div class="ds-virtual-list-visible-items">
                  <div class="user">长回答问题</div>
                  <div class="assistant"><div class="ds-markdown">$longText</div></div>
                </div>
            """.trimIndent(),
        )

        assertTrue(payload.getBoolean("found"))
        assertTrue(payload.getBoolean("truncated"))
        assertEquals(ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS, payload.getString("text").length)
        assertEquals(ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS + 73, payload.getInt("originalLength"))
    }

    @Test
    fun qwenExperimentalAdapterExtractsLatestMarkdown() {
        val payload = evaluate(
            ArenaService.QWEN,
            "qwen_cursor",
            """
                <div class="user-row"><div class="content">用户问题</div></div>
                <div class="assistant-row"><div class="qk-markdown">千问最终文本</div></div>
            """.trimIndent(),
        )

        assertTrue(payload.getBoolean("found"))
        assertEquals("千问最终文本", payload.getString("text"))
        assertFalse(payload.getBoolean("requestIdVisible"))
    }

    @Test
    fun yuanbaoExperimentalAdapterExtractsHycMarkdown() {
        val payload = evaluate(
            ArenaService.YUANBAO,
            "yuanbao_cursor",
            """
                <div class="user-row"><div class="content">用户问题</div></div>
                <div class="assistant-row"><div class="hyc-content-md">元宝最终文本</div></div>
            """.trimIndent(),
        )

        assertTrue(payload.getBoolean("found"))
        assertEquals("元宝最终文本", payload.getString("text"))
        assertFalse(payload.getBoolean("requestIdVisible"))
    }

    @Test
    fun zhipuExperimentalAdapterExtractsGenericAssistantMarkdown() {
        val payload = evaluate(
            ArenaService.ZHIPU,
            "zhipu_cursor",
            """
                <div class="user-message"><div class="content">用户问题</div></div>
                <div class="assistant-message"><div class="markdown-body">智谱最终文本</div></div>
            """.trimIndent(),
        )

        assertTrue(payload.getBoolean("found"))
        assertEquals("智谱最终文本", payload.getString("text"))
        assertFalse(payload.getBoolean("requestIdVisible"))
    }

    @Test
    fun qwenAdapterIncludesNetworkCaptureFallback() {
        val script = ArenaWebResponseScript.build(ArenaService.QWEN, "qwen_network_cursor")

        assertTrue(script.contains("__aiArenaQwenResponses"))
        assertTrue(script.contains("__ai_arena_qwen_response_"))
    }

    @Test
    fun experimentalAdaptersNeverFallBackToPreviousAssistantAnswer() {
        listOf(ArenaService.QWEN, ArenaService.YUANBAO, ArenaService.ZHIPU).forEach { service ->
            val script = ArenaWebResponseScript.build(service, "fresh_answer_cursor")
            assertFalse(script.contains("tagged ? candidates.slice(-1)"))
        }
    }

    @Test
    fun qwenSecurityChallengeReturnsActionableError() {
        val payload = evaluate(
            ArenaService.QWEN,
            "qwen_captcha_cursor",
            """
                <div class="question">测试问题</div>
                <iframe src="about:blank?path=punish&amp;action=captcha"></iframe>
            """.trimIndent(),
        )

        assertFalse(payload.getBoolean("found"))
        assertTrue(payload.getBoolean("securityChallenge"))
        assertTrue(payload.getString("error").contains("安全验证"))
    }

    @Test
    fun qwenDomAnswerWinsOverStaleSecurityChallengeFrame() {
        val payload = evaluate(
            ArenaService.QWEN,
            "qwen_dom_after_challenge",
            """
                <div class="user-row"><div class="content">观点讨论</div></div>
                <div class="assistant-row"><div class="qk-markdown">千问已经正确输出的回答</div></div>
                <iframe src="about:blank?path=punish&amp;action=captcha"></iframe>
            """.trimIndent(),
        )

        assertTrue(payload.getBoolean("found"))
        assertTrue(payload.getBoolean("securityChallenge"))
        assertEquals("千问已经正确输出的回答", payload.getString("text"))
        assertEquals("", payload.optString("error", ""))
    }

    @Test
    fun qwenCompletedNetworkAnswerWinsOverStaleSecurityChallengeFrame() {
        val payload = evaluate(
            ArenaService.QWEN,
            "qwen_network_after_challenge",
            """
                <script>
                  window.__aiArenaQwenResponses = {
                    qwen_network_after_challenge: {
                      done: true,
                      answer: '千问请求专属网络回答'
                    }
                  };
                </script>
                <iframe src="about:blank?path=punish&amp;action=captcha"></iframe>
            """.trimIndent(),
        )

        assertTrue(payload.getBoolean("found"))
        assertTrue(payload.getBoolean("securityChallenge"))
        assertEquals("千问请求专属网络回答", payload.getString("text"))
        assertFalse(payload.getBoolean("streaming"))
    }

    private fun evaluate(service: ArenaService, requestId: String, bodyHtml: String): JSONObject {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = AtomicReference<JSONObject>()
        val failure = AtomicReference<Throwable>()
        val latch = CountDownLatch(1)
        val webViewRef = AtomicReference<WebView>()

        instrumentation.runOnMainSync {
            try {
                val webView = WebView(ApplicationProvider.getApplicationContext())
                webViewRef.set(webView)
                webView.settings.javaScriptEnabled = true
                webView.webViewClient = object : WebViewClient() {
                    private var evaluated = false

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (evaluated) return
                        evaluated = true
                        view.evaluateJavascript(ArenaWebCursorScript.prepare(service, requestId)) {
                            val simulateAnswerArrivingAfterBaseline = """
                                (function() {
                                  const state = window.__aiArenaRequests && window.__aiArenaRequests[${JSONObject.quote(requestId)}];
                                  if (state) state.assistantBaseline = 0;
                                  return true;
                                })();
                            """.trimIndent()
                            view.evaluateJavascript(simulateAnswerArrivingAfterBaseline) {
                            view.evaluateJavascript(ArenaWebCursorScript.bind(service, requestId)) {
                                view.evaluateJavascript(ArenaWebResponseScript.build(service, requestId)) { raw ->
                                    try {
                                        val decoded = JSONTokener(raw).nextValue() as String
                                        val payload = JSONObject(decoded)
                                        val metadataScript = """
                                            (function() {
                                              const requestId = ${JSONObject.quote(requestId)};
                                              const visible = (document.body.innerText || '').includes(requestId);
                                              const localTagBound = Array.from(document.querySelectorAll('[data-ai-arena-request]')).some(function(row) {
                                                return row.getAttribute('data-ai-arena-request') === requestId;
                                              });
                                              return JSON.stringify({ visible, localTagBound });
                                            })();
                                        """.trimIndent()
                                        view.evaluateJavascript(metadataScript) { metadataRaw ->
                                            try {
                                                val metadataDecoded = JSONTokener(metadataRaw).nextValue() as String
                                                val metadata = JSONObject(metadataDecoded)
                                                payload.put("requestIdVisible", metadata.getBoolean("visible"))
                                                payload.put("localTagBound", metadata.getBoolean("localTagBound"))
                                                result.set(payload)
                                            } catch (error: Throwable) {
                                                failure.set(error)
                                            } finally {
                                                latch.countDown()
                                            }
                                        }
                                    } catch (error: Throwable) {
                                        failure.set(error)
                                        latch.countDown()
                                    }
                                }
                            }
                            }
                        }
                    }
                }
                webView.loadDataWithBaseURL(
                    "https://arena.test/",
                    "<html><body>$bodyHtml</body></html>",
                    "text/html",
                    "UTF-8",
                    null,
                )
            } catch (error: Throwable) {
                failure.set(error)
                latch.countDown()
            }
        }

        assertTrue("WebView evaluation timed out", latch.await(20, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { webViewRef.get()?.destroy() }
        failure.get()?.let { throw AssertionError("WebView evaluation failed", it) }
        return result.get() ?: throw AssertionError("No WebView result")
    }
}
