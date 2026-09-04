package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 块级解析是这条链路上最容易出细节 bug 的一层，而它的输入来自六个第三方网站，
 * 真机上很难穷举。这里用「浏览器里实测过的序列化输出」当固定样本反向盯住解析器。
 */
class ArenaMarkdownTest {

    /** 这段就是 DOM 序列化器在 Chrome 里对仿 DeepSeek 答案 DOM 的真实输出。 */
    private val sample = listOf(
        "在AI领域，**RSI** 通常指 **递归自我改进（Recursive Self-Improvement）**\u00B9\u2074\u2078。",
        "",
        "这是一个前沿概念，描绘了 *AI 发展* 的一个关键阶段\u00B9\u2070。",
        "",
        "### 核心逻辑",
        "",
        "1. 模型自己写代码",
        "2. 评估并筛选",
        "  - 自动基准测试",
        "  - 人工抽检",
        "",
        "3. 部署下一代",
        "",
        "> 注意：这不等于 AGI。",
        "",
        "```python",
        "def improve(m):",
        "    return train(m)",
        "```",
        "",
        "| 阶段 | 能力 |",
        "| --- | --- |",
        "| L1 | 辅助编码 |",
        "| L2 | 自主优化 |",
        "",
        "参见 [这篇综述](https://example.com/rsi)，代码里的 `lr=3e-4` 是关键。",
        "",
        "公式里的 3 \\* 4 = 12 不该被当成强调。",
    ).joinToString("\n")

    @Test
    fun parsesEveryBlockKindFromTheRealSerializerOutput() {
        val blocks = ArenaMarkdown.parse(sample)

        assertTrue(blocks[0] is ArenaMdBlock.Paragraph)
        assertEquals(ArenaMdBlock.Heading(3, "核心逻辑"), blocks.filterIsInstance<ArenaMdBlock.Heading>().single())

        val ordered = blocks.filterIsInstance<ArenaMdBlock.Ordered>()
        assertEquals(listOf("1.", "2.", "3."), ordered.map { it.marker })
        assertEquals(listOf(0, 0, 0), ordered.map { it.depth })

        val bullets = blocks.filterIsInstance<ArenaMdBlock.Bullet>()
        assertEquals(listOf("自动基准测试", "人工抽检"), bullets.map { it.text })
        // 嵌套列表靠两个空格缩进表达层级，不能被吃掉
        assertEquals(listOf(1, 1), bullets.map { it.depth })

        assertEquals("注意：这不等于 AGI。", blocks.filterIsInstance<ArenaMdBlock.Quote>().single().text)

        val code = blocks.filterIsInstance<ArenaMdBlock.Code>().single()
        assertEquals("python", code.language)
        assertEquals("def improve(m):\n    return train(m)", code.code)

        val table = blocks.filterIsInstance<ArenaMdBlock.Table>().single()
        assertEquals(listOf("阶段", "能力"), table.header)
        assertEquals(listOf(listOf("L1", "辅助编码"), listOf("L2", "自主优化")), table.rows)
    }

    @Test
    fun citationSuperscriptsSurviveAsPlainCharacters() {
        // 角标已经在网页侧转成了上标字符，解析器不该再动它们
        val text = ArenaMarkdown.plainText(sample)
        assertTrue(text.contains("\u00B9\u2074\u2078"))
        assertTrue(text.contains("\u00B9\u2070"))
    }

    @Test
    fun inlineEmphasisIsParsedAndEscapedStarStaysLiteral() {
        val bold = ArenaMarkdown.inline("普通**加粗**普通")
        assertEquals(listOf(false, true, false), bold.map { it.bold })
        assertEquals("加粗", bold[1].text)

        // 序列化器把正文里的 * 转义成 \*，解析器必须还原成字面量而不是强调
        val literal = ArenaMarkdown.inline("3 \\* 4 = 12")
        assertEquals("3 * 4 = 12", literal.joinToString("") { it.text })
        assertTrue(literal.none { it.italic })
    }

    @Test
    fun inlineCodeWinsOverEmphasisInside() {
        val spans = ArenaMarkdown.inline("设 `lr=3e-4` 与 *斜体*")
        val code = spans.single { it.code }
        assertEquals("lr=3e-4", code.text)
        assertEquals("斜体", spans.single { it.italic }.text)
    }

    @Test
    fun linksKeepLabelAndHref() {
        val spans = ArenaMarkdown.inline("参见 [这篇综述](https://example.com/rsi)。")
        val link = spans.single { it.link != null }
        assertEquals("这篇综述", link.text)
        assertEquals("https://example.com/rsi", link.link)
    }

    @Test
    fun plainTextIsUsableForSpeechAndPreview() {
        val text = ArenaMarkdown.plainText(sample)
        assertTrue(text.contains("递归自我改进"))
        // 标记符号不能读出来
        assertTrue(!text.contains("**"))
        assertTrue(!text.contains("###"))
        assertTrue(!text.contains("| ---"))
        // 有序列表的序号必须保留 —— 这正是 innerText 丢掉的东西
        assertTrue(text.contains("1. 模型自己写代码"))
    }

    @Test
    fun plainTextDoesNotLoseOrderedListNumbersLikeInnerTextDid() {
        val markdown = "1. 第一步\n2. 第二步\n3. 第三步"
        assertEquals("1. 第一步\n2. 第二步\n3. 第三步", ArenaMarkdown.plainText(markdown))
    }

    @Test
    fun malformedInputDegradesToParagraphsInsteadOfThrowing() {
        // 未闭合的围栏、孤立的竖线、半个链接：都不能抛异常也不能丢内容
        val messy = "```python\n没有收尾\n\n| 只有一行竖线 |\n[半个链接](\n**未闭合加粗"
        val blocks = ArenaMarkdown.parse(messy)
        assertTrue(blocks.isNotEmpty())
        val restored = ArenaMarkdown.plainText(messy)
        assertTrue(restored.contains("没有收尾"))
        assertTrue(restored.contains("只有一行竖线"))
        assertTrue(restored.contains("未闭合加粗"))
    }

    @Test
    fun plainOldTextStillRendersAsParagraphs() {
        // 0.5.0 之前存下来的会话是无格式纯文本，解析后必须原样可读
        val legacy = "第一段。\n\n第二段，带一个 3 * 4 的乘号。"
        val blocks = ArenaMarkdown.parse(legacy)
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is ArenaMdBlock.Paragraph })
    }

    @Test
    fun blankAndWhitespaceOnlyInputProducesNoBlocks() {
        assertTrue(ArenaMarkdown.parse("").isEmpty())
        assertTrue(ArenaMarkdown.parse("   \n\n  \t ").isEmpty())
        assertEquals("", ArenaMarkdown.plainText(""))
    }

    @Test
    fun tableRowsShorterThanHeaderDoNotCrash() {
        val markdown = "| a | b | c |\n| --- | --- | --- |\n| 1 |"
        val table = ArenaMarkdown.parse(markdown).filterIsInstance<ArenaMdBlock.Table>().single()
        assertEquals(3, table.header.size)
        assertEquals(listOf(listOf("1")), table.rows)
    }
}
