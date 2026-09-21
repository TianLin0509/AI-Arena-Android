package com.tianlin.aiarena

/** The roundtable is text-only. Old records remain readable, but must never re-upload files. */
internal class ArenaTextOnlyGateway(private val delegate: ArenaGateway) : ArenaGateway by delegate {
    override fun sendPromptWithAttachments(
        service: ArenaService,
        prompt: String,
        requestId: String,
        attachments: List<ArenaAttachment>,
        callback: (SendOutcome) -> Unit,
    ) {
        if (attachments.isNotEmpty()) {
            callback(SendOutcome(false, requestId, "圆桌已停止自动上传附件，请点击 AI 头像，在原网页手动上传并发送。旧附件仍保留在历史中。"))
        } else {
            delegate.sendPrompt(service, prompt, requestId, callback)
        }
    }
}
