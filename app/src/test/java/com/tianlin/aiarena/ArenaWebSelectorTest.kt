package com.tianlin.aiarena

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaWebSelectorTest {
    @Test
    fun doubaoSelectorCannotMatchSendButtonWrapper() {
        val selector = ArenaWebViewPool.sendButtonSelector(ArenaService.DOUBAO)

        assertTrue(selector.contains("button[class*='send-btn']"))
        assertFalse(selector.split(',').any { part ->
            part.trim() == "[class*='send-btn']"
        })
    }

    @Test
    fun zhipuSelectorIncludesLiveMobileSendControl() {
        val selector = ArenaWebViewPool.sendButtonSelector(ArenaService.ZHIPU)

        assertTrue(selector.contains(".send-button-right"))
    }

    @Test
    fun yuanbaoResponseSelectorsNeverIncludeHumanTextComponent() {
        val selectors = ArenaWebCursorScript.responseSelectors(ArenaService.YUANBAO)

        assertTrue(selectors.any { it.contains("hyc-content-md") })
        assertFalse(selectors.any { it.contains("hyc-component-text") })
    }

}
