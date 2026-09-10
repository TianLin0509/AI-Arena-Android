package com.tianlin.aiarena

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual WebView content-URI transfers; all bytes and sites are isolated synthetic fixtures. */
class ArenaAttachmentInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val names = listOf("probe.png", "probe.pdf", "probe.txt")
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun imported() = ArenaAttachmentStore(context).importDocuments(names.map(AttachmentFixtureProvider::uri))

    @Test fun privateCopiesSurviveStoreReopenAndCorruptionIsRejected() {
        val files = imported()
        val reopened = ArenaAttachmentStore(context)
        files.forEach { attachment ->
            assertArrayEquals(AttachmentFixtureProvider.bytes(attachment.name), reopened.verify(attachment).readBytes())
        }
        reopened.dataFile(files.first().id).appendText("corrupt")
        assertTrue(runCatching { reopened.verify(files.first()) }.isFailure)
        reopened.discardImported(files)
    }

    @Test fun unknownSizeOverLimitRollsBackEntireBatch() {
        val store = ArenaAttachmentStore(context)
        val directory = File(context.filesDir, "arena_attachments")
        val before = directory.list()?.toSet().orEmpty()
        val outcome = runCatching { store.importDocuments(listOf(AttachmentFixtureProvider.uri("probe.png"), AttachmentFixtureProvider.uri("too-big.txt"))) }
        assertTrue(outcome.isFailure)
        assertEquals(before, directory.list()?.toSet().orEmpty())
    }

    @Test fun garbageCollectionKeepsDiskSessionsDraftsLeasesAndUnreadableReferences() {
        val store = ArenaAttachmentStore(context)
        val old = imported()
        val leased = store.importDocuments(listOf(AttachmentFixtureProvider.uri("probe.txt")))
        val leaseOwner = "gc-${System.nanoTime()}"
        ArenaAttachmentLeases.issue(context, leaseOwner, leased.single(), store.verify(leased.single()))
        val sessions = File(context.filesDir, "arena_sessions").apply { mkdirs() }
        val reference = File(sessions, "session_gc_${System.nanoTime()}.json")
        reference.writeText(JSONObject().put("version", ArenaSessionJson.SCHEMA_VERSION).put("id", reference.nameWithoutExtension)
            .put("originalQuestion", "Synthetic attachment retention check").put("services", JSONArray()).put("runs", JSONObject())
            .put("history", JSONArray()).put("summary", JSONObject())
            .put("lastRoundAttachments", JSONArray().put(JSONObject().put("id", old[0].id))).toString())
        val fresh = store.importDocuments(listOf(AttachmentFixtureProvider.uri("probe.txt")), setOf(old[1].id))
        assertTrue(store.dataFile(old[0].id).isFile)
        assertTrue(store.dataFile(old[1].id).isFile)
        assertFalse(store.dataFile(old[2].id).exists())
        assertTrue(store.dataFile(leased.single().id).isFile)
        reference.writeText("{broken-json")
        val last = store.importDocuments(listOf(AttachmentFixtureProvider.uri("probe.txt")), emptySet())
        assertTrue(store.dataFile(old[1].id).isFile)
        assertTrue(store.dataFile(fresh[0].id).isFile)
        assertTrue(reference.delete())
        ArenaAttachmentLeases.revoke(leaseOwner)
        store.discardImported(old + fresh + last + leased)
    }

    @Test fun missingVendorStructureAndDecoySuccessNeverPermitSendingOrAvatarUpload() {
        withView("<button aria-label='Upload avatar'>Upload avatar</button><div class='upload-success' data-status='success'>probe.txt uploaded success</div>") { view, _ ->
            val attachment = ArenaAttachment("fixture", "probe.txt", "text/plain", 40, "a".repeat(64))
            ArenaService.defaultMembers.forEach { service ->
                evaluate(view, ArenaAttachmentScript.prepare("decoy", listOf(attachment), service))
                evaluate(view, "window.__arenaAttachment.chosen=true;true")
                assertFalse(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("decoy", service))).getBoolean("ready"))
                assertFalse(JSONObject(evaluate(view, ArenaAttachmentScript.nextControl("decoy", service))).has("x"))
            }
        }
    }

    @Test fun vendorReadinessUsesCurrentReactCommitAndParseSuccess() {
        withView("<div class='_77cefa5'><textarea></textarea><div id='newcard'></div></div>") { view, _ ->
            val attachment = ArenaAttachment("fixture", "probe.txt", "text/plain", 40, "a".repeat(64))
            assertEquals("true", evaluate(view, ArenaAttachmentScript.prepare("vendor", listOf(attachment), ArenaService.DEEPSEEK)))
            evaluate(view, """
                window.__arenaAttachment.chosen=true;
                const card=document.createElement('div');card.className='_25c7358';card.style='height:40px';card.innerHTML='<span class="e70accd6">probe.txt</span>';newcard.appendChild(card);
                const root={tag:3,stateNode:{}},oldRoot={tag:3,stateNode:root.stateNode};root.stateNode.current=root;
                window.currentFile={fileName:'probe.txt',fileSize:40,id:'remote-id',status:'PENDING'};
                const current={memoizedProps:{file:currentFile},return:root},stale={memoizedProps:{file:{...currentFile,status:'SUCCESS'}},return:oldRoot,alternate:current};current.alternate=stale;card['__reactFiber${'$'}fixture']=stale;true;
            """.trimIndent())
            assertFalse(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("vendor", ArenaService.DEEPSEEK))).getBoolean("ready"))
            evaluate(view, "currentFile.status='SUCCESS';true")
            assertTrue(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("vendor", ArenaService.DEEPSEEK))).getBoolean("ready"))
            evaluate(view, "currentFile.status='FAILED';true")
            assertTrue(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("vendor", ArenaService.DEEPSEEK))).has("error"))
        }
        withView("<div data-testid='attachment_area' id='area'></div>") { view, _ ->
            val attachment = ArenaAttachment("fixture", "probe.txt", "text/plain", 40, "a".repeat(64))
            evaluate(view, ArenaAttachmentScript.prepare("doubao", listOf(attachment), ArenaService.DOUBAO))
            evaluate(view, """
                window.__arenaAttachment.chosen=true;const root={tag:3,stateNode:{}};root.stateNode.current=root;
                window.fileState={fileName:'probe.txt',size:40,type:'file',fileKey:'remote',localKey:'local',status:'Normal',parseState:3,reviewState:0};
                area['__reactFiber${'$'}fixture']={memoizedProps:{attachmentStates:[fileState]},return:root};true;
            """.trimIndent())
            assertFalse(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("doubao", ArenaService.DOUBAO))).getBoolean("ready"))
            evaluate(view, "fileState.parseState=1;true")
            assertTrue(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("doubao", ArenaService.DOUBAO))).getBoolean("ready"))
            evaluate(view, "fileState.parseState=2;true")
            assertTrue(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("doubao", ArenaService.DOUBAO))).has("error"))
        }
    }

    @Test fun kimiImagesRequireObservedLoadingToSuccessNotUnspecifiedAppearance() {
        withView("<div data-testid='input-attachment-list' id='area'></div>") { view, _ ->
            val attachment = ArenaAttachment("fixture", "probe.png", "image/png", 40, "a".repeat(64))
            evaluate(view, ArenaAttachmentScript.prepare("kimi", listOf(attachment), ArenaService.KIMI))
            evaluate(view, "window.__arenaAttachment.chosen=true;area.innerHTML='<div class=\"image-thumbnail success\" style=\"height:80px;width:80px\"><img src=\"https://fixture.invalid/p.png\"></div>';true")
            assertFalse(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("kimi", ArenaService.KIMI))).getBoolean("ready"))
            evaluate(view, "area.firstChild.className='image-thumbnail loading';true")
            evaluate(view, "area.firstChild.className='image-thumbnail success';true")
            assertTrue(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("kimi", ArenaService.KIMI))).getBoolean("ready"))
            evaluate(view, "area.firstChild.className='image-thumbnail error';true")
            assertTrue(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("kimi", ArenaService.KIMI))).has("error"))
            assertTrue(JSONObject(evaluate(view, ArenaAttachmentScript.prepare("old-draft", listOf(attachment), ArenaService.KIMI))).has("error"))
        }
    }

    @Test fun doubaoUploadedImageKeepsBlobPreviewWhileRemoteKeyConfirmsUpload() {
        withView("<div data-testid='attachment_area' id='area'></div>") { view, _ ->
            val attachment = ArenaAttachment("fixture", "photo.png", "image/png", 4096, "a".repeat(64))
            evaluate(view, ArenaAttachmentScript.prepare("blob-image", listOf(attachment), ArenaService.DOUBAO))
            evaluate(view, """
                window.__arenaAttachment.chosen=true;const root={tag:3,stateNode:{}};root.stateNode.current=root;
                window.fileState={fileName:'photo.png',size:4096,type:'image',fileKey:'remote-upload-key',localKey:'local-file-key',status:'Normal',parseState:0,reviewState:0,
                  imageList:[{key:'remote-upload-key',image_ori:{url:'blob:https://www.doubao.com/local-preview'},image_thumb:{url:'blob:https://www.doubao.com/local-preview'}}]};
                area['__reactFiber${'$'}fixture']={memoizedProps:{attachmentStates:[fileState]},return:root};true;
            """.trimIndent())
            assertTrue("Current Doubao upload state retains blob previews after successful upload", JSONObject(evaluate(view, ArenaAttachmentScript.readiness("blob-image", ArenaService.DOUBAO))).getBoolean("ready"))
            evaluate(view, "fileState.status='Uploading';true")
            assertFalse(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("blob-image", ArenaService.DOUBAO))).getBoolean("ready"))
            evaluate(view, "fileState.status='Normal';fileState.fileKey='';true")
            assertFalse(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("blob-image", ArenaService.DOUBAO))).getBoolean("ready"))
            evaluate(view, "fileState.fileKey='unrelated-upload-key';true")
            assertFalse(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("blob-image", ArenaService.DOUBAO))).getBoolean("ready"))
            evaluate(view, "fileState.fileKey='remote-upload-key';fileState.reviewState=3;true")
            assertFalse(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("blob-image", ArenaService.DOUBAO))).getBoolean("ready"))
            evaluate(view, "fileState.reviewState=2;true")
            assertTrue(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("blob-image", ArenaService.DOUBAO))).has("error"))
        }
    }

    @Test fun deepSeekImageThumbnailUsesCommittedFileMetadataWithoutDocumentCaption() {
        withView("<div class='_77cefa5'><textarea></textarea><div id='cards'></div></div>") { view, _ ->
            val attachment = ArenaAttachment("fixture", "photo.png", "image/png", 4096, "a".repeat(64))
            evaluate(view, ArenaAttachmentScript.prepare("ds-image", listOf(attachment), ArenaService.DEEPSEEK))
            evaluate(view, """
                window.__arenaAttachment.chosen=true;const root={tag:3,stateNode:{}};root.stateNode.current=root;
                window.imageFile={fileName:'photo.png',fileSize:4096,isImage:true,id:'remote-file-id',localId:'local_file_7',signedPath:'/remote/image',status:'SUCCESS',auditResult:'pass'};
                const card=document.createElement('div');card.className='d5fa3d1b';card.style='height:80px;width:80px';card.innerHTML='<img alt="photo.png" src="blob:https://chat.deepseek.com/local-preview">';
                card['__reactFiber${'$'}fixture']={memoizedProps:{fileName:'photo.png'},return:{memoizedProps:{file:imageFile,fileUploadInfo:{isUploading:false,failed:false},fileErrorState:{hasError:false}},return:root}};cards.appendChild(card);true;
            """.trimIndent())
            assertTrue("DeepSeek image thumbnails have no document caption element", JSONObject(evaluate(view, ArenaAttachmentScript.readiness("ds-image", ArenaService.DEEPSEEK))).getBoolean("ready"))
            evaluate(view, "imageFile.status='PARSING';true")
            assertFalse(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("ds-image", ArenaService.DEEPSEEK))).getBoolean("ready"))
            evaluate(view, "imageFile.status='SUCCESS';imageFile.auditResult='reject';true")
            assertTrue(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("ds-image", ArenaService.DEEPSEEK))).has("error"))
            assertTrue("Existing unsent image must block accidental duplicate upload", JSONObject(evaluate(view, ArenaAttachmentScript.prepare("next-image", listOf(attachment), ArenaService.DEEPSEEK))).has("error"))
        }
    }

    @Test fun deepSeekDeepReactTreesReadOnlyTheCommittedBranchAndRejectCycles() {
        withView("<div class='_77cefa5'><textarea></textarea><div id='cards'></div></div>") { view, _ ->
            val attachment = ArenaAttachment("fixture", "photo.webp", "image/webp", 4096, "a".repeat(64))
            evaluate(view, ArenaAttachmentScript.prepare("deep-fiber", listOf(attachment), ArenaService.DEEPSEEK))
            evaluate(view, """
                window.__arenaAttachment.chosen=true;
                window.installTree=depth=>{
                  cards.innerHTML='';window.rootState={};window.primaryRoot={tag:3,stateNode:rootState};window.alternateRoot={tag:3,stateNode:rootState};rootState.current=alternateRoot;
                  const file=status=>({fileName:'photo.webp',fileSize:4096,isImage:true,id:'remote-id',localId:'local-id',status,auditResult:'pass'});
                  window.primaryProps={file:file('SUCCESS')};window.alternateProps={file:file('PENDING')};
                  const chain=(root,props)=>{let parent=root;for(let i=1;i<depth;i++)parent={memoizedProps:{},return:parent};return {memoizedProps:props,return:parent};};
                  window.primaryFiber=chain(primaryRoot,primaryProps);window.alternateFiber=chain(alternateRoot,alternateProps);primaryFiber.alternate=alternateFiber;alternateFiber.alternate=primaryFiber;
                  const card=document.createElement('div');card.className='d5fa3d1b';card.style='height:80px;width:80px';card['__reactFiber${'$'}fixture']=primaryFiber;cards.appendChild(card);
                };true;
            """.trimIndent())
            fun ready() = JSONObject(evaluate(view, ArenaAttachmentScript.readiness("deep-fiber", ArenaService.DEEPSEEK))).getBoolean("ready")
            listOf(104, 256).forEach { depth ->
                evaluate(view, "installTree($depth);true")
                assertFalse("Uncommitted SUCCESS must not override the current pending branch at depth $depth", ready())
                evaluate(view, "alternateProps.file.status='SUCCESS';true")
                assertTrue("Committed attachment remains readable at depth $depth", ready())
                evaluate(view, "primaryProps.file.status='PENDING';rootState.current=primaryRoot;true")
                assertFalse("Changing HostRoot.current must invalidate the old successful alternate", ready())
                evaluate(view, "primaryProps.file.status='SUCCESS';true")
                assertTrue(ready())
            }
            evaluate(view, "primaryFiber.return=primaryFiber;alternateFiber.return=alternateFiber;true")
            assertFalse("Cyclic return links must fail closed without hanging", ready())
            evaluate(view, "installTree(513);alternateProps.file.status='SUCCESS';true")
            assertFalse("Trees beyond the traversal bound must fail closed", ready())
        }
    }

    @Test fun modernDoubaoOnlyUsesTheComposerLinkedLocalUploadMenu() {
        withView(modernDoubaoFixture()) { view, _ ->
            val attachment = ArenaAttachment("fixture", "photo.png", "image/png", 4096, "a".repeat(64))
            evaluate(view, ArenaAttachmentScript.prepare("modern-menu", listOf(attachment), ArenaService.DOUBAO))
            val trigger = JSONObject(evaluate(view, ArenaAttachmentScript.nextControl("modern-menu", ArenaService.DOUBAO)))
            assertTrue("Current guidance composer must offer its upload menu", trigger.has("x"))
            assertEquals("local-trigger", evaluate(view, "document.elementFromPoint(${trigger.getDouble("x")},${trigger.getDouble("y")}).closest('[data-slot=dropdown-menu-trigger]').id"))
            evaluate(view, "document.getElementById('local-menu').style.display='block';true")
            val item = JSONObject(evaluate(view, ArenaAttachmentScript.nextControl("modern-menu", ArenaService.DOUBAO)))
            assertTrue(item.has("x"))
            assertEquals("local-upload", evaluate(view, "document.elementFromPoint(${item.getDouble("x")},${item.getDouble("y")}).closest('[role=menuitem]').id"))
            // The matching item is now consumed. Other menus, cloud items and avatar controls must stay untouched.
            assertFalse(JSONObject(evaluate(view, ArenaAttachmentScript.nextControl("modern-menu", ArenaService.DOUBAO))).has("x"))
        }
    }

    @Test fun modernDoubaoReadsCommittedAttachmentAreaAndRejectsExistingDraft() {
        withView(modernDoubaoFixture()) { view, _ ->
            val attachment = ArenaAttachment("fixture", "photo.png", "image/png", 4096, "a".repeat(64))
            evaluate(view, ArenaAttachmentScript.prepare("modern-card", listOf(attachment), ArenaService.DOUBAO))
            evaluate(view, """
                window.__arenaAttachment.chosen=true;const root={tag:3,stateNode:{}};root.stateNode.current=root;
                window.modernFile={fileName:'photo.png',size:4096,type:'image',fileKey:'remote-key',localKey:'local-key',status:'Normal',imageList:[{key:'remote-key',image_ori:{url:'blob:https://www.doubao.com/preview'}}]};
                const card=document.createElement('div');card.className='container-tJHWhP flex flex-col pl-12 pr-2';card.style='height:80px;width:180px';
                card['__reactFiber${'$'}fixture']={memoizedProps:{},return:{memoizedProps:{attachmentStates:[modernFile]},return:root}};document.getElementById('upload-slot').appendChild(card);true;
            """.trimIndent())
            assertTrue("Modern upload area has no legacy attachment_area test ID", JSONObject(evaluate(view, ArenaAttachmentScript.readiness("modern-card", ArenaService.DOUBAO))).getBoolean("ready"))
            evaluate(view, "modernFile.status='Uploading';true")
            assertFalse(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("modern-card", ArenaService.DOUBAO))).getBoolean("ready"))
            evaluate(view, "modernFile.status='Retry';true")
            assertTrue(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("modern-card", ArenaService.DOUBAO))).has("error"))
            assertTrue(JSONObject(evaluate(view, ArenaAttachmentScript.prepare("next-modern", listOf(attachment), ArenaService.DOUBAO))).has("error"))
        }
    }

    @Test fun deepSeekMobileWebpConversionRequiresObservedOriginalLocalIdentity() {
        val attachment = ArenaAttachment("fixture", "photo.png", "image/png", 4096, "a".repeat(64))
        listOf(true, false).forEach { observeOriginal ->
            withView("<div class='_77cefa5'><textarea></textarea><div id='cards'></div></div>") { view, _ ->
                evaluate(view, ArenaAttachmentScript.prepare("ds-converted", listOf(attachment), ArenaService.DEEPSEEK))
                evaluate(view, """
                    window.__arenaAttachment.chosen=true;const root={tag:3,stateNode:{}};root.stateNode.current=root;
                    window.imageFile={fileName:'${if (observeOriginal) "photo.png" else "other.png"}',fileSize:4096,isImage:true,id:'local_file_9',localId:'local_file_9',status:'PENDING'};
                    window.imageProps={file:imageFile,fileUploadInfo:{isUploading:true,failed:false},fileErrorState:{hasError:false},isForking:false};
                    const card=document.createElement('div');card.className='d5fa3d1b';card.style='height:80px;width:80px';card.innerHTML='<img alt="photo.png">';
                    card['__reactFiber${'$'}fixture']={memoizedProps:{fileName:imageFile.fileName},return:{memoizedProps:imageProps,return:root}};cards.appendChild(card);true;
                """.trimIndent())
                // The committed original draft is visible to MutationObserver before the asynchronous conversion.
                evaluate(view, "Object.assign(imageFile,{fileName:'photo.webp',fileSize:1234,id:'remote-image-9',status:'SUCCESS',auditResult:'pass',signedPath:'/remote/9'});imageProps.fileUploadInfo.isUploading=false;true")
                val converted = JSONObject(evaluate(view, ArenaAttachmentScript.readiness("ds-converted", ArenaService.DEEPSEEK)))
                if (observeOriginal) assertTrue("Observed original file retains its identity after conversion", converted.getBoolean("ready"))
                else assertTrue("Missing original identity must produce an explicit diagnostic", converted.has("error"))
                if (observeOriginal) {
                    evaluate(view, "imageFile.localId='unrelated-local-id';true")
                    assertTrue(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("ds-converted", ArenaService.DEEPSEEK))).has("error"))
                    evaluate(view, "imageFile.localId='local_file_9';imageFile.fileName='other.webp';true")
                    assertTrue(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("ds-converted", ArenaService.DEEPSEEK))).has("error"))
                    evaluate(view, "imageFile.fileName='photo.webp';imageProps.fileUploadInfo.isUploading=true;true")
                    assertFalse(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("ds-converted", ArenaService.DEEPSEEK))).getBoolean("ready"))
                    evaluate(view, "imageProps.fileUploadInfo.isUploading=false;imageFile.auditResult='unknown';true")
                    assertFalse(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("ds-converted", ArenaService.DEEPSEEK))).getBoolean("ready"))
                    evaluate(view, "imageFile.auditResult='pass';imageProps.fileUploadInfo.failed=true;true")
                    assertTrue(JSONObject(evaluate(view, ArenaAttachmentScript.readiness("ds-converted", ArenaService.DEEPSEEK))).has("error"))
                }
            }
        }
    }

    @Test fun threePendingWebViewsReceiveSameThreeFilesWithExactHashes() {
        val attachments = imported()
        val store = ArenaAttachmentStore(context)
        val files = attachments.map { it to store.verify(it) }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val loaded = CountDownLatch(3)
            val done = CountDownLatch(3)
            val errors = mutableListOf<String>()
            val views = mutableListOf<WebView>()
            lateinit var broker: ArenaFileChooserBroker
            scenario.onActivity { activity ->
                val frame = FrameLayout(activity)
                activity.setContentView(frame)
                broker = ArenaFileChooserBroker(activity)
                ArenaService.defaultMembers.forEach { service ->
                    val view = WebView(activity)
                    view.settings.javaScriptEnabled = true
                    view.settings.allowContentAccess = false
                    view.settings.allowFileAccess = false
                    frame.addView(view, FrameLayout.LayoutParams(720, 1200))
                    view.webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() } }
                    view.webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                            if (!broker.handle(view, callback, params)) callback.onReceiveValue(null)
                            return true
                        }
                    }
                    view.loadDataWithBaseURL(service.url, fixture(service = service), "text/html", "UTF-8", service.url)
                    views += view
                }
            }
            assertTrue(loaded.await(15, TimeUnit.SECONDS))
            onMain {
                views.forEachIndexed { index, view ->
                    ArenaAttachmentTransport(Handler(Looper.getMainLooper()), broker).upload(view, ArenaService.defaultMembers[index], "three-$index", files, { true }) { error ->
                        if (error != null) errors += error
                        done.countDown()
                    }
                }
            }
            assertTrue("Uploads did not settle", done.await(25, TimeUnit.SECONDS))
            assertEquals(emptyList<String>(), errors)
            views.forEach { view ->
                val read = JSONArray(evaluate(view, "JSON.stringify(window.received)"))
                assertEquals(3, read.length())
                repeat(read.length()) { index ->
                    val value = read.getJSONObject(index)
                    val attachment = attachments.single { it.name == value.getString("name") }
                    val actual = android.util.Base64.decode(value.getString("data").substringAfter(','), android.util.Base64.DEFAULT)
                    assertEquals(attachment.sizeBytes, actual.size.toLong())
                    assertEquals(attachment.sha256, MessageDigest.getInstance("SHA-256").digest(actual).joinToString("") { "%02x".format(it) })
                }
            }
            onMain { broker.cancelAll(); views.forEach(WebView::destroy) }
        }
        store.discardImported(attachments)
    }

    @Test fun deepSeekBatchedConversionUsesExactChooserAndUniqueStableLocalId() {
        listOf("valid", "reordered", "same-stem", "old-id", "extra", "wrong-name", "changed-id").forEach { case ->
            withView("<div class='_77cefa5'><textarea></textarea><input type='file' id='picker' multiple style='display:none'><div id='cards'></div></div>") { view, _ ->
                val selected = mutableListOf(ArenaAttachment("fixture", "photo.png", "image/png", 4096, "a".repeat(64)))
                if (case == "same-stem") selected += ArenaAttachment("second", "photo.jpg", "image/jpeg", 8192, "b".repeat(64))
                if (case == "reordered") selected += ArenaAttachment("second", "second.png", "image/png", 8192, "b".repeat(64))
                evaluate(view, """
                    window.reactRoot={tag:3,stateNode:{}};reactRoot.stateNode.current=reactRoot;
                    const old=document.createElement('div');old.className='d5fa3d1b';old.style='height:80px;width:80px;visibility:hidden';old['__reactFiber${'$'}fixture']={memoizedProps:{file:{fileName:'old.png',fileSize:40,localId:'old-local'}},return:reactRoot};cards.appendChild(old);true;
                """.trimIndent())
                evaluate(view, ArenaAttachmentScript.prepare("batched-$case", selected, ArenaService.DEEPSEEK))
                val fileCreation = selected.joinToString(";") { "transfer.items.add(new File([new Uint8Array(${it.sizeBytes})],${ArenaJs.quote(it.name)},{type:${ArenaJs.quote(it.mimeType)}}))" }
                evaluate(view, """
                    const transfer=new DataTransfer();$fileCreation;picker.files=transfer.files;picker.dispatchEvent(new Event('change',{bubbles:true}));
                    window.batchedFiles=[];window.addConverted=(name,localId)=>{const file={fileName:name,fileSize:1234,localId,id:'remote-'+localId,isImage:true,status:'SUCCESS',auditResult:'pass'};batchedFiles.push(file);const card=document.createElement('div');card.className='d5fa3d1b';card.style='height:80px;width:80px';card['__reactFiber${'$'}fixture']={memoizedProps:{file,fileUploadInfo:{isUploading:false,failed:false},fileErrorState:{hasError:false}},return:reactRoot};cards.appendChild(card);};
                    ${if (case == "reordered") "addConverted('second.webp','second-local');" else ""}
                    addConverted('${if (case == "wrong-name") "other.webp" else "photo.webp"}','${if (case == "old-id") "old-local" else "new-local"}');
                    ${if (case == "same-stem") "addConverted('photo.webp','second-local');" else ""}
                    ${if (case == "extra") "addConverted('unexpected.webp','unexpected-local');" else ""}
                    true;
                """.trimIndent())
                val first = JSONObject(evaluate(view, ArenaAttachmentScript.readiness("batched-$case", ArenaService.DEEPSEEK)))
                if (case in listOf("valid", "reordered", "changed-id")) {
                    assertFalse("$case must wait for a second stable observation: $first", first.has("error"))
                    assertFalse(first.getBoolean("ready"))
                    if (case == "changed-id") evaluate(view, "batchedFiles[0].localId='replacement-local';batchedFiles[0].id='replacement-remote';true")
                    Thread.sleep(700)
                    val second = JSONObject(evaluate(view, ArenaAttachmentScript.readiness("batched-$case", ArenaService.DEEPSEEK)))
                    if (case == "changed-id") assertTrue("Replacing a provisional identity must fail", second.has("error"))
                    else assertTrue("$case should bind by the unique selected name and stable ID: $second", second.getBoolean("ready"))
                } else assertTrue("$case must produce a diagnostic and not send: $first", first.has("error"))
            }
        }
    }

    @Test fun parseFailureStopsBeforeSendAndBareFileSelectionIsNotReady() {
        withView(fixture(fail = true)) { view, broker ->
            val files = imported()
            val done = CountDownLatch(1)
            val error = AtomicReference<String?>()
            var sends = 0
            onMain {
                ArenaAttachmentTransport(Handler(Looper.getMainLooper()), broker).upload(view, ArenaService.DEEPSEEK, "fail-upload", files.map { it to ArenaAttachmentStore(context).verify(it) }, { true }) { failure ->
                    error.set(failure)
                    if (failure == null) sends++
                    done.countDown()
                }
            }
            assertTrue(done.await(15, TimeUnit.SECONDS))
            assertNotNull(error.get())
            assertEquals(0, sends)
            evaluate(view, "window.fileStates.forEach(f=>f.status='PENDING');true")
            val result = JSONObject(evaluate(view, ArenaAttachmentScript.readiness("fail-upload", ArenaService.DEEPSEEK)))
            assertFalse(result.getBoolean("ready"))
            ArenaAttachmentStore(context).discardImported(files)
        }
    }

    @Test fun cancellationAndNavigationRejectLateChoosersAndRevokeUris() {
        withView(fixture()) { view, broker ->
            val files = imported()
            val store = ArenaAttachmentStore(context)
            var callbackCount = 0
            var supplied: Array<Uri>? = null
            onMain {
                broker.prepare(view, ArenaService.DEEPSEEK, "old", files.map { it to store.verify(it) }) { callbackCount++ }
                broker.navigated(view)
                assertFalse(broker.handle(view, ValueCallback { supplied = it }, params()))
                broker.prepare(view, ArenaService.DEEPSEEK, "new", files.map { it to store.verify(it) }) { callbackCount++ }
                assertTrue(broker.handle(view, ValueCallback { supplied = it }, params()))
                assertEquals(1, callbackCount)
                assertEquals(3, supplied!!.size)
                broker.cancelAll()
            }
            supplied!!.forEach { uri -> assertTrue(runCatching { context.contentResolver.openInputStream(uri)!!.use { it.read() } }.isFailure) }
            store.discardImported(files)
        }
    }

    @Test fun wrongModeMimeAndOriginNeverReceivePreselectedFiles() {
        withView(fixture()) { view, broker ->
            val files = imported()
            val store = ArenaAttachmentStore(context)
            onMain {
                listOf(params(multiple = false), params(accept = arrayOf("video/*"))).forEach { choice ->
                    var error: String? = null
                    var called = false
                    broker.prepare(view, ArenaService.DEEPSEEK, "blocked", files.map { it to store.verify(it) }) { error = it }
                    assertTrue(broker.handle(view, ValueCallback { called = true; assertNull(it) }, choice))
                    assertTrue(called)
                    assertNotNull(error)
                }
                assertFalse(ArenaFileChooserBroker.trusted(ArenaService.DEEPSEEK, "https://chat.deepseek.com.evil.example/"))
                assertFalse(ArenaFileChooserBroker.trusted(ArenaService.KIMI, "https://kimi.com@evil.example/"))
                assertFalse(ArenaFileChooserBroker.trusted(ArenaService.DOUBAO, "file:///private"))
                broker.cancelAll()
            }
            store.discardImported(files)
        }
    }

    private fun params(multiple: Boolean = true, accept: Array<String> = arrayOf("*/*")) = object : WebChromeClient.FileChooserParams() {
        override fun getMode() = if (multiple) MODE_OPEN_MULTIPLE else MODE_OPEN
        override fun getAcceptTypes() = accept
        override fun isCaptureEnabled() = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun createIntent() = android.content.Intent()
    }

    private fun withView(html: String, block: (WebView, ArenaFileChooserBroker) -> Unit) {
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
                view.loadDataWithBaseURL(ArenaService.DEEPSEEK.url, html, "text/html", "UTF-8", ArenaService.DEEPSEEK.url)
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

    private fun fixture(fail: Boolean = false, service: ArenaService = ArenaService.DEEPSEEK): String {
        val kind = service.name
        val controls = if (service == ArenaService.KIMI) """
            <button class="toolkit-trigger-btn" onclick="menu.innerHTML='<label class=&quot;toolkit-item&quot; role=&quot;menuitem&quot; style=&quot;display:block;width:140px;height:50px&quot;>Upload files<input id=&quot;upload&quot; type=&quot;file&quot; multiple style=&quot;display:none&quot;></label>';document.getElementById('upload').onchange=handle;">Add</button><div id="menu"></div>
        """.trimIndent() else """
            <button data-testid="upload_file_button" onclick="upload.click()">Upload files</button><input id="upload" type="file" multiple accept="image/*,.pdf,.txt" style="display:none">
        """.trimIndent()
        return """
            <meta name="viewport" content="width=device-width,initial-scale=1"><div class="_77cefa5"><textarea></textarea>$controls<div id="cards" data-testid="${if(service == ArenaService.KIMI) "input-attachment-list" else "attachment_area"}"></div></div>
            <script>
            window.received=[];window.fileStates=[];const root={tag:3,stateNode:{}};root.stateNode.current=root;
            cards['__reactFiber${'$'}fixture']={memoizedProps:{attachmentStates:fileStates},return:root};
            function handle(){for(const file of document.getElementById('upload').files){
              const image=file.type.startsWith('image/'),card=document.createElement('div');card.style='height:80px;width:180px';
              const state={fileName:file.name,fileSize:file.size,size:file.size,id:'id-'+file.name,localId:'local-'+file.name,isImage:image,auditResult:'pass',fileKey:'key-'+file.name,localKey:'local-'+file.name,type:image?'image':'file',status:'${if(service == ArenaService.DOUBAO) "Uploading" else "PENDING"}',parseState:3,reviewState:0};fileStates.push(state);
              if('$kind'==='DEEPSEEK'){card.className=image?'d5fa3d1b':'_25c7358';card.innerHTML=image?'<img alt="'+file.name+'" src="blob:https://chat.deepseek.com/local-preview">':'<span class="e70accd6">'+file.name+'</span>';card['__reactFiber${'$'}fixture']={memoizedProps:{file:state},return:root};}
              else if('$kind'==='KIMI'){card.className=image?'image-thumbnail loading':'file-card-container parsing';card.innerHTML=image?'<img src="https://fixture.invalid/p.png">':'<span class="file-card-info-name">'+file.name.replace(/\.[^.]+${'$'}/,'')+'</span><span class="file-ext">'+file.name.split('.').pop()+'</span>';}
              else {card.setAttribute('data-testid','attachment_file_item');card.textContent=file.name;}
              cards.appendChild(card);const reader=new FileReader();reader.onload=()=>{received.push({name:file.name,data:reader.result});setTimeout(()=>{
                state.status=${if(fail) "'FAILED'" else if(service == ArenaService.DOUBAO) "'Normal'" else "'SUCCESS'"};state.parseState=${if(fail) 2 else 1};
                if('$kind'==='DEEPSEEK'&&image&&${!fail}){state.fileName=file.name.replace(/\.[^.]+${'$'}/,'')+'.webp';state.fileSize=Math.max(1,file.size-1);}
                state.imageList=[{key:state.fileKey,image_ori:{url:'blob:https://www.doubao.com/local-preview'}}];
                if('$kind'==='KIMI')card.className=(image?'image-thumbnail ':'file-card-container ')+${if(fail) "'error'" else "'success'"};
              },150);};reader.readAsDataURL(file);
            }}
            const direct=document.getElementById('upload');if(direct)direct.onchange=handle;
            </script>
        """.trimIndent()
    }

    private fun modernDoubaoFixture(): String = """
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>button,[role=menuitem],label{min-width:100px;min-height:40px;display:block}textarea{height:50px}#local-menu{display:none}</style>
        <label>Upload avatar<input type="file" style="display:none"></label>
        <div class="guidance-input-surface"><div class="relative" id="upload-slot"><input type="file" multiple accept="image/*,.txt" style="display:none"></div><textarea></textarea>
          <div class="guidance-input-actions"><button id="local-trigger" data-slot="dropdown-menu-trigger" aria-haspopup="menu"></button>
            <div data-slot="dropdown-menu-trigger" aria-haspopup="menu"><button data-dbx-name="button">Model</button></div>
          </div>
        </div>
        <div role="menu" data-slot="dropdown-menu-content" aria-labelledby="avatar-trigger"><div role="menuitem" data-slot="dropdown-menu-item">上传文件或图片</div></div>
        <div role="menu" data-slot="dropdown-menu-content" aria-labelledby="local-trigger" style="display:none"><input type="file"><div role="menuitem" data-slot="dropdown-menu-item">上传文件或图片</div></div>
        <div id="local-menu" role="menu" data-slot="dropdown-menu-content" aria-labelledby="local-trigger"><div role="menuitem" data-slot="dropdown-menu-item">选择云盘文件</div><div role="menuitem" data-slot="dropdown-menu-item" id="local-upload">上传文件或图片</div></div>
        <script>const button=document.createElement('button');button.setAttribute('data-dbx-name','button');button.setAttribute('aria-haspopup','menu');button.textContent='+';document.getElementById('local-trigger').appendChild(button);</script>
    """.trimIndent()
}
