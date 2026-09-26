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

class ArenaDoubaoPromptIdentityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val request = "doubao-raw-prompt"
    private val prompt = "Use **bold** and `x`.\n```python\n    return 7\n```"
    private val rendered = "<p>Use <strong>bold</strong> and <code>x</code>.</p><pre><code>    return 7</code></pre><span>展开全部</span>"

    @Test fun v2OnlyFollowupKeepsRawMarkdownEvenWithUnfinishedBlockFlag() = page { view ->
        prepare(view, prompt)
        mount(view, prompt, rendered)
        useV2(view, prompt, keepV1 = false)
        assertEquals("true", bind(view))
        assertEquals("This round answer", JSONObject(js(view, ArenaWebResponseScript.build(ArenaService.DOUBAO, request, requireIdentity = true))).getString("text"))
    }

    @Test fun equivalentV1AndV2OriginalTextsCanConfirmTheSameMessage() = page { view ->
        prepare(view, prompt)
        mount(view, prompt, rendered)
        useV2(view, prompt, keepV1 = true)
        assertEquals("true", bind(view))
    }

    @Test fun conflictingV1AndV2OriginalTextsCannotSelectTheConvenientSchema() = page { view ->
        prepare(view, prompt)
        mount(view, prompt, rendered)
        useV2(view, "different original text", keepV1 = true)
        assertEquals("false", bind(view))
    }

    @Test fun malformedPresentV1CannotFallBackToValidV2() = page { view ->
        prepare(view, prompt)
        mount(view, prompt, rendered)
        useV2(view, prompt, keepV1 = true)
        listOf("null", "[]", "[{block_type:99999,content_obj:{text:'ignored'}}]").forEach { bad ->
            js(view, "fixtureMessage.content_blocks=$bad")
            assertEquals(bad, "false", bind(view))
        }
    }

    @Test fun malformedPresentV2CannotFallBackToValidV1() = page { view ->
        prepare(view, prompt)
        mount(view, prompt, rendered)
        val text = JSONObject.quote(prompt)
        listOf("null", "[]", "[{block_type:99999,content:{text_block:{text:$text}}}]",
            "[{block_type:10000,content:{text_block:{text:7}}}]",
            "[{block_type:10000,is_deleted:true,content:{text_block:{text:$text}}}]",
            "[{block_type:10000,is_deleted:'false',content:{text_block:{text:$text}}}]",
            "[{block_type:10000,is_finish:null,content:{text_block:{text:$text}}}]",
            "[{block_type:10000,content:{text_block:{text:$text}}},{block_type:10000,content:{text_block:{text:''}}}]").forEach { bad ->
            js(view, "fixtureMessage.content_blocks_v2=$bad")
            assertEquals(bad, "false", bind(view))
        }
    }

    @Test fun v2RawCodeIndentationCannotBeCollapsedIntoAMatch() = page { view ->
        prepare(view, prompt)
        mount(view, prompt, rendered)
        useV2(view, prompt.replace("    return", "        return"), keepV1 = false)
        assertEquals("false", bind(view))
    }

    @Test fun v2OriginalTextStillRequiresTheSameOfficialMessageId() = page { view ->
        prepare(view, prompt)
        mount(view, prompt, rendered)
        useV2(view, prompt, keepV1 = false)
        js(view, "fixtureMessage.message_id='other-message'")
        assertEquals("false", bind(view))
    }

    @Test fun conflictingOwnedV2SnapshotsCannotBorrowAnotherOriginalText() = page { view ->
        prepare(view, prompt)
        mount(view, prompt, rendered)
        useV2(view, prompt, keepV1 = false)
        js(view, "fixtureBoundary.memoizedProps={value:{message:{message_id:'user-current',content_blocks_v2:[{block_type:10000,content:{text_block:{text:'other question'}}}]}}}")
        assertEquals("false", bind(view))
    }

    @Test fun lateV2MetadataCanConfirmWithoutReplacingTheOriginalRow() = page { view ->
        prepare(view, prompt)
        mount(view, prompt, rendered)
        js(view, "fixtureMessage.content_blocks=undefined")
        assertEquals("false", bind(view))
        useV2(view, prompt, keepV1 = false)
        assertEquals("true", bind(view))
    }

    private fun useV2(view: WebView, text: String, keepV1: Boolean) {
        js(view, "fixtureMessage.content_blocks_v2=[{block_type:10000,is_finish:false,content:{text_block:{text:${JSONObject.quote(text)}}}}];" +
            (if (keepV1) "" else "fixtureMessage.content_blocks=undefined;") + "true")
    }

    @Test fun renderedMarkdownReceiptUsesOriginalMessageTextAndStableId() = page { view ->
        prepare(view, prompt)
        mount(view, prompt, rendered)
        assertEquals("true", bind(view))
        val response = JSONObject(js(view, ArenaWebResponseScript.build(ArenaService.DOUBAO, request, requireIdentity = true)))
        assertEquals("This round answer", response.getString("text"))
        assertEquals("user-current", js(view, "window.__aiArenaRequests['$request'].boundUserId"))
    }

    @Test fun staleAttachedFiberUsesCommittedBranchWithinItsOwnRow() = page { view ->
        prepare(view, prompt)
        mount(view, prompt, rendered)
        js(view, """
            const staleRoot={tag:3,stateNode:fixtureRoot.stateNode};
            const staleOwner={memoizedProps:{message:{message_id:'user-current',content_blocks:[{block_type:10000,content_obj:{text:'old text'}}]}},return:staleRoot};
            const stale={stateNode:fixtureBoundary.stateNode,return:staleOwner,alternate:fixtureBoundary};
            fixtureBoundary.alternate=stale;fixtureBoundary.stateNode['__reactFiber${'$'}fixture']=stale;
        """)
        assertEquals("true", bind(view))
    }

    @Test fun matchingRenderedTextCannotOverrideAnotherMessageId() = page { view ->
        prepare(view, "same visible question")
        mount(view, "same visible question", "same visible question")
        js(view, "fixtureMessage.message_id='some-other-user'")
        assertEquals("false", bind(view))
    }

    @Test fun matchingRenderedTextCannotOverrideDifferentRawQuestion() = page { view ->
        prepare(view, "same visible question")
        mount(view, "different **raw** question", "same visible question")
        assertEquals("false", bind(view))
    }

    @Test fun rawCodeIndentationMustMatchEvenWhenRenderedWhitespaceCollapses() = page { view ->
        val expected = "code\n    return 7"
        prepare(view, expected)
        mount(view, "code\n        return 7", "<p>code</p><pre>    return 7</pre>")
        assertEquals("false", bind(view))
    }

    @Test fun conflictingOwnedMessageSnapshotsDoNotFallBackToVisibleText() = page { view ->
        prepare(view, "same visible question")
        mount(view, "same visible question", "same visible question")
        js(view, "fixtureBoundary.memoizedProps={value:{message:{message_id:'user-current',content_blocks:[{block_type:10000,content_obj:{text:'conflicting question'}}]}}}")
        assertEquals("false", bind(view))
    }

    @Test fun unknownBlockSchemaCannotUseTtsOrRenderedTextAsReceipt() = page { view ->
        prepare(view, "same visible question")
        mount(view, "same visible question", "same visible question")
        js(view, "fixtureMessage.tts_content='same visible question';fixtureMessage.content_blocks[0].block_type=99999")
        assertEquals("false", bind(view))
    }

    @Test fun aMatchingMessageOutsideTheRowCannotSupplyItsRawText() = page { view ->
        prepare(view, prompt)
        mount(view, prompt, rendered)
        js(view, "fixtureRoot.memoizedProps={message:fixtureMessage};fixtureOwner.memoizedProps={}")
        assertEquals("false", bind(view))
    }

    @Test fun missingOriginalExpectationCannotFallBackToCollapsedWhitespace() = page { view ->
        prepare(view, prompt)
        mount(view, prompt, rendered)
        js(view, "delete window.__aiArenaRequests['$request'].expectedRawPrompt")
        assertEquals("false", bind(view))
    }

    @Test fun missingReactMetadataRejectsRenderedMarkdown() = page { view ->
        prepare(view, "Use bold and x.\nreturn 7")
        mount(view, prompt, rendered)
        js(view, "delete fixtureBoundary.stateNode['__reactFiber${'$'}fixture']")
        assertEquals("false", bind(view))
    }

    @Test fun missingReactMetadataCannotEraseDifferentInternalSpaces() = page { view ->
        prepare(view, "literal    question")
        mount(view, "literal question", "literal question")
        js(view, "delete fixtureBoundary.stateNode['__reactFiber${'$'}fixture']")
        assertEquals("false", bind(view))
    }

    @Test fun decodedEntitiesWithoutFormattingTagsAreNotOriginalPrompt() = page { view ->
        prepare(view, "& *")
        mount(view, "&amp; \\*", "<div class=\"md-box-root\">&amp; *</div>")
        js(view, "delete fixtureBoundary.stateNode['__reactFiber${'$'}fixture']")
        assertEquals("false", bind(view))
    }

    @Test fun multipleRawBlocksCannotInventAnUnobservedSeparator() = page { view ->
        prepare(view, "first\nsecond")
        mount(view, "first", "first<br>second")
        js(view, "fixtureMessage.content_blocks.push({block_type:10000,content_obj:{text:'second'}})")
        assertEquals("false", bind(view))
    }

    @Test fun modernMessageWithoutWebsiteIdCannotUseUnnumberedCompatibilityMode() = page { view ->
        prepare(view, "current question")
        mount(view, "current question", "current question")
        js(view, "document.getElementById('fixture-row').removeAttribute('id');document.querySelector('[data-send-message-boundary]').setAttribute('data-message-id','');fixtureMessage.message_id='';true")
        assertEquals("false", bind(view))
        assertEquals("false", js(view, ArenaWebCursorScript.bind(ArenaService.DOUBAO, request)))
    }

    @Test fun legacyLiteralBubbleRemainsSupportedWithoutModernMetadata() = page { view ->
        prepare(view, "legacy question")
        js(view, "document.body.innerHTML='<div class=\"v_list_row\" data-observe-row data-message-id=\"legacy\"><span class=\"bg-g-send\">legacy question</span></div>'")
        assertEquals("true", bind(view))
    }

    private fun prepare(view: WebView, text: String) {
        js(view, ArenaWebCursorScript.prepare(ArenaService.DOUBAO, request, text))
        js(view, "(()=>{${ArenaWebCursorScript.stateBootstrap(request)}${ArenaWebMessageIdentity.helper(ArenaService.DOUBAO)}arenaRecordSubmission();return true;})()")
    }

    private fun mount(view: WebView, raw: String, renderedBody: String) {
        val html = """<div id="fixture-row" data-target-id="message-box-target-id"><div data-send-message-boundary data-message-id="user-current"><div class="bg-g-send">$renderedBody</div></div></div><div data-target-id="message-box-target-id"><div data-reply-message><div class="md-box-root">This round answer</div></div><div class="message-action-bar" style="height:30px">Copy</div></div>"""
        js(view, "document.body.innerHTML=" + JSONObject.quote(html))
        js(view, """
            ${ArenaReactFixture.script}
            window.fixtureRoot={tag:3,stateNode:{},child:null};fixtureRoot.stateNode.current=fixtureRoot;
            const row=document.getElementById('fixture-row');
            const rowFiber=fixtureFiber(row,{},fixtureRoot);
            window.fixtureMessage={message_id:'user-current',content_blocks:[{block_type:10000,is_deleted:false,content_obj:{text:${JSONObject.quote(raw)}}}]};
            window.fixtureOwner=fixtureNode({message:fixtureMessage},rowFiber);
            window.fixtureBoundary=fixtureFiber(row.querySelector('[data-send-message-boundary]'),{},fixtureOwner);
        """)
    }

    private fun bind(view: WebView) = js(view, ArenaWebCursorScript.bind(ArenaService.DOUBAO, request, requireIdentity = true))

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
            view.loadDataWithBaseURL("https://doubao-identity.test/", "<html><body></body></html>", "text/html", "UTF-8", null)
        }
        try { assertTrue(ready.await(8, TimeUnit.SECONDS)); block(view) }
        finally { instrumentation.runOnMainSync { view.destroy() } }
    }
}
