package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaSkinTest {
    @Test
    fun unknownSkinNameFallsBackToDefaultInsteadOfCrashing() {
        assertEquals(ArenaSkin.default, ArenaSkin.fromName(null))
        assertEquals(ArenaSkin.default, ArenaSkin.fromName(""))
        assertEquals(ArenaSkin.default, ArenaSkin.fromName("SKIN_FROM_A_FUTURE_VERSION"))
    }

    @Test
    fun everySkinNameRoundTrips() {
        ArenaSkin.entries.forEach { skin ->
            assertEquals(skin, ArenaSkin.fromName(skin.name))
        }
    }

    @Test
    fun skinsAreVisuallyDistinctNotJustRecoloured() {
        val signatures = ArenaSkin.entries.map { skin ->
            val metrics = skin.metrics
            listOf(
                skin.palette.page,
                skin.palette.accent,
                metrics.cardCorner,
                metrics.borderWidth,
                metrics.typeScale,
            )
        }
        assertEquals(
            "每套皮肤都应有独立的配色 + 圆角 + 描边 + 字号组合",
            ArenaSkin.entries.size,
            signatures.distinct().size,
        )
    }

    @Test
    fun exactlyOneSkinIsDark() {
        val dark = ArenaSkin.entries.filter { it.palette.isDark }
        assertEquals(listOf(ArenaSkin.NIGHT), dark)
    }

    @Test
    fun elderSkinIsLargerAndHeavierThanDefault() {
        val elder = ArenaSkin.ELDER.metrics
        val clear = ArenaSkin.CLEAR.metrics

        assertTrue("长辈皮肤字号必须更大", elder.typeScale > clear.typeScale)
        assertTrue("长辈皮肤触摸目标必须更大", elder.minTouch > clear.minTouch)
        assertTrue("长辈皮肤主按钮必须更高", elder.primaryButtonHeight > clear.primaryButtonHeight)
        assertTrue("长辈皮肤描边必须更粗", elder.borderWidth > clear.borderWidth)
    }

    @Test
    fun everySkinKeepsTextSeparatedFromItsBackground() {
        ArenaSkin.entries.forEach { skin ->
            val palette = skin.palette
            assertNotEquals("${skin.name} 正文色与页面底色不能相同", palette.ink, palette.page)
            assertNotEquals("${skin.name} 正文色与卡片底色不能相同", palette.ink, palette.surface)
            assertNotEquals("${skin.name} 主色与卡片底色不能相同", palette.accent, palette.surface)
            assertNotEquals("${skin.name} 主视觉前景与背景不能相同", palette.onHero, palette.heroStart)
        }
    }

    @Test
    fun serviceNameParsingIsForgiving() {
        assertEquals(ArenaService.DEEPSEEK, ArenaService.fromName("DEEPSEEK"))
        assertNull(ArenaService.fromName("A_SERVICE_REMOVED_IN_A_LATER_VERSION"))
        assertNull(ArenaService.fromName(null))
    }

    @Test
    fun answerModeParsingFallsBackToParallel() {
        assertEquals(AnswerMode.SERIAL, AnswerMode.fromName("SERIAL"))
        assertEquals(AnswerMode.PARALLEL, AnswerMode.fromName("SOMETHING_ELSE"))
        assertEquals(AnswerMode.PARALLEL, AnswerMode.fromName(null))
    }
}
