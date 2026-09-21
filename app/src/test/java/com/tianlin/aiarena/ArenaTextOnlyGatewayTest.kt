package com.tianlin.aiarena

import org.junit.Assert.*
import org.junit.Test

class ArenaTextOnlyGatewayTest {
    private class Gateway : ArenaGateway {
        var sends = 0
        var uploads = 0
        var cancelled: ArenaService? = null
        override fun sendPrompt(service: ArenaService, prompt: String, requestId: String, callback: (SendOutcome) -> Unit) {
            sends++; callback(SendOutcome(true, requestId, "sent"))
        }
        override fun sendPromptWithAttachments(service: ArenaService, prompt: String, requestId: String,
            attachments: List<ArenaAttachment>, callback: (SendOutcome) -> Unit) { uploads++ }
        override fun readResponse(service: ArenaService, requestId: String, callback: (ResponseSnapshot) -> Unit) = Unit
        override fun cancelAutomation(service: ArenaService) { cancelled = service }
    }
    @Test fun historicalAttachmentRetryCannotUploadOrSilentlySendTextOnly() {
        val delegate = Gateway()
        val attachment = ArenaAttachment("old", "旧附件.png", "image/png", 32, "a".repeat(64))
        var result: SendOutcome? = null
        ArenaTextOnlyGateway(delegate).sendPromptWithAttachments(ArenaService.DEEPSEEK, "旧问题", "retry",
            listOf(attachment)) { result = it }
        assertEquals(0, delegate.sends)
        assertEquals(0, delegate.uploads)
        assertFalse(result!!.success)
        assertEquals("retry", result!!.requestId)
        assertTrue(result!!.detail.contains("手动上传"))
    }
    @Test fun ordinaryTextAndCancellationStillReachTheSameProvider() {
        val delegate = Gateway()
        val gateway = ArenaTextOnlyGateway(delegate)
        var sent = false
        gateway.sendPromptWithAttachments(ArenaService.KIMI, "问题", "text", emptyList()) { sent = it.success }
        gateway.cancelAutomation(ArenaService.KIMI)
        assertTrue(sent)
        assertEquals(1, delegate.sends)
        assertEquals(0, delegate.uploads)
        assertEquals(ArenaService.KIMI, delegate.cancelled)
    }
}
