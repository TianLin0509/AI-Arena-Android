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
                  <div class="assistant"><div class="ds-markdown"><p>DeepSeek 最终文本</p><img alt="不应进入结果" /></div><div style="height:32px"><button class="ds-button--icon">复制</button></div></div>
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
                  <div class="md-box-root">豆包最终文本</div><div class="message-action-bar" style="height:32px"><button>复制</button></div>
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
    fun kimiCodeHeaderWithoutBodyIsStillStreamingEvenWhenActionsShow() {
        // 2026-09-23 real page: actions bar visible while the highlighter had rendered only "Python / Copy".
        val code = { body: String -> """
            <div class="chat-content-item-user">用户问题</div>
            <div class="chat-content-item-assistant">
              <div class="segment-content"><div class="markdown-container"><div class="markdown"><div class="segment-code">
                <div class="sticky-release"><div class="sticky-release-rail"><div class="sticky-release-header"><header class="segment-code-header"><span class="segment-code-lang">Python</span><span><div class="icon-button">Copy</div></span></header></div></div></div>
                $body
              </div></div></div></div>
              <div class="segment-assistant-actions" style="height:32px"><button>复制</button></div>
            </div>
        """.trimIndent() }
        val pending = evaluate(ArenaService.KIMI, "kimi_code_pending", code(""))
        assertTrue("A code block without its body is not a finished answer", pending.getBoolean("streaming"))
        val done = evaluate(ArenaService.KIMI, "kimi_code_done",
            code("<div class='syntax-highlighter segment-code-content'><pre class='language-python'><code>def f(x):\n    return x + 1</code></pre></div>"))
        assertFalse(done.getBoolean("streaming"))
        assertEquals("```python\ndef f(x):\n    return x + 1\n```", done.getString("finalText"))
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
                  <div class="segment-assistant-actions" style="height:32px"><button>复制</button></div>
                </div>
            """.trimIndent(),
        )

        assertTrue(payload.getBoolean("found"))
        assertFalse(payload.getBoolean("streaming"))
        assertEquals("Kimi 最终文本", payload.getString("text"))
        assertFalse(payload.getString("text").contains("内部思考"))
    }

    @Test
    fun absentOrCollapsedCompletionBarsStillMeanStreaming() {
        val cases = mapOf(
            ArenaService.DEEPSEEK to """
                <div class="ds-virtual-list-visible-items">
                  <div class="user">用户问题</div>
                  <div class="assistant"><div class="ds-markdown">未结束的回答</div></div>
                </div>
            """.trimIndent(),
            ArenaService.DOUBAO to """
                <div class="v_list_row" data-observe-row><div class="bg-g-send">用户问题</div></div>
                <div class="v_list_row" data-observe-row><div class="md-box-root">未结束的回答</div>
                  <div class="message-action-bar" style="height:0;overflow:hidden"><button>复制</button></div>
                </div>
            """.trimIndent(),
            ArenaService.KIMI to """
                <div class="chat-content-item-user">用户问题</div>
                <div class="chat-content-item-assistant">
                  <div class="segment-content"><div class="markdown-container">未结束的回答</div></div>
                  <div class="segment-assistant-actions" style="height:32px"></div>
                </div>
            """.trimIndent(),
        )
        cases.forEach { (service, html) ->
            val payload = evaluate(service, "still_streaming_${service.name}", html)
            assertTrue("${service.name} must expose the partial answer", payload.getBoolean("found"))
            assertEquals("未结束的回答", payload.getString("text"))
            assertTrue("${service.name} has no visible completion controls", payload.getBoolean("streaming"))
        }
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

    /** 结构取自 2026-09-15 未登录 Gemini 真实页面（user-query / model-response / aria-busy / message-actions）。 */
    @Test
    fun geminiAdapterWaitsForBusyFlagThenExtractsMarkdown() {
        fun page(busy: Boolean) = """
            <div class="conversation-container">
              <user-query><div class="query-text"><p class="query-text-line">用户问题</p></div></user-query>
              <model-response><message-content><div class="markdown markdown-main-panel" aria-busy="$busy"><p>Gemini 最终文本</p></div></message-content>
                <message-actions style="display:block;width:160px;height:48px"><button>复制</button></message-actions></model-response>
            </div>
        """.trimIndent()

        val done = evaluate(ArenaService.GEMINI, "gemini_done", page(busy = false))
        assertTrue(done.getBoolean("found"))
        assertFalse(done.getBoolean("streaming"))
        assertEquals("Gemini 最终文本", done.getString("text"))
        assertTrue(done.getBoolean("localTagBound"))

        val busy = evaluate(ArenaService.GEMINI, "gemini_busy", page(busy = true))
        assertTrue("aria-busy=true 时即使操作栏已在也不能判完成", busy.getBoolean("streaming"))
    }

    /** Claude 结构按 data-is-streaming 约定的夹具；真实登录页面结构待账号登录后核对。 */
    @Test
    fun claudeAdapterUsesStreamingAttribute() {
        fun page(streaming: Boolean) = """
            <div data-testid="user-message">用户问题</div>
            <div data-is-streaming="$streaming"><div class="font-claude-response"><p>Claude 最终文本</p></div></div>
        """.trimIndent()

        val done = evaluate(ArenaService.CLAUDE, "claude_done", page(streaming = false))
        assertTrue(done.getBoolean("found"))
        assertFalse(done.getBoolean("streaming"))
        assertEquals("Claude 最终文本", done.getString("text"))
        assertTrue(done.getBoolean("localTagBound"))
        assertTrue(evaluate(ArenaService.CLAUDE, "claude_streaming", page(streaming = true)).getBoolean("streaming"))
    }

    /** ChatGPT 按 data-message-author-role 约定的夹具；真实登录页面结构待账号登录后核对。 */
    @Test
    fun chatGptAdapterWaitsForCopyActionAndIgnoresPreviousAnswer() {
        fun page(withCopy: Boolean) = """
            <article><div data-message-author-role="user">旧问题</div></article>
            <article><div data-message-author-role="assistant"><div class="markdown"><p>旧回答</p></div></div>
              <button data-testid="copy-turn-action-button" style="width:24px;height:24px">复制</button></article>
            <article><div data-message-author-role="user">用户问题</div></article>
            <article><div data-message-author-role="assistant"><div class="markdown"><p>ChatGPT 最终文本</p></div></div>
              ${if (withCopy) "<button data-testid=\"copy-turn-action-button\" style=\"width:24px;height:24px\">复制</button>" else ""}</article>
        """.trimIndent()

        val done = evaluate(ArenaService.CHATGPT, "chatgpt_done", page(withCopy = true))
        assertTrue(done.getBoolean("found"))
        assertFalse(done.getBoolean("streaming"))
        assertEquals("ChatGPT 最终文本", done.getString("text"))
        assertTrue(evaluate(ArenaService.CHATGPT, "chatgpt_streaming", page(withCopy = false)).getBoolean("streaming"))
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

    @Test
    fun doubaoMixedContainerPreservesInlineCodeAndTextContinuity() {
        val result = evaluateMarkdown("""<div class="container-fBOrXO"><div class="container-enLQFx">START<div></div>
            网页收到一条<code>{"status":"running"}</code>的消息。</div><div class="container-enLQFx">末段<code>输入不符合规范</code>是提示。<div></div>END</div></div>""")
        assertEquals("START\n\n网页收到一条`{\"status\":\"running\"}`的消息。\n\n末段`输入不符合规范`是提示。\n\nEND", result)
    }

    @Test
    fun mixedInlineRunsKeepFormattingAroundNestedBlocks() {
        val result = evaluateMarkdown("""<div>前<code>x</code><strong>粗体</strong><a href="https://example.test/">链接</a><br>后<ul><li>条目<code>y</code></li></ul>尾<em>强调</em><blockquote>引<code>z</code><div>子块</div>末</blockquote></div>""")
        assertEquals("前`x`**粗体**[链接](https://example.test/)\n后\n\n- 条目`y`\n\n尾*强调*\n\n> 引`z`\n>\n> 子块\n>\n> 末", result)
    }

    @Test
    fun codeBlockRetainsInternalBlankLinesSpacesAndBackticks() {
        val code = "def f():\n    text = \"\"\"a  \n\n\nb\"\"\"\n    return text\n# ```literal```"
        val result = evaluateMarkdown("<div>前<pre><code class='language-python'>$code</code></pre>后</div>")
        val block = ArenaMarkdown.parse(result).filterIsInstance<ArenaMdBlock.Code>().single()
        assertEquals("python", block.language)
        assertEquals(code, block.code)
        assertEquals("后", (ArenaMarkdown.parse(result).last() as ArenaMdBlock.Paragraph).text)
    }

    @Test
    fun inlineCodeRoundTripsLiteralBackticksAndEdgeSpaces() {
        assertEquals("数`42`和`7`", evaluateMarkdown("<div style='font-size:20px'>数<code style='font-size:12px'>42</code>和<span style='font-size:12px'><code>7</code></span></div>"))
        val result = evaluateMarkdown("<div>前<code>`x`</code><div></div>后<code> a  b </code></div>")
        assertEquals(listOf("`x`", " a  b "), ArenaMarkdown.parse(result).filterIsInstance<ArenaMdBlock.Paragraph>()
            .flatMap { ArenaMarkdown.inline(it.text) }.filter { it.code }.map { it.text })
    }

    @Test
    fun adjacentInlineCodeDoesNotCreateDelimiterCharacters() {
        val result = evaluateMarkdown("<div>前<code>x</code><code>y</code>中<span><code>a</code></span><code>b</code>后</div>")
        assertEquals("前`xy`中`ab`后", result)
        assertEquals("前xy中ab后", ArenaMarkdown.plainText(result))
        assertEquals("前`xy`后", evaluateMarkdown("<div>前<a href='#local'><code>x</code></a><code>y</code>后</div>"))
    }

    @Test
    fun citationEnDashRemainsDistinctFromQuestionMarkLinks() {
        val result = evaluateMarkdown("<div><a href='https://example.test/'>\u20133</a><a href='https://example.test/'>?3</a></div>")
        assertEquals("³[?3](https://example.test/)", result)
    }

    @Test
    fun providerCodeHeadersAreExcludedWithoutDroppingLiteralAnswerText() {
        val widgets = listOf(
            "<div class='md-code-block'><div class='md-code-block-banner-wrap'><div class='md-code-block-banner'>python<div role='button'>Copy</div><div role='button'>Download</div></div></div><pre>Copy\n运行</pre></div>",
            "<div class='segment-code'><div class='sticky-release'><header class='segment-code-header'><span>Python</span>Copy</header></div><div class='segment-code-content'><pre><code>Copy\n运行</code></pre></div></div>",
            "<div class='code-area'><div data-copy-ignore='true'>python 运行 Copy</div><div class='code-content'><pre><code>Copy\n运行</code></pre></div></div>",
        )
        for (widget in widgets) {
            assertEquals("Copy 运行\n\n```\nCopy\n运行\n```\n\nDownload", evaluateMarkdown("<p>Copy 运行</p>" + widget + "<p>Download</p>"))
        }
    }

    @Test
    fun codeHeaderLookalikesOutsideWidgetsRemainAnswerContent() {
        assertEquals("Copy\n\n运行\n\nDownload", evaluateMarkdown("<div class='md-code-block-banner-wrap'>Copy</div><div data-copy-ignore='true'>运行</div><div class='segment-code-header'>Download</div>"))
        assertEquals("Copy", evaluateMarkdown("<div class='md-code-block'><div class='md-code-block-banner-wrap'>Copy</div></div>"))
    }

    @Test
    fun codeHeaderContainmentCannotDiscardCodeOrNestedWidgetProse() {
        assertEquals("```\nCopy\n```", evaluateMarkdown("<div class='md-code-block'><div class='md-code-block-banner-wrap'><pre>Copy</pre></div></div>"))
        assertEquals("```\nCopy 运行\n```", evaluateMarkdown("<div class='code-area'><pre><span data-copy-ignore='true'>Copy 运行</span></pre></div>"))
        assertEquals("Copy\n\n```\nx\n```", evaluateMarkdown("<div class='md-code-block'><div class='md-code-block-banner-wrap'>Copy</div><div class='segment-code'><pre>x</pre></div></div>"))
    }

    @Test
    fun deepSeekCurrentRequestBusyRefusalIsExplicit() {
        val result = evaluate(ArenaService.DEEPSEEK, "busy_current", "<div class='ds-virtual-list-visible-items' style='width:360px'><div><div class='ds-message'>问题<div role='button' class='ds-button--warning'>重试</div></div><div class='_11d6b3a'><span class='_1ce76f5'>Server busy, please try again later.</span></div></div></div>")
        assertTrue(result.toString(), result.has("error"))
        assertTrue(result.getString("error").contains("DeepSeek 官网当前繁忙"))
        assertFalse(result.getBoolean("found"))
    }

    @Test
    fun deepSeekBusyTextInQuestionOrOldRequestDoesNotRejectCurrentAnswer() {
        val result = evaluate(ArenaService.DEEPSEEK, "busy_old", "<div class='ds-virtual-list-visible-items' style='width:360px'><div><div class='ds-message'>旧问题<div role='button' class='ds-button--warning'>重试</div></div><div class='_11d6b3a'><span class='_1ce76f5'>Server busy, please try again later.</span></div></div><div><div class='ds-message'><span class='_1ce76f5'>Server busy, please try again later.</span></div></div><div><div class='ds-markdown'>当前回答</div><div style='height:32px'><button class='ds-button--icon'>复制</button></div></div></div>")
        assertFalse(result.has("error"))
        assertEquals("当前回答", result.getString("text"))
    }

    @Test
    fun deepSeekHiddenBusyRefusalDoesNotRejectCurrentRequest() {
        for (hiddenStyle in listOf("display:none", "visibility:hidden", "opacity:0")) {
            val result = evaluate(ArenaService.DEEPSEEK, "busy_hidden", "<div class='ds-virtual-list-visible-items' style='width:360px'><div><div class='ds-message'>问题<div role='button' class='ds-button--warning'>重试</div></div><div class='_11d6b3a' style='$hiddenStyle'><span class='_1ce76f5'>Server busy, please try again later.</span></div></div></div>")
            assertFalse(result.has("error"))
        }
    }

    private fun evaluateMarkdown(body: String): String = evaluate(
        ArenaService.DOUBAO, "markdown_mixed",
        "<div class='v_list_row' data-observe-row><div class='bg-g-send'>用户问题</div></div>" +
            "<div class='v_list_row' data-observe-row><div class='md-box-root'>$body</div>" +
            "<div class='message-action-bar' style='height:32px'><button>复制</button></div></div>",
    ).getString("text")

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
