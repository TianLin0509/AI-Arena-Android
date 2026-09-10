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

    @Test fun kimiOpenUploadLabelRecoversLostTapWithBoundAndKeepsChangedControlsUntouched() {
        listOf("recover", "exhausted", "closed", "replaced").forEach { mode ->
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
                    assertEquals("Only the menu trigger and one valid local upload may receive native DOWN", 2, nativeDowns.get())
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
            <div class="_77cefa5 chat-editor ${if (modernDoubao) "guidance-input-surface" else ""}"><textarea id="chat-input" placeholder="Message"></textarea>$controls
            ${if (currentImageUi && service == ArenaService.KIMI) "<div class='send-button-container' style='width:80px;height:40px' onclick='send()'>Send<svg class='send-icon'></svg></div>" else "<button class='send-msg-btn' aria-label='发送' onclick='send()'>Send</button>"}
            ${if (modernDoubao) "" else area}</div>
            ${if (modernDoubao) "<div id='upload-menu' role='menu' data-slot='dropdown-menu-content' aria-labelledby='upload-trigger' style='display:none'><div role='menuitem' data-slot='dropdown-menu-item' style='height:48px;width:180px'>选择云盘文件</div><div role='menuitem' data-slot='dropdown-menu-item' style='height:48px;width:180px' onclick=\"upload.click();document.getElementById('upload-menu').style.display='none'\">上传文件或图片</div></div>" else ""}
            <script>
            ${if (modernDoubao) "const plus=document.createElement('button');plus.setAttribute('data-dbx-name','button');plus.setAttribute('aria-haspopup','menu');plus.textContent='+';document.getElementById('upload-trigger').appendChild(plus);" else ""}
            window.fixtureReady=true;window.received=[];window.fileStates=[];window.sendCount=0;window.sentText='';
            const root={tag:3,stateNode:{}};root.stateNode.current=root;
            cards['__reactFiber${'$'}fixture']={memoizedProps:{attachmentStates:fileStates},return:root};
            window.completeFixture=()=>{fileStates.forEach(f=>{f.status='${if(service == ArenaService.DOUBAO) "Normal" else "SUCCESS"}';f.parseState=1;
              if('${service.name}'==='DEEPSEEK'&&f.isImage&&!f.fileName.endsWith('.webp')){f.fileName=f.fileName.replace(/\.[^.]+${'$'}/,'')+'.webp';f.fileSize=Math.max(1,f.fileSize-1);}
              f.imageList=[{key:f.fileKey,image_ori:{url:'blob:https://www.doubao.com/local-preview'}}];});
              Array.from(cards.children).forEach(c=>{if('${service.name}'==='KIMI')c.className=(c.classList.contains('image-thumbnail')?'image-thumbnail ':'file-card-container ')+'success';});};
            function handle(event){for(const file of (event?.target||document.getElementById('upload')).files){
              const image=file.type.startsWith('image/');
              const state={fileName:file.name,fileSize:file.size,size:file.size,id:'id-'+file.name,localId:'local-'+file.name,isImage:image,auditResult:'pass',fileKey:'key-'+file.name,localKey:'local-'+file.name,type:image?'image':'file',status:'${if(service == ArenaService.DOUBAO) "Uploading" else "PENDING"}',parseState:3,reviewState:0};fileStates.push(state);
              if('${service.name}'==='DEEPSEEK'&&image&&$currentImageUi){state.fileName=file.name.replace(/\.[^.]+${'$'}/,'')+'.webp';state.fileSize=Math.max(1,file.size-1);}
              const card=document.createElement('div');card.style='height:60px;width:180px';
              if('${service.name}'==='DEEPSEEK'){card.className=image?'d5fa3d1b':'_25c7358';card.innerHTML=image?'<img alt="'+file.name+'" src="blob:https://chat.deepseek.com/local-preview">':'<span class="e70accd6">'+file.name+'</span>';card['__reactFiber${'$'}fixture']={memoizedProps:{file:state},return:root};}
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
