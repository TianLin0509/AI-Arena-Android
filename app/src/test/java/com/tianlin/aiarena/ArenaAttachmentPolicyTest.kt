package com.tianlin.aiarena

import org.junit.Assert.*
import org.junit.Test

class ArenaAttachmentPolicyTest {
    private fun file(id: String = "a", size: Long = 1, mime: String = "image/png", name: String = "photo.png") = ArenaAttachment(id, name, mime, size, "a".repeat(64))
    @Test fun limitsAreCheckedWithoutOverflow() {
        assertNull(ArenaAttachmentPolicy.validate(listOf(file())))
        assertNotNull(ArenaAttachmentPolicy.validate((1..4).map { file(it.toString()) }))
        assertNotNull(ArenaAttachmentPolicy.validate(listOf(file(size = Long.MAX_VALUE))))
        assertNotNull(ArenaAttachmentPolicy.validate((1..3).map { file(it.toString(), 8L * 1024 * 1024) }))
        assertNotNull(ArenaAttachmentPolicy.validate(listOf(file(size = 0))))
        assertNotNull(ArenaAttachmentPolicy.validate(listOf(file(mime = "application/x-msdownload"))))
    }
    @Test fun pickerAcceptsEmptyMimeWildcardAndExtensionButNotUnrelatedTypes() {
        assertTrue(ArenaAttachmentPolicy.accepts(file(), emptyList()))
        assertTrue(ArenaAttachmentPolicy.accepts(file(), listOf("image/*")))
        assertTrue(ArenaAttachmentPolicy.accepts(file(), listOf(".jpg,.PNG")))
        assertFalse(ArenaAttachmentPolicy.accepts(file(), listOf("application/pdf")))
    }
}
