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
            <button class="toolkit-trigger-btn" onclick="menu.innerHTML='<label class=&quot;toolkit-item&quot; role=&quot;menuitem&quot; style=&quot;display:block;width:140px;height:50px&quot;>Upload files<input id=&quot;upload&quot; type=&quot;file&quot; style=&quot;display:none&quot;></label>';upload.onchange=handle;">Add</button><div id="menu"></div>
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
            function handle(){for(const file of upload.files){
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
