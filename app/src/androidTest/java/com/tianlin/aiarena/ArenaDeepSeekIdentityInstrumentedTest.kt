package com.tianlin.aiarena

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test

class ArenaDeepSeekIdentityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val service = ArenaService.DEEPSEEK
    private val request = "deepseek-current"

    @Test fun virtualizedReceiptUsesFullQuestionAndNewKeyInsteadOfRowCount() = page { view ->
        html(view, user("1", "old one") + answer("old answer") + user("3", "old two"))
        prepare(view, "current question")
        submit(view)
        html(view, user("3", "old two") + user("-2", "current question") + answer("CURRENT ANSWER"))
        assertTrue(ArenaWebMessageIdentity.supported(service))
        assertEquals("true", bind(view))
        assertEquals("-2", js(view, "window.__aiArenaRequests['$request'].boundUserId"))
        assertEquals("CURRENT ANSWER", response(view).getString("finalText"))
    }

    @Test fun sameTextOldKeyCannotConfirmButNewKeyCanAfterOldRowsLeave() = page { view ->
        html(view, user("5", "same question"))
        prepare(view, "same question"); submit(view)
        html(view, user("5", "same question") + answer("OLD ANSWER"))
        assertEquals("false", bind(view))
        html(view, user("-2", "same question") + answer("NEW ANSWER"))
        assertEquals("true", bind(view))
        assertEquals("NEW ANSWER", response(view).getString("finalText"))
    }

    @Test fun receiptSurvivesWindowLedgerLossAndBindsOnlyExactPrompt() = page { view ->
        prepare(view, "first line\nsecond line"); submit(view)
        js(view, "delete window.__aiArenaRequests;delete window.__aiArenaSendClicks;true")
        html(view, user("-2", "first line WRONG second line"))
        assertEquals("false", bind(view))
        html(view, user("-2", "first line<br>second line") + answer("EXACT ANSWER"))
        assertEquals("true", bind(view))
        assertEquals("EXACT ANSWER", response(view).getString("finalText"))
    }

    @Test fun pinnedKeyCannotDriftToIdenticalReplacementOrCopiedTag() = page { view ->
        prepare(view, "same question"); submit(view)
        html(view, user("-2", "same question")); assertEquals("true", bind(view))
        html(view, user("-4", "same question") + answer("OTHER ANSWER"))
        js(view, "document.querySelector('[data-virtual-list-item-key]').setAttribute('data-ai-arena-request','$request');delete window.__aiArenaRequests;true")
        assertEquals("false", bind(view))
        assertFalse(response(view).getBoolean("found"))
        html(view, user("-2", "same question") + answer("OWN ANSWER") + user("-4", "same question") + answer("OTHER ANSWER"))
        assertEquals("OWN ANSWER", response(view).getString("finalText"))
    }

    @Test fun absentOwnAnswerMustNotCrossNextUserOrUseGlobalLatestAnswer() = page { view ->
        prepare(view, "current question"); submit(view)
        html(view, user("-2", "current question")); assertEquals("true", bind(view))
        html(view, user("-2", "current question") + "<div>decoration</div>" + user("-4", "next question") + answer("NEXT ANSWER"))
        assertFalse(response(view).getBoolean("found"))
        html(view, user("-4", "next question") + answer("NEXT ANSWER"))
        assertFalse(response(view).getBoolean("found"))
    }

    @Test fun missingKeyAndAssistantQuotationCannotBecomeReceipt() = page { view ->
        prepare(view, "current question"); submit(view)
        html(view, user("", "current question")); assertEquals("false", bind(view))
        html(view, "<div data-virtual-list-item-key='2'><div class='ds-message'><div class='ds-markdown'><div class='ds-collapsible-text'>current question</div></div></div></div>")
        assertEquals("false", bind(view))
    }

    @Test fun sameKeyAndPromptFromAnotherConversationCannotReplaceBoundAnswer() = page { view ->
        js(view, "history.pushState({},'', '/a/chat/s/first');true")
        prepare(view, "same question"); submit(view)
        html(view, user("-2", "same question")); assertEquals("true", bind(view))
        js(view, "history.pushState({},'', '/a/chat/s/other');true")
        html(view, user("-2", "same question") + answer("OTHER CONVERSATION"))
        assertEquals("scope_changed", bind(view)); assertEquals(ArenaWebMessageIdentity.scopeChangedDetail, response(view).getString("error"))
    }

    @Test fun conversationChangeBeforeReceiptCannotBindReusedKey() = page { view ->
        js(view, "history.pushState({},'', '/a/chat/s/first');true")
        prepare(view, "same question"); submit(view)
        js(view, "history.pushState({},'', '/a/chat/s/other');true")
        html(view, user("-2", "same question") + answer("OTHER CONVERSATION"))
        assertEquals("scope_changed", bind(view))
    }

    @Test fun restoredCursorCannotUseRecycledKeyInANewDocumentAtSameUrl() = page { view ->
        prepare(view, "same question"); submit(view)
        html(view, user("-2", "same question")); assertEquals("true", bind(view))
        val saved = js(view, "sessionStorage.getItem('__ai_arena_cursor_$request')")
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView, url: String?) { ready.countDown() } }
            view.loadDataWithBaseURL("https://identity.test/a/chat/s/first", "<html><body></body></html>", "text/html", "UTF-8", null)
        }
        assertTrue(ready.await(8, TimeUnit.SECONDS))
        assertEquals("undefined", js(view, "typeof window.__aiArenaDeepSeekDocument"))
        js(view, "sessionStorage.setItem('__ai_arena_cursor_$request',${JSONObject.quote(saved)});true")
        html(view, user("-2", "same question") + answer("NEW DOCUMENT ANSWER"))
        assertEquals("scope_changed", bind(view)); assertEquals(ArenaWebMessageIdentity.scopeChangedDetail, response(view).getString("error"))
    }

    @Test fun homepageReceiptWaitsForConversationUrlThenReadsFormalAnswer() = page { view ->
        js(view, "history.pushState({},'', '/');true")
        prepare(view, "first question"); submit(view)
        html(view, user("-2", "first question"))
        assertEquals("false", bind(view))
        js(view, "history.pushState({},'', '/a/chat/s/new');true")
        html(view, user("-2", "first question") + answer("FIRST ANSWER"))
        assertEquals("true", bind(view))
        assertEquals("FIRST ANSWER", response(view).getString("finalText"))
    }

    private fun user(id: String, text: String) = "<div data-virtual-list-item-key='$id'><div class='ds-message'><div class='ds-collapsible-text'>$text</div></div></div>"
    private fun answer(text: String) = "<div><div class='ds-markdown ds-assistant-message-main-content'>$text</div><div style='height:32px'><button class='ds-button--icon'>Copy</button></div></div>"
    private fun html(view: WebView, body: String) { js(view, "document.body.innerHTML=" + JSONObject.quote("<div class='ds-virtual-list-visible-items'>$body</div>")) }
    private fun prepare(view: WebView, prompt: String) { js(view, ArenaWebCursorScript.prepare(service, request, prompt)) }
    private fun submit(view: WebView) { js(view, "(()=>{${ArenaWebCursorScript.stateBootstrap(request)}${ArenaWebMessageIdentity.helper(service)}arenaRecordSubmission();return true;})()") }
    private fun bind(view: WebView) = js(view, ArenaWebCursorScript.bind(service, request, requireIdentity = true))
    private fun response(view: WebView) = JSONObject(js(view, ArenaWebResponseScript.build(service, request, requireIdentity = true)))
    private fun js(view: WebView, source: String): String {
        val done = CountDownLatch(1); val result = AtomicReference<String>()
        instrumentation.runOnMainSync { view.evaluateJavascript(source) { raw -> result.set(JSONTokener(raw).nextValue().toString()); done.countDown() } }
        assertTrue(done.await(8, TimeUnit.SECONDS)); return result.get()
    }
    private fun page(block: (WebView) -> Unit) {
        val ready = CountDownLatch(1); lateinit var view: WebView
        instrumentation.runOnMainSync {
            view = WebView(ApplicationProvider.getApplicationContext())
            view.settings.javaScriptEnabled = true; view.settings.domStorageEnabled = true
            view.webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView, url: String?) { ready.countDown() } }
            view.loadDataWithBaseURL("https://identity.test/a/chat/s/first", "<html><body></body></html>", "text/html", "UTF-8", null)
        }
        try { assertTrue(ready.await(8, TimeUnit.SECONDS)); block(view) }
        finally { instrumentation.runOnMainSync { view.destroy() } }
    }
}
