package com.tianlin.aiarena

import org.junit.Assert.*
import org.junit.Test

class AttachmentDraftTest {
    private fun file(id: String) = ArenaAttachment(id, "$id.png", "image/png", 32, "a".repeat(64))

    @Test fun cancellingPickerPreservesAlreadySelectedFiles() {
        val first = file("first")
        val draft = AttachmentDraft(listOf(first))
        draft.choose { callback -> callback(Result.success(emptyList())) }
        assertEquals(listOf(first), draft.attachments)
        assertFalse(draft.picking)
        assertNull(draft.error)
    }

    @Test fun openingNewSessionRejectsAnOldPickerResult() {
        val draft = AttachmentDraft()
        lateinit var reply: (Result<List<ArenaAttachment>>) -> Unit
        draft.choose { reply = it }
        assertTrue(draft.picking)
        draft.clear()
        reply(Result.success(listOf(file("old-session"))))
        assertTrue(draft.attachments.isEmpty())
        assertFalse(draft.picking)
    }

    @Test fun lateOldResultCannotOverwriteTheNextSelection() {
        val draft = AttachmentDraft()
        lateinit var old: (Result<List<ArenaAttachment>>) -> Unit
        lateinit var current: (Result<List<ArenaAttachment>>) -> Unit
        draft.choose { old = it }
        draft.clear()
        draft.choose { current = it }
        old(Result.failure(IllegalStateException("old failure")))
        assertTrue(draft.picking)
        assertNull(draft.error)
        current(Result.success(listOf(file("current"))))
        assertEquals("current", draft.attachments.single().id)
    }

    @Test fun oversizedBatchDoesNotDiscardPreviousSelection() {
        val original = file("first")
        val draft = AttachmentDraft(listOf(original))
        draft.choose { it(Result.success(listOf(file("a"), file("b"), file("c")))) }
        assertEquals(listOf(original), draft.attachments)
        assertNotNull(draft.error)
    }

    @Test fun pickerErrorRemainsVisibleAndDoesNotPretendSuccess() {
        val draft = AttachmentDraft()
        draft.choose { throw IllegalStateException("没有可用的文件选择器") }
        assertTrue(draft.attachments.isEmpty())
        assertFalse(draft.picking)
        assertEquals("没有可用的文件选择器", draft.error)
    }

    @Test fun disposedDraftRejectsCallbacksAndDoesNotLoseExistingAttachments() {
        val first = file("first")
        val draft = AttachmentDraft(listOf(first))
        lateinit var reply: (Result<List<ArenaAttachment>>) -> Unit
        draft.choose { reply = it }
        draft.invalidate()
        reply(Result.success(listOf(file("late"))))
        assertEquals(listOf(first), draft.attachments)
    }

    @Test fun attachmentOnlyQuestionIsExplicitWhileTypedPromptIsUntouched() {
        assertEquals(AttachmentPromptPolicy.DEFAULT_QUESTION, AttachmentPromptPolicy.withDefault(" ", listOf(file("a"))))
        assertEquals("  原始要求\n", AttachmentPromptPolicy.withDefault("  原始要求\n", listOf(file("a"))))
        assertEquals("", AttachmentPromptPolicy.withDefault("", emptyList()))
    }

    @Test fun legacyGatewayRejectsAttachmentsWithoutSendingText() {
        var textSends = 0
        val gateway = object : ArenaGateway {
            override fun sendPrompt(service: ArenaService, prompt: String, requestId: String, callback: (SendOutcome) -> Unit) { textSends++ }
            override fun readResponse(service: ArenaService, requestId: String, callback: (ResponseSnapshot) -> Unit) = Unit
        }
        var result: SendOutcome? = null
        gateway.sendPromptWithAttachments(ArenaService.DEEPSEEK, "请看图", "request", listOf(file("a"))) { result = it }
        assertEquals(0, textSends)
        assertFalse(result!!.success)
    }
}
