package com.tianlin.aiarena

import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Production pool/client/broker/scripts, with isolated pages shaped like the three provider editors. */
class ArenaParallelWebViewInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val members = ArenaService.defaultMembers
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun doubaoFollowupUsesV2OnlyRawMessageWithoutResendingOrReadingFirstAnswer() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            evaluate(view, """
                window.sentQuestions=[];
                window.send=()=>{
                  const number=++sendCount,raw=document.querySelector('textarea').value;
                  sentQuestions.push(raw);document.querySelector('textarea').value='';
                  setTimeout(()=>{
                    const row=document.createElement('div');row.setAttribute('data-target-id','message-box-target-id');
                    row.innerHTML='<div data-send-message-boundary data-message-id="followup-'+number+'"><div class="bg-g-send"><p>Keep <code>x</code> exactly.</p><pre><code>    return '+number+'</code></pre><span>展开全部</span></div></div>';
                    document.body.appendChild(row);
                    const message=fixtureDoubaoMessage(row,raw);
                    message.content_blocks_v2=[{block_type:10000,is_finish:false,content:{text_block:{text:raw}}}];
                    if(number>1)message.content_blocks=undefined;
                    document.body.insertAdjacentHTML('beforeend','<div data-target-id="message-box-target-id"><div data-reply-message><div class="md-box-root">Answer '+number+'</div></div><div class="message-action-bar" style="height:30px">Copy</div></div>');
                  },1800);
                };true;
            """.trimIndent())
            (1..2).forEach { number ->
                val prompt = "Keep `x` exactly.\n```python\n    return $number\n```"
                val request = "raw-followup-$number"
                val done = CountDownLatch(1)
                val outcome = AtomicReference<SendOutcome>()
                onMain { pool.sendPrompt(ArenaService.DOUBAO, prompt, request) { outcome.set(it); done.countDown() } }
                assertTrue(done.await(22, TimeUnit.SECONDS))
                assertTrue(outcome.get().toString(), outcome.get().success)
                assertEquals(number.toString(), evaluate(view, "sendCount"))
                assertEquals(prompt, evaluate(view, "sentQuestions[${number - 1}]"))
                assertEquals("followup-$number", evaluate(view, "window.__aiArenaRequests['$request'].boundUserId"))
                val answer = JSONObject(evaluate(view, ArenaWebResponseScript.build(ArenaService.DOUBAO, request, requireIdentity = true)))
                assertEquals("Answer $number", answer.getString("text"))
            }
        }
    }

    @Test fun doubaoRenderedMarkdownPromptConfirmsOneSubmissionUsingOriginalMessage() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            val prompt = "Keep `x` exactly.\n```python\n    return 7\n```"
            evaluate(view, """
                window.send=()=>{
                  sendCount++;sentText=document.querySelector('textarea').value;
                  document.querySelector('textarea').value='';
                  setTimeout(()=>{
                    const row=document.createElement('div');row.setAttribute('data-target-id','message-box-target-id');
                    row.innerHTML='<div data-send-message-boundary data-message-id="markdown-question"><div class="bg-g-send"><div class="md-box-root"><p>Keep <code>x</code> exactly.</p><pre><code>    return 7</code></pre></div><span>展开全部</span></div></div>';
                    document.body.appendChild(row);fixtureDoubaoMessage(row,sentText);
                    document.body.insertAdjacentHTML('beforeend','<div data-target-id="message-box-target-id"><div data-reply-message><div class="md-box-root">Strict raw receipt answer</div></div><div class="message-action-bar" style="height:30px">Copy</div></div>');
                  },1800);
                };true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPrompt(ArenaService.DOUBAO, prompt, "raw-markdown-pool") { outcome.set(it); done.countDown() } }
            assertTrue(done.await(22, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("1", evaluate(view, "sendCount"))
            assertEquals(prompt, evaluate(view, "sentText"))
            val answer = JSONObject(evaluate(view, ArenaWebResponseScript.build(ArenaService.DOUBAO, "raw-markdown-pool", requireIdentity = true)))
            assertEquals("Strict raw receipt answer", answer.getString("text"))
        }
    }

    @Test fun kimiVisibleBusyDialogIsExplicitFailureWithoutResubmission() = verifyKimiBusyDialog(false)

    @Test fun kimiHiddenBusyDialogAndQuotedBodyDoNotRejectAcceptedQuestion() = verifyKimiBusyDialog(true)

    @Test fun kimiReceiptBetweenProbesOutranksVisibleBusyDialog() = verifyKimiBusyDialog(false, lateReceipt = true)

    private fun verifyKimiBusyDialog(hidden: Boolean, lateReceipt: Boolean = false) {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.KIMI)
            evaluate(view, """
                window.send=()=>{
                  sendCount++;
                  const modal=document.createElement('div');modal.className='modal-mask';
                  modal.style='width:300px;min-height:100px;'+($hidden?'display:none':'');
                  modal.innerHTML='<div data-testid="confirm-dialog"><div class="body">Too many people are chatting with Kimi; a subscription will grant you priority access.</div><button data-testid="confirm-dialog-cancel">Got it</button><button data-testid="confirm-dialog-confirm">Upgrade</button></div>';
                  document.body.appendChild(modal);
                  const quote=document.createElement('p');quote.textContent='Too many people are chatting with Kimi';document.body.appendChild(quote);
                  const accept=()=>{
                    const user=document.createElement('div');user.className='chat-content-item-user';user.setAttribute('data-conversation-turn-id','fixture-user-'+sendCount);
                    user.setAttribute('data-conversation-turn-id','busy-negative');user.textContent=document.querySelector('textarea').value;
                    document.body.appendChild(user);document.querySelector('textarea').value='';
                  };
                  if ($hidden) accept();
                  if ($lateReceipt) setTimeout(()=>{
                    const original=document.querySelectorAll.bind(document);let armed=true;
                    document.querySelectorAll=function(selector){
                      const result=original(selector);
                      if(armed&&selector==='.chat-content-item-user:not(.awaiting-failure)'){
                        armed=false;Promise.resolve().then(()=>{accept();window.lateReceiptInserted=true;});
                      }
                      return result;
                    };
                  },1800);
                };true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPrompt(ArenaService.KIMI, "busy question", "busy-kimi") { outcome.set(it); done.countDown() } }
            assertTrue(done.await(20, TimeUnit.SECONDS))
            assertEquals(outcome.get().toString(), hidden || lateReceipt, outcome.get().success)
            if (!hidden && !lateReceipt) assertEquals(ArenaKimiRejection.busyDetail, outcome.get().detail)
            if (lateReceipt) assertEquals("true", evaluate(view, "window.lateReceiptInserted"))
            assertEquals("1", evaluate(view, "sendCount"))
            assertEquals(if (hidden) "" else "busy", evaluate(view, ArenaKimiRejection.expression))
        }
    }

    @Test fun doubaoQueuedQuestionCanBecomeAcceptedWithoutAnotherClick() = verifyDoubaoQueue("late")

    @Test fun doubaoPermanentQueueIsNotReportedAsDelivered() = verifyDoubaoQueue("pending")

    @Test fun doubaoOldIdenticalQueueDoesNotBelongToCurrentSubmission() = verifyDoubaoQueue("old")

    private fun verifyDoubaoQueue(mode: String) {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            evaluate(view, """
                window.alternateClicks=0;
                const alternate=document.createElement('button');alternate.className='bg-dbx-fill-highlight';
                alternate.textContent='Queue alternate';alternate.onclick=()=>alternateClicks++;
                document.getElementById('input-engine-container').prepend(alternate);
                const queue=()=>{const row=document.createElement('div');row.setAttribute('data-item-id','fixture-queue');
                  row.setAttribute('data-item-status','Pending');row.innerHTML='<div class="richTextPreview">queued question</div>';
                  document.body.appendChild(row);return row;};
                if ('$mode'==='old') queue();
                window.send=()=>{
                  sendCount++;document.querySelector('textarea').value='';
                  if ('$mode'==='old') return;
                  const pending=queue();
                  if ('$mode'==='late') setTimeout(()=>{
                    requestAnimationFrame(()=>{
                      const row=document.createElement('div');row.setAttribute('data-target-id','message-box-target-id');
                      row.innerHTML='<div data-send-message-boundary data-message-id="accepted-queue">queued question</div>';
                      document.body.appendChild(row);fixtureDoubaoMessage(row,'queued question');pending.remove();
                    });
                  },18000);
                };true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPrompt(ArenaService.DOUBAO, "queued question", "queue-probe") { outcome.set(it); done.countDown() } }
            assertTrue(done.await(55, TimeUnit.SECONDS))
            assertEquals(outcome.get().toString(), mode == "late", outcome.get().success)
            // An existing website queue means Doubao is busy: the question must stay unsent, not join it.
            assertEquals(if (mode == "old") "0" else "1", evaluate(view, "sendCount"))
            assertEquals("0", evaluate(view, "alternateClicks"))
            if (mode == "pending") assertTrue(outcome.get().detail.contains("待发送队列"))
            if (mode == "old") assertFalse(outcome.get().detail.contains("待发送队列"))
            if (mode == "old") assertTrue(outcome.get().detail.contains("未发送"))
        }
    }

    @Test fun freshDoubaoPageWithPendingQueueIsNotEmpty() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            onMain {
                val client = view.webViewClient
                view.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, icon: android.graphics.Bitmap?) = client.onPageStarted(view, url, icon)
                    override fun onPageFinished(view: WebView, url: String?) = client.onPageFinished(view, url)
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest) = android.webkit.WebResourceResponse(
                        "text/html", "UTF-8", "<textarea></textarea><div data-item-status='Pending'>older queued question</div>".byteInputStream())
                }
            }
            val done = CountDownLatch(1)
            val ready = AtomicBoolean(true)
            onMain { pool.openFreshConversation(ArenaService.DOUBAO) { ready.set(it); done.countDown() } }
            assertTrue(done.await(92, TimeUnit.SECONDS))
            assertFalse(ready.get())
        }
    }

    @Test fun coldUnconfirmedReadyPageIsProbedBeforeSending() = listOf(ArenaService.DOUBAO, ArenaService.KIMI).forEach { verifyColdLoginProbe(it, false) }

    @Test fun coldExplicitLoginPageDoesNotReceiveAQuestion() = listOf(ArenaService.DOUBAO, ArenaService.KIMI).forEach { verifyColdLoginProbe(it, true) }

    @Suppress("UNCHECKED_CAST")
    private fun verifyColdLoginProbe(service: ArenaService, loginVisible: Boolean) {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(service)
            evaluate(view, """
                window.send=()=>{
                  sendCount++;const input=document.querySelector('textarea');sentText=input.value;input.value='';
                  const user=document.createElement('div');
                  if (${service == ArenaService.KIMI}) {
                    user.className='chat-content-item-user';user.setAttribute('data-conversation-turn-id','fixture-user-'+sendCount);user.setAttribute('data-conversation-turn-id','cold');user.textContent=sentText;
                  } else {
                    user.setAttribute('data-target-id','message-box-target-id');
                    const body=document.createElement('div');body.setAttribute('data-send-message-boundary','');body.setAttribute('data-message-id','cold');body.textContent=sentText;user.appendChild(body);
                  }
                  document.body.appendChild(user);
                  if (${service == ArenaService.DOUBAO}) fixtureDoubaoMessage(user,sentText);
                };
                if ($loginVisible) {const login=document.createElement('button');login.textContent='Log in';document.body.appendChild(login);}
                true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain {
                (field(pool, "confirmedSignedIn") as MutableSet<ArenaService>).remove(service)
                pool.statuses[service] = ServiceStatus(ConnectionState.LOADING, "cold startup")
                pool.sendPrompt(service, "cold question", "cold-probe") { outcome.set(it); done.countDown() }
            }
            assertTrue(done.await(20, TimeUnit.SECONDS))
            assertEquals(outcome.get().toString(), !loginVisible, outcome.get().success)
            assertEquals(if (loginVisible) "0" else "1", evaluate(view, "sendCount"))
        }
    }

    @Test fun kimiMultilinePlainTextPastePreservesParagraphsAndSubmitsOnce() = verifyKimiMultilinePaste(0)

    @Test fun deepSeekVirtualizedReceiptDoesNotRequireCountGrowthOrClearedInput() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DEEPSEEK)
            evaluate(view, """
                window.history.pushState({},'', '/a/chat/s/fixture');
                const history=document.createElement('div');history.className='ds-virtual-list-visible-items';document.body.appendChild(history);
                const user=(key,text)=>'<div data-virtual-list-item-key="'+key+'"><div class="ds-message"><div class="ds-collapsible-text">'+text+'</div></div></div>';
                history.innerHTML=user('1','old one')+user('3','old two');
                window.send=()=>{
                  sendCount++;sentText=document.querySelector('textarea').value;
                  history.innerHTML=user('3','old two')+user('-2',sentText);
                  delete window.__aiArenaSendClicks;
                };true;
            """.trimIndent())
            val done = CountDownLatch(1); val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPrompt(ArenaService.DEEPSEEK, "current question", "virtualized-deepseek") { outcome.set(it); done.countDown() } }
            assertTrue(done.await(30, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("1", evaluate(view, "sendCount"))
            assertEquals("-2", evaluate(view, "window.__aiArenaRequests['virtualized-deepseek'].boundUserId"))
            assertEquals("current question", evaluate(view, "document.querySelector('textarea').value"))
        }
    }

    @Test fun busyDeepSeekRendererKeepsOneReceiptBeyondShortCallbackDeadline() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DEEPSEEK)
            evaluate(view, """
                window.send=()=>{
                  sendCount++;const input=document.querySelector('textarea');sentText=input.value;input.value='';
                  window.history.pushState({},'', '/a/chat/s/fixture');
                  const history=document.createElement('div');history.className='ds-virtual-list-visible-items';
                  const user=document.createElement('div');user.setAttribute('data-virtual-list-item-key','-2');
                  user.innerHTML='<div class="ds-message"><div class="ds-collapsible-text"></div></div>';
                  user.querySelector('.ds-collapsible-text').textContent=sentText;history.appendChild(user);document.body.appendChild(history);
                  const until=Date.now()+15000;while(Date.now()<until){}
                };true;
            """.trimIndent())
            val done = CountDownLatch(1); val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPrompt(ArenaService.DEEPSEEK, "slow receipt", "slow-deepseek") { outcome.set(it); done.countDown() } }
            assertTrue(done.await(35, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("1", evaluate(view, "sendCount"))
            assertEquals("-2", evaluate(view, "window.__aiArenaRequests['slow-deepseek'].boundUserId"))
        }
    }

    @Test fun deepSeekHomepageWaitsForSessionBeforeReceiptAndDoesNotClickTwice() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DEEPSEEK)
            evaluate(view, """
                window.wasUnboundOnHome=false;
                window.send=()=>{
                  sendCount++;const input=document.querySelector('textarea');sentText=input.value;
                  const root=document.createElement('div');root.className='ds-virtual-list-visible-items';
                  root.innerHTML='<div data-virtual-list-item-key="-2"><div class="ds-message"><div class="ds-collapsible-text"></div></div></div>';
                  root.querySelector('.ds-collapsible-text').textContent=sentText;document.body.appendChild(root);
                  setTimeout(()=>{
                    wasUnboundOnHome=!window.__aiArenaRequests['home-deepseek'].bound && location.pathname==='/';
                    history.pushState({},'', '/a/chat/s/new-fixture');
                    root.insertAdjacentHTML('beforeend','<div><div class="ds-markdown ds-assistant-message-main-content">HOME ANSWER</div></div>');
                  },3300);
                };true;
            """.trimIndent())
            val done = CountDownLatch(1); val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPrompt(ArenaService.DEEPSEEK, "first question", "home-deepseek") { outcome.set(it); done.countDown() } }
            assertTrue(done.await(30, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("true", evaluate(view, "wasUnboundOnHome"))
            assertEquals("1", evaluate(view, "sendCount"))
            val response = JSONObject(evaluate(view, ArenaWebResponseScript.build(ArenaService.DEEPSEEK, "home-deepseek", requireIdentity = true)))
            assertEquals("HOME ANSWER", response.getString("finalText"))
        }
    }

    @Test fun changedDeepSeekConversationBeforeScheduledSubmitNeverClicks() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DEEPSEEK)
            evaluate(view, """
                history.pushState({},'', '/a/chat/s/first');
                window.send=()=>{sendCount++;};
                document.querySelector('textarea').addEventListener('input',()=>{
                  history.pushState({},'', '/a/chat/s/other');
                },{once:true});true;
            """.trimIndent())
            val done = CountDownLatch(1); val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPrompt(ArenaService.DEEPSEEK, "same question", "changed-deepseek") { outcome.set(it); done.countDown() } }
            assertTrue(done.await(30, TimeUnit.SECONDS))
            assertFalse(outcome.get().toString(), outcome.get().success)
            assertEquals("0", evaluate(view, "sendCount"))
            assertEquals(ArenaWebMessageIdentity.scopeChangedDetail, outcome.get().detail)
            assertEquals("same question", evaluate(view, "document.querySelector('textarea').value"))
        }
    }

    @Test fun kimiFailedOptimisticRowAndLateReceiptDoNotRepeatInputOrSubmit() = verifyKimiMultilinePaste(0, failedOptimistic = true)

    @Test fun slowKimiPasteKeepsOneSubmissionAfterFocusCallbackDeadline() = verifyKimiMultilinePaste(15_000)

    @Test fun cancelledSlowKimiPasteCannotSendLaterOrBlockAnotherProvider() = verifyKimiMultilinePaste(15_000, cancel = true)

    @Test fun kimiSingleLineReplacesExistingLexicalDraft() = verifyKimiMultilinePaste(0, existingDraft = true, singleLine = true)

    @Test fun kimiMultilineReplacesExistingLexicalDraft() = verifyKimiMultilinePaste(0, existingDraft = true)

    @Test fun deferredKimiPasteCannotOverwriteAfterItsPreconditionsChange() {
        val interruptions = listOf(
            "window.__aiArenaCancelledRequests={'deferred-guard':true}",
            "editor.blur()",
            "editor.replaceWith(editor.cloneNode(true))",
            "editor.textContent='USER EDIT'",
            "window.getSelection().collapse(editor,0)",
        )
        interruptions.forEach { interrupt ->
            withPool(emptyMap()) { pool, views, _ ->
                val view = views.getValue(ArenaService.KIMI)
                evaluate(view, """
                    const editor=document.createElement('div');editor.contentEditable='true';
                    editor.className='chat-input-editor';editor.setAttribute('data-lexical-editor','true');
                    editor.textContent='OLD DRAFT';editor.style='min-height:80px';
                    document.querySelector('textarea').replaceWith(editor);
                    window.pastes=0;window.interrupted=false;
                    editor.addEventListener('paste',e=>{pastes++;e.preventDefault();});
                    document.addEventListener('selectionchange',()=>{
                      if(!interrupted && String(window.getSelection())==='OLD DRAFT') {
                        interrupted=true;$interrupt;
                      }
                    });true;
                """.trimIndent())
                val settled = CountDownLatch(1)
                onMain {
                    pool.sendPrompt(ArenaService.KIMI, "NEW QUESTION", "deferred-guard") {}
                }
                waitUntil("selection interception actually occurred") { evaluate(view, "interrupted") == "true" }
                onMain { view.postDelayed({ settled.countDown() }, 250L) }
                assertTrue(settled.await(8, TimeUnit.SECONDS))
                assertEquals(interrupt, "true", evaluate(view, "interrupted"))
                assertEquals(interrupt, "0", evaluate(view, "pastes"))
                assertEquals(interrupt, "0", evaluate(view, "sendCount"))
                assertEquals(if (interrupt.contains("USER EDIT")) "USER EDIT" else "OLD DRAFT",
                    evaluate(view, "document.querySelector('.chat-input-editor').textContent"))
                onMain { pool.cancelAutomation(ArenaService.KIMI) }
            }
        }
    }

    @Test fun temporaryProviderRowsWaitForStableIdsWithoutResending() {
        listOf(ArenaService.KIMI, ArenaService.DOUBAO).forEach { service ->
            listOf(false, true).forEach { remount ->
                verifyTemporaryProviderRow(service, remount, true, false)
            }
        }
    }

    @Test fun permanentlyUnnumberedProviderRowsNeverConfirmDelivery() {
        listOf(ArenaService.KIMI, ArenaService.DOUBAO).forEach { verifyTemporaryProviderRow(it, false, false, false) }
    }

    @Test fun cancelledTemporaryProviderRowsCannotConfirmLateDelivery() {
        listOf(ArenaService.KIMI, ArenaService.DOUBAO).forEach { verifyTemporaryProviderRow(it, true, true, true) }
    }

    private fun verifyTemporaryProviderRow(service: ArenaService, remount: Boolean, assignId: Boolean, cancel: Boolean) {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(service)
            evaluate(view, """
                window.send=()=>{
                  sendCount++;const input=document.querySelector('textarea');sentText=input.value;input.value='';
                  let row=document.createElement('div');
                  if (${service == ArenaService.KIMI}) row.className='chat-content-item-user';
                  else row.setAttribute('data-target-id','message-box-target-id');
                  const body=document.createElement('div');body.textContent=sentText;
                  if (${service == ArenaService.DOUBAO}) body.setAttribute('data-send-message-boundary','');
                  row.appendChild(body);document.body.appendChild(row);
                  window.earlyBound=false;
                  setTimeout(()=>{earlyBound=!!window.__aiArenaRequests?.['temporary-id']?.bound;},3000);
                  if ($assignId) setTimeout(()=>{
                    if ($remount) {const replacement=row.cloneNode(true);row.replaceWith(replacement);row=replacement;}
                    if (${service == ArenaService.KIMI}) row.setAttribute('data-conversation-turn-id','stable-id');
                    else {row.firstElementChild.setAttribute('data-message-id','stable-id');fixtureDoubaoMessage(row,sentText);}
                  },4000);
                };true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain {
                pool.sendPrompt(service, "temporary row question", "temporary-id") { outcome.set(it);done.countDown() }
            }
            // Wait for the actual send, then cancel before its website ID becomes available.
            if (cancel) {
                val sentDeadline = System.currentTimeMillis() + 12_000
                while (evaluate(view, "sendCount") == "0" && System.currentTimeMillis() < sentDeadline) Thread.sleep(100)
                assertEquals("1", evaluate(view, "sendCount"))
                onMain { pool.cancelAutomation(service) }
                val settled = CountDownLatch(1)
                onMain { view.postDelayed({ settled.countDown() }, 5_000L) }
                assertTrue(settled.await(8, TimeUnit.SECONDS))
                assertNull(outcome.get())
            } else {
                assertTrue(done.await(30, TimeUnit.SECONDS))
                assertEquals(outcome.get().toString(), assignId, outcome.get().success)
                assertEquals("false", evaluate(view, "earlyBound"))
                if (assignId) assertEquals("stable-id", evaluate(view, "window.__aiArenaRequests['temporary-id'].boundUserId"))
            }
            assertEquals("1", evaluate(view, "sendCount"))
        }
    }

    @Test fun cancelledPendingKimiInputDoesNotPollOrCompleteDuringItsReplacement() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.KIMI)
            evaluate(view, """
                const editor=document.createElement('div');editor.contentEditable='true';
                editor.className='chat-input-editor';editor.setAttribute('data-lexical-editor','true');
                editor.textContent='OLD DRAFT';editor.style='min-height:80px';document.querySelector('textarea').replaceWith(editor);
                window.pastes=0;window.oldProbeReads=0;
                editor.addEventListener('paste',e=>{
                  e.preventDefault();pastes++;
                  if(pastes===1) {
                    const old=window.__aiArenaRequests['pending-a'];
                    Object.defineProperty(old,'inputFailure',{get(){oldProbeReads++;return undefined;}});
                  } else editor.textContent=e.clipboardData.getData('text/plain');
                });
                window.send=()=>{
                  sendCount++;sentText=editor.innerText;editor.textContent='';
                  const user=document.createElement('div');user.className='chat-content-item-user';
                  user.setAttribute('data-conversation-turn-id','replacement-id');user.textContent=sentText;document.body.appendChild(user);
                };true;
            """.trimIndent())
            val oldOutcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPrompt(ArenaService.KIMI, "QUESTION A", "pending-a") { oldOutcome.set(it) } }
            val deadline = System.currentTimeMillis() + 12_000
            while (evaluate(view, "pastes") == "0" && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertEquals("1", evaluate(view, "pastes"))
            val before = evaluate(view, "oldProbeReads").toInt()
            val done = CountDownLatch(1)
            val next = AtomicReference<SendOutcome>()
            onMain {
                pool.cancelAutomation(ArenaService.KIMI)
                pool.sendPrompt(ArenaService.KIMI, "QUESTION B", "pending-b") { next.set(it);done.countDown() }
            }
            assertTrue(done.await(20, TimeUnit.SECONDS))
            assertTrue(next.get().toString(), next.get().success)
            assertNull(oldOutcome.get())
            assertTrue("Only an already queued old probe may finish", evaluate(view, "oldProbeReads").toInt() <= before + 1)
            assertEquals("1", evaluate(view, "sendCount"))
            assertEquals("QUESTION B", evaluate(view, "sentText"))
        }
    }

    private fun verifyKimiMultilinePaste(busyMillis: Int, cancel: Boolean = false, existingDraft: Boolean = false, singleLine: Boolean = false, failedOptimistic: Boolean = false) {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.KIMI)
            evaluate(view, """
                const old=document.querySelector('textarea'),editor=document.createElement('div');
                editor.contentEditable='true';editor.className='chat-input-editor';editor.setAttribute('data-lexical-editor','true');
                editor.style='white-space:pre-wrap;min-height:80px';old.replaceWith(editor);
                window.pastes=0;window.pastedText='';
                window.enterEvents=0;editor.addEventListener('keydown',e=>{if(e.key==='Enter')enterEvents++;});editor.addEventListener('keyup',e=>{if(e.key==='Enter')enterEvents++;});
                editor.textContent=$existingDraft ? 'OLD DRAFT MUST BE REPLACED' : '';
                let editorSelection='';
                // Model Lexical's committed selection independently of the DOM selection.
                document.addEventListener('selectionchange',()=>{
                  const selected=String(window.getSelection());
                  setTimeout(()=>{editorSelection=selected;},40);
                });
                if ($existingDraft) editor.addEventListener('beforeinput',e=>{
                  if(e.inputType==='insertText') e.preventDefault();
                });
                editor.addEventListener('paste',e=>{
                  e.preventDefault();pastes++;pastedText=e.clipboardData.getData('text/plain');
                  const until=Date.now()+$busyMillis;while(Date.now()<until){}
                  const replaced=(!$existingDraft || editorSelection===editor.textContent) ? pastedText : editor.textContent+pastedText;
                  setTimeout(()=>{editor.textContent=replaced;},150);
                });
                window.send=()=>{
                  sendCount++;sentText=editor.innerText;
                  const user=document.createElement('div');user.className='chat-content-item-user';user.setAttribute('data-conversation-turn-id','fixture-user-'+sendCount);user.setAttribute('data-conversation-turn-id','multiline');
                  user.textContent=sentText;editor.textContent='';
                  if ($failedOptimistic) {
                    const failed=user.cloneNode(true);failed.classList.add('awaiting-failure');failed.setAttribute('data-conversation-turn-id','failed-send-fixture');document.body.appendChild(failed);
                    setTimeout(()=>document.body.appendChild(user),3000);
                  } else document.body.appendChild(user);
                };true;
            """.trimIndent())
            val prompt = if (singleLine) "NEW SINGLE LINE QUESTION" else "第一行 <不是 HTML>\n第二行 & 中文\n\n第四行"
            if (cancel) evaluate(views.getValue(ArenaService.DEEPSEEK), """
                window.send=()=>{
                  sendCount++;const input=document.querySelector('textarea');sentText=input.value;input.value='';
                  window.history.pushState({},'', '/a/chat/s/fixture');
                  const history=document.createElement('div');history.className='ds-virtual-list-visible-items';
                  const user=document.createElement('div');user.setAttribute('data-virtual-list-item-key','-2');
                  user.innerHTML='<div class="ds-message"><div class="ds-collapsible-text"></div></div>';
                  user.querySelector('.ds-collapsible-text').textContent=sentText;history.appendChild(user);document.body.appendChild(history);
                };true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPrompt(ArenaService.KIMI, prompt, "multiline-kimi") { outcome.set(it); done.countDown() } }
            if (cancel) {
                val otherDone = CountDownLatch(1)
                val settled = CountDownLatch(1)
                val otherOutcome = AtomicReference<SendOutcome>()
                onMain {
                    view.postDelayed({
                        pool.cancelAutomation(ArenaService.KIMI)
                        pool.sendPrompt(ArenaService.DEEPSEEK, "after cancelled paste", "after-paste") {
                            otherOutcome.set(it); otherDone.countDown()
                        }
                    }, 7_000L)
                    view.postDelayed({ settled.countDown() }, 25_000L)
                }
                assertTrue(otherDone.await(35, TimeUnit.SECONDS))
                assertTrue(otherOutcome.get().toString(), otherOutcome.get().success)
                assertTrue(settled.await(30, TimeUnit.SECONDS))
                assertNull("Cancelled task must not report a late result", outcome.get())
                assertEquals("Paste must have started before cancellation", "1", evaluate(view, "pastes"))
                assertEquals("0", evaluate(view, "sendCount"))
                assertEquals("1", evaluate(views.getValue(ArenaService.DEEPSEEK), "sendCount"))
                return@withPool
            }
            assertTrue(done.await(38, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals(prompt, evaluate(view, "pastedText"))
            assertEquals(prompt, evaluate(view, "sentText"))
            assertEquals("1", evaluate(view, "pastes"))
            assertEquals("1", evaluate(view, "sendCount"))
            assertEquals("0", evaluate(view, "enterEvents"))
            if (failedOptimistic) assertEquals("multiline", evaluate(view, "window.__aiArenaRequests['multiline-kimi'].boundUserId"))
        }
    }

    @Test fun freshSlowLoadRetainsASeparateHydratedEditorBudget() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            val loaded = CountDownLatch(1)
            val page = """
                <textarea id="ssr" readonly></textarea><img src="/fresh-slow-resource">
                <script>addEventListener('load',()=>setTimeout(()=>{
                  document.querySelector('#ssr').outerHTML='<div class="tiptap ProseMirror" contenteditable="true"></div>';
                  window.editorReplaced=true;
                },1200));</script>
            """.trimIndent()
            onMain {
                val production = view.webViewClient
                view.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageStarted(v: WebView, url: String?, icon: android.graphics.Bitmap?) = production.onPageStarted(v, url, icon)
                    override fun onPageFinished(v: WebView, url: String?) { loaded.countDown(); production.onPageFinished(v, url) }
                    override fun shouldInterceptRequest(v: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse {
                        if (request.url.path == "/fresh-slow-resource") {
                            Thread.sleep(54_000L)
                            return android.webkit.WebResourceResponse("image/png", "UTF-8", byteArrayOf().inputStream())
                        }
                        return android.webkit.WebResourceResponse("text/html", "UTF-8", page.byteInputStream())
                    }
                }
            }
            val dispatched = CountDownLatch(1)
            val calls = AtomicInteger()
            val gateway = object : ArenaGateway by pool {
                override fun sendPromptWithAttachments(service: ArenaService, prompt: String, requestId: String,
                    attachments: List<ArenaAttachment>, callback: (SendOutcome) -> Unit) {
                    if (service == ArenaService.DOUBAO) { calls.incrementAndGet(); dispatched.countDown() }
                    callback(SendOutcome(false, requestId, "fixture stops at dispatch"))
                }
            }
            lateinit var controller: ArenaSessionController
            onMain { controller = ArenaSessionController(gateway); assertTrue(controller.startInitial("fresh slow load", members)) }
            try {
                assertFalse("A not-yet-loaded page cannot send", dispatched.await(3, TimeUnit.SECONDS))
                assertTrue("Real subresource load must finish", loaded.await(60, TimeUnit.SECONDS))
                assertTrue("Editor hydration gets its own budget after the 54 second load", dispatched.await(22, TimeUnit.SECONDS))
                assertEquals("true", evaluate(view, "window.editorReplaced === true"))
                assertEquals(1, calls.get())
            } finally { onMain { controller.destroy() } }
        }
    }

    @Test fun freshPageRejectsAssistantOnlyHistoryAndFailedUserPlaceholders() {
        val pages = listOf(
            ArenaService.KIMI to "<div class='chat-content-item-user awaiting-failure'>failed old prompt</div>",
            ArenaService.KIMI to "<div class='chat-content-item-assistant'>old answer</div>",
            ArenaService.DOUBAO to "<div data-target-id='message-box-target-id'><div data-reply-message>old answer</div></div>",
            ArenaService.DEEPSEEK to "<div class='ds-virtual-list-visible-items'><div><div class='ds-message'><div class='ds-markdown ds-assistant-message-main-content'>old answer</div></div></div></div>",
        )
        for ((service, history) in pages) withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(service)
            interceptFreshPage(view, "<textarea></textarea>$history")
            val done = CountDownLatch(1)
            val ready = AtomicBoolean(true)
            onMain { pool.openFreshConversation(service) { ready.set(it); done.countDown() } }
            assertTrue(done.await(92, TimeUnit.SECONDS))
            assertFalse("Restored history cannot become a new conversation: $service $history", ready.get())
            assertTrue(evaluate(view, "document.body.innerText").contains("old"))
        }
    }

    @Test fun freshEditorRejectsRestoredDraftWithoutChangingIt() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            interceptFreshPage(view, "<textarea>unsent existing draft</textarea>")
            val done = CountDownLatch(1)
            val ready = AtomicBoolean(true)
            onMain { pool.openFreshConversation(ArenaService.DOUBAO) { ready.set(it); done.countDown() } }
            assertTrue(done.await(92, TimeUnit.SECONDS))
            assertFalse("A root URL with an unsent draft is not fresh", ready.get())
            assertEquals("unsent existing draft", evaluate(view, "document.querySelector('textarea').value"))
        }
    }

    @Test fun freshEditorWaitsForVisibleEnabledUniqueCurrentInput() {
        for (body in listOf("<textarea disabled></textarea>", "<textarea readonly></textarea>",
            "<div style='opacity:0'><textarea></textarea></div>", "<textarea></textarea><textarea></textarea>")) {
            withPool(emptyMap()) { pool, views, _ ->
                val view = views.getValue(ArenaService.DOUBAO)
                interceptFreshPage(view, body)
                val done = CountDownLatch(1)
                val ready = AtomicBoolean(false)
                onMain { pool.openFreshConversation(ArenaService.DOUBAO) { ready.set(it); done.countDown() } }
                assertFalse("Invalid input must not become ready: $body", done.await(4, TimeUnit.SECONDS))
                evaluate(view, "document.body.innerHTML='<div id=live class=\"tiptap ProseMirror\" contenteditable=true></div>'; true")
                assertTrue(done.await(6, TimeUnit.SECONDS))
                assertTrue(ready.get())
                assertEquals("live", evaluate(view, "window.__aiArenaFreshPage.input.id"))
            }
        }
    }

    @Test fun freshEditorCancellationSettlesWhileJavascriptCallbackIsMissing() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = installHeldFreshView(pool, views.getValue(ArenaService.DOUBAO))
            val done = CountDownLatch(1)
            val ready = AtomicBoolean(true)
            val calls = AtomicInteger()
            onMain { pool.openFreshConversation(ArenaService.DOUBAO) { ready.set(it); calls.incrementAndGet(); done.countDown() } }
            assertTrue(view.probed.await(8, TimeUnit.SECONDS))
            onMain { pool.cancelAutomation(ArenaService.DOUBAO) }
            assertTrue("Cancellation must not depend on a JavaScript callback", done.await(1, TimeUnit.SECONDS))
            assertFalse(ready.get())
            onMain { view.held?.onReceiveValue("true") }
            Thread.sleep(500)
            assertEquals(1, calls.get())
        }
    }

    @Test fun freshEditorLateTrueCannotBeatAnExpiredDeadline() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = installHeldFreshView(pool, views.getValue(ArenaService.DOUBAO))
            val done = CountDownLatch(1)
            val ready = AtomicBoolean(true)
            val method = ArenaWebViewPool::class.java.declaredMethods.single { it.name == "waitForFreshPage" }.apply { isAccessible = true }
            onMain {
                val generation = (field(pool, "navigationGenerations") as Map<*, *>)[ArenaService.DOUBAO]
                method.invoke(pool, ArenaService.DOUBAO, view, generation, SystemClock.elapsedRealtime() + 300L,
                    { value: Boolean -> ready.set(value); done.countDown() })
            }
            assertTrue(view.probed.await(2, TimeUnit.SECONDS))
            Thread.sleep(500)
            onMain { view.held?.onReceiveValue("true") }
            assertTrue(done.await(1, TimeUnit.SECONDS))
            assertFalse("An expired probe cannot grant permission to send", ready.get())
        }
    }

    @Test fun freshEditorReplacedWebViewCannotAcceptLateTrue() {
        withPool(emptyMap()) { pool, views, _ ->
            val original = views.getValue(ArenaService.DOUBAO)
            val view = installHeldFreshView(pool, original)
            val done = CountDownLatch(1)
            val ready = AtomicBoolean(true)
            onMain { pool.openFreshConversation(ArenaService.DOUBAO) { ready.set(it); done.countDown() } }
            assertTrue(view.probed.await(8, TimeUnit.SECONDS))
            onMain {
                @Suppress("UNCHECKED_CAST")
                val map = field(pool, "webViews") as MutableMap<ArenaService, WebView>
                map[ArenaService.DOUBAO] = views.getValue(ArenaService.KIMI)
                view.held?.onReceiveValue("true")
                map[ArenaService.DOUBAO] = view
            }
            assertTrue(done.await(1, TimeUnit.SECONDS))
            assertFalse(ready.get())
        }
    }

    @Test fun freshEditorNativeWatchdogSettlesWithoutJavascriptReply() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = installHeldFreshView(pool, views.getValue(ArenaService.DOUBAO))
            val done = CountDownLatch(1)
            val ready = AtomicBoolean(true)
            val calls = AtomicInteger()
            onMain { pool.openFreshConversation(ArenaService.DOUBAO) { ready.set(it); calls.incrementAndGet(); done.countDown() } }
            assertTrue(view.probed.await(8, TimeUnit.SECONDS))
            assertFalse(done.await(2, TimeUnit.SECONDS))
            assertTrue("The independent editor budget must expire without any JS callback", done.await(90, TimeUnit.SECONDS))
            assertFalse(ready.get())
            onMain { view.held?.onReceiveValue("true") }
            Thread.sleep(500)
            assertEquals(1, calls.get())
        }
    }

    @Test fun freshDoubaoWaitsForTiptapEditorInsteadOfInterimTextarea() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            // Real 2026-09-23 sequence: a hydrated interim TEXTAREA stays for seconds, then tiptap replaces it.
            interceptFreshPage(view, """
                <textarea class="textarea-YelHeN" data-testid="chat_input_input" placeholder="发消息..."></textarea>
                <script>setTimeout(()=>{document.querySelector('textarea').outerHTML='<div id="rich" class="tiptap ProseMirror" contenteditable="true"></div>';window.swappedAt=Date.now();},5000);</script>
            """.trimIndent())
            val done = CountDownLatch(1)
            val ready = AtomicBoolean(false)
            onMain { pool.openFreshConversation(ArenaService.DOUBAO) { ready.set(it); done.countDown() } }
            assertFalse("The interim textarea is replaced later and must not become ready", done.await(4, TimeUnit.SECONDS))
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue(ready.get())
            assertEquals("rich", evaluate(view, "window.__aiArenaFreshPage.input.id"))
        }
    }

    @Test fun freshDoubaoLongInterimEditorIsNeverTypedInto() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            // Under a busy shared renderer thread the interim editor stayed ~16 s before tiptap mounted.
            interceptFreshPage(view, "<textarea data-testid=chat_input_input placeholder=发消息...></textarea>")
            val done = CountDownLatch(1)
            val ready = AtomicBoolean(false)
            onMain { pool.openFreshConversation(ArenaService.DOUBAO) { ready.set(it); done.countDown() } }
            assertFalse("A stable interim editor is still not ready", done.await(18, TimeUnit.SECONDS))
            evaluate(view, "document.querySelector('textarea').outerHTML='<div id=\"rich\" class=\"tiptap ProseMirror\" contenteditable=\"true\"></div>';true")
            assertTrue(done.await(8, TimeUnit.SECONDS))
            assertTrue(ready.get())
            assertEquals("rich", evaluate(view, "window.__aiArenaFreshPage.input.id"))
        }
    }

    @Test fun doubaoFollowupWaitsForTiptapWhenOnlyTheInterimEditorExists() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            evaluate(view, """
                const old=document.querySelector('textarea');old.setAttribute('data-testid','chat_input_input');
                window.send=()=>{window.sendCount++;};
                setTimeout(()=>{window.richAt=Date.now();const rich=document.createElement('div');rich.className='tiptap ProseMirror';rich.contentEditable='true';rich.id='rich';old.replaceWith(rich);},6000);
                true;
            """.trimIndent())
            val done = CountDownLatch(1)
            onMain { pool.sendPrompt(ArenaService.DOUBAO, "must not enter the interim box", "interim-followup") { done.countDown() } }
            Thread.sleep(5_000)
            assertEquals("The interim editor must stay untouched", "", evaluate(view, "document.querySelector('textarea')?.value ?? 'gone'"))
            assertTrue(done.await(45, TimeUnit.SECONDS))
            assertEquals("true", evaluate(view, "!!window.richAt"))
        }
    }

    @Test fun freshPageTransientHistoryDuringHydrationDoesNotFailTheRound() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            interceptFreshPage(view, """
                <div class="tiptap ProseMirror" contenteditable="true"></div>
                <div id="transient" data-target-id="message-box-target-id"><div data-reply-message>skeleton</div></div>
                <script>setTimeout(()=>document.getElementById('transient').remove(),2500);</script>
            """.trimIndent())
            val done = CountDownLatch(1)
            val ready = AtomicBoolean(false)
            onMain { pool.openFreshConversation(ArenaService.DOUBAO) { ready.set(it); done.countDown() } }
            assertTrue(done.await(12, TimeUnit.SECONDS))
            assertTrue("A single hydration sample with history must not decide the round", ready.get())
        }
    }

    @Test fun freshPreparingPageStaysLaidOutUntilSettled() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            interceptFreshPage(view, "<textarea></textarea><script>setTimeout(()=>document.body.innerHTML='<div class=\"tiptap ProseMirror\" contenteditable=true></div>',3000)</script>")
            onMain { pool.setProtectedServices(emptySet()) }
            waitUntil("idle page is not drawn") { var gone = false; onMain { gone = view.visibility == View.GONE }; gone }
            val done = CountDownLatch(1)
            val ready = AtomicBoolean(false)
            onMain { pool.openFreshConversation(ArenaService.DOUBAO) { ready.set(it); done.countDown() } }
            // Drawn but not interactive for the whole preparation: a GONE WebView gets ~1 frame per second.
            repeat(25) {
                onMain {
                    assertEquals(View.VISIBLE, view.visibility)
                    assertEquals(View.VISIBLE, pool.container.visibility)
                    assertFalse(pool.container.isClickable)
                    assertFalse(view.isFocusable)
                }
                Thread.sleep(100)
            }
            assertTrue(done.await(15, TimeUnit.SECONDS))
            assertTrue(ready.get())
            waitUntil("settled page returns to GONE") { var gone = false; onMain { gone = view.visibility == View.GONE }; gone }
        }
    }

    @Test fun roundMembersStayDrawnAndHiddenPagesDoNotTakeUserTaps() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            evaluate(view, "window.userTaps=0;document.addEventListener('pointerdown',()=>userTaps++,true);true")
            onMain { pool.setProtectedServices(setOf(ArenaService.DOUBAO)) }
            waitUntil("round member drawn") { var shown = false; onMain { shown = view.visibility == View.VISIBLE && pool.container.visibility == View.VISIBLE }; shown }
            // A tap that reaches the hidden container (e.g. through a blank Compose area) is swallowed.
            onMain {
                val now = SystemClock.uptimeMillis()
                listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
                    val event = android.view.MotionEvent.obtain(now, now, action, 50f, 50f, 0)
                    pool.container.dispatchTouchEvent(event); event.recycle()
                }
            }
            Thread.sleep(500)
            assertEquals("0", evaluate(view, "userTaps"))
            onMain { pool.setProtectedServices(emptySet()) }
            waitUntil("finished round member is not drawn") { var gone = false; onMain { gone = view.visibility == View.GONE }; gone }
        }
    }

    @Test fun doubaoBusyPageIsNotClickedUntilIdleThenSendsOnce() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            evaluate(view, """
                document.getElementById('input-engine-container').insertAdjacentHTML('beforeend','<button id="break" data-testid="chat_input_local_break_button" style="width:36px;height:36px">Stop</button>');
                window.send=()=>{
                  const number=++sendCount,raw=document.querySelector('textarea').value;
                  window.sentWhileBusy=!!document.getElementById('break');document.querySelector('textarea').value='';
                  const row=document.createElement('div');row.setAttribute('data-target-id','message-box-target-id');
                  row.innerHTML='<div data-send-message-boundary data-message-id="idle-'+number+'"><div class="bg-g-send">'+raw+'</div></div>';
                  document.body.appendChild(row);fixtureDoubaoMessage(row,raw);
                };true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPrompt(ArenaService.DOUBAO, "question after the previous answer ends", "busy-then-idle") { outcome.set(it); done.countDown() } }
            assertFalse(done.await(8, TimeUnit.SECONDS))
            assertEquals("A busy Doubao page would queue the question", "0", evaluate(view, "sendCount"))
            assertEquals("question after the previous answer ends", evaluate(view, "document.querySelector('textarea').value"))
            evaluate(view, "document.getElementById('break').remove();true")
            assertTrue(done.await(20, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("1", evaluate(view, "sendCount"))
            assertEquals("false", evaluate(view, "window.sentWhileBusy"))
        }
    }

    @Test fun doubaoPersistentQueueReportsNotSentWithoutClicking() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            evaluate(view, """
                document.body.insertAdjacentHTML('beforeend','<div data-testid="queue-message-item" data-item-id="queue-item-old" data-item-status="Pending">older queued question</div>');
                true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPrompt(ArenaService.DOUBAO, "must not join the website queue", "persistent-queue") { outcome.set(it); done.countDown() } }
            assertTrue(done.await(55, TimeUnit.SECONDS))
            assertFalse(outcome.get().success)
            assertTrue(outcome.get().detail, outcome.get().detail.contains("未发送"))
            assertEquals("0", evaluate(view, "sendCount"))
        }
    }

    private fun interceptFreshPage(view: WebView, page: String) {
        onMain {
            val production = view.webViewClient
            view.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageStarted(v: WebView, url: String?, icon: android.graphics.Bitmap?) = production.onPageStarted(v, url, icon)
                override fun onPageFinished(v: WebView, url: String?) = production.onPageFinished(v, url)
                override fun shouldInterceptRequest(v: WebView, request: android.webkit.WebResourceRequest) =
                    android.webkit.WebResourceResponse("text/html", "UTF-8", page.byteInputStream())
            }
        }
    }

    private class HeldFreshWebView(context: android.content.Context) : WebView(context) {
        val probed = CountDownLatch(1)
        var held: android.webkit.ValueCallback<String>? = null
        override fun evaluateJavascript(script: String, callback: android.webkit.ValueCallback<String>?) {
            if (script.contains("__aiArenaFreshPage")) { held = callback; probed.countDown() }
            else super.evaluateJavascript(script, callback)
        }
    }

    private fun installHeldFreshView(pool: ArenaWebViewPool, original: WebView): HeldFreshWebView {
        lateinit var replacement: HeldFreshWebView
        onMain {
            replacement = HeldFreshWebView(original.context)
            replacement.settings.javaScriptEnabled = true
            replacement.webViewClient = original.webViewClient
            @Suppress("UNCHECKED_CAST")
            val map = field(pool, "webViews") as MutableMap<ArenaService, WebView>
            map[ArenaService.DOUBAO] = replacement
            pool.container.removeView(original)
            original.stopLoading(); original.destroy()
            pool.container.addView(replacement, FrameLayout.LayoutParams(-1, -1))
        }
        interceptFreshPage(replacement, "<textarea></textarea>")
        return replacement
    }

    @Test fun freshPageWaitsForLateEditorHydrationUnderSharedBudget() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            val page = "<script>setTimeout(()=>document.body.innerHTML='<div class=\"tiptap ProseMirror\" contenteditable=true></div>',12000)</script>"
            onMain {
                val productionClient = view.webViewClient
                view.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, icon: android.graphics.Bitmap?) = productionClient.onPageStarted(view, url, icon)
                    override fun onPageFinished(view: WebView, url: String?) = productionClient.onPageFinished(view, url)
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse =
                        android.webkit.WebResourceResponse("text/html", "UTF-8", page.byteInputStream())
                }
            }
            val done = CountDownLatch(1)
            val fresh = AtomicBoolean(false)
            onMain { pool.openFreshConversation(ArenaService.DOUBAO) { fresh.set(it); done.countDown() } }
            assertFalse(done.await(2, TimeUnit.SECONDS))
            assertTrue(done.await(22, TimeUnit.SECONDS))
            assertTrue("An editor hydrated after the old eight second probe window is usable", fresh.get())
        }
    }

    @Test fun freshPageCanCompleteLoadingAfterTwentySecondsWithoutSendingEarly() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            onMain {
                val productionClient = view.webViewClient
                view.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, icon: android.graphics.Bitmap?) = productionClient.onPageStarted(view, url, icon)
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.postDelayed({ productionClient.onPageFinished(view, url) }, 26_000L)
                    }
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse =
                        android.webkit.WebResourceResponse("text/html", "UTF-8", "<div class='tiptap ProseMirror' contenteditable='true'></div>".byteInputStream())
                }
            }
            val done = CountDownLatch(1)
            val gateway = object : ArenaGateway by pool {
                override fun sendPromptWithAttachments(service: ArenaService, prompt: String, requestId: String,
                    attachments: List<ArenaAttachment>, callback: (SendOutcome) -> Unit) {
                    if (service == ArenaService.DOUBAO) done.countDown()
                    callback(SendOutcome(false, requestId, "fixture stops at dispatch"))
                }
            }
            lateinit var controller: ArenaSessionController
            onMain { controller = ArenaSessionController(gateway); assertTrue(controller.startInitial("slow fresh", listOf(ArenaService.DEEPSEEK, ArenaService.DOUBAO))) }
            try {
                assertFalse("Must await the owned navigation", done.await(2, TimeUnit.SECONDS))
                assertTrue("Controller must retain a valid fresh page after 25 seconds", done.await(38, TimeUnit.SECONDS))
            } finally { onMain { controller.destroy() } }
        }
    }

    @Test fun busyKimiRendererCanConfirmOneSubmissionAfterShortCallbackDeadline() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.KIMI)
            evaluate(view, """
                window.send=()=>{
                  window.sendCount++;const text=document.querySelector('textarea').value;document.querySelector('textarea').value='';
                  const until=Date.now()+15000;while(Date.now()<until){}
                  const user=document.createElement('div');user.className='chat-content-item-user';user.setAttribute('data-conversation-turn-id','fixture-user-'+sendCount);
                  user.setAttribute('data-conversation-turn-id','busy-accepted');user.textContent=text;document.body.appendChild(user);
                };true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPrompt(ArenaService.KIMI, "busy current question", "busy-kimi") { outcome.set(it); done.countDown() } }
            assertTrue(done.await(35, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("1", evaluate(view, "sendCount"))
        }
    }

    @Test fun lostCursorCannotConfirmOldAnswerOrSubmitAgain() {
        for (service in listOf(ArenaService.DOUBAO, ArenaService.KIMI)) {
            withPool(emptyMap()) { pool, views, _ ->
                val view = views.getValue(service)
                val oldRows = if (service == ArenaService.KIMI)
                    "<div class='chat-content-item-user'>old question</div><div class='chat-content-item-assistant'><div class='markdown-container'>old answer</div></div>"
                else "<div class='v_list_row' data-observe-row><div class='bg-g-send'>old question</div></div><div class='v_list_row' data-observe-row><div class='md-box-root'>old answer</div></div>"
                evaluate(view, """
                    window.send=()=>{
                      window.sendCount++;
                      delete window.__aiArenaRequests;delete window.__aiArenaSendClicks;
                      sessionStorage.clear();
                      document.body.insertAdjacentHTML('beforeend',${JSONObject.quote(oldRows)});
                    };true;
                """.trimIndent())
                val done = CountDownLatch(1)
                val outcome = AtomicReference<SendOutcome>()
                onMain { pool.sendPrompt(service, "current question", "lost-cursor") { outcome.set(it); done.countDown() } }
                assertTrue(done.await(35, TimeUnit.SECONDS))
                assertFalse(outcome.get().toString(), outcome.get().success)
                assertEquals("Must not retry a submitted question after losing both cursor stores", "1", evaluate(view, "sendCount"))
                assertEquals("false", evaluate(view, ArenaWebCursorScript.bind(service, "lost-cursor", requireIdentity = true)))
                val response = JSONObject(evaluate(view, ArenaWebResponseScript.build(service, "lost-cursor", requireIdentity = true)))
                assertFalse(response.getBoolean("found"))
                assertEquals("", response.getString("text"))
                assertTrue(response.getString("error").contains("定位信息已丢失"))
            }
        }
    }

    @Test fun kimiLateReceiptCannotBindPreviousAnswerOrClickTwice() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.KIMI)
            evaluate(view, """
                document.body.insertAdjacentHTML('beforeend','<div class="chat-content-item-user" data-conversation-turn-id="old">old question</div><div class="chat-content-item-assistant"><div class="markdown-container">old answer</div></div>');
                window.send=()=>{
                  window.sendCount++;const text=document.querySelector('textarea').value;document.querySelector('textarea').value='';
                  setTimeout(()=>{const user=document.createElement('div');user.className='chat-content-item-user';user.setAttribute('data-conversation-turn-id','fixture-user-'+sendCount);user.setAttribute('data-conversation-turn-id','new');user.textContent=text;document.body.appendChild(user);
                    const answer=document.createElement('div');answer.className='chat-content-item-assistant';answer.innerHTML='<div class="markdown-container">new complete answer</div><div class="segment-assistant-actions" style="height:30px"><button>Copy</button></div>';document.body.appendChild(answer);},4500);
                };true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPrompt(ArenaService.KIMI, "new question", "late-kimi-receipt") { outcome.set(it); done.countDown() } }
            assertTrue(done.await(18, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("1", evaluate(view, "sendCount"))
            val response = AtomicReference<ResponseSnapshot>()
            val read = CountDownLatch(1)
            onMain { pool.readResponse(ArenaService.KIMI, "late-kimi-receipt") { response.set(it); read.countDown() } }
            assertTrue(read.await(5, TimeUnit.SECONDS))
            assertEquals("new complete answer", response.get().text)
        }
    }

    @Test fun kimiEditorReplacementDuringReadinessReceivesOnlyCurrentPrompt() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.KIMI)
            evaluate(view, """
                const old=document.querySelector('textarea');old.value='old restored draft';
                setTimeout(()=>{const replacement=old.cloneNode(true);replacement.value='restored draft';old.replaceWith(replacement);window.editorReplaced=true;},1200);
                window.send=()=>{window.sendCount++;window.sentText=document.querySelector('textarea').value;
                  const user=document.createElement('div');user.className='chat-content-item-user';user.setAttribute('data-conversation-turn-id','fixture-user-'+sendCount);user.textContent=window.sentText;document.body.appendChild(user);document.querySelector('textarea').value='';};true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPrompt(ArenaService.KIMI, "current replacement question", "replaced-kimi-editor") { outcome.set(it); done.countDown() } }
            assertTrue(done.await(18, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("true", evaluate(view, "window.editorReplaced"))
            assertEquals("current replacement question", evaluate(view, "window.sentText"))
            assertEquals("1", evaluate(view, "sendCount"))
        }
    }

    @Test fun doubaoWholeDocumentNavigationRetainsOneSubmission() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            val page = """
                <textarea>navigation question</textarea><button id="flow-end-msg-send" onclick="sessionStorage.setItem('duplicateClicks',String(Number(sessionStorage.getItem('duplicateClicks')||0)+1))">Send</button>
                <script>${ArenaReactFixture.script}setTimeout(()=>{document.body.insertAdjacentHTML('beforeend','<div data-target-id="message-box-target-id"><div data-send-message-boundary data-message-id="new"><span class="bg-g-send">navigation question</span></div></div>');fixtureDoubaoMessage(document.querySelector('[data-target-id=message-box-target-id]'),'navigation question');},5000);</script>
            """.trimIndent()
            onMain {
                val productionClient = view.webViewClient
                view.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, icon: android.graphics.Bitmap?) = productionClient.onPageStarted(view, url, icon)
                    override fun onPageFinished(view: WebView, url: String?) = productionClient.onPageFinished(view, url)
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse =
                        android.webkit.WebResourceResponse("text/html", "UTF-8", page.byteInputStream())
                }
            }
            evaluate(view, "window.send=()=>{sessionStorage.setItem('firstClicks',String(Number(sessionStorage.getItem('firstClicks')||0)+1));location.href='https://www.doubao.com/chat/?receipt=1';};true;")
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPrompt(ArenaService.DOUBAO, "navigation question", "navigation-one-click") { outcome.set(it); done.countDown() } }
            assertTrue(done.await(23, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("1", evaluate(view, "sessionStorage.getItem('firstClicks')"))
            assertEquals("0", evaluate(view, "sessionStorage.getItem('duplicateClicks')||'0'"))
        }
    }

    @Test fun freshConversationRejectsLateRestoredHistoryAndCancelledNavigation() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.KIMI)
            val page = """
                <textarea placeholder="Message"></textarea>
                <script>setTimeout(()=>{document.body.insertAdjacentHTML('beforeend','<div class="chat-content-item-user" data-conversation-turn-id="old">restored old question</div>');},1300);</script>
            """.trimIndent()
            onMain {
                val productionClient = view.webViewClient
                view.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, icon: android.graphics.Bitmap?) = productionClient.onPageStarted(view, url, icon)
                    override fun onPageFinished(view: WebView, url: String?) = productionClient.onPageFinished(view, url)
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse =
                        android.webkit.WebResourceResponse("text/html", "UTF-8", page.byteInputStream())
                }
            }
            val done = CountDownLatch(1)
            val fresh = AtomicBoolean(true)
            onMain { pool.openFreshConversation(ArenaService.KIMI) { fresh.set(it); done.countDown() } }
            assertTrue(done.await(92, TimeUnit.SECONDS))
            assertFalse("A root URL whose history hydrates later is not a fresh chat", fresh.get())
            val cancelled = CountDownLatch(1)
            val calls = AtomicInteger()
            onMain {
                pool.openFreshConversation(ArenaService.KIMI) { fresh.set(it); calls.incrementAndGet(); cancelled.countDown() }
                pool.cancelAutomation()
            }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertFalse(fresh.get())
            Thread.sleep(2200)
            assertEquals("An old completion must not settle navigation twice", 1, calls.get())
        }
    }

    @Test fun doubaoAttachmentUsesBrowserInputWithPrivateEditorAndPreservesChineseLines() {
        withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.DOUBAO)
            installPrivateDoubaoEditor(view)
            val prompt = "请检查附件里的编号。\n第二行：中文与 English 123。\n\n最后一行：保留空行。"
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.DOUBAO, prompt, "browser-input-lines", listOf(attachment)) { outcome.set(it); done.countDown() } }
            assertTrue(done.await(14, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("The website's private setter must not bypass its browser input path", "0", evaluate(view, "privateSetCalls"))
            val events = JSONArray(evaluate(view, "JSON.stringify(browserInputs)"))
            assertTrue("Chromium must emit an input event for the inserted message", events.length() > 0)
            assertTrue("A synthetic input event is not the browser editing path", (0 until events.length()).any { events.getJSONObject(it).getBoolean("trusted") })
            android.util.Log.i("ArenaBrowserInputEvidence", evaluate(view, "JSON.stringify({inputs:browserInputs,sentHtml,sentInnerText,sentText})"))
            assertEquals(prompt, evaluate(view, "sentText"))
            assertEquals("true", evaluate(view, "sendTrusted"))
            assertEquals("1", evaluate(view, "sendCount"))
            verifyBytes(view, attachment)
        }
    }

    @Test fun doubaoAttachmentBrowserInputFailureDoesNotFallbackOrSend() {
        withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.DOUBAO)
            installPrivateDoubaoEditor(view)
            evaluate(view, "const command=document.execCommand.bind(document);document.execCommand=(name,...args)=>name==='insertText'?false:command(name,...args);true")
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.DOUBAO, "不能伪造成功\n第二行", "browser-input-failure", listOf(attachment)) { outcome.set(it); done.countDown() } }
            assertTrue(done.await(14, TimeUnit.SECONDS))
            assertFalse(outcome.get().toString(), outcome.get().success)
            assertTrue(outcome.get().detail, outcome.get().detail.contains("浏览器输入失败"))
            assertEquals("0", evaluate(view, "privateSetCalls"))
            assertEquals("0", evaluate(view, "browserInputs.length"))
            assertEquals("", evaluate(view, "document.getElementById('chat-input').innerText"))
            assertEquals("0", evaluate(view, "sendCount"))
            assertEquals("null", evaluate(view, "sendTrusted"))
            verifyBytes(view, attachment)
        }
    }

    private fun installPrivateDoubaoEditor(view: WebView) {
        evaluate(view, """
            const old=document.getElementById('chat-input'),input=document.createElement('div');
            input.id='chat-input';input.className='tiptap ProseMirror';input.contentEditable='true';
            input.style='min-height:90px;width:240px;white-space:pre-wrap';old.replaceWith(input);
            window.privateSetCalls=0;window.browserInputs=[];window.sendTrusted=null;
            input.editor={commands:{setContent(doc){privateSetCalls++;input.innerText=doc.content.map(p=>(p.content||[]).map(t=>t.text).join('')).join('\n');return true;},focus(){input.focus();}}};
            input.addEventListener('input',e=>browserInputs.push({trusted:e.isTrusted,type:e.inputType,data:e.data}));
            document.getElementById('flow-end-msg-send').addEventListener('click',e=>sendTrusted=e.isTrusted,true);
            // Model the site's paragraph serialization. Chromium's raw innerText counts
            // an empty <div><br></div> twice under pre-wrap, although it is one empty block.
            const editorText=()=>{
              const lines=[''];let first=true;
              for(const node of input.childNodes){
                if(node.nodeType===Node.TEXT_NODE)lines[lines.length-1]+=node.nodeValue;
                else if(node.nodeName==='BR')lines.push('');
                else if(node.nodeName==='DIV'||node.nodeName==='P'){
                  if(first)lines[0]=node.textContent;else lines.push(node.textContent);
                }else throw Error('Unexpected fixture editor node');
                first=false;
              }
              return lines.join('\n');
            };
            window.send=()=>{
              const text=editorText();if(!text.trim())return;
              if(!fileStates.length||fileStates.some(f=>f.status!=='Normal'||f.parseState!==1))throw Error('send before attachment ready');
              window.sentText=text;window.sentHtml=input.innerHTML;window.sentInnerText=input.innerText;window.sendCount++;input.replaceChildren();
            };true;
        """.trimIndent())
    }

    @Test fun doubaoAttachmentUsesOneTrustedSendAndReleasesBeforeLateDelivery() {
        withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.DOUBAO)
            evaluate(view, """
                const button=document.getElementById('flow-end-msg-send'),click=button.click.bind(button);
                window.scriptClicks=0;window.enterEvents=0;window.sendEvents=[];window.pendingDelivery=0;
                button.click=()=>{scriptClicks++;click();};
                document.addEventListener('keydown',e=>{if(e.key==='Enter')enterEvents++;},true);
                button.addEventListener('click',e=>sendEvents.push({trusted:e.isTrusted,focus:document.hasFocus(),visible:document.visibilityState}),true);
                window.send=()=>{const input=document.querySelector('textarea'),text=input.value;if(!text)return;
                  pendingDelivery++;setTimeout(()=>{window.focusDuringDelivery=document.hasFocus();window.sentText=text;window.sendCount++;input.value='';pendingDelivery--;},1000);};true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.DOUBAO, "native-exactly-once", "native-exactly-once", listOf(attachment)) { outcome.set(it); done.countDown() } }
            assertTrue(done.await(14, TimeUnit.SECONDS))
            waitUntil("all delayed fixture deliveries finish") { evaluate(view, "pendingDelivery") == "0" }
            assertTrue(outcome.get().toString(), outcome.get().success)
            val events = JSONArray(evaluate(view, "JSON.stringify(sendEvents)"))
            assertEquals(1, events.length())
            assertTrue("The send itself must be a Chromium trusted click", events.getJSONObject(0).getBoolean("trusted"))
            assertTrue(events.getJSONObject(0).getBoolean("focus"))
            assertEquals("visible", events.getJSONObject(0).getString("visible"))
            assertEquals("0", evaluate(view, "scriptClicks"))
            assertEquals("0", evaluate(view, "enterEvents"))
            assertEquals("false", evaluate(view, "focusDuringDelivery"))
            verifyBytes(view, attachment)
            assertEquals("1", evaluate(view, "sendCount"))
        }
    }

    @Test fun nativeDoubaoDisabledSendWaitsWithoutReservingOrFallingBackToScript() {
        withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.DOUBAO)
            evaluate(view, """
                const button=document.getElementById('flow-end-msg-send'),click=button.click.bind(button);button.disabled=true;
                window.scriptClicks=0;window.prematureReservation=null;window.sendTrusted=null;
                button.click=()=>{scriptClicks++;click();};button.addEventListener('click',e=>sendTrusted=e.isTrusted,true);
                document.querySelector('textarea').addEventListener('input',()=>{
                  setTimeout(()=>{prematureReservation=!!window.__aiArenaSendClicks?.['native-disabled'];},600);
                  setTimeout(()=>{button.disabled=false;},1200);
                },{once:true});true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.DOUBAO, "native-disabled", "native-disabled", listOf(attachment)) { outcome.set(it); done.countDown() } }
            assertTrue(done.await(14, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("false", evaluate(view, "prematureReservation"))
            assertEquals("true", evaluate(view, "sendTrusted"))
            assertEquals("0", evaluate(view, "scriptClicks"))
            verifyBytes(view, attachment)
            assertEquals("1", evaluate(view, "sendCount"))
        }
    }

    @Test fun nativeDoubaoChangeLeavesPlainTextAndOtherProvidersOnTheirExistingPath() {
        withPool(emptyMap()) { pool, views, attachment ->
            val doubao = views.getValue(ArenaService.DOUBAO)
            val kimi = views.getValue(ArenaService.KIMI)
            evaluate(doubao, """
                window.send=()=>{
                  const input=document.querySelector('textarea');window.sentText=input.value;window.sendCount++;input.value='';
                  const row=document.createElement('div');row.setAttribute('data-target-id','message-box-target-id');
                  const user=document.createElement('div');user.setAttribute('data-send-message-boundary','');user.setAttribute('data-message-id','plain-accepted');
                  user.textContent=sentText;row.appendChild(user);document.body.appendChild(row);fixtureDoubaoMessage(row,sentText);
                };true;
            """.trimIndent())
            listOf(doubao, kimi).forEach { view -> evaluate(view, "const sendButton=document.querySelector('.send-msg-btn');window.sendTrusted=null;sendButton.addEventListener('click',e=>sendTrusted=e.isTrusted,true);true") }
            val done = CountDownLatch(2)
            val outcomes = mutableListOf<SendOutcome>()
            onMain {
                pool.sendPrompt(ArenaService.DOUBAO, "plain-text-original", "plain-text-original") { outcomes += it; done.countDown() }
                pool.sendPromptWithAttachments(ArenaService.KIMI, "other-provider-original", "other-provider-original", listOf(attachment)) { outcomes += it; done.countDown() }
            }
            assertTrue(done.await(14, TimeUnit.SECONDS))
            onMain { assertTrue(outcomes.toString(), outcomes.all { it.success }) }
            listOf(doubao, kimi).forEach { view ->
                assertEquals("false", evaluate(view, "sendTrusted"))
                assertEquals("false", evaluate(view, "!!window.__aiArenaNativeSend"))
                assertEquals("1", evaluate(view, "sendCount"))
            }
            verifyBytes(kimi, attachment)
        }
    }

    @Test fun nativeDoubaoCancellationStopsLateUpAndAllowsReplacementAndKimi() {
        withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.DOUBAO)
            val cancelled = AtomicBoolean()
            val oldCallbacks = AtomicInteger()
            val downCount = AtomicInteger()
            val upCount = AtomicInteger()
            val cancelCount = AtomicInteger()
            val done = CountDownLatch(2)
            val outcomes = mutableListOf<SendOutcome>()
            var nativeGesture = false
            onMain {
                view.setOnTouchListener { _, event ->
                    @Suppress("UNCHECKED_CAST")
                    val active = (field(pool, "automations") as Map<ArenaService, Any>)[ArenaService.DOUBAO]
                    if (event.action == android.view.MotionEvent.ACTION_DOWN && active != null && optionalField(active, "cancelNativeTouch") != null) {
                        nativeGesture = true; downCount.incrementAndGet()
                        if (cancelled.compareAndSet(false, true)) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            pool.cancelAutomation(ArenaService.DOUBAO)
                            view.evaluateJavascript("cards.innerHTML='';fileStates.length=0;received=[];upload.value='';document.querySelector('textarea').value='';true") {
                                pool.sendPromptWithAttachments(ArenaService.DOUBAO, "native-replacement", "native-replacement", listOf(attachment)) { result -> outcomes += result; done.countDown() }
                            }
                        }, 40L)
                    }
                    if (nativeGesture && event.action == android.view.MotionEvent.ACTION_UP) { nativeGesture = false; upCount.incrementAndGet() }
                    if (nativeGesture && event.action == android.view.MotionEvent.ACTION_CANCEL) { nativeGesture = false; cancelCount.incrementAndGet() }
                    false
                }
                pool.sendPromptWithAttachments(ArenaService.DOUBAO, "native-cancelled", "native-cancelled", listOf(attachment)) { oldCallbacks.incrementAndGet() }
                pool.sendPromptWithAttachments(ArenaService.KIMI, "kimi-survives-native-cancel", "kimi-survives-native-cancel", listOf(attachment)) { outcomes += it; done.countDown() }
            }
            assertTrue("The native DOWN must be observed before cancellation", run { val deadline=SystemClock.elapsedRealtime()+8_000L; while(!cancelled.get()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);cancelled.get() })
            assertTrue(done.await(15, TimeUnit.SECONDS))
            onMain { assertTrue(outcomes.toString(), outcomes.all { it.success }) }
            assertEquals(0, oldCallbacks.get())
            assertEquals(2, downCount.get())
            assertEquals(1, cancelCount.get())
            assertEquals("Only the replacement may receive a final UP", 1, upCount.get())
            assertEquals("native-replacement", evaluate(view, "sentText"))
            assertEquals("1", evaluate(view, "sendCount"))
            verifyBytes(view, attachment)
            verifyBytes(views.getValue(ArenaService.KIMI), attachment)
        }
    }

    @Test fun nativeDoubaoAmbiguousSendButtonsStopWithoutClickingOrTextFallback() {
        withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.DOUBAO)
            evaluate(view, "const button=document.getElementById('flow-end-msg-send');button.after(button.cloneNode(true));true")
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.DOUBAO, "ambiguous-native", "ambiguous-native", listOf(attachment)) { outcome.set(it); done.countDown() } }
            assertTrue(done.await(12, TimeUnit.SECONDS))
            assertFalse(outcome.get().toString(), outcome.get().success)
            assertTrue(outcome.get().detail, outcome.get().detail.contains("唯一"))
            assertEquals("0", evaluate(view, "sendCount"))
            verifyBytes(view, attachment)
        }
    }

    @Test fun cancelledNativeUpKeepsGuardUntilTheRealQueuedClickIsRejected() {
        withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.DOUBAO)
            evaluate(view, "window.lateTrustedClicks=0;document.addEventListener('click',e=>{if(e.isTrusted&&e.target.closest('#flow-end-msg-send'))lateTrustedClicks++;},true);true")
            val delayed = AtomicBoolean()
            val delivered = CountDownLatch(1)
            val kimiDone = CountDownLatch(1)
            val oldCallbacks = AtomicInteger()
            val kimiOutcome = AtomicReference<SendOutcome>()
            var nativeGesture = false
            onMain {
                pool.show(ArenaService.DOUBAO)
                view.setOnTouchListener { _, event ->
                    @Suppress("UNCHECKED_CAST")
                    val active = (field(pool, "automations") as Map<ArenaService, Any>)[ArenaService.DOUBAO]
                    if (event.action == android.view.MotionEvent.ACTION_DOWN && active != null && optionalField(active,"cancelNativeTouch") != null) nativeGesture = true
                    if (event.action == android.view.MotionEvent.ACTION_UP && nativeGesture && delayed.compareAndSet(false,true)) {
                        nativeGesture = false
                        val queued = android.view.MotionEvent.obtain(event)
                        val handler = android.os.Handler(android.os.Looper.getMainLooper())
                        handler.postDelayed({ pool.cancelAutomation(ArenaService.DOUBAO) },40L)
                        // Hold the UP at the View boundary, then let Chromium produce its real trusted click.
                        handler.postDelayed({ try { view.onTouchEvent(queued) } finally { queued.recycle(); delivered.countDown() } },250L)
                        true
                    } else false
                }
                pool.sendPromptWithAttachments(ArenaService.DOUBAO,"cancel-queued-click","cancel-queued-click",listOf(attachment)){oldCallbacks.incrementAndGet()}
                pool.sendPromptWithAttachments(ArenaService.KIMI,"kimi-after-queued-click","kimi-after-queued-click",listOf(attachment)){kimiOutcome.set(it);kimiDone.countDown()}
            }
            assertTrue("Fixture must deliver the real delayed native UP",delivered.await(10,TimeUnit.SECONDS))
            waitUntil("Chromium must actually emit the late trusted click") { evaluate(view,"lateTrustedClicks") == "1" }
            assertTrue(kimiDone.await(10,TimeUnit.SECONDS))
            assertTrue(kimiOutcome.get().toString(),kimiOutcome.get().success)
            assertEquals(0,oldCallbacks.get())
            assertEquals("Cancelled queued click must never reach the site's send handler","0",evaluate(view,"sendCount"))
            verifyBytes(views.getValue(ArenaService.KIMI),attachment)
        }
    }

    @Test fun nativeDoubaoSendRemeasuresChangedGeometryBeforeTheOnlyDown() {
        withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.DOUBAO)
            val changed = AtomicBoolean()
            val nativeDowns = AtomicInteger()
            val geometry = GeometryFixture {
                onMain {
                    if (field(pool, "focusAction") == null || !changed.compareAndSet(false, true)) return@onMain
                    val left=view.left;val top=view.top;val right=view.right;val bottom=view.bottom
                    view.layout(left,top,right,top+1)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        view.layout(left,top,right,bottom)
                        view.evaluateJavascript("document.getElementById('flow-end-msg-send').style.transform='translateY(40px)';true",null)
                    },150L)
                }
            }
            installGeometryFixture(pool, view, geometry)
            onMain { view.setOnTouchListener { _, event ->
                @Suppress("UNCHECKED_CAST")
                val active=(field(pool,"automations") as Map<ArenaService,Any>)[ArenaService.DOUBAO]
                if(event.action==android.view.MotionEvent.ACTION_DOWN&&active!=null&&optionalField(active,"cancelNativeTouch")!=null){assertTrue(view.height>1);nativeDowns.incrementAndGet()};false
            } }
            evaluate(view,"const hit=document.elementFromPoint.bind(document);document.elementFromPoint=(x,y)=>{const target=hit(x,y);if(target?.closest('#flow-end-msg-send'))GeometryFixture.changeSize();return target;};true")
            val done=CountDownLatch(1)
            val outcome=AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.DOUBAO,"native-new-geometry","native-new-geometry",listOf(attachment)){outcome.set(it);done.countDown()} }
            assertTrue(done.await(14,TimeUnit.SECONDS))
            assertTrue("Fixture must change the native layout between JS measurement and callback",changed.get())
            assertTrue(outcome.get().toString(),outcome.get().success)
            assertEquals(1,nativeDowns.get())
            assertEquals("1",evaluate(view,"sendCount"))
            verifyBytes(view,attachment)
        }
    }

    @Test fun currentChildMembershipIgnoresStaleDoubaoDraftAndDeliversExactlyOnce() {
        withPool(emptyMap()) { pool, views, attachment ->
            val view = views.getValue(ArenaService.DOUBAO)
            evaluate(view, """
                const stale=cards['__reactFiber${'$'}fixture'];
                const current=fixtureNode({attachmentStates:fileStates},null,cards);
                current.return=root;root.child=current;
                stale.memoizedProps={attachmentStates:[{fileName:'removed-old.png'},{fileName:'removed-new.png'}]};
                stale.alternate=current;current.alternate=stale;
                // Both return chains reach root.current, but only alternate is a current child.
                window.fixtureBothReturnsCurrent=stale.return===root&&current.return===root;true;
            """.trimIndent())
            val done = CountDownLatch(1)
            var outcome: SendOutcome? = null
            onMain {
                pool.sendPromptWithAttachments(ArenaService.DOUBAO, "current-child-only", "current-child-only", listOf(attachment)) {
                    outcome = it; done.countDown()
                }
            }
            assertTrue(done.await(12, TimeUnit.SECONDS))
            onMain { assertTrue("Removed stale drafts must not block the actual empty composer: $outcome", outcome?.success == true) }
            assertEquals("true", evaluate(view, "window.fixtureBothReturnsCurrent"))
            verifyBytes(view, attachment)
            assertEquals("1", evaluate(view, "window.sendCount"))
            assertEquals("current-child-only", evaluate(view, "window.sentText"))
        }
    }

    @Test fun sharedBailoutChildReadsActualParentAndWaitsForCurrentUpload() {
        withPool(mapOf(ArenaService.DOUBAO to 30_000L)) { pool, views, attachment ->
            val view = views.getValue(ArenaService.DOUBAO)
            evaluate(view, """
                const leaf=cards['__reactFiber${'$'}fixture'];leaf.memoizedProps={};
                const staleParent=fixtureNode({attachmentStates:[]},null);
                const actualParent=fixtureNode({attachmentStates:fileStates},null);
                root.child=actualParent;actualParent.return=root;actualParent.child=leaf;
                staleParent.return=root;staleParent.child=leaf;leaf.return=staleParent;
                const originalHandle=handle;upload.onchange=event=>{
                  originalHandle(event);
                  staleParent.memoizedProps={attachmentStates:fileStates.map(f=>({...f,status:'Normal',parseState:1}))};
                };
                window.sendAttempts=0;const originalSend=send;send=()=>{sendAttempts++;originalSend();};true;
            """.trimIndent())
            val done = CountDownLatch(1)
            var outcome: SendOutcome? = null
            onMain {
                pool.sendPromptWithAttachments(ArenaService.DOUBAO, "wait-current-parent", "wait-current-parent", listOf(attachment)) {
                    outcome = it; done.countDown()
                }
            }
            waitUntil("real URI must reach the shared-child fixture") { JSONArray(evaluate(view, "JSON.stringify(window.received)")).length() == 1 }
            Thread.sleep(1_800)
            assertEquals("A stale successful parent must not cause even one send attempt", "0", evaluate(view, "window.sendAttempts"))
            evaluate(view, "window.completeFixture();true")
            assertTrue(done.await(10, TimeUnit.SECONDS))
            onMain { assertTrue(outcome.toString(), outcome?.success == true) }
            verifyBytes(view, attachment)
            assertEquals("1", evaluate(view, "window.sendAttempts"))
            assertEquals("1", evaluate(view, "window.sendCount"))
        }
    }

    @Test fun backgroundProbeCannotAcquireNativeFocusOrExposeHiddenWebContent() {
        withPool(emptyMap()) { pool, views, _ ->
            startBackgroundProbe(pool)
            onMain {
                val service = field(pool, "backgroundProbeService") as ArenaService
                val view = views.getValue(service)
                assertEquals(View.VISIBLE, view.visibility)
                assertFalse("Hidden probe must reject native focus", view.requestFocus())
                assertFalse(view.hasFocus())
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, view.importantForAccessibility)
                pool.show(service)
                assertTrue("Opening original page must restore native focus", view.requestFocus())
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, view.importantForAccessibility)
            }
        }
    }

    @Test fun backgroundProbePreservesTheUsersComposeFocusAndOpenKeyboard() {
        withPool(emptyMap()) { pool, views, _ ->
            val editorFocus = FocusRequester()
            val editorFocused = AtomicBoolean(false)
            val ready = AtomicBoolean(false)
            var showKeyboard: () -> Unit = {}
            lateinit var compose: ComposeView
            onMain {
                pool.show(ArenaService.KIMI)
                pool.cancelAutomation()
                val host = pool.container.parent as FrameLayout
                compose = ComposeView(host.context)
                host.addView(compose, FrameLayout.LayoutParams(-1, -1))
                compose.setContent {
                    val value = remember { mutableStateOf("") }
                    val keyboard = LocalSoftwareKeyboardController.current
                    SideEffect { showKeyboard = { keyboard?.show() }; ready.set(true) }
                    Box(Modifier.fillMaxSize()) {
                        BasicTextField(
                            value = value.value,
                            onValueChange = { value.value = it },
                            modifier = Modifier.fillMaxWidth().height(80.dp)
                                .focusRequester(editorFocus).onFocusChanged { editorFocused.set(it.isFocused) },
                        )
                    }
                }
            }
            waitUntil("Compose input mounted") { ready.get() }
            onMain { editorFocus.requestFocus(); showKeyboard() }
            fun imeVisible(): Boolean {
                var visible = false
                onMain { visible = ViewCompat.getRootWindowInsets(compose)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                return visible
            }
            waitUntil("User keyboard opened") { editorFocused.get() && imeVisible() }
            startBackgroundProbe(pool)
            onMain {
                val probing = field(pool, "backgroundProbeService") as ArenaService
                assertFalse("Probe cannot steal the Compose editor", views.getValue(probing).requestFocus())
            }
            waitUntil("background probes finish") {
                var complete = false
                onMain { complete = field(pool, "backgroundProbeService") == null }
                complete
            }
            assertTrue("User Compose editor keeps focus", editorFocused.get())
            assertTrue("Probe completion must not hide the user's keyboard", imeVisible())
        }
    }

    private fun startBackgroundProbe(pool: ArenaWebViewPool) {
        onMain {
            pool.show(ArenaService.KIMI)
            pool.cancelAutomation()
            pool.probeAll()
            pool.show(null)
        }
        waitUntil("background probe started") {
            var active = false
            onMain { active = field(pool, "backgroundProbeService") != null }
            active
        }
    }

    @Test fun slowUploadDoesNotBlockOtherProvidersAndTabChangesKeepFilesAndPromptsSeparate() {
        withPool(mapOf(ArenaService.DEEPSEEK to 8_000L), fileName = "probe.png") { pool, views, attachment ->
            val done = CountDownLatch(3)
            val outcomes = mutableMapOf<ArenaService, SendOutcome>()
            val times = mutableMapOf<ArenaService, Long>()
            val started = SystemClock.elapsedRealtime()
            onMain {
                members.forEach { service ->
                    pool.sendPromptWithAttachments(service, "parallel-${service.name}", "slow-${service.name}", listOf(attachment)) { outcome ->
                        outcomes[service] = outcome
                        times[service] = SystemClock.elapsedRealtime() - started
                        done.countDown()
                    }
                }
                pool.show(ArenaService.KIMI)
            }
            waitUntil("all three file pickers must deliver before slow parsing finishes") {
                views.values.all { JSONArray(evaluate(it, "JSON.stringify(window.received)")).length() == 1 }
            }
            onMain {
                pool.show(ArenaService.DOUBAO)
                assertEquals(View.VISIBLE, views.getValue(ArenaService.DEEPSEEK).visibility)
                pool.show(null)
            }
            waitUntil("fast providers must finish while DeepSeek is parsing") {
                onMain { assertFalse(outcomes.containsKey(ArenaService.DEEPSEEK)) }
                var fastDone = false
                onMain { fastDone = ArenaService.DOUBAO in outcomes && ArenaService.KIMI in outcomes }
                fastDone
            }
            assertTrue(done.await(15, TimeUnit.SECONDS))
            onMain {
                assertTrue(outcomes.toString(), outcomes.values.all { it.success })
                assertTrue(times.toString(), times.getValue(ArenaService.DOUBAO) < times.getValue(ArenaService.DEEPSEEK))
                assertTrue(times.toString(), times.getValue(ArenaService.KIMI) < times.getValue(ArenaService.DEEPSEEK))
                android.util.Log.i("ArenaParallelEvidence", "slow-upload callback milliseconds=$times")
            }
            views.forEach { (service, view) ->
                verifyBytes(view, attachment)
                assertEquals("parallel-${service.name}", evaluate(view, "window.sentText"))
                assertEquals("1", evaluate(view, "window.sendCount"))
            }
        }
    }

    @Test fun kimiCurrentDivSendControlActuallySubmitsAfterAttachment() {
        withPool(emptyMap()) { pool, views, attachment ->
            val view = views.getValue(ArenaService.KIMI)
            evaluate(view, """
                document.querySelector('._77cefa5').classList.add('chat-editor');
                const old=document.querySelector('.send-msg-btn'),sendControl=document.createElement('div');sendControl.className='send-button-container';sendControl.style='height:40px;width:80px';sendControl.innerHTML='<svg class="send-icon"></svg>';sendControl.onclick=send;old.replaceWith(sendControl);true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.KIMI, "current-send-control", "current-kimi", listOf(attachment)) { outcome.set(it); done.countDown() } }
            assertTrue(done.await(15, TimeUnit.SECONDS))
            assertTrue("Current Kimi div send control must confirm the submission: ${outcome.get()}", outcome.get().success)
            verifyBytes(view, attachment)
            assertEquals("1", evaluate(view, "window.sendCount"))
            assertEquals("current-send-control", evaluate(view, "window.sentText"))
        }
    }

    @Test fun nativeUploadKeepsFocusUntilDelayedBrowserInputActivationAndReleasesBeforeParsing() {
        withPool(mapOf(ArenaService.KIMI to 30_000L), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.KIMI)
            installDelayedKimiActivation(view)
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.KIMI, "delayed-browser-click", "delayed-browser-click", listOf(attachment)) { outcome.set(it); done.countDown() } }
            waitUntil("Browser must activate the real file input after the delayed label click") {
                evaluate(view, "window.inputActivation !== null") == "true"
            }
            val activation = JSONObject(evaluate(view, "JSON.stringify(window.inputActivation)"))
            val deliveryDeadline = SystemClock.elapsedRealtime() + 1_500L
            while (JSONArray(evaluate(view, "JSON.stringify(window.received)")).length() == 0 && SystemClock.elapsedRealtime() < deliveryDeadline) Thread.sleep(50)
            val deliveredBeforeAssertion = JSONArray(evaluate(view, "JSON.stringify(window.received)")).length()
            android.util.Log.i("ArenaClickEvidence", "delayed input focus=${activation.getBoolean("focus")} activation=${activation.getBoolean("active")} sinceUpMs=${activation.getDouble("sinceUp")} received=$deliveredBeforeAssertion")
            assertTrue("Input activation lost native focus: $activation", activation.getBoolean("focus"))
            assertTrue("The native tap must supply transient user activation: $activation", activation.getBoolean("active"))
            assertTrue("Fixture must delay browser input activation after UP: $activation", activation.getDouble("sinceUp") >= 180.0)
            waitUntil("Real native chooser must deliver the selected URI") { JSONArray(evaluate(view, "JSON.stringify(window.received)")).length() == 1 }
            verifyBytes(view, attachment)
            waitUntil("Focus lease must finish without waiting for network parsing") {
                var released = false
                onMain { released = field(pool, "focusAction") == null }
                released
            }
            assertEquals("No text before attachment parsing", "0", evaluate(view, "window.sendCount"))
            evaluate(view, "window.completeFixture();true")
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("1", evaluate(view, "window.sendCount"))
        }
    }

    @Test fun unacknowledgedNativeClickHasABoundedFocusLeaseAndDoesNotBlockOtherProviders() {
        withPool(emptyMap()) { pool, views, attachment ->
            val kimi = views.getValue(ArenaService.KIMI)
            evaluate(kimi, "document.querySelector('.toolkit-trigger-btn').addEventListener('click',event=>{event.preventDefault();event.stopImmediatePropagation();},true);true")
            val done = CountDownLatch(2)
            val outcomes = mutableMapOf<ArenaService, SendOutcome>()
            val started = SystemClock.elapsedRealtime()
            onMain {
                listOf(ArenaService.KIMI, ArenaService.DEEPSEEK).forEach { service ->
                    pool.sendPromptWithAttachments(service, "bounded-click-${service.name}", "bounded-click-${service.name}", listOf(attachment)) {
                        outcomes[service] = it
                        done.countDown()
                    }
                }
            }
            assertTrue("An unacknowledged click must not hold the focus queue until upload timeout", done.await(12, TimeUnit.SECONDS))
            onMain {
                assertFalse(outcomes.getValue(ArenaService.KIMI).success)
                assertTrue(outcomes.getValue(ArenaService.KIMI).detail, outcomes.getValue(ArenaService.KIMI).detail.contains("菜单连续 3 次"))
                assertTrue(outcomes.toString(), outcomes.getValue(ArenaService.DEEPSEEK).success)
            }
            assertTrue(SystemClock.elapsedRealtime() - started < 12_000L)
            assertEquals("0", evaluate(kimi, "window.sendCount"))
            assertEquals(0, JSONArray(evaluate(kimi, "JSON.stringify(window.received)")).length())
            verifyBytes(views.getValue(ArenaService.DEEPSEEK), attachment)
        }
    }

    @Test fun cancellingAfterNativeUpRejectsLateChooserAndCannotReleaseAnotherProvidersFocus() {
        withPool(emptyMap()) { pool, views, attachment ->
            val kimi = views.getValue(ArenaService.KIMI)
            val deepSeek = views.getValue(ArenaService.DEEPSEEK)
            installDelayedKimiActivation(kimi)
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            val oldCallbacks = AtomicInteger()
            val cancelled = AtomicBoolean()
            onMain {
                var ups = 0
                kimi.setOnTouchListener { _, event ->
                    if (event.action == android.view.MotionEvent.ACTION_UP && ++ups == 2) {
                        kimi.postDelayed({
                            pool.cancelAutomation(ArenaService.KIMI)
                            cancelled.set(true)
                            pool.sendPromptWithAttachments(ArenaService.DEEPSEEK, "after-delayed-cancel", "after-delayed-cancel", listOf(attachment)) { outcome.set(it); done.countDown() }
                        }, 40L)
                    }
                    false
                }
                pool.sendPromptWithAttachments(ArenaService.KIMI, "obsolete-delayed-click", "obsolete-delayed-click", listOf(attachment)) { oldCallbacks.incrementAndGet() }
            }
            assertTrue(done.await(12, TimeUnit.SECONDS))
            assertTrue(cancelled.get())
            assertEquals(0, oldCallbacks.get())
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("0", evaluate(kimi, "window.sendCount"))
            assertEquals(0, JSONArray(evaluate(kimi, "JSON.stringify(window.received)")).length())
            assertEquals("after-delayed-cancel", evaluate(deepSeek, "window.sentText"))
            verifyBytes(deepSeek, attachment)
        }
    }

    private fun installDelayedKimiActivation(view: WebView) {
        evaluate(view, """
            window.inputActivation=null;window.lastUploadUp=0;
            document.querySelector('.toolkit-trigger-btn').addEventListener('click',()=>{
              const label=menu.querySelector('label'),input=label.querySelector('input');
              label.addEventListener('pointerup',()=>{window.lastUploadUp=performance.now();});
              input.addEventListener('click',()=>{window.inputActivation={focus:document.hasFocus(),active:navigator.userActivation.isActive,sinceUp:performance.now()-window.lastUploadUp};},true);
              label.addEventListener('click',event=>{
                if(event.target===input)return;
                event.preventDefault();
                setTimeout(()=>input.click(),220);
              });
            });true;
        """.trimIndent())
    }

    @Test fun deepSeekLostDirectTapRecoversOnceAndStopsTappingAfterUriDelivery() {
        withPool(mapOf(ArenaService.DEEPSEEK to 30_000L), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.DEEPSEEK)
            evaluate(view, """
                window.directTapTimes=[];
                document.querySelector('[data-testid=upload_file_button]').addEventListener('click',event=>{
                  directTapTimes.push(Date.now());
                  if(directTapTimes.length===1){event.preventDefault();event.stopImmediatePropagation();}
                },true);true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.DEEPSEEK, "direct-recovery", "direct-recovery", listOf(attachment)) { outcome.set(it); done.countDown() } }
            waitUntil("A lost direct click must recover or return an explicit outcome") { outcome.get() != null || evaluate(view, "received.length") == "1" }
            assertEquals("The first unacknowledged direct click must not end the request: ${outcome.get()}", "1", evaluate(view, "received.length"))
            verifyBytes(view, attachment)
            val times = JSONArray(evaluate(view, "JSON.stringify(directTapTimes)"))
            assertEquals(2, times.length())
            assertTrue("Recovery must wait at least two seconds", times.getLong(1) - times.getLong(0) >= 2_000L)
            Thread.sleep(2_500)
            assertEquals("A delivered URI must never trigger another local picker while parsing", "2", evaluate(view, "directTapTimes.length"))
            assertEquals("0", evaluate(view, "sendCount"))
            evaluate(view, "completeFixture();true")
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("1", evaluate(view, "sendCount"))
        }
    }

    @Test fun deepSeekReplacedButtonsCannotResetTheRequestWideAttemptLimitOrBlockKimi() {
        withPool(emptyMap()) { pool, views, attachment ->
            val view = views.getValue(ArenaService.DEEPSEEK)
            evaluate(view, """
                window.directTapTimes=[];
                const arm=button=>button.addEventListener('click',event=>{
                  event.preventDefault();event.stopImmediatePropagation();directTapTimes.push(Date.now());
                  const replacement=button.cloneNode(true);button.replaceWith(replacement);arm(replacement);
                },true);arm(document.querySelector('[data-testid=upload_file_button]'));true;
            """.trimIndent())
            val done = CountDownLatch(2)
            val outcomes = mutableMapOf<ArenaService, SendOutcome>()
            val completedAt = mutableMapOf<ArenaService, Long>()
            onMain { listOf(ArenaService.DEEPSEEK, ArenaService.KIMI).forEach { service ->
                pool.sendPromptWithAttachments(service, "bounded-direct-${service.name}", "bounded-direct-${service.name}", listOf(attachment)) {
                    outcomes[service] = it; completedAt[service] = System.currentTimeMillis(); done.countDown()
                }
            } }
            assertTrue("Three failed attempts must settle without the 120 second upload timeout", done.await(18, TimeUnit.SECONDS))
            onMain {
                assertFalse(outcomes.getValue(ArenaService.DEEPSEEK).success)
                assertTrue(outcomes.getValue(ArenaService.DEEPSEEK).detail, outcomes.getValue(ArenaService.DEEPSEEK).detail.contains("连续 3 次"))
                assertTrue(outcomes.toString(), outcomes.getValue(ArenaService.KIMI).success)
            }
            val kimiReceivedAt = JSONArray(evaluate(views.getValue(ArenaService.KIMI), "JSON.stringify(received)")).getJSONObject(0).getLong("at")
            android.util.Log.i("ArenaDirectEvidence", "kimiUriAt=$kimiReceivedAt deepSeekStoppedAt=${completedAt.getValue(ArenaService.DEEPSEEK)} kimiCompletedAt=${completedAt.getValue(ArenaService.KIMI)}")
            assertTrue("Kimi must receive its real URI while DeepSeek is still recovering", kimiReceivedAt < completedAt.getValue(ArenaService.DEEPSEEK))
            val times = JSONArray(evaluate(view, "JSON.stringify(directTapTimes)"))
            assertEquals("Replacing DOM nodes cannot reset a request's attempt budget", 3, times.length())
            (1 until times.length()).forEach { assertTrue(times.getLong(it) - times.getLong(it - 1) >= 2_000L) }
            assertEquals("0", evaluate(view, "received.length"))
            assertEquals("0", evaluate(view, "sendCount"))
            verifyBytes(views.getValue(ArenaService.KIMI), attachment)
        }
    }

    @Test fun deepSeekChangedInputOrAmbiguousComposerCannotReceiveARecoveryTap() {
        listOf("input-replaced", "extra-composer").forEach { mode ->
            withPool(emptyMap()) { pool, views, attachment ->
                val view = views.getValue(ArenaService.DEEPSEEK)
                evaluate(view, """
                    window.directTaps=0;
                    document.querySelector('[data-testid=upload_file_button]').addEventListener('click',event=>{
                      event.preventDefault();event.stopImmediatePropagation();directTaps++;
                      if('$mode'==='input-replaced'){const replacement=upload.cloneNode(true);upload.replaceWith(replacement);replacement.onchange=handle;}
                      else {const composer=document.querySelector('._77cefa5').cloneNode(true);document.body.appendChild(composer);}
                    },true);true;
                """.trimIndent())
                val done = CountDownLatch(1)
                val outcome = AtomicReference<SendOutcome>()
                onMain { pool.sendPromptWithAttachments(ArenaService.DEEPSEEK, mode, "direct-$mode", listOf(attachment)) { outcome.set(it); done.countDown() } }
                assertTrue(done.await(12, TimeUnit.SECONDS))
                assertFalse(outcome.get().success)
                assertTrue(outcome.get().detail, outcome.get().detail.contains(if (mode == "input-replaced") "入口已变化" else "唯一"))
                assertEquals("1", evaluate(view, "directTaps"))
                assertEquals("0", evaluate(view, "received.length"))
                assertEquals("0", evaluate(view, "sendCount"))
            }
        }
    }

    @Test fun deepSeekCancelledDirectTapRejectsLateChooserWithoutAffectingAnotherProvider() {
        withPool(emptyMap()) { pool, views, attachment ->
            val view = views.getValue(ArenaService.DEEPSEEK)
            evaluate(view, """
                window.directTaps=0;window.lateInputActivations=0;
                document.querySelector('[data-testid=upload_file_button]').addEventListener('click',event=>{
                  event.preventDefault();event.stopImmediatePropagation();directTaps++;
                  setTimeout(()=>{lateInputActivations++;upload.click();},220);
                },true);true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            val oldCallbacks = AtomicInteger()
            val cancelled = AtomicBoolean()
            val lateChoosers = AtomicInteger()
            val rejectedChoosers = AtomicInteger()
            onMain {
                val productionClient = checkNotNull(view.webChromeClient)
                view.webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onShowFileChooser(webView: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                        lateChoosers.incrementAndGet()
                        return productionClient.onShowFileChooser(webView, android.webkit.ValueCallback { uris ->
                            if (uris == null) rejectedChoosers.incrementAndGet()
                            callback.onReceiveValue(uris)
                        }, params)
                    }
                }
                view.setOnTouchListener { _, event ->
                    if (event.action == android.view.MotionEvent.ACTION_UP && !cancelled.get()) view.postDelayed({
                        cancelled.set(true)
                        pool.cancelAutomation(ArenaService.DEEPSEEK)
                        pool.sendPromptWithAttachments(ArenaService.KIMI, "after-direct-cancel", "after-direct-cancel", listOf(attachment)) { outcome.set(it); done.countDown() }
                    }, 40L)
                    false
                }
                pool.sendPromptWithAttachments(ArenaService.DEEPSEEK, "obsolete-direct", "obsolete-direct", listOf(attachment)) { oldCallbacks.incrementAndGet() }
            }
            assertTrue(done.await(12, TimeUnit.SECONDS))
            assertTrue(cancelled.get())
            assertEquals("1", evaluate(view, "lateInputActivations"))
            assertEquals("The real late chooser must reach the production WebChromeClient", 1, lateChoosers.get())
            assertEquals("The production client must explicitly reject its URI callback", 1, rejectedChoosers.get())
            assertEquals(0, oldCallbacks.get())
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("1", evaluate(view, "directTaps"))
            assertEquals("0", evaluate(view, "received.length"))
            assertEquals("0", evaluate(view, "sendCount"))
            verifyBytes(views.getValue(ArenaService.KIMI), attachment)
        }
    }

    @Test fun deepSeekGeometryReservationReturnCannotReplenishTheDirectAttemptBudget() {
        withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.DEEPSEEK)
            val restored = AtomicBoolean()
            val geometry = GeometryFixture {
                onMain {
                    val left = view.left; val top = view.top; val right = view.right; val bottom = view.bottom
                    view.layout(left, top, right, top + 1)
                    assertEquals(1, view.height)
                    view.postDelayed({ view.layout(left, top, right, bottom); restored.set(true) }, 200L)
                }
            }
            installGeometryFixture(pool, view, geometry, ArenaService.DEEPSEEK)
            evaluate(view, """
                const hit=document.elementFromPoint.bind(document);window.directSnapshots=0;window.directTaps=0;
                document.elementFromPoint=(x,y)=>{const target=hit(x,y);
                  if(target?.closest('[data-testid=upload_file_button]')&&window.__arenaAttachment){directSnapshots++;if(directSnapshots===1)GeometryFixture.changeSize();}return target;
                };
                document.querySelector('[data-testid=upload_file_button]').addEventListener('click',event=>{event.preventDefault();event.stopImmediatePropagation();directTaps++;},true);true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.DEEPSEEK, "direct-geometry-budget", "direct-geometry-budget", listOf(attachment)) { outcome.set(it); done.countDown() } }
            assertTrue(done.await(15, TimeUnit.SECONDS))
            assertTrue("Fixture layout restore must finish before teardown", restored.get())
            assertFalse(outcome.get().success)
            assertTrue(outcome.get().detail, outcome.get().detail.contains("连续 3 次"))
            assertEquals("A returned untouched reservation still consumes the same request budget", "3", evaluate(view, "directSnapshots"))
            assertEquals("The first out-of-bounds snapshot must never touch the webpage", "2", evaluate(view, "directTaps"))
            assertEquals("0", evaluate(view, "received.length"))
            assertEquals("0", evaluate(view, "sendCount"))
        }
    }

    @Test fun doubaoSendPathsReserveOneClickUntilLateUserDomConfirmsDelivery() {
        withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.DOUBAO)
            evaluate(view, """
                window.acceptedSends=0;window.pendingSends=0;
                window.send=()=>{
                  const input=document.querySelector('textarea'),text=input.value;
                  if(!text.trim())return;
                  if(fileStates.length!==1||fileStates[0].status!=='Normal')throw Error('attachment not ready');
                  acceptedSends++;pendingSends++;
                  setTimeout(()=>{
                    const row=document.createElement('div');row.className='v_list_row';row.setAttribute('data-observe-row','');
                    const user=document.createElement('span');user.className='bg-g-send';user.textContent=text;row.appendChild(user);document.body.appendChild(row);
                    window.sentText=text;window.sendCount++;input.value='';pendingSends--;
                  },1800);
                };true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.DOUBAO, "unique-late-dom", "unique-late-dom", listOf(attachment)) { outcome.set(it); done.countDown() } }
            assertTrue(done.await(14, TimeUnit.SECONDS))
            waitUntil("All fixture response DOM callbacks finish before teardown") { evaluate(view, "window.pendingSends") == "0" }
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("The website accepted more than one send while its DOM was still stale", "1", evaluate(view, "window.acceptedSends"))
            assertEquals("1", evaluate(view, "window.sendCount"))
            assertEquals("unique-late-dom", evaluate(view, "window.sentText"))
            verifyBytes(view, attachment)
        }
    }

    @Test fun disabledDoubaoSendDoesNotReserveTheOnlyClickBeforeItBecomesReady() {
        withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.DOUBAO)
            evaluate(view, """
                const button=document.querySelector('.send-msg-btn');button.disabled=true;window.reservedWhileDisabled=null;
                document.querySelector('textarea').addEventListener('input',()=>{
                  setTimeout(()=>{window.reservedWhileDisabled=!!window.__aiArenaSendClicks?.['disabled-later'];},1100);
                  setTimeout(()=>{button.disabled=false;},1600);
                },{once:true});true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.DOUBAO, "disabled-later", "disabled-later", listOf(attachment)) { outcome.set(it); done.countDown() } }
            assertTrue(done.await(14, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("false", evaluate(view, "window.reservedWhileDisabled"))
            assertEquals("1", evaluate(view, "window.sendCount"))
            verifyBytes(view, attachment)
        }
    }

    @Test fun cancelledSendTimersCannotReserveOrClickAnExplicitReplacementRequest() {
        withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.DOUBAO)
            val oldCallbacks = AtomicInteger()
            onMain { pool.sendPromptWithAttachments(ArenaService.DOUBAO, "cancel-before-click", "cancel-before-click", listOf(attachment)) { oldCallbacks.incrementAndGet() } }
            waitUntil("Old request has injected text but its scheduled click has not run") { evaluate(view, "document.querySelector('textarea').value") == "cancel-before-click" }
            onMain { pool.cancelAutomation(ArenaService.DOUBAO) }
            evaluate(view, "cards.innerHTML='';fileStates.length=0;received=[];upload.value='';document.querySelector('textarea').value='';true")
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.DOUBAO, "explicit-replacement", "explicit-replacement", listOf(attachment)) { outcome.set(it); done.countDown() } }
            assertTrue(done.await(14, TimeUnit.SECONDS))
            assertEquals(0, oldCallbacks.get())
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("false", evaluate(view, "!!window.__aiArenaSendClicks?.['cancel-before-click']"))
            assertEquals("1", evaluate(view, "window.sendCount"))
            assertEquals("explicit-replacement", evaluate(view, "window.sentText"))
            verifyBytes(view, attachment)
        }
    }

    @Test fun kimiActualFailedDocumentCardReturnsPromptlyWithoutSendingText() {
        withPool(emptyMap()) { pool, views, attachment ->
            val view = views.getValue(ArenaService.KIMI)
            evaluate(view, """
                window.completeFixture=()=>{
                  fileStates.forEach(file=>{file.status='FAILED';file.parseState=2;});
                  cards.querySelectorAll('.file-card-container').forEach(card=>{
                    card.className='file-card-container normal error';card.querySelector('.file-ext')?.remove();
                    const icon=document.createElement('img');icon.className='file-card-icon';icon.alt='txt';card.prepend(icon);
                    const status=document.createElement('div');status.className='file-card-info-status error';status.textContent='Upload failed';card.appendChild(status);
                  });
                };true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.KIMI, "must-not-send", "kimi-document-failure", listOf(attachment)) { outcome.set(it); done.countDown() } }
            assertTrue("Known webpage upload failure must finish before the 120 second timeout", done.await(12, TimeUnit.SECONDS))
            assertFalse(outcome.get().success)
            assertTrue(outcome.get().detail, outcome.get().detail.contains("Kimi 附件上传或解析失败"))
            verifyBytes(view, attachment)
            assertEquals("0", evaluate(view, "window.sendCount"))
            assertEquals("", evaluate(view, "window.sentText"))
            assertEquals("", evaluate(view, "document.querySelector('textarea').value"))
        }
    }

    @Test fun kimiReopensClosedOrRebindsReplacedUploadMenuAndDeliversOnce() {
        listOf("closed", "replaced").forEach { mode ->
            withPool(emptyMap()) { pool, views, attachment ->
                val view = views.getValue(ArenaService.KIMI)
                installKimiMenuRecovery(view, mode)
                val done = CountDownLatch(1)
                val outcome = AtomicReference<SendOutcome>()
                onMain { pool.sendPromptWithAttachments(ArenaService.KIMI, "rebind-$mode", "rebind-$mode", listOf(attachment)) { outcome.set(it); done.countDown() } }
                assertTrue("An undelivered $mode menu must recover without the 120s timeout", done.await(18, TimeUnit.SECONDS))
                assertTrue(outcome.get().toString(), outcome.get().success)
                assertEquals("2", evaluate(view, "window.labelTaps"))
                assertEquals(if (mode == "closed") "2" else "1", evaluate(view, "window.triggerTaps"))
                assertEquals("1", evaluate(view, "window.sendCount"))
                assertEquals("true", evaluate(view, "labelTimes[1]-labelTimes[0]>=2000"))
                verifyBytes(view, attachment)
            }
        }
    }

    @Test fun kimiRecreatedMenuCannotResetTheRequestAttemptBudget() {
        withPool(emptyMap()) { pool, views, attachment ->
            val view = views.getValue(ArenaService.KIMI)
            installKimiMenuRecovery(view, "exhausted")
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.KIMI, "bounded-rebind", "bounded-rebind", listOf(attachment)) { outcome.set(it); done.countDown() } }
            assertTrue("Rebuilt nodes must exhaust one request budget", done.await(20, TimeUnit.SECONDS))
            assertFalse(outcome.get().toString(), outcome.get().success)
            assertTrue(outcome.get().detail, outcome.get().detail.contains("连续 3 次"))
            assertFalse(outcome.get().detail, outcome.get().detail.contains("登录"))
            assertEquals("3", evaluate(view, "window.triggerTaps"))
            assertEquals("3", evaluate(view, "window.labelTaps"))
            assertEquals("0", evaluate(view, "window.sendCount"))
            assertEquals("0", evaluate(view, "window.received.length"))
        }
    }

    @Test fun cancelledKimiMenuRecoveryCannotReopenOrDeliverAndOtherProviderCompletes() {
        withPool(emptyMap()) { pool, views, attachment ->
            val view = views.getValue(ArenaService.KIMI)
            installKimiMenuRecovery(view, "closed")
            var cancelledCallbacks = 0
            onMain { pool.sendPromptWithAttachments(ArenaService.KIMI, "cancel-rebind", "cancel-rebind", listOf(attachment)) { cancelledCallbacks++ } }
            waitUntil("The first real label activation closes its menu") { evaluate(view, "window.labelTaps") == "1" }
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain {
                pool.cancelAutomation(ArenaService.KIMI)
                pool.sendPromptWithAttachments(ArenaService.DEEPSEEK, "other-provider", "other-provider", listOf(attachment)) { outcome.set(it); done.countDown() }
            }
            assertTrue(done.await(15, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            Thread.sleep(2_100)
            assertEquals("1", evaluate(view, "window.triggerTaps"))
            assertEquals("1", evaluate(view, "window.labelTaps"))
            assertEquals("0", evaluate(view, "window.received.length"))
            assertEquals(0, cancelledCallbacks)
            verifyBytes(views.getValue(ArenaService.DEEPSEEK), attachment)
        }
    }

    @Test fun kimiLateOriginalChooserStillConfirmsAfterBindingReplacementInput() {
        withPool(emptyMap()) { pool, views, attachment ->
            val view = views.getValue(ArenaService.KIMI)
            evaluate(view, """
                const trigger=document.querySelector('.toolkit-trigger-btn'),open=trigger.onclick;
                window.triggerTaps=0;window.lateRebound=false;
                trigger.onclick=()=>{triggerTaps++;open.call(trigger);const input=menu.querySelector('input'),label=input.parentElement;
                  if(triggerTaps===1){window.originalInput=input;input.onclick=()=>setTimeout(()=>{trigger.setAttribute('aria-expanded','false');menu.innerHTML='';},0);}
                  else label.addEventListener('click',event=>event.preventDefault());
                };true;
            """.trimIndent())
            val chooserCalls = AtomicInteger()
            val delivered = CountDownLatch(1)
            val pending = AtomicReference<android.webkit.ValueCallback<Array<Uri>>>()
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            onMain {
                val delegate = view.webChromeClient!!
                view.webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onShowFileChooser(webView: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                        chooserCalls.incrementAndGet()
                        pending.set(callback)
                        val deadline = SystemClock.elapsedRealtime() + 10_000L
                        fun awaitRebind() {
                            if (pending.get() !== callback) return
                            view.evaluateJavascript("!!window.__arenaAttachment&&window.__arenaAttachment.selectionInput!==window.originalInput&&window.__arenaAttachment.fileControlAttempt?.count===2") { rebound ->
                                if (pending.get() !== callback) return@evaluateJavascript
                                if (rebound == "true") {
                                    view.evaluateJavascript("window.lateRebound=true", null)
                                    pending.set(null)
                                    delegate.onShowFileChooser(webView, callback, params)
                                    delivered.countDown()
                                } else if (SystemClock.elapsedRealtime() >= deadline) {
                                    pending.set(null); callback.onReceiveValue(null); delivered.countDown()
                                } else handler.postDelayed({ awaitRebind() }, 40L)
                            }
                        }
                        awaitRebind()
                        return true
                    }
                }
            }
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            try {
                onMain { pool.sendPromptWithAttachments(ArenaService.KIMI, "late-original", "late-original", listOf(attachment)) { outcome.set(it); done.countDown() } }
                assertTrue("Held real chooser must settle", delivered.await(14, TimeUnit.SECONDS))
                assertEquals("The old real chooser arrives after the new input was bound", "true", evaluate(view, "window.lateRebound"))
                assertTrue(done.await(12, TimeUnit.SECONDS))
                assertTrue(outcome.get().toString(), outcome.get().success)
                assertEquals(1, chooserCalls.get())
                assertEquals("1", evaluate(view, "window.sendCount"))
                verifyBytes(view, attachment)
            } finally {
                onMain { handler.removeCallbacksAndMessages(null); pending.getAndSet(null)?.onReceiveValue(null) }
                // Flush an evaluate callback queued before teardown while the WebView is still alive.
                evaluate(view, "true")
            }
        }
    }

    private fun installKimiMenuRecovery(view: WebView, mode: String) {
        evaluate(view, """
            window.labelTaps=0;window.triggerTaps=0;window.labelTimes=[];
            const initial=document.querySelector('.toolkit-trigger-btn'),original=initial.onclick;
            function bind(trigger){trigger.onclick=function(){triggerTaps++;original.call(this);menu.setAttribute('aria-labelledby',this.id);bindLabel(this);};}
            function bindLabel(trigger){const label=menu.querySelector('label'),input=label.querySelector('input');
              label.addEventListener('click',event=>{if(event.target===input)return;labelTaps++;labelTimes.push(Date.now());
                if('$mode'!=='exhausted'&&labelTaps>1)return;
                event.preventDefault();
                if('$mode'==='replaced'){const replacement=label.cloneNode(true);label.replaceWith(replacement);replacement.querySelector('input').onchange=handle;bindLabel(trigger);}
                else {trigger.setAttribute('aria-expanded','false');menu.innerHTML='';
                  const replacement=trigger.cloneNode(true);replacement.id='recreated-toolkit-'+labelTaps;trigger.replaceWith(replacement);bind(replacement);}
              });
            }
            bind(initial);true;
        """.trimIndent())
    }

    @Test fun kimiOpenUploadLabelRecoversLostTapWithBound() {
        listOf("recover", "exhausted").forEach { mode ->
            withPool(emptyMap()) { pool, views, attachment ->
                val view = views.getValue(ArenaService.KIMI)
                evaluate(view, """
                    const trigger=document.querySelector('.toolkit-trigger-btn'),original=trigger.onclick;
                    window.labelTaps=0;window.triggerTaps=0;
                    trigger.onclick=()=>{triggerTaps++;original.call(trigger);
                      const label=menu.querySelector('label'),input=label.querySelector('input');
                      label.addEventListener('click',event=>{if(event.target===input)return;labelTaps++;
                        if('$mode'==='recover'&&labelTaps>1)return;
                        event.preventDefault();
                        if('$mode'==='closed'){trigger.setAttribute('aria-expanded','false');menu.style.display='none';}
                        if('$mode'==='replaced'){const replacement=input.cloneNode();input.replaceWith(replacement);replacement.onchange=handle;}
                      });
                    };true;
                """.trimIndent())
                val done = CountDownLatch(1)
                val outcome = AtomicReference<SendOutcome>()
                onMain { pool.sendPromptWithAttachments(ArenaService.KIMI, "label-$mode", "label-$mode", listOf(attachment)) { outcome.set(it); done.countDown() } }
                if (mode in listOf("closed", "replaced")) {
                    waitUntil("first label attempt") { evaluate(view, "window.labelTaps") == "1" }
                    Thread.sleep(3_000)
                    assertEquals("Changed controls cannot inherit old label attempts", "1", evaluate(view, "window.labelTaps"))
                    assertEquals("The menu trigger must not be reopened by an old label attempt", "1", evaluate(view, "window.triggerTaps"))
                    assertEquals("0", evaluate(view, "window.sendCount"))
                    onMain { pool.cancelAutomation(ArenaService.KIMI) }
                } else {
                    assertTrue("$mode label attempts must settle without waiting 120 seconds", done.await(16, TimeUnit.SECONDS))
                    assertEquals(if (mode == "recover") "2" else "3", evaluate(view, "window.labelTaps"))
                    assertEquals(mode == "recover", outcome.get().success)
                    if (mode == "recover") { verifyBytes(view, attachment); assertEquals("1", evaluate(view, "window.sendCount")) }
                    else { assertTrue(outcome.get().detail, outcome.get().detail.contains("连续 3 次")); assertEquals("0", evaluate(view, "window.sendCount")) }
                }
            }
        }
    }

    @Test fun kimiAlreadyOpenMenuUsesLocalUploadWithoutTogglingTheTrigger() {
        withPool(emptyMap()) { pool, views, attachment ->
            val view = views.getValue(ArenaService.KIMI)
            evaluate(view, """
                const trigger=document.querySelector('.toolkit-trigger-btn'),original=trigger.onclick;
                original.call(trigger);window.triggerTaps=0;
                trigger.onclick=()=>{triggerTaps++;if(trigger.getAttribute('aria-expanded')==='true'){trigger.setAttribute('aria-expanded','false');menu.innerHTML='';}else original.call(trigger);};true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.KIMI, "already-open", "already-open", listOf(attachment)) { outcome.set(it); done.countDown() } }
            waitUntil("local upload starts or unwanted trigger tap occurs") { evaluate(view, "window.triggerTaps>0||window.received.length>0") == "true" }
            assertEquals("Fresh request must not close an already open local menu", "0", evaluate(view, "window.triggerTaps"))
            assertTrue(done.await(15, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            verifyBytes(view, attachment)
        }
    }

    @Test fun deliveredChooserWithDelayedChangeNeverReceivesAnotherNativeTap() {
        withPool(emptyMap()) { pool, views, attachment ->
            val view = views.getValue(ArenaService.KIMI)
            val chooserCalls = AtomicInteger()
            onMain {
                val delegate = view.webChromeClient!!
                view.webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onShowFileChooser(webView: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                        chooserCalls.incrementAndGet()
                        return delegate.onShowFileChooser(webView, android.webkit.ValueCallback { uris ->
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ callback.onReceiveValue(uris) }, 3_100L)
                        }, params)
                    }
                }
            }
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.KIMI, "delayed-change", "delayed-change", listOf(attachment)) { outcome.set(it); done.countDown() } }
            assertTrue(done.await(17, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("Native broker acceptance stops retries before renderer change arrives", 1, chooserCalls.get())
            verifyBytes(view, attachment)
        }
    }

    @Test fun cancelledHiddenAutomationRejectsLateChooserWithoutOpeningManualPicker() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.KIMI)
            var returned = false
            onMain {
                pool.cancelAutomation()
                val params = object : android.webkit.WebChromeClient.FileChooserParams() {
                    override fun getMode() = MODE_OPEN
                    override fun getAcceptTypes() = arrayOf("text/plain")
                    override fun isCaptureEnabled() = false
                    override fun getTitle(): CharSequence? = null
                    override fun getFilenameHint(): String? = null
                    override fun createIntent() = android.content.Intent()
                }
                view.webChromeClient!!.onShowFileChooser(view, android.webkit.ValueCallback { assertNull(it); returned = true }, params)
                assertTrue("A late hidden automation chooser must be rejected synchronously", returned)
            }
        }
    }

    @Test fun kimiDetachedUploadInputStillConfirmsExactNativeFilesBeforeSending() {
        listOf("probe.txt", "probe.png").forEach { name ->
            withPool(emptyMap(), fileName = name) { pool, views, attachment ->
                val view = views.getValue(ArenaService.KIMI)
                evaluate(view, """
                    const trigger=document.querySelector('.toolkit-trigger-btn'),open=trigger.onclick;
                    window.directChanges=0;window.documentChanges=0;window.changedWhileDetached=false;
                    document.addEventListener('change',()=>documentChanges++,true);
                    trigger.onclick=()=>{open.call(trigger);const input=menu.querySelector('input');window.selectedInput=input;
                      input.addEventListener('change',()=>{directChanges++;changedWhileDetached=!input.isConnected;},true);
                      input.onclick=()=>setTimeout(()=>{trigger.setAttribute('aria-expanded','false');menu.innerHTML='';},0);
                    };true;
                """.trimIndent())
                onMain {
                    val delegate = view.webChromeClient!!
                    view.webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onShowFileChooser(webView: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
                            delegate.onShowFileChooser(webView, android.webkit.ValueCallback { uris ->
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ callback.onReceiveValue(uris) }, 500L)
                            }, params)
                    }
                }
                val done = CountDownLatch(1)
                val outcome = AtomicReference<SendOutcome>()
                onMain { pool.sendPromptWithAttachments(ArenaService.KIMI, "detached-$name", "detached-$name", listOf(attachment)) { outcome.set(it); done.countDown() } }
                waitUntil("Native selected bytes reached the detached website input") { evaluate(view, "received.length===1&&directChanges===1&&changedWhileDetached") == "true" }
                assertEquals("Detached input change must not reach document", "0", evaluate(view, "window.documentChanges"))
                verifyBytes(view, attachment)
                assertTrue("Detached native input selection must settle without 120 second timeout", done.await(12, TimeUnit.SECONDS))
                assertTrue(outcome.get().toString(), outcome.get().success)
                assertEquals("1", evaluate(view, "window.sendCount"))
                assertEquals("detached-$name", evaluate(view, "window.sentText"))
                assertEquals("Completed request must clean up its page state", "true", evaluate(view, "!window.__arenaAttachment"))
            }
        }
    }

    @Test fun doubaoLateExtraTrustedAttachmentStateReportsDraftConflictWithoutTimeout() {
        withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.DOUBAO)
            evaluate(view, """
                const complete=window.completeFixture;
                window.completeFixture=()=>{complete();fileStates.push({...fileStates[0],fileName:'older-draft.png',fileKey:'older-key',localKey:'older-local',imageList:[{key:'older-key'}]});};true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.DOUBAO, "must-not-send-late-draft", "doubao-late-draft", listOf(attachment)) { outcome.set(it); done.countDown() } }
            waitUntil("Current upload and late restored old draft are both Normal") { evaluate(view, "fileStates.length===2&&fileStates.every(file=>file.status==='Normal')") == "true" }
            verifyBytes(view, attachment)
            assertTrue("Known extra draft must be reported before the upload timeout", done.await(8, TimeUnit.SECONDS))
            assertFalse(outcome.get().success)
            assertTrue(outcome.get().detail, outcome.get().detail.contains("额外") && outcome.get().detail.contains("豆包"))
            assertEquals("0", evaluate(view, "window.sendCount"))
            assertEquals("", evaluate(view, "document.querySelector('textarea').value"))
            assertEquals("The application must not delete website drafts", "2", evaluate(view, "fileStates.length"))
        }
    }

    @Test fun nativeGeometryChangesRequeryTheUntouchedLocalControl() = verifyGeometryChange("transient")

    @Test fun persistentNativeGeometryMismatchStopsAfterThreeSnapshotsWithoutTouch() = verifyGeometryChange("persistent")

    @Test fun zeroNativeOrInvalidCssViewportsNeverDispatchAnOriginTouch() {
        listOf("zero-native", "css-width", "css-height").forEach(::verifyGeometryChange)
    }

    private fun verifyGeometryChange(mode: String) {
            withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
                val view = views.getValue(ArenaService.DOUBAO)
                val nativeDowns = AtomicInteger()
                val changed = AtomicInteger()
                val geometry = GeometryFixture {
                    onMain {
                        val count = changed.incrementAndGet()
                        if (mode == "transient" && count > 1) return@onMain
                        val left = view.left; val top = view.top; val right = view.right; val bottom = view.bottom
                        when (mode) {
                            "zero-native" -> { view.layout(left, top, left, bottom); assertEquals(0, view.width) }
                            "css-width", "css-height" -> Unit
                            else -> { view.layout(left, top, right, top + 1); assertEquals(1, view.height) }
                        }
                        android.util.Log.i("ArenaGeometryFixture", "nativeWidth=${view.width} nativeHeight=${view.height} originalWidth=${right-left} originalHeight=${bottom-top}")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            view.layout(left, top, right, bottom)
                            view.evaluateJavascript("restoreFixtureViewport();document.getElementById('upload-menu').style.transform='translateY(90px)';true", null)
                        }, 200L)
                    }
                }
                installGeometryFixture(pool, view, geometry)
                onMain {
                    view.setOnTouchListener { _, event -> if (event.action == android.view.MotionEvent.ACTION_DOWN) nativeDowns.incrementAndGet(); false }
                }
                evaluate(view, """
                    const widthProperty=Object.getOwnPropertyDescriptor(window,'innerWidth'),heightProperty=Object.getOwnPropertyDescriptor(window,'innerHeight');
                    window.restoreFixtureViewport=()=>{Object.defineProperty(window,'innerWidth',widthProperty);Object.defineProperty(window,'innerHeight',heightProperty);};
                    const hit=document.elementFromPoint.bind(document);window.localSnapshots=0;window.cloudClicks=0;
                    document.querySelector('#upload-menu [role=menuitem]').onclick=()=>cloudClicks++;
                    document.elementFromPoint=(x,y)=>{const target=hit(x,y),item=target?.closest('[data-slot=dropdown-menu-item]');
                      if(item&&item.textContent==='上传文件或图片'&&window.__arenaAttachment){localSnapshots++;
                        if('$mode'==='css-width')Object.defineProperty(window,'innerWidth',{value:0,configurable:true});
                        if('$mode'==='css-height')Object.defineProperty(window,'innerHeight',{value:Infinity,configurable:true});
                        GeometryFixture.changeSize();}return target;
                    };true;
                """.trimIndent())
                val done = CountDownLatch(1)
                val outcome = AtomicReference<SendOutcome>()
                onMain { pool.sendPromptWithAttachments(ArenaService.DOUBAO, "geometry-$mode", "geometry-$mode", listOf(attachment)) { outcome.set(it); done.countDown() } }
                assertTrue("Geometry recovery must finish within a small bound", done.await(14, TimeUnit.SECONDS))
                assertEquals("Old coordinates must never click the adjacent cloud item", "0", evaluate(view, "cloudClicks"))
                if (mode == "transient") {
                    assertTrue("A new layout must be queried without losing the untouched local upload control: ${outcome.get()}", outcome.get().success)
                    assertEquals("2", evaluate(view, "localSnapshots"))
                    assertEquals("Only the menu trigger, one valid upload and one final send may receive native DOWN", 3, nativeDowns.get())
                    verifyBytes(view, attachment)
                    assertEquals("1", evaluate(view, "sendCount"))
                } else {
                    assertFalse(outcome.get().success)
                    assertTrue(outcome.get().detail, outcome.get().detail.contains("布局"))
                    assertEquals("Persistent mismatch must stop after three fresh snapshots", "3", evaluate(view, "localSnapshots"))
                    assertEquals("No out-of-bounds local input receives native DOWN", 1, nativeDowns.get())
                    assertEquals("0", evaluate(view, "received.length"))
                    assertEquals("0", evaluate(view, "sendCount"))
                }
            }
    }

    @Test fun geometryCancellationCannotTouchOrRestoreAnOldControlIntoANewRequest() {
        withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.DOUBAO)
            val cancelled = AtomicBoolean()
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            var oldCallbacks = 0
            val geometry = GeometryFixture {
                if (cancelled.compareAndSet(false, true)) onMain {
                    val left = view.left; val top = view.top; val right = view.right; val bottom = view.bottom
                    view.layout(left, top, right, top + 1)
                    pool.cancelAutomation(ArenaService.DOUBAO)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        view.layout(left, top, right, bottom)
                        pool.sendPromptWithAttachments(ArenaService.DOUBAO, "geometry-new", "geometry-new", listOf(attachment)) { outcome.set(it); done.countDown() }
                    }, 250L)
                }
            }
            installGeometryFixture(pool, view, geometry)
            evaluate(view, """
                const hit=document.elementFromPoint.bind(document);
                document.elementFromPoint=(x,y)=>{const target=hit(x,y),item=target?.closest('[data-slot=dropdown-menu-item]');if(item&&item.textContent==='上传文件或图片')GeometryFixture.changeSize();return target;};true;
            """.trimIndent())
            onMain { pool.sendPromptWithAttachments(ArenaService.DOUBAO, "geometry-old", "geometry-old", listOf(attachment)) { oldCallbacks++ } }
            assertTrue(done.await(15, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            onMain { assertEquals("Cancelled request cannot receive a late result", 0, oldCallbacks) }
            verifyBytes(view, attachment)
            assertEquals("geometry-new", evaluate(view, "sentText"))
            assertEquals("1", evaluate(view, "sendCount"))
        }
    }

    @Test fun delayedChooserAcceptedDuringGeometryReturnKeepsItsInputAndFinishesOnce() {
        withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
            val view = views.getValue(ArenaService.KIMI)
            val chooserCalls = AtomicInteger()
            val deliveries = AtomicInteger()
            val rendererReturned = AtomicBoolean()
            var pendingChooser: (() -> Unit)? = null
            val delivery = GeometryFixture { onMain { val pending = pendingChooser; pendingChooser = null; pending?.invoke() } }
            val geometry = GeometryFixture {
                onMain {
                    val left = view.left; val top = view.top; val right = view.right; val bottom = view.bottom
                    view.layout(left, top, right, top + 1)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ view.layout(left, top, right, bottom) }, 200L)
                }
            }
            onMain { view.addJavascriptInterface(delivery, "DelayedDeliveryFixture") }
            installGeometryFixture(pool, view, geometry, ArenaService.KIMI)
            evaluate(view, """
                const hit=document.elementFromPoint.bind(document);window.labelSnapshots=0;window.forceRestoreRace=false;window.raceControlId=0;window.removedBeforeChange=0;
                document.elementFromPoint=(x,y)=>{const target=hit(x,y);if(target?.closest('label.toolkit-item')){
                  labelSnapshots++;if(labelSnapshots===2){forceRestoreRace=true;raceControlId=window.__arenaAttachment.controlSequence+1;GeometryFixture.changeSize();}
                }return target;};
                const trigger=document.querySelector('.toolkit-trigger-btn'),open=trigger.onclick;
                trigger.onclick=()=>{open.call(trigger);const input=menu.querySelector('input'),remove=input.removeEventListener.bind(input);
                  input.removeEventListener=(type,listener,capture)=>{if(type==='change'&&capture===true&&input.files.length===0)removedBeforeChange++;remove(type,listener,capture);};
                };true;
            """.trimIndent())
            onMain {
                val delegate = view.webChromeClient!!
                view.webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onShowFileChooser(webView: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                        chooserCalls.incrementAndGet()
                        pendingChooser = {
                            delegate.onShowFileChooser(webView, android.webkit.ValueCallback { uris ->
                                if (uris != null) deliveries.incrementAndGet()
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ callback.onReceiveValue(uris); rendererReturned.set(true) }, 500L)
                            }, params)
                        }
                        webView.evaluateJavascript("""
                            const request=window.__arenaAttachment;let pending=request.pendingControl;
                            Object.defineProperty(request,'pendingControl',{configurable:true,get(){
                              if(forceRestoreRace&&pending?.id===raceControlId){forceRestoreRace=false;DelayedDeliveryFixture.changeSize();return null;}
                              return pending;
                            },set(value){pending=value;}});true;
                        """.trimIndent(), null)
                        return true
                    }
                }
            }
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.KIMI, "late-geometry-delivery", "late-geometry-delivery", listOf(attachment)) { outcome.set(it); done.countDown() } }
            assertTrue(done.await(16, TimeUnit.SECONDS))
            // Drain the fixture's deliberately delayed native callback before assertion failure can destroy its WebView.
            waitUntil("Delayed renderer callback returned before fixture teardown", 3_000L) { rendererReturned.get() }
            assertTrue("An accepted native file cannot be aborted by a stale geometry return: ${outcome.get()}", outcome.get().success)
            assertEquals(1, chooserCalls.get())
            assertEquals(1, deliveries.get())
            assertEquals("0", evaluate(view, "removedBeforeChange"))
            assertEquals("2", evaluate(view, "labelSnapshots"))
            verifyBytes(view, attachment)
            assertEquals("1", evaluate(view, "sendCount"))
            assertEquals("late-geometry-delivery", evaluate(view, "sentText"))
        }
    }

    private class GeometryFixture(private val change: () -> Unit) {
        @android.webkit.JavascriptInterface fun changeSize() = change()
    }

    private fun installGeometryFixture(pool: ArenaWebViewPool, view: WebView, geometry: GeometryFixture, service: ArenaService = ArenaService.DOUBAO) {
        onMain {
            view.addJavascriptInterface(geometry, "GeometryFixture")
            view.loadDataWithBaseURL(service.url, fixture(service, 150L, true), "text/html", "UTF-8", service.url)
        }
        waitUntil("Fresh synthetic document installs its geometry bridge") { evaluate(view, "typeof GeometryFixture!=='undefined'&&window.fixtureReady===true") == "true" }
        onMain { pool.statuses[service] = ServiceStatus(ConnectionState.SIGNED_IN, "isolated geometry fixture") }
    }

    @Test fun kimiLateExtraFileOrImageStopsBeforeSendingSelectedAttachment() {
        listOf("file", "image").forEach { kind ->
            withPool(emptyMap()) { pool, views, attachment ->
                val view = views.getValue(ArenaService.KIMI)
                evaluate(view, """
                    const complete=window.completeFixture;
                    window.completeFixture=()=>{complete();const extra=document.createElement('div');extra.style='height:60px;width:160px';extra.className='${if (kind == "file") "file-card-container success" else "image-thumbnail success"}';extra.innerHTML='${if (kind == "file") "<span class=\"file-card-info-name\">unexpected</span><span class=\"file-ext\">txt</span>" else "<img alt=\"unexpected\">"}';cards.appendChild(extra);};true;
                """.trimIndent())
                val done = CountDownLatch(1)
                val outcome = AtomicReference<SendOutcome>()
                onMain { pool.sendPromptWithAttachments(ArenaService.KIMI, "must-not-send-extra", "extra-$kind", listOf(attachment)) { outcome.set(it); done.countDown() } }
                assertTrue(done.await(12, TimeUnit.SECONDS))
                assertFalse("Late extra $kind must not authorize a send", outcome.get().success)
                assertTrue(outcome.get().detail, outcome.get().detail.contains("额外"))
                verifyBytes(view, attachment)
                assertEquals("0", evaluate(view, "window.sendCount"))
                assertEquals("", evaluate(view, "document.querySelector('textarea').value"))
            }
        }
    }

    @Test fun bothSendScriptsRespectDisabledContainersWithoutEnterFallback() {
        withPool(emptyMap()) { pool, views, _ ->
            val view = views.getValue(ArenaService.KIMI)
            evaluate(view, """
                document.querySelector('._77cefa5').classList.add('chat-editor');
                const old=document.querySelector('.send-msg-btn');window.disabledControl=document.createElement('div');disabledControl.className='send-button-container disabled';disabledControl.style='height:40px;width:80px';
                window.disabledClicks=0;window.enterKeys=0;disabledControl.onclick=()=>disabledClicks++;old.replaceWith(disabledControl);document.querySelector('textarea').addEventListener('keydown',e=>{if(e.key==='Enter')enterKeys++});true;
            """.trimIndent())
            val initial = ArenaWebViewPool::class.java.getDeclaredMethod("sendScript", ArenaService::class.java, String::class.java, String::class.java).apply { isAccessible = true }
            val retry = ArenaWebViewPool::class.java.getDeclaredMethod("clickSendScript", ArenaService::class.java, String::class.java).apply { isAccessible = true }
            val states = listOf("disabledControl.className='send-button-container disabled'", "disabledControl.className='send-button-container';disabledControl.setAttribute('aria-disabled','true')", "disabledControl.removeAttribute('aria-disabled');disabledControl.setAttribute('data-disabled','true')")
            states.forEachIndexed { index, state ->
                evaluate(view, "$state;true")
                evaluate(view, initial.invoke(pool, ArenaService.KIMI, ArenaJs.quote("blocked-$index"), "disabled-$index") as String)
                assertEquals("not_ready", evaluate(view, retry.invoke(pool, ArenaService.KIMI, "disabled-$index") as String))
                Thread.sleep(1_550)
                assertEquals("0", evaluate(view, "window.disabledClicks"))
                assertEquals("0", evaluate(view, "window.enterKeys"))
            }
        }
    }

    @Test fun nativeSendArmingUsesAndroidMillisecondPrecisionWithoutAcceptingStaleEvents() {
        withPool(emptyMap(), fileName = "probe.png") { pool, views, _ ->
            val view = views.getValue(ArenaService.DOUBAO)
            val setup = ArenaWebViewPool::class.java.getDeclaredMethod("nativeDoubaoSetupScript", String::class.java, String::class.java)
                .apply { isAccessible = true }
            evaluate(view, setup.invoke(pool, "clock-boundary", "clock-boundary") as String)
            // Replay the captured timing boundary into the production event guard. The separate
            // menu recovery test continues to exercise real trusted Android touch events.
            val result = JSONObject(evaluate(view, """
                (()=>{
                  const n=window.__aiArenaNativeSend,b=document.getElementById('flow-end-msg-send');
                  n.target=b;n.input=document.querySelector('textarea');n.armedAt=5474.4;
                  const event=(type,timeStamp,isTrusted=true,target=b)=>({type,timeStamp,isTrusted,target});
                  n.pointer(event('pointerup',5475.3));const orphanUp=n.up;
                  n.pointer(event('pointerdown',5473.4));const stale=n.down;
                  n.pointer(event('pointerdown',5474.3,false));const untrusted=n.down;
                  n.pointer(event('pointerdown',5474.3,true,document.body));const wrongTarget=n.down;
                  n.pointer(event('pointerdown',5474.3));const accepted=n.down;
                  n.pointer(event('pointerup',5578.3));const up=n.up;
                  n.down=null;n.up=null;n.armedAt=5475.1;
                  n.pointer(event('pointerdown',5474.1));const boundary=n.down;
                  n.pointer(event('pointerdown',5474.9));const crossMillisecond=n.down;
                  return JSON.stringify({orphanUp,stale,untrusted,wrongTarget,accepted,up,boundary,crossMillisecond});
                })();
            """.trimIndent()))
            listOf("orphanUp", "stale", "untrusted", "wrongTarget", "boundary").forEach { key ->
                assertTrue("Guard must reject $key", result.isNull(key))
            }
            assertFalse("Same-millisecond trusted DOWN must be accepted", result.isNull("accepted"))
            assertEquals(5474.3, result.getDouble("accepted"), 0.001)
            assertEquals(5578.3, result.getDouble("up"), 0.001)
            assertEquals(5474.9, result.getDouble("crossMillisecond"), 0.001)
        }
    }

    @Test fun doubaoClosedModernMenuRetriesLostTapWithoutRepeatingOpenMenuOrUploadItem() {
        listOf("recover", "exhausted", "open").forEach { mode ->
            withPool(emptyMap(), fileName = "probe.png") { pool, views, attachment ->
                val view = views.getValue(ArenaService.DOUBAO)
                evaluate(view, """
                    const trigger=document.getElementById('upload-trigger'),original=trigger.onclick;
                    trigger.setAttribute('aria-expanded','false');trigger.setAttribute('aria-controls','upload-menu');
                    window.uploadTriggerTaps=0;window.uploadItemTaps=0;
                    const local=Array.from(document.querySelectorAll('#upload-menu [role=menuitem]')).find(e=>e.textContent==='上传文件或图片'),choose=local.onclick;
                    local.onclick=()=>{uploadItemTaps++;if('$mode'!=='open')choose.call(local);};
                    trigger.onclick=()=>{uploadTriggerTaps++;
                      if(('$mode'==='recover'&&uploadTriggerTaps>1)||'$mode'==='open'){trigger.setAttribute('aria-expanded','true');original.call(trigger);}
                    };true;
                """.trimIndent())
                val done = CountDownLatch(1)
                val outcome = AtomicReference<SendOutcome>()
                onMain { pool.sendPromptWithAttachments(ArenaService.DOUBAO, "doubao-retry-$mode", "doubao-menu-$mode", listOf(attachment)) { outcome.set(it); done.countDown() } }
                if (mode == "open") {
                    waitUntil("local menu item clicked") { evaluate(view, "window.uploadItemTaps") == "1" }
                    Thread.sleep(3_000)
                    assertEquals("An open menu must not be toggled by retry", "1", evaluate(view, "window.uploadTriggerTaps"))
                    assertEquals("Upload menu item must not be retried", "1", evaluate(view, "window.uploadItemTaps"))
                    assertEquals(1L, done.count)
                    onMain { pool.cancelAutomation(ArenaService.DOUBAO) }
                } else {
                    assertTrue("$mode must settle after bounded menu attempts", done.await(15, TimeUnit.SECONDS))
                    assertEquals(if (mode == "recover") "2" else "3", evaluate(view, "window.uploadTriggerTaps"))
                    assertEquals(mode == "recover", outcome.get().success)
                    assertEquals(if (mode == "recover") "1" else "0", evaluate(view, "window.sendCount"))
                    assertEquals(if (mode == "recover") "1" else "0", evaluate(view, "window.uploadItemTaps"))
                    if (mode == "recover") verifyBytes(view, attachment)
                }
            }
        }
    }

    @Test fun kimiWaitsForHydratedMenuIdentityBeforeTheFirstNativeTap() {
        withPool(emptyMap()) { pool, views, attachment ->
            val view = views.getValue(ArenaService.KIMI)
            evaluate(view, """
                const trigger=document.querySelector('.toolkit-trigger-btn'),original=trigger.onclick;
                trigger.removeAttribute('id');trigger.removeAttribute('aria-haspopup');trigger.removeAttribute('aria-expanded');
                window.earlyToolkitTaps=0;window.hydratedToolkitTaps=0;window.toolkitHydrated=false;
                trigger.onclick=()=>{if(!toolkitHydrated){earlyToolkitTaps++;return;}hydratedToolkitTaps++;trigger.setAttribute('aria-expanded','true');original.call(trigger);};
                setTimeout(()=>{trigger.id='hydrated-toolkit';trigger.setAttribute('aria-haspopup','menu');trigger.setAttribute('aria-expanded','false');window.toolkitHydrated=true;},1800);true;
            """.trimIndent())
            val done = CountDownLatch(1)
            val outcome = AtomicReference<SendOutcome>()
            onMain { pool.sendPromptWithAttachments(ArenaService.KIMI, "hydrated-menu", "hydrated-kimi", listOf(attachment)) { outcome.set(it); done.countDown() } }
            waitUntil("toolkit hydration") { evaluate(view, "window.toolkitHydrated") == "true" }
            assertEquals("Uninitialized trigger must not consume a native tap", "0", evaluate(view, "window.earlyToolkitTaps"))
            assertTrue(done.await(15, TimeUnit.SECONDS))
            assertTrue(outcome.get().toString(), outcome.get().success)
            assertEquals("1", evaluate(view, "window.hydratedToolkitTaps"))
            assertEquals("1", evaluate(view, "window.sendCount"))
            verifyBytes(view, attachment)
        }
    }

    @Test fun kimiClosedToolkitRetriesLostTapWithABoundAndNeverRetogglesOpenMenu() {
        listOf("recover", "exhausted", "open").forEach { mode ->
            withPool(emptyMap()) { pool, views, attachment ->
                val view = views.getValue(ArenaService.KIMI)
                evaluate(view, """
                    const trigger=document.querySelector('.toolkit-trigger-btn'),original=trigger.onclick;
                    trigger.id='fixture-toolkit';trigger.setAttribute('aria-haspopup','menu');trigger.setAttribute('aria-controls','menu');trigger.setAttribute('aria-expanded','false');
                    menu.setAttribute('role','menu');menu.setAttribute('aria-labelledby',trigger.id);window.toolkitTaps=0;
                    trigger.onclick=()=>{toolkitTaps++;
                      if('$mode'==='recover'&&toolkitTaps>1){trigger.setAttribute('aria-expanded','true');original.call(trigger);}
                      if('$mode'==='open'){trigger.setAttribute('aria-expanded','true');menu.innerHTML='<div role="menuitem" style="height:40px">Loading menu</div>';}
                    };true;
                """.trimIndent())
                val done = CountDownLatch(1)
                val outcome = AtomicReference<SendOutcome>()
                onMain { pool.sendPromptWithAttachments(ArenaService.KIMI, "retry-$mode", "retry-$mode", listOf(attachment)) { outcome.set(it); done.countDown() } }
                if (mode == "open") {
                    waitUntil("toolkit opened") { evaluate(view, "window.toolkitTaps") == "1" }
                    Thread.sleep(3_000)
                    assertEquals("An open menu must never be toggled by retry", "1", evaluate(view, "window.toolkitTaps"))
                    assertEquals(1L, done.count)
                    onMain { pool.cancelAutomation(ArenaService.KIMI) }
                } else {
                    assertTrue("$mode must settle after a bounded menu retry", done.await(15, TimeUnit.SECONDS))
                    assertEquals(if (mode == "recover") "2" else "3", evaluate(view, "window.toolkitTaps"))
                    assertEquals(mode == "recover", outcome.get().success)
                    if (mode == "recover") { verifyBytes(view, attachment); assertEquals("1", evaluate(view, "window.sendCount")) }
                    else assertEquals("0", evaluate(view, "window.sendCount"))
                }
            }
        }
    }

    @Test fun cancellingOneUploadRevokesOnlyItsLeasesAndDoesNotFinishOrCancelOtherTasks() {
        withPool(members.associateWith { 30_000L }) { pool, views, attachment ->
            val done = CountDownLatch(2)
            val outcomes = mutableMapOf<ArenaService, SendOutcome>()
            onMain {
                members.forEach { service ->
                    pool.sendPromptWithAttachments(service, "cancel-${service.name}", "cancel-${service.name}", listOf(attachment)) { outcome ->
                        outcomes[service] = outcome
                        done.countDown()
                    }
                }
            }
            waitUntil("three independent leases") { views.values.all { JSONArray(evaluate(it, "JSON.stringify(window.received)")).length() == 1 } }
            lateinit var uris: Map<ArenaService, Uri>
            onMain {
                uris = leaseUris(pool)
                assertEquals(members.toSet(), uris.keys)
                pool.cancelAutomation(ArenaService.DEEPSEEK)
                pool.show(ArenaService.KIMI)
            }
            assertTrue(runCatching { context.contentResolver.openInputStream(uris.getValue(ArenaService.DEEPSEEK))!!.close() }.isFailure)
            members.filter { it != ArenaService.DEEPSEEK }.forEach { service ->
                val bytes = context.contentResolver.openInputStream(uris.getValue(service))!!.use { it.readBytes() }
                assertEquals(attachment.sha256, sha(bytes))
                evaluate(views.getValue(service), "window.completeFixture();true")
            }
            assertTrue(done.await(12, TimeUnit.SECONDS))
            onMain {
                assertEquals(setOf(ArenaService.DOUBAO, ArenaService.KIMI), outcomes.keys)
                assertTrue(outcomes.toString(), outcomes.values.all { it.success })
            }
            assertEquals("0", evaluate(views.getValue(ArenaService.DEEPSEEK), "window.sendCount"))
        }
    }

    @Test fun cancelledNativeTouchCannotCancelImmediateSameProviderReplacement() {
        withPool(emptyMap()) { pool, views, attachment ->
            val view = views.getValue(ArenaService.DEEPSEEK)
            val done = CountDownLatch(1)
            var oldCallbacks = 0
            var outcome: SendOutcome? = null
            onMain {
                var replaced = false
                view.setOnTouchListener { _, event ->
                    if (!replaced && event.action == android.view.MotionEvent.ACTION_DOWN) {
                        replaced = true
                        // Post after the old DOWN is dispatched, before its scheduled UP.
                        view.post {
                            pool.cancelAutomation(ArenaService.DEEPSEEK)
                            pool.sendPromptWithAttachments(ArenaService.DEEPSEEK, "replacement", "new-touch", listOf(attachment)) {
                                outcome = it
                                done.countDown()
                            }
                        }
                    }
                    false
                }
                pool.sendPromptWithAttachments(ArenaService.DEEPSEEK, "obsolete", "old-touch", listOf(attachment)) { oldCallbacks++ }
            }
            assertTrue(done.await(15, TimeUnit.SECONDS))
            onMain { assertEquals(0, oldCallbacks); assertTrue(outcome.toString(), outcome?.success == true) }
            assertEquals("replacement", evaluate(view, "window.sentText"))
            assertEquals("1", evaluate(view, "window.sendCount"))
            verifyBytes(view, attachment)
        }
    }

    @Test fun navigatingOneUploadingPageDoesNotStopOtherProviders() {
        withPool(members.associateWith { 30_000L }) { pool, views, attachment ->
            val done = CountDownLatch(3)
            val outcomes = mutableMapOf<ArenaService, SendOutcome>()
            onMain { members.forEach { service ->
                pool.sendPromptWithAttachments(service, "navigate-${service.name}", "navigate-${service.name}", listOf(attachment)) {
                    outcomes[service] = it
                    done.countDown()
                }
            } }
            waitUntil("all uploads start") { views.values.all { JSONArray(evaluate(it, "JSON.stringify(window.received)")).length() == 1 } }
            onMain { views.getValue(ArenaService.DEEPSEEK).loadDataWithBaseURL("https://untrusted.invalid/", "<p>left provider</p>", "text/html", "UTF-8", null) }
            members.filter { it != ArenaService.DEEPSEEK }.forEach { evaluate(views.getValue(it), "window.completeFixture();true") }
            assertTrue(done.await(12, TimeUnit.SECONDS))
            onMain {
                assertFalse(outcomes.getValue(ArenaService.DEEPSEEK).success)
                assertTrue(outcomes.getValue(ArenaService.DOUBAO).success)
                assertTrue(outcomes.getValue(ArenaService.KIMI).success)
            }
        }
    }

    @Test fun globalStopRevokesEveryLeaseSuppressesLateCallbacksAndAllowsANewRequest() {
        withPool(members.associateWith { 30_000L }) { pool, views, attachment ->
            var oldCallbacks = 0
            onMain { members.forEach { service ->
                pool.sendPromptWithAttachments(service, "stopped-${service.name}", "stopped-${service.name}", listOf(attachment)) { oldCallbacks++ }
            } }
            waitUntil("all uploads start before global stop") { views.values.all { JSONArray(evaluate(it, "JSON.stringify(window.received)")).length() == 1 } }
            lateinit var uris: Map<ArenaService, Uri>
            onMain {
                uris = leaseUris(pool)
                pool.cancelAutomation()
            }
            uris.values.forEach { uri -> assertTrue(runCatching { context.contentResolver.openInputStream(uri)!!.close() }.isFailure) }
            views.values.forEach { evaluate(it, "window.completeFixture();true") }
            Thread.sleep(900)
            onMain { assertEquals(0, oldCallbacks) }
            views.values.forEach { assertEquals("0", evaluate(it, "window.sendCount")) }
            val view = views.getValue(ArenaService.DEEPSEEK)
            evaluate(view, "cards.innerHTML='';fileStates.length=0;received=[];upload.value='';true")
            val done = CountDownLatch(1)
            var outcome: SendOutcome? = null
            onMain {
                pool.sendPromptWithAttachments(ArenaService.DEEPSEEK, "after-stop", "after-stop", listOf(attachment)) {
                    outcome = it
                    done.countDown()
                }
            }
            waitUntil("new request receives attachment") { JSONArray(evaluate(view, "JSON.stringify(window.received)")).length() == 1 }
            evaluate(view, "window.completeFixture();true")
            assertTrue(done.await(8, TimeUnit.SECONDS))
            onMain { assertTrue(outcome.toString(), outcome?.success == true); assertEquals(0, oldCallbacks) }
            assertEquals("after-stop", evaluate(view, "window.sentText"))
            assertEquals("1", evaluate(view, "window.sendCount"))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun leaseUris(pool: ArenaWebViewPool): Map<ArenaService, Uri> {
        val broker = field(pool, "fileBroker")!!
        val requests = field(broker, "requests") as Map<WebView, Any>
        val leases = field(ArenaAttachmentLeases, "leases") as Map<String, ArenaAttachmentLeases.Lease>
        return requests.values.associate { request ->
            val owner = field(request, "leaseOwner") as String
            val token = leases.entries.single { it.value.owner == owner }.key
            (field(request, "service") as ArenaService) to Uri.parse("content://${context.packageName}.attachments/$token")
        }
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    // The frozen RED APK predates the native gesture field; absence means this path did not run.
    private fun optionalField(instance: Any, name: String): Any? = instance.javaClass.declaredFields.firstOrNull { it.name == name }?.apply { isAccessible = true }?.get(instance)

    @Suppress("UNCHECKED_CAST")
    private fun withPool(delays: Map<ArenaService, Long>, fileName: String = "probe.txt", block: (ArenaWebViewPool, Map<ArenaService, WebView>, ArenaAttachment) -> Unit) {
        val store = ArenaAttachmentStore(context)
        val attachment = store.importDocuments(listOf(AttachmentFixtureProvider.uri(fileName))).single()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var pool: ArenaWebViewPool
            lateinit var views: Map<ArenaService, WebView>
            scenario.onActivity { activity ->
                val poolField = MainActivity::class.java.getDeclaredField("webViewPool").apply { isAccessible = true }
                (poolField.get(activity) as ArenaWebViewPool).destroy()
                pool = ArenaWebViewPool(activity)
                poolField.set(activity, pool)
                pool.setProtectedServices(members.toSet())
                val host = FrameLayout(activity)
                activity.setContentView(host)
                host.addView(pool.container, FrameLayout.LayoutParams(-1, -1))
                members.forEach(pool::open)
                views = (field(pool, "webViews") as Map<ArenaService, WebView>).toMap()
                views.forEach { (service, view) ->
                    view.stopLoading()
                    view.loadDataWithBaseURL(service.url, fixture(service, delays[service] ?: 150L, attachment.mimeType.startsWith("image/")), "text/html", "UTF-8", service.url)
                }
                pool.show(null)
            }
            waitUntil("fixture pages loaded") { views.values.all { evaluate(it, "window.fixtureReady === true") == "true" } }
            onMain { members.forEach { pool.statuses[it] = ServiceStatus(ConnectionState.SIGNED_IN, "isolated fixture") } }
            try { block(pool, views, attachment) } finally { onMain { pool.destroy() } }
        }
        store.discardImported(listOf(attachment))
    }

    private fun waitUntil(message: String, timeoutMs: Long = 12_000L, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (predicate()) return
            Thread.sleep(75)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail(message)
    }

    private fun evaluate(view: WebView, script: String): String {
        val result = AtomicReference<String>()
        val done = CountDownLatch(1)
        onMain { view.evaluateJavascript(script) { raw -> result.set(JSONTokener(raw).nextValue().toString()); done.countDown() } }
        assertTrue("evaluateJavascript callback missing", done.await(6, TimeUnit.SECONDS))
        return result.get()
    }

    private fun verifyBytes(view: WebView, attachment: ArenaAttachment) {
        val received = JSONArray(evaluate(view, "JSON.stringify(window.received)"))
        assertEquals(1, received.length())
        val file = received.getJSONObject(0)
        assertEquals(attachment.name, file.getString("name"))
        val bytes = android.util.Base64.decode(file.getString("data").substringAfter(','), android.util.Base64.DEFAULT)
        assertEquals(attachment.sizeBytes, bytes.size.toLong())
        assertEquals(attachment.sha256, sha(bytes))
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fixture(service: ArenaService, delay: Long, currentImageUi: Boolean): String {
        val modernDoubao = currentImageUi && service == ArenaService.DOUBAO
        val area = if (modernDoubao) "<div id='cards' class='container-tJHWhP flex flex-col pl-12 pr-2'></div>"
            else "<div id='cards' data-testid='${if (service == ArenaService.KIMI) "input-attachment-list" else "attachment_area"}'></div>"
        val controls = if (service == ArenaService.KIMI) """
            <button class="toolkit-trigger-btn" id="fixture-toolkit" aria-haspopup="menu" aria-controls="menu" aria-expanded="false" onclick="this.setAttribute('aria-expanded','true');menu.innerHTML='<label class=&quot;toolkit-item&quot; role=&quot;menuitem&quot; style=&quot;display:block;width:140px;height:50px&quot;>Upload files<input id=&quot;upload&quot; type=&quot;file&quot; style=&quot;display:none&quot;></label>';upload.onchange=handle;">Add</button><div id="menu" role="menu" aria-labelledby="fixture-toolkit"></div>
        """ else if (modernDoubao) """
            <div class="relative"><input id="upload" type="file" accept="image/*" style="display:none">$area</div>
            <div class="guidance-input-actions"><button id="upload-trigger" data-slot="dropdown-menu-trigger" aria-haspopup="menu" onclick="document.getElementById('upload-menu').style.display='block'"></button></div>
        """ else """
            <button data-testid="upload_file_button" onclick="upload.click()">Upload files</button><input id="upload" type="file" accept=".txt,image/*" style="display:none">
        """
        return """
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>textarea{height:70px;width:240px}button{height:48px;min-width:110px}</style>
            <div ${if (service == ArenaService.DOUBAO) "id='input-engine-container'" else ""} class="_77cefa5 chat-editor ${if (modernDoubao) "guidance-input-surface" else ""}"><textarea id="chat-input" placeholder="Message"></textarea>$controls
            ${if (currentImageUi && service == ArenaService.KIMI) "<div class='send-button-container' style='width:80px;height:40px' onclick='send()'>Send<svg class='send-icon'></svg></div>" else "<button ${if (service == ArenaService.DOUBAO) "id='flow-end-msg-send'" else ""} class='send-msg-btn' aria-label='发送' onclick='send()'>Send</button>"}
            ${if (modernDoubao) "" else area}</div>
            ${if (modernDoubao) "<div id='upload-menu' role='menu' data-slot='dropdown-menu-content' aria-labelledby='upload-trigger' style='display:none'><div role='menuitem' data-slot='dropdown-menu-item' style='height:48px;width:180px'>选择云盘文件</div><div role='menuitem' data-slot='dropdown-menu-item' style='height:48px;width:180px' onclick=\"upload.click();document.getElementById('upload-menu').style.display='none'\">上传文件或图片</div></div>" else ""}
            <script>
            ${ArenaReactFixture.script}
            ${if (modernDoubao) "const plus=document.createElement('button');plus.setAttribute('data-dbx-name','button');plus.setAttribute('aria-haspopup','menu');plus.textContent='+';document.getElementById('upload-trigger').appendChild(plus);" else ""}
            window.fixtureReady=true;window.received=[];window.fileStates=[];window.sendCount=0;window.sentText='';
            const root={tag:3,stateNode:{}};root.stateNode.current=root;
            fixtureFiber(cards,{attachmentStates:fileStates},root);
            window.completeFixture=()=>{fileStates.forEach(f=>{f.status='${if(service == ArenaService.DOUBAO) "Normal" else "SUCCESS"}';f.parseState=1;
              if('${service.name}'==='DEEPSEEK'&&f.isImage&&!f.fileName.endsWith('.webp')){f.fileName=f.fileName.replace(/\.[^.]+${'$'}/,'')+'.webp';f.fileSize=Math.max(1,f.fileSize-1);}
              f.imageList=[{key:f.fileKey,image_ori:{url:'blob:https://www.doubao.com/local-preview'}}];});
              Array.from(cards.children).forEach(c=>{if('${service.name}'==='KIMI')c.className=(c.classList.contains('image-thumbnail')?'image-thumbnail ':'file-card-container ')+'success';});};
            function handle(event){for(const file of (event?.target||document.getElementById('upload')).files){
              const image=file.type.startsWith('image/');
              const state={fileName:file.name,fileSize:file.size,size:file.size,id:'id-'+file.name,localId:'local-'+file.name,isImage:image,auditResult:'pass',fileKey:'key-'+file.name,localKey:'local-'+file.name,type:image?'image':'file',status:'${if(service == ArenaService.DOUBAO) "Uploading" else "PENDING"}',parseState:3,reviewState:0};fileStates.push(state);
              if('${service.name}'==='DEEPSEEK'&&image&&$currentImageUi){state.fileName=file.name.replace(/\.[^.]+${'$'}/,'')+'.webp';state.fileSize=Math.max(1,file.size-1);}
              const card=document.createElement('div');card.style='height:60px;width:180px';
              if('${service.name}'==='DEEPSEEK'){card.className=image?'d5fa3d1b':'_25c7358';card.innerHTML=image?'<img alt="'+file.name+'" src="blob:https://chat.deepseek.com/local-preview">':'<span class="e70accd6">'+file.name+'</span>';fixtureFiber(card,{file:state},root);}
              else if('${service.name}'==='KIMI'){card.className=image?'image-thumbnail loading':'file-card-container parsing';card.innerHTML=image?'<img src="https://fixture.invalid/p.png">':'<span class="file-card-info-name">'+file.name.replace(/\.[^.]+${'$'}/,'')+'</span><span class="file-ext">txt</span>';}
              else {card.setAttribute('data-testid','attachment_file_item');card.textContent=file.name;}
              cards.appendChild(card);const reader=new FileReader();reader.onload=()=>{received.push({name:file.name,data:reader.result,at:Date.now()});setTimeout(completeFixture,$delay);};reader.readAsDataURL(file);
            }}
            const direct=document.getElementById('upload');if(direct)direct.onchange=handle;
            function send(){const input=document.querySelector('textarea');if(!input.value.trim())return;
              if(!fileStates.length||fileStates.some(f=>f.status!=='${if(service == ArenaService.DOUBAO) "Normal" else "SUCCESS"}'||f.parseState!==1))throw Error('send before attachment ready');
              window.sentText=input.value;window.sendCount++;input.value='';}
            </script>
        """.trimIndent()
    }
}
