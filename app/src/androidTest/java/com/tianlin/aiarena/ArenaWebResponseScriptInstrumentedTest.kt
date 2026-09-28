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
                CODE_BODY
              </div></div></div></div>
              <div class="segment-assistant-actions" style="height:32px"><button>复制</button></div>
            </div>
        """.trimIndent().replace("CODE_BODY", body) }
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
    fun qwenCompletedThinkingNeverBecomesTheFinalAnswer() {
        // Observed on the current mobile site: thinking has its own COMPLETE markdown
        // before the answer-common-card exists, even while no stop button is visible.
        val thinking = """
            <div class="user-row"><div class="content">读取附件</div></div>
            <div data-chat-answers-wrap="answer-1">
              <div class="thinking-content-tIwPU3"><div class="markdown-pc-special-class" data-md-skip-card-detect>
                <div class="qk-markdown qk-markdown-react qk-markdown-complete"><p>先理解目标并检查输入内容，不是最终答案。</p></div>
              </div></div>
              FINAL_ANSWER
            </div>
        """.trimIndent()
        for (strict in listOf(false, true)) {
            fun read(suffix: String, final: String): JSONObject {
                val old = """<div class="message-card-wrap question" data-message-id="qwen-old"><div class="question-text-card">读取附件</div></div><div class="qk-markdown qk-markdown-react qk-markdown-complete">上一轮答案</div>"""
                val next = """<div class="message-card-wrap question" data-message-id="qwen-next"><div class="question-text-card">另一个问题</div></div><div class="qk-markdown qk-markdown-react qk-markdown-complete">下一轮答案</div>"""
                val current = thinking.replace("FINAL_ANSWER", final).replace(
                    """<div class="user-row"><div class="content">读取附件</div></div>""",
                    """<div class="message-card-wrap question" data-message-id="qwen-current"><div class="question-text-card">读取附件</div></div>""",
                )
                val result = evaluate(ArenaService.QWEN, "qwen_${strict}_$suffix", if (strict) old else current,
                    strictPrompt = if (strict) "读取附件" else "", afterPrepareHtml = if (strict) current + next else null)
                if (strict) {
                    assertTrue(result.getBoolean("localTagBound"))
                    assertEquals("读取附件", result.getString("expectedPrompt"))
                    assertEquals("qwen-current", result.getString("boundUserId"))
                }
                assertFalse(result.getBoolean("requestIdVisible"))
                return result
            }
            val pending = read("thinking_only", "")
            assertFalse("Thinking alone must not become a stable answer", pending.getBoolean("found"))
            assertEquals("", pending.getString("text"))
            assertTrue(pending.getBoolean("streaming"))
            for (complete in listOf(false, true)) {
                val final = """<div class="answer-common-card"><div class="markdown-pc-special-class"><div class="qk-markdown qk-markdown-react ${if (complete) "qk-markdown-complete" else ""}"><p>CODE: DOC-73908700</p><p>APPLES: 7</p><p>PEARS: 4</p></div></div></div>"""
                val result = read("real_answer_$complete", final)
                assertTrue(result.getBoolean("found"))
                assertEquals("CODE: DOC-73908700\n\nAPPLES: 7\n\nPEARS: 4", result.getString("text"))
                assertEquals(!complete, result.getBoolean("streaming"))
            }
            val split = read("split_answer", """<div class="answer-common-card"><div class="qk-markdown qk-markdown-react">仍在生成的第一段</div><div class="qk-markdown qk-markdown-react qk-markdown-complete">已完成的第二段</div></div>""")
            assertTrue(split.getBoolean("streaming"))
            assertEquals("仍在生成的第一段\n\n已完成的第二段", split.getString("text"))
        }
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
    fun wrappedListItemKeepsNestedCodeAndDropsDrawnBullet() {
        // 2026-09-27 Yuanbao real page: li > span.dot "•" + span.content > (div.ybc-p, pre > ... > pre > code).
        val result = evaluateMarkdown("""<ul><li class="ybc-li-component"><span class="ybc-li-component__dot-wp"><span class="ybc-li-component_dot">•</span></span><span class="ybc-li-component_content"><div class="ybc-p"><strong>去重</strong>：先去重再推导：</div>
            <pre class="ybc-pre-component"><div class="hyc-common-markdown__code__hd"><span>python</span></div><pre class="hyc-common-markdown__code-lan"><div><pre><code class="language-python">return {y: f(y) for y in dict.fromkeys(years)}</code></pre></div></pre></pre></span></li>
            <li class="ybc-li-component"><span class="ybc-li-component__dot-wp"><span class="ybc-li-component_dot">•</span></span><span class="ybc-li-component_content"><div class="ybc-p">只要闰年：<code>[y for y in r]</code></div></span></li></ul>""")
        assertEquals("- **去重**：先去重再推导：\n```python\nreturn {y: f(y) for y in dict.fromkeys(years)}\n```\n\n- 只要闰年：`[y for y in r]`", result)
    }

    @Test
    fun qwenFinishedStreamCompletesAnAnswerThePageDrewOnlyPartly() {
        // 2026-09-27 real account: a hidden WebView marked the answer complete after drawing only a prefix.
        val old = """<div class="message-card-wrap question" data-message-id="qwen-old"><div class="question-text-card">讨论题</div></div><div class="qk-markdown qk-markdown-react qk-markdown-complete">上一轮答案</div>"""
        fun current(shown: String) = """<div class="message-card-wrap question" data-message-id="qwen-now"><div class="question-text-card">讨论题</div></div>
            <div data-chat-answers-wrap="a"><div class="answer-common-card"><div class="qk-markdown qk-markdown-react qk-markdown-complete">$shown</div></div></div>"""
        fun read(label: String, shown: String, fetched: String, ageMillis: Long): JSONObject {
            val record = "window.__aiArenaQwenResponses = window.__aiArenaQwenResponses || {};" +
                "window.__aiArenaQwenResponses[${JSONObject.quote("qwen_fetch_$label")}] = {done: true, answer: '', fetchDone: true," +
                " fetchAnswer: ${JSONObject.quote(fetched)}, fetchCalledAt: Date.now() - $ageMillis};"
            return evaluate(ArenaService.QWEN, "qwen_fetch_$label", old, strictPrompt = "讨论题",
                afterPrepareHtml = current(shown), afterPrepareScript = record)
        }
        val full = "**认同**：甲说得对。\n\n**不认同**：\n\n- 乙的数字有误\n- 丙漏了一点\n\n[[inline_action_reply_1]]"
        val expected = "**认同**：甲说得对。\n\n**不认同**：\n\n- 乙的数字有误\n- 丙漏了一点"
        val partial = read("partial", "<p><strong>认同</strong>：甲说得对。</p><p><strong>不认同</strong>：</p><ul><li>乙的</li></ul>", full, 0)
        assertEquals(expected, partial.getString("text"))
        assertEquals(expected, partial.getString("finalText"))
        assertFalse(partial.getBoolean("streaming"))
        val empty = read("empty", "", full, 0)
        assertEquals(expected, empty.getString("text"))
        val stale = read("stale", "<p><strong>认同</strong>：甲说得对。</p>", full, 60_000)
        assertEquals("A stream from before this round's send never replaces the page", "**认同**：甲说得对。", stale.getString("text"))
        // Formulas arrive as TeX in the stream but are readable on the page; code keeps its dollar signs.
        val tex = "由 \$a^2+b^2=c^2\$ 可得：\n\$\$b = \\sqrt{144} = 12\$\$\n\n```sh\necho \$HOME \$PATH\n```\n\n[[inline_action_reply_1]]"
        val formula = read("formula", """<p>由 <span class="katex"><span class="katex-mathml"><math><semantics><annotation encoding="application/x-tex">a^2+b^2=c^2</annotation></semantics></math></span><span class="katex-html" aria-hidden="true">a2+b2=c2</span></span> 可得：</p>""", tex, 0)
        assertEquals("由 a²+b²=c² 可得：\n\nb = √144 = 12\n\n```sh\necho \$HOME \$PATH\n```", formula.getString("text"))
        val mismatch = read("mismatch", "<p>页面上的另一段回答</p>", "完全不同的一段回答", 0)
        assertEquals("A stream that does not continue the drawn text is ignored", "页面上的另一段回答", mismatch.getString("text"))
    }

    @Test
    fun renderedFormulasKeepTheirMeaning() {
        // 2026-09-27 Doubao: KaTeX's visible layer is aria-hidden, so "把 2^8 写成" became "把、、写成".
        val doubao = { tex: String, shown: String ->
            """<span class="container-wTfUs6 math-inline" copy-text="$tex"><span class="katex"><span aria-hidden="true" class="katex-html">$shown</span></span></span>"""
        }
        val standard = """<span class="katex"><span class="katex-mathml"><math><semantics><mrow><mi>x</mi></mrow><annotation encoding="application/x-tex">x_1 \times y^{2}</annotation></semantics></math></span><span class="katex-html" aria-hidden="true">x1×y2</span></span>"""
        val result = evaluateMarkdown("<p>把${doubao("\\(2^8\\)", "28")}和${doubao("\\(2^{n}-1\\)", "2n−1")}写成；另有$standard。</p>" +
            "<table><tr><th>式</th></tr><tr><td>${doubao("\\(2^{16}\\)", "216")}</td></tr></table>" +
            "<div class=\"math-block\" copy-text=\"\\[\\frac{a}{b} \\le 1\\]\"><span class=\"katex-display\"><span class=\"katex\"><span aria-hidden=\"true\" class=\"katex-html\">ab≤1</span></span></span></div>")
        assertEquals("把2⁸和2ⁿ-1写成；另有x₁ × y²。\n\n| 式 |\n| --- |\n| 2¹⁶ |\n\na/b ≤ 1", result)
        // Nested groups and layout commands seen on DeepSeek / Doubao answers the same day.
        val nested = evaluateMarkdown("<p>${doubao("\\(x=\\frac{-b\\pm\\sqrt{b^2-4ac}}{2a}\\)", "x")}，${doubao("\\(\\boxed{x_1=3,\\ x_2=2}\\)", "x")}，" +
            "${doubao("\\(1+2+\\cdots+2^{10}\\)", "x")}，${doubao("\\(S=\\dfrac12\\times3\\times4\\)", "x")}</p>")
        assertEquals("x=(-b±√(b²-4ac))/(2a)，x₁=3, x₂=2，1+2+⋯+2¹⁰，S=1/2×3×4", nested)
        // Yuanbao: data-latex spans; display formulas sit in a PRE without code.
        val yuanbao = evaluateMarkdown("""<pre class="ybc-pre-component"><span class="ybc-markdown-katex ybc-markdown-katex--d" data-latex="ax^2 + bx + c = 0 \quad (a \neq 0)"><span class="katex-display"><span class="katex"><span class="katex-html" aria-hidden="true">ax2+bx+c=0(a=0)</span></span></span></span></pre>""" +
            """<p>两边同除以 <span class="ybc-markdown-katex" data-latex="a"><span class="katex"><span class="katex-html" aria-hidden="true">a</span></span></span>：<span class="ybc-markdown-katex" data-latex="x^2 + \frac{b}{a}x = 0"><span class="katex"><span class="katex-html" aria-hidden="true">x</span></span></span></p>""")
        assertEquals("ax² + bx + c = 0 (a ≠ 0)\n\n两边同除以 a：x² + b/a·x = 0", yuanbao)
        // Qwen: role=math aria-label carries the TeX; a KaTeX with no source keeps its visible text.
        val qwen = evaluateMarkdown("""<div class="qk-md-paragraph">方程 <span class="qk-md-katext qk-md-katext-inline" role="math" aria-label="x^2+2x+5=0"><span class="katex"><span class="katex-html" aria-hidden="true">x2+2x+5=0</span></span></span> 无实根；<span class="katex"><span class="katex-html" aria-hidden="true">y=1</span></span></div>""")
        assertEquals("方程 x²+2x+5=0 无实根；y=1", qwen)
    }

    @Test
    fun providerToolbarsAroundTablesAndToolRunsAreNotAnswerText() {
        // 2026-09-27 real pages: Qwen table toolbar and Zhipu code-interpreter status.
        val result = evaluateMarkdown("""<div class="qk-md-table-wrapper"><div class="qk-md-table-action"><span class="qk-md-table-action-title">表格</span><div class="qk-md-table-download-menu"><div>下载为表格</div><div>导出为图片</div></div></div><div class="qk-md-table-container"><table><thead><tr><th>输入</th><th>输出</th></tr></thead><tbody><tr><td>[1]</td><td>{1: 1}</td></tr></tbody></table></div></div>
            <div class="tool-template-container"><div class="tool-finished-container"><div class="tool-finished-status"><div><span>python运行：已完成</span></div></div></div></div>
            <p>表格之后的正文</p><div class="qk-md-table-action">不是表格旁的工具栏</div>""")
        assertEquals("| 输入 | 输出 |\n| --- | --- |\n| \\[1\\] | {1: 1} |\n\n表格之后的正文\n\n不是表格旁的工具栏", result)
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

    private fun evaluate(service: ArenaService, requestId: String, bodyHtml: String,
                         strictPrompt: String = "", afterPrepareHtml: String? = null, afterPrepareScript: String = ""): JSONObject {
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
                        view.evaluateJavascript(ArenaWebCursorScript.prepare(service, requestId, strictPrompt)) {
                            val simulateAnswerArrivingAfterBaseline = """
                                (function() {
                                  const state = window.__aiArenaRequests && window.__aiArenaRequests[${JSONObject.quote(requestId)}];
                                  if (state && ${strictPrompt.isEmpty()}) state.assistantBaseline = 0;
                                  if (state && ${strictPrompt.isNotEmpty()}) state.submittedAt = Date.now();
                                  ${afterPrepareHtml?.let { "document.body.insertAdjacentHTML('beforeend', ${JSONObject.quote(it)});" } ?: ""}
                                  $afterPrepareScript
                                  return true;
                                })();
                            """.trimIndent()
                            view.evaluateJavascript(simulateAnswerArrivingAfterBaseline) {
                            view.evaluateJavascript(ArenaWebCursorScript.bind(service, requestId, strictPrompt.isNotEmpty())) {
                                view.evaluateJavascript(ArenaWebResponseScript.build(service, requestId, strictPrompt.isNotEmpty())) { raw ->
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
                                              const state = window.__aiArenaRequests?.[requestId];
                                              return JSON.stringify({ visible, localTagBound, expectedPrompt: state?.expectedPrompt || '', boundUserId: state?.boundUserId || '' });
                                            })();
                                        """.trimIndent()
                                        view.evaluateJavascript(metadataScript) { metadataRaw ->
                                            try {
                                                val metadataDecoded = JSONTokener(metadataRaw).nextValue() as String
                                                val metadata = JSONObject(metadataDecoded)
                                                payload.put("requestIdVisible", metadata.getBoolean("visible"))
                                                payload.put("localTagBound", metadata.getBoolean("localTagBound"))
                                                payload.put("expectedPrompt", metadata.getString("expectedPrompt"))
                                                payload.put("boundUserId", metadata.getString("boundUserId"))
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
