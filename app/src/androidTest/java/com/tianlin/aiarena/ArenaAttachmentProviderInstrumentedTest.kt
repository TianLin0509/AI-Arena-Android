package com.tianlin.aiarena

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 千问、元宝的上传菜单与就绪判定，以及输入区残留草稿的清理。
 * 夹具按 2026-09-15 真实登录网页上观察到的结构缩写，所有网页与字节都是合成的。
 */
class ArenaAttachmentProviderInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private val image = ArenaAttachment("image", "photo.png", "image/png", 4096, "a".repeat(64))
    private val document = ArenaAttachment("doc", "notes.txt", "text/plain", 48, "b".repeat(64))

    @Test fun qwenOpensItsAttachMenuAndPicksTheItemForTheSelectedKind() {
        listOf(image to "qwen-image", document to "qwen-doc").forEach { (attachment, expectedItem) ->
            withView(qwenFixture(), ArenaService.QWEN) { view ->
                val id = "qwen-menu-${attachment.id}"
                assertEquals("true", evaluate(view, ArenaAttachmentScript.prepare(id, listOf(attachment), ArenaService.QWEN)))
                val trigger = JSONObject(evaluate(view, ArenaAttachmentScript.nextControl(id, ArenaService.QWEN)))
                assertTrue("A closed menu must offer its trigger: $trigger", trigger.has("x"))
                assertEquals("radix-attach", evaluate(view, "document.elementFromPoint(${trigger.getDouble("x")},${trigger.getDouble("y")}).closest('button').id"))
                evaluate(view, "openQwenMenu();true")
                val item = JSONObject(evaluate(view, ArenaAttachmentScript.nextControl(id, ArenaService.QWEN)))
                assertTrue("An open menu must offer the item for this kind: $item", item.has("x"))
                assertEquals(expectedItem, evaluate(view, "document.elementFromPoint(${item.getDouble("x")},${item.getDouble("y")}).closest('[role=menuitem]').id"))
                assertFalse("The chosen item is not tapped again immediately", JSONObject(evaluate(view, ArenaAttachmentScript.nextControl(id, ArenaService.QWEN))).has("x"))
                if (attachment == image) {
                    Thread.sleep(2_100)
                    val retry = JSONObject(evaluate(view, ArenaAttachmentScript.nextControl(id, ArenaService.QWEN)))
                    assertTrue("An unactivated item gets a bounded second tap: $retry", retry.has("x"))
                    assertTrue(retry.getBoolean("retryableTap"))
                }
            }
        }
    }

    @Test fun qwenReadinessFollowsRecordStatusForImageAndDocumentCards() {
        listOf(false, true).forEach { documentCard ->
            val attachment = if (documentCard) document else image
            withView(qwenFixture(), ArenaService.QWEN) { view ->
                val id = "qwen-ready-$documentCard"
                evaluate(view, ArenaAttachmentScript.prepare(id, listOf(attachment), ArenaService.QWEN))
                evaluate(view, "window.__arenaAttachment.chosen=true;window.current=addQwenCard(${ArenaJs.quote(attachment.name)},${attachment.sizeBytes},'${if (documentCard) "file" else "image"}',$documentCard);true")
                assertFalse("Uploading (3) is not ready", readiness(view, id, ArenaService.QWEN).getBoolean("ready"))
                evaluate(view, "current.recordStatus=0;current.uploadProgress=100;current.fileUrl='https://workspace-zb-cdn.qianwen.com/fixture';true")
                assertFalse("Parsing (0) is not ready", readiness(view, id, ArenaService.QWEN).getBoolean("ready"))
                evaluate(view, "current.recordStatus=1;true")
                assertTrue("Parse success (1) with a remote URL is ready", readiness(view, id, ArenaService.QWEN).getBoolean("ready"))
                evaluate(view, "current.recordStatus=-1;true")
                assertTrue("Upload failure stops before sending", readiness(view, id, ArenaService.QWEN).has("error"))
                evaluate(view, "current.recordStatus=2;true")
                assertTrue("Parse failure stops before sending", readiness(view, id, ArenaService.QWEN).has("error"))
                evaluate(view, "current.recordStatus=1;addQwenCard('extra.png',10,'image',false);true")
                assertTrue("An extra card stops before sending", readiness(view, id, ArenaService.QWEN).has("error"))
            }
        }
    }

    @Test fun yuanbaoPicksMenuItemsByReactKeyAndWaitsForFinish() {
        listOf(image to "yb-image", document to "yb-file").forEach { (attachment, expectedItem) ->
            withView(yuanbaoFixture(), ArenaService.YUANBAO) { view ->
                val id = "yuanbao-${attachment.id}"
                assertEquals("true", evaluate(view, ArenaAttachmentScript.prepare(id, listOf(attachment), ArenaService.YUANBAO)))
                val trigger = JSONObject(evaluate(view, ArenaAttachmentScript.nextControl(id, ArenaService.YUANBAO)))
                assertTrue(trigger.has("x"))
                assertEquals("yb-add", evaluate(view, "document.elementFromPoint(${trigger.getDouble("x")},${trigger.getDouble("y")}).closest('button').id"))
                evaluate(view, "openYuanbaoMenu();true")
                val item = JSONObject(evaluate(view, ArenaAttachmentScript.nextControl(id, ArenaService.YUANBAO)))
                assertTrue("Labels are in another language; the React key still identifies the item: $item", item.has("x"))
                assertEquals(expectedItem, evaluate(view, "document.elementFromPoint(${item.getDouble("x")},${item.getDouble("y")}).closest('[role=menuitem]').id"))

                evaluate(view, "window.__arenaAttachment.chosen=true;window.current=addYuanbaoItem(${ArenaJs.quote(attachment.name)},${attachment.sizeBytes},'${if (attachment == image) "image" else "txt"}');true")
                assertFalse("Loading is not ready even though the site enables its send button", readiness(view, id, ArenaService.YUANBAO).getBoolean("ready"))
                evaluate(view, "current.status='finish';current.progress=100;true")
                assertFalse("A finished card still needs its remote file ID", readiness(view, id, ArenaService.YUANBAO).getBoolean("ready"))
                evaluate(view, "current.fileId='remote-fixture';true")
                assertTrue(readiness(view, id, ArenaService.YUANBAO).getBoolean("ready"))
                evaluate(view, "current.status='error';true")
                assertTrue(readiness(view, id, ArenaService.YUANBAO).has("error"))
            }
        }
    }

    @Test fun leftoverDraftsAreRemovedOnlyThroughKnownDeleteControls() {
        withView(qwenFixture(), ArenaService.QWEN) { view ->
            evaluate(view, "addQwenCard('old.png',10,'image',false);addQwenCard('old.txt',10,'file',true);document.querySelectorAll('[data-icon-type=qwpcicon-close2]').forEach(icon=>icon.addEventListener('click',()=>icon.parentElement.remove()));true")
            assertTrue("Without cleanup the old draft still blocks the request", JSONObject(evaluate(view, ArenaAttachmentScript.prepare("blocked", listOf(image), ArenaService.QWEN))).has("error"))
            val removed = JSONObject(evaluate(view, ArenaAttachmentScript.leftovers(ArenaService.QWEN, remove = true)))
            assertEquals(2, removed.getInt("count"))
            assertEquals(2, removed.getInt("clicked"))
            assertEquals(0, JSONObject(evaluate(view, ArenaAttachmentScript.leftovers(ArenaService.QWEN, remove = false))).getInt("count"))
            assertEquals("true", evaluate(view, ArenaAttachmentScript.prepare("clean", listOf(image), ArenaService.QWEN)))
        }
        withView("<div class='_77cefa5'><textarea></textarea><div class='_25c7358' style='width:160px;height:60px'><span>old.txt</span><button id='other' onclick='window.otherClicked=true'>x</button></div></div>", ArenaService.DEEPSEEK) { view ->
            val result = JSONObject(evaluate(view, ArenaAttachmentScript.leftovers(ArenaService.DEEPSEEK, remove = true)))
            assertEquals(1, result.getInt("count"))
            assertEquals("A card without its known delete control is reported, not guessed", 0, result.getInt("clicked"))
            assertEquals("false", evaluate(view, "String(!!window.otherClicked)"))
        }
        withView("""
            <div data-testid='input-attachment-list'>
              <div class='image-thumbnail success' style='width:80px;height:80px'><img src='https://fixture.invalid/p.png'><div class='image-delete-container' style='width:16px;height:16px'></div></div>
              <div class='file-card-container normal success' style='width:160px;height:60px'><p class='file-card-info-name'>old</p><div class='file-card-delete toggle-icon' style='width:16px;height:16px'></div></div>
            </div>
            <script>document.querySelectorAll('.image-delete-container,.file-card-delete').forEach(control=>control.addEventListener('click',()=>control.parentElement.remove()));</script>
        """.trimIndent(), ArenaService.KIMI) { view ->
            val result = JSONObject(evaluate(view, ArenaAttachmentScript.leftovers(ArenaService.KIMI, remove = true)))
            assertEquals(2, result.getInt("clicked"))
            assertEquals(0, JSONObject(evaluate(view, ArenaAttachmentScript.leftovers(ArenaService.KIMI, remove = false))).getInt("count"))
        }
        withView("""
            <div class='guidance-input-surface'><div class='relative'><div><div class='container-tJHWhP'>
              <div aria-label='old.png' data-kind='image' role='button' style='width:60px;height:60px'><svg aria-label='delete' width='16' height='16'></svg></div>
            </div></div></div><textarea></textarea></div>
            <script>document.querySelector('svg[aria-label=delete]').addEventListener('click',event=>event.currentTarget.parentElement.remove());</script>
        """.trimIndent(), ArenaService.DOUBAO) { view ->
            val result = JSONObject(evaluate(view, ArenaAttachmentScript.leftovers(ArenaService.DOUBAO, remove = true)))
            assertEquals("Doubao's current wrapper adds one level around the area", 1, result.getInt("clicked"))
            assertEquals(0, JSONObject(evaluate(view, ArenaAttachmentScript.leftovers(ArenaService.DOUBAO, remove = false))).getInt("count"))
        }
        withView("<textarea></textarea>", ArenaService.ZHIPU) { view ->
            evaluate(view, ArenaAttachmentScript.prepare("zhipu", listOf(image), ArenaService.ZHIPU))
            assertTrue(JSONObject(evaluate(view, ArenaAttachmentScript.nextControl("zhipu", ArenaService.ZHIPU))).has("error"))
        }
    }

    @Test fun transportClearsLeftoverDraftsThenUploadsAndStopsWhenTheSiteKeepsRestoringThem() {
        listOf("none", "once", "forever").forEach { restore ->
            val imported = ArenaAttachmentStore(context).importDocuments(listOf(AttachmentFixtureProvider.uri("probe.txt")))
            val store = ArenaAttachmentStore(context)
            try {
                withView(deepSeekLeftoverFixture(restore), ArenaService.DEEPSEEK) { view, broker ->
                    val done = CountDownLatch(1)
                    val outcome = AtomicReference<String?>("pending")
                    onMain {
                        ArenaAttachmentTransport(Handler(Looper.getMainLooper()), broker)
                            .upload(view, ArenaService.DEEPSEEK, "leftover-$restore", imported.map { it to store.verify(it) }, { true }) { error ->
                                outcome.set(error)
                                done.countDown()
                            }
                    }
                    assertTrue("Upload for $restore did not settle", done.await(30, TimeUnit.SECONDS))
                    val received = JSONArray(evaluate(view, "JSON.stringify(window.received)"))
                    if (restore == "forever") {
                        assertNotNull("A draft the site keeps restoring must stop the request", outcome.get())
                        assertEquals("No file is delivered while an old draft remains", 0, received.length())
                        assertEquals("Cleanup is bounded", "2", evaluate(view, "String(window.leftoverRemoved)"))
                    } else {
                        assertNull("$restore: cleanup should let the upload continue", outcome.get())
                        assertEquals(1, received.length())
                        assertEquals("probe.txt", received.getJSONObject(0).getString("name"))
                        assertEquals("0", evaluate(view, "String(document.querySelectorAll('.leftover').length)"))
                    }
                }
            } finally {
                store.discardImported(imported)
            }
        }
    }

    @Test fun yuanbaoSentDetectionIgnoresNavigationGuidePopups() {
        withView("""
            <div class="yb-nav__user"><div class="auto-search-guide-popup__content" id="guide" style="display:none">guide</div></div>
            <div class="agent-chat__list" id="list"></div>
        """.trimIndent(), ArenaService.YUANBAO) { view ->
            val state = JSONObject(evaluate(view, ArenaWebCursorScript.prepare(ArenaService.YUANBAO, "yuanbao-sent")))
            assertEquals(0, state.getInt("userBaseline"))
            val advanced = "(function(){ ${ArenaWebCursorScript.stateBootstrap("yuanbao-sent")} return ${ArenaWebCursorScript.conversationAdvancedExpression(ArenaService.YUANBAO)}; })()"
            evaluate(view, "document.getElementById('guide').style.display='block';true")
            assertEquals("A navigation guide popup is not a sent question", "false", evaluate(view, advanced))
            evaluate(view, "document.getElementById('list').innerHTML='<div class=\"agent-chat__list__item agent-chat__list__item--human\">question</div>';true")
            assertEquals("true", evaluate(view, advanced))
        }
    }

    @Test fun kimiUpgradeModalReportsNotSentInsteadOfWaiting() {
        val modal = "<div><div class='modal-mask' style='position:fixed;inset:0'><div class='modal-container'><div class='body'>Currently available to Moderato/Plus and higher-tier members. Upgrade your membership to enjoy more benefits.</div><button>Got it</button></div></div></div>"
        withView(modal, ArenaService.KIMI) { view ->
            val payload = JSONObject(evaluate(view, ArenaWebResponseScript.build(ArenaService.KIMI, "kimi-upgrade")))
            assertTrue("An upgrade modal without our question must be reported: $payload", payload.optString("error").contains("会员"))
        }
        withView("<div class='chat-content-item-user' data-ai-arena-request='kimi-sent'>question</div>$modal", ArenaService.KIMI) { view ->
            val payload = JSONObject(evaluate(view, ArenaWebResponseScript.build(ArenaService.KIMI, "kimi-sent")))
            assertFalse("A question that was already sent keeps waiting for its answer: $payload", payload.optString("error").contains("会员"))
        }
    }

    private fun readiness(view: WebView, id: String, service: ArenaService) = JSONObject(evaluate(view, ArenaAttachmentScript.readiness(id, service)))

    private fun qwenFixture(): String = """
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>button,[role=menuitem]{display:block;min-width:120px;min-height:44px}[contenteditable]{min-height:40px;width:240px}</style>
        <div data-chat-input-shell="true">
          <div data-chat-input-top-content="true" id="top"></div>
          <div role="textbox" contenteditable="true" data-slate-editor="true"></div>
          <button type="button" id="radix-attach" aria-label="添加附件" aria-haspopup="menu" aria-expanded="false">+</button>
        </div>
        <div id="qwen-menu" role="menu" aria-labelledby="radix-attach" style="display:none">
          <div role="menuitem" id="qwen-doc">上传文档</div><div role="menuitem" id="qwen-image">上传图片</div><div role="menuitem">拍照</div>
        </div>
        <script>
        window.openQwenMenu=()=>{document.getElementById('radix-attach').setAttribute('aria-expanded','true');document.getElementById('qwen-menu').style.display='block';};
        window.qwenRoot={tag:3,stateNode:{}};qwenRoot.stateNode.current=qwenRoot;
        window.addQwenCard=(name,size,type,spread)=>{
          const record={recordId:'upload_'+name,recordType:type,fileName:name,fileSize:size,recordStatus:3,uploadProgress:10,fileUrl:''};
          const card=document.createElement('div');card.style='width:60px;height:60px;display:inline-block';
          card.innerHTML='<span data-icon-type="qwpcicon-close2" style="display:inline-block;width:16px;height:16px"></span>';
          const owner=fixtureNode(spread?Object.assign(record,{del(){}}):{record,del(){}},qwenRoot);
          fixtureFiber(card,{},owner);document.getElementById('top').appendChild(card);return record;
        };
        </script>
    """.trimIndent()

    private fun yuanbaoFixture(): String = """
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>button{display:block;min-width:120px;min-height:44px}.ql-editor{min-height:40px;width:240px}</style>
        <div data-new-input-card="true">
          <div data-input-resource-area="true" id="area"></div>
          <div class="ql-editor" contenteditable="true"></div>
          <div data-new-input-control="add-tools">
            <button type="button" id="yb-add" data-new-input-control="add-tools-trigger" aria-expanded="false" aria-label="Add">+</button>
            <div role="menu" id="yb-menu" style="display:none"><button role="menuitem" id="yb-image">Bild hochladen</button><button role="menuitem" id="yb-file">Lokale Dateien</button></div>
          </div>
        </div>
        <script>
        window.ybRoot={tag:3,stateNode:{}};ybRoot.stateNode.current=ybRoot;
        const keyed=(id,key)=>{const holder=fixtureNode({},ybRoot);holder.key=key;fixtureFiber(document.getElementById(id),{role:'menuitem'},holder);};
        keyed('yb-file','local_file');keyed('yb-image','upload_pic');
        window.openYuanbaoMenu=()=>{document.getElementById('yb-add').setAttribute('aria-expanded','true');document.getElementById('yb-menu').style.display='block';};
        window.addYuanbaoItem=(name,size,type)=>{
          const file={type,name,size,status:'loading',progress:0,fileId:'',id:'local-'+name};
          const item=document.createElement('div');item.style='width:60px;height:60px;display:inline-block';
          item.innerHTML='<div aria-label="删除文件" style="width:16px;height:16px"></div>';
          fixtureFiber(item,{file},ybRoot);document.getElementById('area').appendChild(item);return file;
        };
        </script>
    """.trimIndent()

    private fun deepSeekLeftoverFixture(restore: String): String = """
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <div class="_77cefa5"><textarea></textarea><button data-testid="upload_file_button" onclick="upload.click()">Upload files</button><input id="upload" type="file" multiple accept="image/*,.pdf,.txt" style="display:none"><div id="cards"></div></div>
        <script>
        window.received=[];window.leftoverRemoved=0;const root={tag:3,stateNode:{}};root.stateNode.current=root;
        window.addLeftover=()=>{
          const card=document.createElement('div');card.className='_25c7358 leftover';card.style='height:60px;width:160px';
          card.innerHTML='<span class="e70accd6">old.txt</span><div tabindex="0" style="width:20px;height:20px"><div class="ds-icon"></div></div>';
          card.lastElementChild.addEventListener('click',()=>{
            window.leftoverRemoved++;card.remove();
            if('$restore'==='forever'||('$restore'==='once'&&window.leftoverRemoved===1))setTimeout(addLeftover,300);
          });
          fixtureFiber(card,{file:{fileName:'old.txt',fileSize:10,localId:'old-local-'+Date.now(),id:'old',status:'SUCCESS'}},root);cards.appendChild(card);
        };
        if('$restore'!=='none')addLeftover();
        document.getElementById('upload').onchange=()=>{for(const file of document.getElementById('upload').files){
          const card=document.createElement('div');card.className='_25c7358';card.style='height:60px;width:160px';card.innerHTML='<span class="e70accd6">'+file.name+'</span>';
          const state={fileName:file.name,fileSize:file.size,localId:'local-'+file.name,id:'local-'+file.name,status:'PENDING'};
          fixtureFiber(card,{file:state},root);cards.appendChild(card);
          const reader=new FileReader();reader.onload=()=>{received.push({name:file.name,size:file.size});setTimeout(()=>{state.status='SUCCESS';state.id='remote-'+file.name;},150);};reader.readAsArrayBuffer(file);
        }};
        </script>
    """.trimIndent()

    private fun withView(html: String, service: ArenaService, block: (WebView) -> Unit) = withView(html, service) { view, _ -> block(view) }

    private fun withView(html: String, service: ArenaService, block: (WebView, ArenaFileChooserBroker) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var view: WebView
            lateinit var broker: ArenaFileChooserBroker
            val loaded = CountDownLatch(1)
            scenario.onActivity { activity ->
                broker = ArenaFileChooserBroker(activity)
                view = WebView(activity)
                view.settings.javaScriptEnabled = true
                activity.setContentView(view)
                view.webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() } }
                view.webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                        if (!broker.handle(view, callback, params)) callback.onReceiveValue(null)
                        return true
                    }
                }
                view.loadDataWithBaseURL(service.url, "<script>${ArenaReactFixture.script}</script>$html", "text/html", "UTF-8", service.url)
            }
            assertTrue(loaded.await(15, TimeUnit.SECONDS))
            try { block(view, broker) } finally { onMain { broker.cancelAll(); view.destroy() } }
        }
    }

    private fun evaluate(view: WebView, script: String): String {
        val result = AtomicReference<String>()
        val done = CountDownLatch(1)
        onMain { view.evaluateJavascript(script) { raw -> result.set(JSONTokener(raw).nextValue().toString()); done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return result.get()
    }
}
