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

class ArenaMessageIdentityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val request = "identity-current"

    @Test fun doubaoCurrentRowsExtractOnlyTheirOwnAnswer() = page { view ->
        prepare(view, ArenaService.DOUBAO, "本轮问题")
        js(view, "document.body.innerHTML=" + JSONObject.quote("""
            <div data-target-id="message-box-target-id"><div data-send-message-boundary data-message-id="user-new"><div class="bg-g-send"><p>本轮问题</p></div></div></div>
            <div data-target-id="message-box-target-id"><div data-reply-message="true"><div data-message-id="answer-new"><div class="md-box-root">本轮完整答案</div></div><div class="message-action-bar" style="height:30px"><button>Copy</button></div></div></div>
        """))
        js(view, "${ArenaReactFixture.script}fixtureDoubaoMessage(document.querySelector('[data-target-id=message-box-target-id]'),'本轮问题');true")
        submit(view, ArenaService.DOUBAO)
        assertEquals("true", js(view, ArenaWebCursorScript.bind(ArenaService.DOUBAO, request)))
        val response = JSONObject(js(view, ArenaWebResponseScript.build(ArenaService.DOUBAO, request)))
        assertEquals("本轮完整答案", response.getString("text"))
        assertFalse(response.getBoolean("streaming"))
    }

    @Test fun multilineParagraphsAndBreaksMatchTheOriginalQuestion() = page { view ->
        prepare(view, ArenaService.KIMI, "第一行\n第二行\n第三行")
        js(view, "document.body.innerHTML='<div class=\"chat-content-item-user\" data-conversation-turn-id=\"new\"><div class=\"user-content__text\"><p>第一行<br>第二行</p><p>第三行</p></div></div>'")
        submit(view, ArenaService.KIMI)
        assertEquals("true", js(view, ArenaWebCursorScript.bind(ArenaService.KIMI, request)))
    }

    @Test fun restoredDifferentQuestionCannotConfirmOrExposeItsOldAnswer() = page { view ->
        prepare(view, ArenaService.KIMI, "current question")
        js(view, "document.body.innerHTML='<div class=\"chat-content-item-user\">old question</div><div class=\"chat-content-item-assistant\"><div class=\"markdown-container\">NO IMAGE</div></div>'")
        submit(view, ArenaService.KIMI)
        assertEquals("false", js(view, ArenaWebCursorScript.bind(ArenaService.KIMI, request)))
        assertFalse(JSONObject(js(view, ArenaWebResponseScript.build(ArenaService.KIMI, request))).getBoolean("found"))
    }

    @Test fun identicalOldMessageRemountCannotBecomeNewReceipt() = page { view ->
        js(view, "document.body.innerHTML='<div class=\"chat-content-item-user\" data-conversation-turn-id=\"old\">same question</div>'")
        prepare(view, ArenaService.KIMI, "same question")
        js(view, "document.body.innerHTML='<div class=\"chat-content-item-user\" data-conversation-turn-id=\"old\">same question</div>'")
        submit(view, ArenaService.KIMI)
        assertEquals("false", js(view, ArenaWebCursorScript.bind(ArenaService.KIMI, request)))
        // The old row can leave the virtual list before a repeated question is confirmed.
        js(view, "document.body.innerHTML='<div class=\"chat-content-item-user\" data-conversation-turn-id=\"new\">same question</div>'")
        assertEquals("true", js(view, ArenaWebCursorScript.bind(ArenaService.KIMI, request)))
        js(view, "document.body.innerHTML='<div class=\"chat-content-item-user\" data-conversation-turn-id=\"old\">same question</div>'")
        js(view, "document.body.insertAdjacentHTML('beforeend','<div class=\"chat-content-item-user\" data-conversation-turn-id=\"new\">same question</div>')")
        assertEquals("true", js(view, ArenaWebCursorScript.bind(ArenaService.KIMI, request)))
        assertEquals("new", js(view, "document.querySelector('[data-ai-arena-request]').getAttribute('data-conversation-turn-id')"))
    }

    @Test fun kimiFailedOptimisticRowIsNotASuccessfulReceipt() = page { view ->
        prepare(view, ArenaService.KIMI, "current question")
        js(view, "document.body.innerHTML='<div class=\"chat-content-item-user awaiting-failure\" data-conversation-turn-id=\"failed-send-id\">current question</div>'")
        submit(view, ArenaService.KIMI)
        assertEquals("false", js(view, ArenaWebCursorScript.bind(ArenaService.KIMI, request)))
        js(view, "document.body.insertAdjacentHTML('beforeend','<div class=\"chat-content-item-user\" data-conversation-turn-id=\"accepted\">current question</div>')")
        assertEquals("true", js(view, ArenaWebCursorScript.bind(ArenaService.KIMI, request)))
        assertEquals("accepted", js(view, "document.querySelector('[data-ai-arena-request]').getAttribute('data-conversation-turn-id')"))
    }

    @Test fun submissionReceiptSurvivesWindowStateLossWithoutAnotherClick() = page { view ->
        prepare(view, ArenaService.KIMI, "current question")
        submit(view, ArenaService.KIMI)
        js(view, "delete window.__aiArenaRequests;delete window.__aiArenaSendClicks;document.body.innerHTML='<div class=\"chat-content-item-user\" data-conversation-turn-id=\"accepted\">current question</div>'")
        assertEquals("true", js(view, ArenaWebCursorScript.bind(ArenaService.KIMI, request)))
        assertEquals("true", js(view, "(()=>{${ArenaWebCursorScript.stateBootstrap(request)}return state.submittedAt>0;})()"))
    }

    @Test fun onlyExplicitLegacyAttachmentCursorAllowsLegacyAnswerReading() = page { view ->
        js(view, ArenaWebCursorScript.prepare(ArenaService.KIMI, request, legacyAttachment = true))
        js(view, "document.body.innerHTML='<div class=\"chat-content-item-user\">legacy file question</div><div class=\"chat-content-item-assistant\"><div class=\"markdown-container\">legacy file answer</div></div>'")
        assertEquals("true", js(view, ArenaWebCursorScript.bind(ArenaService.KIMI, request)))
        val response = JSONObject(js(view, ArenaWebResponseScript.build(ArenaService.KIMI, request, requireIdentity = true)))
        assertEquals("legacy file answer", response.getString("text"))
        js(view, "delete window.__aiArenaRequests;sessionStorage.clear();true")
        assertFalse(JSONObject(js(view, ArenaWebResponseScript.build(ArenaService.KIMI, request, requireIdentity = true))).getBoolean("found"))
    }

    @Test fun boundStableIdentityCannotDriftToLaterIdenticalQuestion() = bothIdentityProviders { view, service ->
        prepare(view, service, "same question")
        html(view, user(service, "owner", "same question"))
        submit(view, service)
        assertEquals("true", js(view, ArenaWebCursorScript.bind(service, request, requireIdentity = true)))
        html(view, user(service, "other", "same question") + answer(service, "OTHER ANSWER"))
        assertEquals(service.name, "false", js(view, ArenaWebCursorScript.bind(service, request, requireIdentity = true)))
        assertFalse(service.name, response(view, service).getBoolean("found"))
    }

    @Test fun boundStableIdentityOutranksCopiedRequestTag() = bothIdentityProviders { view, service ->
        prepare(view, service, "same question")
        html(view, user(service, "owner", "same question"))
        submit(view, service)
        assertEquals("true", js(view, ArenaWebCursorScript.bind(service, request, requireIdentity = true)))
        html(view, user(service, "other", "same question") + answer(service, "OTHER ANSWER") + user(service, "owner", "same question") + answer(service, "OWN ANSWER"))
        js(view, "document.body.firstElementChild.setAttribute('data-ai-arena-request', '$request');true")
        assertEquals(service.name, "OWN ANSWER", response(view, service).getString("text"))
    }

    @Test fun missingOwnAnswerMustNotCrossTheNextUserBoundary() = bothIdentityProviders { view, service ->
        prepare(view, service, "current question")
        html(view, user(service, "owner", "current question"))
        submit(view, service)
        assertEquals("true", js(view, ArenaWebCursorScript.bind(service, request, requireIdentity = true)))
        html(view, user(service, "owner", "current question") + "<div>decorative spacer</div>" + user(service, "next", "next question") + answer(service, "NEXT ANSWER"))
        assertFalse(service.name, response(view, service).getBoolean("found"))
    }

    @Test fun ownAnswerBeforeNextUserRemainsReadableAfterSameIdRemount() = bothIdentityProviders { view, service ->
        prepare(view, service, "current question")
        html(view, user(service, "owner", "current question"))
        submit(view, service)
        assertEquals("true", js(view, ArenaWebCursorScript.bind(service, request, requireIdentity = true)))
        html(view, user(service, "owner", "current question") + answer(service, "OWN ANSWER") + user(service, "next", "next question") + answer(service, "NEXT ANSWER"))
        js(view, "delete window.__aiArenaRequests;true")
        assertEquals(service.name, "OWN ANSWER", response(view, service).getString("text"))
    }

    @Test fun lateWebsiteIdIsPinnedBeforeAnIdenticalReplacementAppears() = bothIdentityProviders { view, service ->
        prepare(view, service, "current question")
        html(view, user(service, "", "current question"))
        submit(view, service)
        assertEquals("false", js(view, ArenaWebCursorScript.bind(service, request, requireIdentity = true)))
        val selector = if (service == ArenaService.KIMI) ".chat-content-item-user" else "[data-message-id]"
        val attribute = if (service == ArenaService.KIMI) "data-conversation-turn-id" else "data-message-id"
        js(view, "document.querySelector('$selector').setAttribute('$attribute','assigned-later');document.body.insertAdjacentHTML('beforeend'," + JSONObject.quote(answer(service, "OWN ANSWER")) + ");true")
        if (service == ArenaService.DOUBAO) js(view, "fixtureDoubaoMessage(document.querySelector('[data-target-id=message-box-target-id]'),'current question');true")
        assertEquals("true", js(view, ArenaWebCursorScript.bind(service, request, requireIdentity = true)))
        assertEquals("OWN ANSWER", response(view, service).getString("text"))
        html(view, user(service, "replacement", "current question") + answer(service, "REPLACEMENT ANSWER"))
        js(view, "delete window.__aiArenaRequests;true")
        assertFalse(service.name, response(view, service).getBoolean("found"))
    }

    @Test fun unnumberedBoundRowCannotBecomeAnotherIdenticalMessage() = bothIdentityProviders { view, service ->
        prepare(view, service, "current question")
        // Only the legacy DOM protocol supports an unnumbered object-bound receipt.
        html(view, if (service == ArenaService.DOUBAO) """<div class="v_list_row" data-observe-row><span class="bg-g-send">current question</span></div>""" else user(service, "", "current question"))
        submit(view, service)
        assertEquals("true", js(view, ArenaWebCursorScript.bind(service, request)))
        html(view, user(service, "other", "current question") + answer(service, "OTHER ANSWER"))
        assertFalse(service.name, response(view, service).getBoolean("found"))
    }

    @Test fun kimiAcceptedRowThatBecomesFailedNeverExposesNextAnswer() = page { view ->
        prepare(view, ArenaService.KIMI, "current question")
        html(view, user(ArenaService.KIMI, "owner", "current question"))
        submit(view, ArenaService.KIMI)
        assertEquals("true", js(view, ArenaWebCursorScript.bind(ArenaService.KIMI, request, requireIdentity = true)))
        html(view, user(ArenaService.KIMI, "owner", "current question").replace("chat-content-item-user", "chat-content-item-user awaiting-failure") + user(ArenaService.KIMI, "other", "current question") + answer(ArenaService.KIMI, "OTHER ANSWER"))
        assertFalse(response(view, ArenaService.KIMI).getBoolean("found"))
    }

    private fun bothIdentityProviders(block: (WebView, ArenaService) -> Unit) {
        listOf(ArenaService.KIMI, ArenaService.DOUBAO).forEach { service -> page { block(it, service) } }
    }
    private fun html(view: WebView, body: String) {
        js(view, "document.body.innerHTML=" + JSONObject.quote(body))
        js(view, "${ArenaReactFixture.script}document.querySelectorAll('[data-send-message-boundary]').forEach(boundary=>fixtureDoubaoMessage(boundary.closest('[data-target-id=message-box-target-id]'),boundary.textContent));true")
    }
    private fun user(service: ArenaService, id: String, text: String): String = when (service) {
        ArenaService.KIMI -> """<div class="chat-content-item-user" data-conversation-turn-id="$id">$text</div>"""
        else -> """<div data-target-id="message-box-target-id"><div data-send-message-boundary data-message-id="$id">$text</div></div>"""
    }
    private fun answer(service: ArenaService, text: String): String = when (service) {
        ArenaService.KIMI -> """<div class="chat-content-item-assistant"><div class="markdown-container">$text</div><div class="segment-assistant-actions" style="height:30px">Copy</div></div>"""
        else -> """<div data-target-id="message-box-target-id"><div data-reply-message><div class="md-box-root">$text</div></div><div class="message-action-bar" style="height:30px">Copy</div></div>"""
    }
    private fun response(view: WebView, service: ArenaService) = JSONObject(js(view, ArenaWebResponseScript.build(service, request, requireIdentity = true)))

    private fun prepare(view: WebView, service: ArenaService, prompt: String) {
        js(view, ArenaWebCursorScript.prepare(service, request, prompt))
    }
    private fun submit(view: WebView, service: ArenaService) {
        js(view, "(()=>{${ArenaWebCursorScript.stateBootstrap(request)}${ArenaWebMessageIdentity.helper(service)}arenaRecordSubmission();return true;})()")
    }
    private fun js(view: WebView, source: String): String {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync { view.evaluateJavascript(source) { raw -> result.set(JSONTokener(raw).nextValue().toString()); done.countDown() } }
        assertTrue(done.await(8, TimeUnit.SECONDS))
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
            view.loadDataWithBaseURL("https://identity.test/", "<html><body></body></html>", "text/html", "UTF-8", null)
        }
        try { assertTrue(ready.await(8, TimeUnit.SECONDS)); block(view) }
        finally { instrumentation.runOnMainSync { view.destroy() } }
    }
}
