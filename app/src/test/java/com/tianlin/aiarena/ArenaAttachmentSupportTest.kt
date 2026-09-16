package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaAttachmentSupportTest {
    private fun image(name: String = "photo.png") = ArenaAttachment(name, name, "image/png", 10, "a".repeat(64))
    private fun document(name: String = "notes.pdf") = ArenaAttachment(name, name, "application/pdf", 10, "b".repeat(64))

    @Test fun onlyTheFiveAdaptedMembersCanReceiveDraftAttachments() {
        val adapted = setOf(ArenaService.DEEPSEEK, ArenaService.DOUBAO, ArenaService.KIMI, ArenaService.QWEN, ArenaService.YUANBAO)
        ArenaService.entries.forEach { service ->
            assertEquals(service.name, service in adapted, ArenaAttachmentSupport.supports(service))
        }
        assertTrue(ArenaAttachmentSupport.unsupportedReason(ArenaService.ZHIPU).contains("智谱"))
        // 境外成员走通用说明，不能落进「智谱」那条专属文案。
        listOf(ArenaService.CLAUDE, ArenaService.CHATGPT, ArenaService.GEMINI).forEach { service ->
            val reason = ArenaAttachmentSupport.unsupportedReason(service)
            assertTrue(reason.contains(service.displayName))
            assertFalse(reason.contains("智谱"))
        }
    }

    @Test fun noAttachmentsNeverBlocksAnyMember() {
        ArenaService.entries.forEach { assertNull(ArenaAttachmentSupport.sendError(it, emptyList())) }
        assertNull(ArenaAttachmentSupport.notice(ArenaService.entries, emptyList()))
    }

    @Test fun qwenAndYuanbaoRejectMixedKindsButAcceptOneKind() {
        val mixed = listOf(image(), document())
        listOf(ArenaService.QWEN, ArenaService.YUANBAO).forEach { service ->
            assertNotNull(ArenaAttachmentSupport.sendError(service, mixed))
            assertNull(ArenaAttachmentSupport.sendError(service, listOf(image(), image("second.png"))))
            assertNull(ArenaAttachmentSupport.sendError(service, listOf(document(), document("second.pdf"))))
        }
        listOf(ArenaService.DEEPSEEK, ArenaService.DOUBAO, ArenaService.KIMI).forEach { service ->
            assertNull(ArenaAttachmentSupport.sendError(service, mixed))
        }
        assertTrue(ArenaAttachmentSupport.isMixed(mixed))
        assertFalse(ArenaAttachmentSupport.isMixed(listOf(image())))
    }

    @Test fun noticeNamesOnlyTheMembersThatWillNotReceiveTheFiles() {
        assertNull(ArenaAttachmentSupport.notice(ArenaService.defaultMembers, listOf(image())))
        val zhipu = ArenaAttachmentSupport.notice(listOf(ArenaService.DEEPSEEK, ArenaService.ZHIPU), listOf(image()))
        assertNotNull(zhipu)
        assertTrue(zhipu!!.contains("智谱"))
        assertFalse(zhipu.contains("DeepSeek"))
        val mixed = ArenaAttachmentSupport.notice(listOf(ArenaService.KIMI, ArenaService.QWEN), listOf(image(), document()))
        assertNotNull(mixed)
        assertTrue(mixed!!.contains("千问"))
        assertFalse(mixed.contains("Kimi"))
    }
}
