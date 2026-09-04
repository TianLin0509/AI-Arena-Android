package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.1 起答案是 Markdown，朗读前必须把标记洗干净：
 * 把星号井号竖线逐个念出来，比不朗读还糟。
 */
class SpeechMarkdownTest {

    @Test
    fun emphasisAndHeadingMarkersAreNotSpoken() {
        val spoken = SpeechTextPolicy.normalize("### 核心逻辑" + "\n\n" + "这是 **加粗** 与 *斜体*。")
        assertFalse(spoken.contains("#"))
        assertFalse(spoken.contains("*"))
        assertTrue(spoken.contains("核心逻辑"))
        assertTrue(spoken.contains("加粗"))
    }

    @Test
    fun citationSuperscriptsAreDropped() {
        // 网页侧把角标转成了上标字符；逐字读"上标一上标四"毫无意义
        val spoken = SpeechTextPolicy.normalize("递归自我改进\u00B9\u2074\u2078。")
        assertEquals("递归自我改进。", spoken)
    }

    @Test
    fun codeBlocksBecomeAShortHintInsteadOfBeingReadOut() {
        val markdown = "先看实现：" + "\n\n" + "```python" + "\n" + "def improve(m):" + "\n" +
            "    return train(m)" + "\n" + "```" + "\n\n" + "就这些。"
        val spoken = SpeechTextPolicy.normalize(markdown)
        assertFalse(spoken.contains("def improve"))
        assertTrue(spoken.contains("代码块"))
        assertTrue(spoken.contains("就这些。"))
    }

    @Test
    fun tableSeparatorRowsAreNotSpokenAsDashes() {
        val markdown = "| 阶段 | 能力 |" + "\n" + "| --- | --- |" + "\n" + "| L1 | 辅助编码 |"
        val spoken = SpeechTextPolicy.normalize(markdown)
        assertFalse(spoken.contains("---"))
        assertFalse(spoken.contains("|"))
        assertTrue(spoken.contains("阶段"))
        assertTrue(spoken.contains("辅助编码"))
    }

    @Test
    fun linkLabelIsSpokenWithoutTheUrl() {
        val spoken = SpeechTextPolicy.normalize("参见 [这篇综述](https://example.com/rsi)。")
        assertTrue(spoken.contains("这篇综述"))
        assertFalse(spoken.contains("example.com"))
    }

    @Test
    fun chunkingStillWorksOnMarkdownInput() {
        val markdown = "## 标题" + "\n\n" + "正文。".repeat(400)
        val chunks = SpeechTextPolicy.chunks(markdown, maxCharacters = 200)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.length <= 200 })
        assertFalse(chunks.any { it.contains("#") })
    }
}
