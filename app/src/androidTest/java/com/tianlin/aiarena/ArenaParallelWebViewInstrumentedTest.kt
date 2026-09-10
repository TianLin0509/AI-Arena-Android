package com.tianlin.aiarena

import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
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

/** Production pool/client/broker/scripts, with isolated pages shaped like the three provider editors. */
class ArenaParallelWebViewInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val members = ArenaService.defaultMembers
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun slowUploadDoesNotBlockOtherProvidersAndTabChangesKeepFilesAndPromptsSeparate() {
        withPool(mapOf(ArenaService.DEEPSEEK to 8_000L)) { pool, views, attachment ->
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
    private fun withPool(delays: Map<ArenaService, Long>, block: (ArenaWebViewPool, Map<ArenaService, WebView>, ArenaAttachment) -> Unit) {
        val store = ArenaAttachmentStore(context)
        val attachment = store.importDocuments(listOf(AttachmentFixtureProvider.uri("probe.txt"))).single()
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
                    view.loadDataWithBaseURL(service.url, fixture(service, delays[service] ?: 150L), "text/html", "UTF-8", service.url)
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

    private fun fixture(service: ArenaService, delay: Long): String {
        val controls = if (service == ArenaService.KIMI) """
            <button class="toolkit-trigger-btn" onclick="menu.innerHTML='<label class=&quot;toolkit-item&quot; role=&quot;menuitem&quot; style=&quot;display:block;width:140px;height:50px&quot;>Upload files<input id=&quot;upload&quot; type=&quot;file&quot; style=&quot;display:none&quot;></label>';upload.onchange=handle;">Add</button><div id="menu"></div>
        """ else """
            <button data-testid="upload_file_button" onclick="upload.click()">Upload files</button><input id="upload" type="file" accept=".txt" style="display:none">
        """
        return """
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>textarea{height:70px;width:240px}button{height:48px;min-width:110px}</style>
            <div class="_77cefa5"><textarea id="chat-input" placeholder="Message"></textarea>$controls
            <button class="send-msg-btn" aria-label="发送" onclick="send()">Send</button>
            <div id="cards" data-testid="${if(service == ArenaService.KIMI) "input-attachment-list" else "attachment_area"}"></div></div>
            <script>
            window.fixtureReady=true;window.received=[];window.fileStates=[];window.sendCount=0;window.sentText='';
            const root={tag:3,stateNode:{}};root.stateNode.current=root;
            cards['__reactFiber${'$'}fixture']={memoizedProps:{attachmentStates:fileStates},return:root};
            window.completeFixture=()=>{fileStates.forEach(f=>{f.status='${if(service == ArenaService.DOUBAO) "Normal" else "SUCCESS"}';f.parseState=1;});
              Array.from(cards.children).forEach(c=>{if('${service.name}'==='KIMI')c.className='file-card-container success';});};
            function handle(){for(const file of upload.files){
              const state={fileName:file.name,fileSize:file.size,size:file.size,id:'id-'+file.name,fileKey:'key-'+file.name,localKey:'local-'+file.name,type:'file',status:'${if(service == ArenaService.DOUBAO) "Uploading" else "PENDING"}',parseState:3,reviewState:0};fileStates.push(state);
              const card=document.createElement('div');card.style='height:60px;width:180px';
              if('${service.name}'==='DEEPSEEK'){card.className='_25c7358';card.innerHTML='<span class="e70accd6">'+file.name+'</span>';card['__reactFiber${'$'}fixture']={memoizedProps:{file:state},return:root};}
              else if('${service.name}'==='KIMI'){card.className='file-card-container parsing';card.innerHTML='<span class="file-card-info-name">'+file.name.replace(/\.[^.]+${'$'}/,'')+'</span><span class="file-ext">txt</span>';}
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
