package com.tianlin.aiarena

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 境外成员的网页只在各自官网域名上被信任（上传与发送中导航判定都依赖它）。 */
@RunWith(AndroidJUnit4::class)
class ArenaForeignServicesInstrumentedTest {
    @Test
    fun foreignProviderPagesAreTrustedOnlyOnTheirOwnHosts() {
        assertTrue(ArenaFileChooserBroker.trusted(ArenaService.CLAUDE, "https://claude.ai/chat/abc"))
        assertTrue(ArenaFileChooserBroker.trusted(ArenaService.CHATGPT, "https://chatgpt.com/c/abc"))
        assertTrue(ArenaFileChooserBroker.trusted(ArenaService.CHATGPT, "https://chat.openai.com/c/abc"))
        assertTrue(ArenaFileChooserBroker.trusted(ArenaService.GEMINI, "https://gemini.google.com/app/8fb051286895ae52"))

        assertFalse(ArenaFileChooserBroker.trusted(ArenaService.CLAUDE, "https://claude.ai.example.com/chat"))
        assertFalse(ArenaFileChooserBroker.trusted(ArenaService.CHATGPT, "http://chatgpt.com/"))
        assertFalse(ArenaFileChooserBroker.trusted(ArenaService.GEMINI, "https://accounts.google.com/signin"))
        assertFalse(ArenaFileChooserBroker.trusted(ArenaService.GEMINI, "https://chatgpt.com/"))
        assertFalse(ArenaFileChooserBroker.trusted(ArenaService.DEEPSEEK, "https://claude.ai/new"))
    }
}
