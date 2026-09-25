package com.tianlin.aiarena

import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test

/** Run the shipped scripts in Android WebView; no provider accounts involved. */
class ArenaOtherProvidersInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val services = listOf(ArenaService.QWEN, ArenaService.YUANBAO, ArenaService.ZHIPU,
        ArenaService.CLAUDE, ArenaService.CHATGPT, ArenaService.GEMINI)
    private val request = "other-providers-current"

    @Test fun productionPoolWaitsForMatchingReceiptAndClicksOnlyOnce() {
        services.forEach { service -> withPool(service, wrongReceipt = false) { pool, view ->
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            instrumentation.runOnMainSync { pool.sendPrompt(service, "current question", request) { outcome.set(it); done.countDown() } }
            assertTrue(service.name, done.await(30, TimeUnit.SECONDS))
            assertTrue("$service ${outcome.get()}", outcome.get().success)
            assertEquals(service.name, "1", js(view, "window.sendCount"))
            assertEquals(service.name, "POOL ANSWER", response(view, service).getString("text"))
        } }
    }

    @Test fun productionPoolRejectsUnrelatedReceiptEvenWhenEditorIsCleared() {
        withPool(ArenaService.YUANBAO, wrongReceipt = true) { pool, view ->
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            instrumentation.runOnMainSync { pool.sendPrompt(ArenaService.YUANBAO, "current question", request) { outcome.set(it); done.countDown() } }
            assertTrue(done.await(30, TimeUnit.SECONDS))
            assertFalse(outcome.get().toString(), outcome.get().success)
            assertEquals("1", js(view, "window.sendCount"))
        }
    }

    @Test fun unrelatedNewUserAndEmptyEditorCannotAcknowledgeOurQuestion() = each { view, service ->
        prepare(view, service)
        html(view, user(service, "other", "another question") + answer(service, "WRONG"))
        submit(view, service)
        assertEquals(service.name, "false", bind(view, service))
        assertFalse(service.name, response(view, service).getBoolean("found"))
    }

    @Test fun currentAnswerStopsBeforeNextQuestionAndUsesItsOwnCompletionSignal() = each { view, service ->
        prepare(view, service)
        html(view, user(service, "ours") + answer(service, "FIRST", done = false) +
            user(service, "next", "next question") + answer(service, "WRONG"))
        submit(view, service)
        assertEquals(service.name, "true", bind(view, service))
        val result = response(view, service)
        assertEquals(service.name, "FIRST", result.getString("text"))
        if (service != ArenaService.QWEN) assertTrue(service.name, result.getBoolean("streaming"))
    }

    @Test fun virtualizedAnchorRemovalCannotFallBackToAnotherAnswer() = each { view, service ->
        prepare(view, service)
        html(view, user(service, "ours") + answer(service, "FIRST"))
        submit(view, service)
        assertEquals("true", bind(view, service))
        html(view, user(service, "next") + answer(service, "WRONG"))
        assertFalse(service.name, response(view, service).getBoolean("found"))
    }

    @Test fun stableMessageIdSurvivesRerenderButNotWrongText() = each { view, service ->
        prepare(view, service)
        html(view, user(service, "ours"))
        submit(view, service)
        assertEquals("true", bind(view, service))
        html(view, user(service, "ours") + answer(service, "RECOVERED"))
        assertEquals(service.name, "RECOVERED", response(view, service).getString("text"))
        html(view, user(service, "ours", "changed question") + answer(service, "WRONG"))
        assertFalse(service.name, response(view, service).getBoolean("found"))
    }

    @Test fun idlessReceiptPinsExactNodeAndNeverDriftsOnRemount() = each { view, service ->
        prepare(view, service)
        html(view, user(service, "ours").replace("data-message-id='ours'", "") + answer(service, "FIRST"))
        submit(view, service)
        assertEquals(service.name, "true", bind(view, service))
        assertEquals(service.name, "FIRST", response(view, service).getString("text"))
        js(view, "document.body.innerHTML=document.body.innerHTML;true")
        assertFalse(service.name, response(view, service).getBoolean("found"))
    }

    @Test fun sameOldQuestionCannotBeReusedAsNewReceipt() = each { view, service ->
        html(view, user(service, "old"))
        prepare(view, service)
        html(view, user(service, "old") + answer(service, "WRONG"))
        submit(view, service)
        assertEquals(service.name, "false", bind(view, service))
        html(view, user(service, "new") + answer(service, "NEW"))
        assertEquals(service.name, "true", bind(view, service))
        assertEquals(service.name, "NEW", response(view, service).getString("text"))
    }

    @Test fun navigationOrReloadInvalidatesReceipt() = each { view, service ->
        prepare(view, service)
        html(view, user(service, "ours") + answer(service, "FIRST"))
        submit(view, service)
        assertEquals("true", bind(view, service))
        js(view, "history.replaceState(null,'','/another-conversation');true")
        assertTrue(service.name, response(view, service).has("error"))
        js(view, "history.replaceState(null,'','/');delete window.__aiArenaProviderDocument;true")
        assertTrue(service.name, response(view, service).has("error"))
    }

    @Test fun navigationBeforeSubmissionCannotBindOrSendInAnotherConversation() = each { view, service ->
        prepare(view, service)
        js(view, "history.replaceState(null,'','/another-conversation');true")
        html(view, user(service, "other") + answer(service, "WRONG"))
        assertEquals(service.name, "scope_changed", bind(view, service))
        submit(view, service)
        assertTrue(service.name, response(view, service).has("error"))
    }

    @Test fun existingConversationCannotNavigateBeforeReceiptEvenAfterClick() = each { view, service ->
        js(view, "history.replaceState(null,'','/existing-conversation');true")
        prepare(view, service)
        submit(view, service)
        js(view, "history.replaceState(null,'','/another-conversation');true")
        html(view, user(service, "other") + answer(service, "WRONG"))
        assertEquals(service.name, "scope_changed", bind(view, service))
        assertTrue(service.name, response(view, service).has("error"))
    }

    @Test fun matchingOldChatRouteWithoutPriorReceiptCannotSupplyAnAnswer() = page { view ->
        prepare(view, ArenaService.QWEN)
        submit(view, ArenaService.QWEN)
        js(view, "history.replaceState(null,'','/chat/old-thread');true")
        html(view, user(ArenaService.QWEN, "old") + answer(ArenaService.QWEN, "OLD ANSWER"))
        assertEquals("scope_changed", bind(view, ArenaService.QWEN))
        assertTrue(response(view, ArenaService.QWEN).has("error"))
    }

    @Test fun freshChatNavigationKeepsOnlyUserWitnessedBeforeTheRouteChange() = page { view ->
        prepare(view, ArenaService.QWEN)
        submit(view, ArenaService.QWEN)
        html(view, user(ArenaService.QWEN, "ours"))
        js(view, "history.pushState(null,'','/chat/new-thread');true")
        js(view, "document.body.insertAdjacentHTML('beforeend',${JSONObject.quote(answer(ArenaService.QWEN, "NEW ANSWER"))});true")
        assertEquals("true", bind(view, ArenaService.QWEN))
        assertEquals("NEW ANSWER", response(view, ArenaService.QWEN).getString("text"))
        js(view, "history.replaceState(null,'','/chat/other-thread');true")
        assertTrue(response(view, ArenaService.QWEN).has("error"))
    }

    @Test fun zhipuRecycledRowNumberIsNotAServerMessageId() = page { view ->
        val service = ArenaService.ZHIPU
        prepare(view, service)
        html(view, "<div class='conversation question' id='row-question-0'>current question</div>")
        submit(view, service)
        assertEquals("true", bind(view, service))
        html(view, "<div class='conversation question' id='row-question-0'>current question</div>" + answer(service, "WRONG"))
        assertFalse(response(view, service).getBoolean("found"))
    }

    @Test fun zhipuWaitsForAsynchronousButtonReadinessWithoutDuplicateDispatch() = page { view ->
        zhipuSetup(view)
        zhipuPost(view)
        zhipuPost(view)
        val deadline = System.currentTimeMillis() + 6000
        while (js(view, "window.sendCount") == "0" && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals("1", js(view, "window.sendCount"))
        assertEquals("current question", js(view, "window.sentText"))
    }

    @Test fun zhipuCancelledWaitDoesNotSendWhenButtonLaterEnables() = page { view ->
        zhipuSetup(view)
        zhipuPost(view)
        js(view, "window.__aiArenaCancelledRequests={'$request':true};true")
        Thread.sleep(900)
        assertEquals("0", js(view, "window.sendCount"))
    }

    @Test fun zhipuLateEnabledButtonAfterDeadlineDoesNotSend() = page { view ->
        zhipuSetup(view)
        js(view, "window.realNow=Date.now;window.testNow=realNow();Date.now=()=>testNow;true")
        zhipuPost(view)
        Thread.sleep(150)
        js(view, "window.testNow+=5000;document.querySelector('button').disabled=false;true")
        Thread.sleep(700)
        assertEquals("0", js(view, "window.sendCount"))
        assertEquals("error:send_disabled", js(view, "window.__aiArenaZhipuDispatchResults['$request']"))
    }

    private fun zhipuSetup(view: WebView) {
        js(view, ArenaWebViewPool.ZHIPU_DOCUMENT_START_CAPTURE)
        html(view, "<textarea></textarea><button class='button-right-inner' disabled>Send</button>")
        prepare(view, ArenaService.ZHIPU)
        js(view, """
            window.sendCount=0;
            const editor=document.querySelector('textarea'),button=document.querySelector('button');
            editor.addEventListener('input',()=>setTimeout(()=>{button.disabled=false;},500));
            button.addEventListener('mousedown',()=>{window.sendCount++;window.sentText=editor.value;});true;
        """.trimIndent())
    }
    private fun zhipuPost(view: WebView) { js(view, "window.postMessage({channel:'__ai_arena_zhipu_send_v1',requestId:'$request',text:'current question'},location.origin);true") }

    @Test fun qwenUnboundNetworkCaptureAndSidebarCannotMasqueradeAsAnswer() = page { view ->
        val service = ArenaService.QWEN
        prepare(view, service)
        html(view, "<div class='user-menu'><div class='content'>current question</div></div>" + answer(service, "WRONG"))
        submit(view, service)
        js(view, "window.__aiArenaQwenResponses={'$request':{answer:'UNRELATED STREAM',done:true}};true")
        assertEquals("false", bind(view, service))
        assertFalse(response(view, service).getBoolean("found"))
    }

    @Test fun qwenSlatePlaceholderIsNotADraftAndActualDraftIsPreserved() = page { view ->
        html(view, "<div contenteditable='true'><p><span data-slate-zero-width='n'><br></span><span data-slate-placeholder='true' contenteditable='false'>向千问提问</span></p></div>")
        val read = "(()=>{${ArenaWebViewPool.editorDraftHelper}return arenaEditorDraft(document.querySelector('[contenteditable=true]'));})()"
        assertEquals("", js(view, read))
        js(view, "document.querySelector('p').insertAdjacentHTML('beforeend','<span>向千问提问，这是我实际写的草稿</span>');true")
        assertEquals("向千问提问，这是我实际写的草稿", js(view, read))
        assertEquals("true", js(view, "!!document.querySelector('[data-slate-placeholder]')"))
    }

    @Test fun yuanbaoKeepsAllCurrentSpeechBlocksAndDropsThinking() = page { view ->
        val service = ArenaService.YUANBAO
        prepare(view, service)
        html(view, user(service, "ours") + answer(service, "FIRST") + answer(service,
            "<div class='thinking'>PRIVATE REASONING</div><p>SECOND</p>"))
        submit(view, service)
        assertEquals("true", bind(view, service))
        assertEquals("FIRST\n\nSECOND", response(view, service).getString("finalText"))
    }

    @Test fun yuanbaoDeepSearchV2ShellKeepsFinalMarkdownWithoutItsThinking() = page { view ->
        val service = ArenaService.YUANBAO
        prepare(view, service)
        html(view, user(service, "ours") + answer(service, """
            <div class='hyc-component-deep-search-agent hyc-component-deep-search-agent--v2-with-cot'>
              <div class='hyc-component-deep-search-agent__think-container'>PRIVATE REASONING THAT IS NOT THE ANSWER</div>
              <button class='hyc-component-deep-search-agent_direct-answer-btn'>Quick Answer</button>
              <div class='hyc-content-md hyc-content-md-done'><div class='hyc-common-markdown'><p>FINAL ANSWER</p></div></div>
            </div>
        """.trimIndent()))
        submit(view, service)
        assertEquals("true", bind(view, service))
        assertEquals("FINAL ANSWER", response(view, service).getString("finalText"))
    }

    @Test fun yuanbaoCollapsedPromptExcludesExpandControlFromReceipt() = page { view ->
        val service = ArenaService.YUANBAO
        prepare(view, service)
        html(view, """
            <div class='agent-chat__list__item--human' data-conv-id='thread_3'>
              <div class='agent-chat__bubble--human'><div class='hyc-content-text'>current question</div></div>
              <div class='expand-control'>Expand</div>
            </div>
        """.trimIndent() + answer(service, "CURRENT ANSWER"))
        submit(view, service)
        assertEquals("true", bind(view, service))
        assertEquals("CURRENT ANSWER", response(view, service).getString("text"))
    }

    @Test fun observedCodeWidgetsPreserveLanguageIndentationAndBlankLines() {
        val body = "def value(x):\n    if x is not None:\n        return x + 7391\n\n# END"
        listOf(
            "<div class='md-code-block'><div class='md-code-block-banner-wrap'><span>python</span><div role='button'>Copy</div></div><pre>$body</pre></div>",
            "<div class='qw-md-code'><div class='sticky'><span>python</span><div>Copy</div></div><div class='codeHighlighterWrapper'><pre><code>$body</code></pre></div></div>",
            "<div class='artifacts-container'><div class='artifacts-outer'><div class='language'>python</div><div class='copy-button'>复制</div></div><div class='language language-python'><pre><code>$body</code></pre></div></div>",
        ).forEach { fixture -> page { view ->
            html(view, "<div id='source'><p>python is mentioned in prose.</p>$fixture</div>")
            val result = js(view, "(()=>{${ArenaMarkdownScript.helper}return arenaToMarkdown(document.getElementById('source'));})()")
            assertEquals("python is mentioned in prose.\n\n```python\n$body\n```", result)
        } }
    }

    @Test fun zhipuCollapsedReasoningCannotLeakIntoTheFinalAnswer() = page { view ->
        val service = ArenaService.ZHIPU
        prepare(view, service)
        html(view, user(service, "ours") + answer(service, """
            <div class='answer-item'><div class='thinking thinkFinish'>思考结束
              <div class='thinking-area' style='display:none'>PRIVATE REASONING THAT IS NOT THE ANSWER</div>
            </div></div><div class='answer-item'><p>FINAL ANSWER</p></div>
        """.trimIndent()))
        submit(view, service)
        assertEquals("true", bind(view, service))
        assertEquals("FINAL ANSWER", response(view, service).getString("finalText"))
    }

    private fun user(service: ArenaService, id: String, text: String = "current question") = when (service) {
        ArenaService.QWEN -> "<div class='message-card-wrap question' data-message-id='$id'>$text</div>"
        ArenaService.YUANBAO -> "<div class='agent-chat__list__item--human' data-message-id='$id'>$text</div>"
        ArenaService.ZHIPU -> "<div class='conversation question' data-message-id='$id'>$text</div>"
        ArenaService.CLAUDE -> "<div data-testid='user-message' data-message-id='$id'>$text</div>"
        ArenaService.CHATGPT -> "<div data-message-author-role='user' data-message-id='$id'>$text</div>"
        else -> "<user-query data-message-id='$id'>$text</user-query>"
    }

    private fun answer(service: ArenaService, text: String, done: Boolean = true): String {
        val display = if (done) "block" else "none"
        return when (service) {
            ArenaService.QWEN -> "<div class='message-card-wrap answer'><div class='qk-markdown'>$text</div></div>"
            ArenaService.YUANBAO -> "<div class='agent-chat__list__item--ai'><div class='agent-chat__conv--ai__speech_show'>$text</div><div class='agent-chat__conv--ai__toolbar' style='display:$display;height:30px'>Copy</div></div>"
            ArenaService.ZHIPU -> "<div class='answer'><div class='answer-content'>$text</div><div class='interact' style='display:$display;height:30px'>Copy</div></div>"
            ArenaService.CLAUDE -> "<div data-is-streaming='${!done}'><div class='font-claude-response'>$text</div></div>"
            ArenaService.CHATGPT -> "<article><div data-message-author-role='assistant'><div class='markdown'>$text</div></div><button data-testid='copy-turn-action-button' style='display:$display'>Copy</button></article>"
            else -> "<model-response><message-content><div class='markdown' aria-busy='${!done}'>$text</div></message-content><message-actions style='display:$display;height:30px'>Copy</message-actions></model-response>"
        }
    }

    private fun prepare(view: WebView, service: ArenaService) { js(view, ArenaWebCursorScript.prepare(service, request, "current question")) }
    private fun submit(view: WebView, service: ArenaService) { js(view, "(()=>{${ArenaWebCursorScript.stateBootstrap(request)}${ArenaWebMessageIdentity.helper(service)}arenaRecordSubmission();return true;})()") }
    private fun bind(view: WebView, service: ArenaService) = js(view, ArenaWebCursorScript.bind(service, request, requireIdentity = true))
    private fun response(view: WebView, service: ArenaService) = JSONObject(js(view, ArenaWebResponseScript.build(service, request, requireIdentity = true)))
    private fun html(view: WebView, body: String) { js(view, "document.body.innerHTML=" + JSONObject.quote(body)) }
    private fun each(block: (WebView, ArenaService) -> Unit) { services.forEach { service -> page { block(it, service) } } }

    @Suppress("UNCHECKED_CAST")
    private fun withPool(service: ArenaService, wrongReceipt: Boolean, block: (ArenaWebViewPool, WebView) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var pool: ArenaWebViewPool
            lateinit var view: WebView
            val receipt = user(service, "pool-user", if (wrongReceipt) "another question" else "current question") + answer(service, "POOL ANSWER")
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("webViewPool").apply { isAccessible = true }
                (field.get(activity) as ArenaWebViewPool).destroy()
                pool = ArenaWebViewPool(activity)
                field.set(activity, pool)
                pool.setProtectedServices(setOf(service))
                val host = FrameLayout(activity)
                activity.setContentView(host)
                host.addView(pool.container, FrameLayout.LayoutParams(-1, -1))
                pool.open(service)
                val views = ArenaWebViewPool::class.java.getDeclaredField("webViews").apply { isAccessible = true }.get(pool) as Map<ArenaService, WebView>
                view = views.getValue(service)
                view.stopLoading()
                view.loadDataWithBaseURL(service.url, """
                    <html><body><textarea id='prompt-textarea'></textarea>
                    <button id='yuanbao-send-btn' class='button-right-inner send-button' aria-label='Send message'>Send</button>
                    <script>
                    window.sendCount=0;
                    const input=document.querySelector('textarea');
                    input.addEventListener('input',function(){});
                    document.querySelector('button').addEventListener('${if (service == ArenaService.ZHIPU) "mousedown" else "click"}',function(){
                      window.sendCount++;input.value='';
                      setTimeout(function(){document.body.insertAdjacentHTML('beforeend',${JSONObject.quote(receipt)});},1800);
                    });
                    window.fixtureReady=true;
                    </script></body></html>
                """.trimIndent(), "text/html", "UTF-8", service.url)
                pool.show(null)
            }
            try {
                val deadline = System.currentTimeMillis() + 12000
                while (js(view, "window.fixtureReady===true") != "true" && System.currentTimeMillis() < deadline) Thread.sleep(100)
                assertEquals("true", js(view, "window.fixtureReady===true"))
                instrumentation.runOnMainSync { pool.statuses[service] = ServiceStatus(ConnectionState.SIGNED_IN, "isolated fixture") }
                block(pool, view)
            } finally { instrumentation.runOnMainSync { pool.destroy() } }
        }
    }
    private fun js(view: WebView, script: String): String {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { raw -> result.set(JSONTokener(raw).nextValue().toString()); done.countDown() } }
        assertTrue(done.await(12, TimeUnit.SECONDS))
        return result.get()
    }
    private fun page(block: (WebView) -> Unit) {
        val ready = CountDownLatch(1)
        lateinit var view: WebView
        instrumentation.runOnMainSync {
            view = WebView(ApplicationProvider.getApplicationContext())
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) { ready.countDown() }
            }
            view.loadDataWithBaseURL("https://provider-fixture.test/", "<html><body></body></html>", "text/html", "UTF-8", null)
        }
        try { assertTrue(ready.await(12, TimeUnit.SECONDS)); block(view) }
        finally { instrumentation.runOnMainSync { view.destroy() } }
    }
}
