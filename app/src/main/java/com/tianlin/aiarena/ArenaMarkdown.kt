package com.tianlin.aiarena

/**
 * 网页答案的 Markdown 表示与解析。
 *
 * 六家 AI 站点本来都是「模型吐 Markdown → 前端渲染成 HTML」，所以把 DOM 转回
 * Markdown 是一次近乎无损的逆运算，而且六家会收敛到同一种中间表示。
 * 抓取侧见 [ArenaMarkdownScript]，渲染侧见 ArenaComponents 的 MarkdownText。
 *
 * 这个文件刻意只依赖 Kotlin 标准库：块级解析是最容易出细节 bug 的一层，
 * 必须能在 JVM 单元测试里直接覆盖，不能拖进 Compose 或 Android 运行时。
 */
sealed interface ArenaMdBlock {
    data class Paragraph(val text: String) : ArenaMdBlock
    data class Heading(val level: Int, val text: String) : ArenaMdBlock
    data class Bullet(val depth: Int, val text: String) : ArenaMdBlock
    data class Ordered(val depth: Int, val marker: String, val text: String) : ArenaMdBlock
    data class Quote(val text: String) : ArenaMdBlock
    data class Code(val language: String, val code: String) : ArenaMdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : ArenaMdBlock
    data object Divider : ArenaMdBlock
}

/** 行内片段。link 非空时表示这段是链接。 */
data class ArenaMdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

object ArenaMarkdown {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)")
    private val BULLET = Regex("^(\\s*)([-*+])\\s+(.*)")
    private val ORDERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)")
    private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)")
    private val DIVIDER = Regex("^\\s{0,3}([-*_])\\s*(\\1\\s*){2,}$")
    private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([A-Za-z0-9+#._-]*)\\s*$")
    private val TABLE_DIVIDER = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")

    /** 把 Markdown 源码切成块。无法识别的行一律并进段落，绝不丢内容。 */
    fun parse(source: String): List<ArenaMdBlock> {
        if (source.isBlank()) return emptyList()
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<ArenaMdBlock>()
        val paragraph = StringBuilder()
        val quote = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotBlank()) blocks += ArenaMdBlock.Paragraph(paragraph.toString().trim())
            paragraph.setLength(0)
        }

        fun flushQuote() {
            if (quote.isNotBlank()) blocks += ArenaMdBlock.Quote(quote.toString().trim())
            quote.setLength(0)
        }

        fun flushAll() {
            flushParagraph()
            flushQuote()
        }

        var index = 0
        while (index < lines.size) {
            val line = lines[index]

            val fence = FENCE.find(line)
            if (fence != null) {
                flushAll()
                val marker = fence.groupValues[1]
                val language = fence.groupValues[2]
                val body = StringBuilder()
                index += 1
                while (index < lines.size) {
                    val current = lines[index]
                    // 只有同种围栏才算收尾，避免 ``` 里出现 ~~~ 时提前闭合。
                    val closing = FENCE.find(current)
                    if (closing != null && closing.groupValues[1].isNotEmpty() &&
                        closing.groupValues[1][0] == marker[0] &&
                        closing.groupValues[1].length >= marker.length &&
                        closing.groupValues[2].isEmpty()
                    ) {
                        index += 1
                        break
                    }
                    body.append(current).append('\n')
                    index += 1
                }
                blocks += ArenaMdBlock.Code(language, body.toString().trimEnd('\n'))
                continue
            }

            if (line.isBlank()) {
                flushAll()
                index += 1
                continue
            }

            if (DIVIDER.matches(line)) {
                flushAll()
                blocks += ArenaMdBlock.Divider
                index += 1
                continue
            }

            // 表格：当前行像一行单元格，且下一行是 |---|---| 分隔线。
            if (line.contains('|') && index + 1 < lines.size && TABLE_DIVIDER.matches(lines[index + 1])) {
                flushAll()
                val header = splitRow(line)
                val rows = mutableListOf<List<String>>()
                index += 2
                while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                    rows += splitRow(lines[index])
                    index += 1
                }
                blocks += ArenaMdBlock.Table(header, rows)
                continue
            }

            val heading = HEADING.find(line)
            if (heading != null) {
                flushAll()
                blocks += ArenaMdBlock.Heading(
                    level = heading.groupValues[1].length,
                    text = heading.groupValues[2].trim(),
                )
                index += 1
                continue
            }

            val quoted = QUOTE.find(line)
            if (quoted != null && line.trimStart().startsWith(">")) {
                flushParagraph()
                if (quote.isNotEmpty()) quote.append('\n')
                quote.append(quoted.groupValues[1].trim())
                index += 1
                continue
            }

            val ordered = ORDERED.find(line)
            if (ordered != null) {
                flushAll()
                blocks += ArenaMdBlock.Ordered(
                    depth = indentDepth(ordered.groupValues[1]),
                    marker = ordered.groupValues[2] + ".",
                    text = ordered.groupValues[3].trim(),
                )
                index += 1
                continue
            }

            val bullet = BULLET.find(line)
            if (bullet != null) {
                flushAll()
                blocks += ArenaMdBlock.Bullet(
                    depth = indentDepth(bullet.groupValues[1]),
                    text = bullet.groupValues[3].trim(),
                )
                index += 1
                continue
            }

            flushQuote()
            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(line.trim())
            index += 1
        }
        flushAll()
        return blocks
    }

    private fun indentDepth(indent: String): Int {
        val width = indent.sumOf { if (it == '\t') 4 else 1 }
        return (width / 2).coerceIn(0, 4)
    }

    private fun splitRow(line: String): List<String> =
        line.trim().trim('|').split('|').map { it.trim() }

    /**
     * 行内解析。反引号优先级最高（代码里不再解析强调），其余按 ** / ~~ / * / 链接处理。
     * 反斜杠转义原样保留字符本身。
     */
    fun inline(source: String): List<ArenaMdSpan> {
        if (source.isEmpty()) return emptyList()
        val spans = mutableListOf<ArenaMdSpan>()
        val buffer = StringBuilder()
        var bold = false
        var italic = false
        var strike = false
        var index = 0

        fun flush() {
            if (buffer.isEmpty()) return
            spans += ArenaMdSpan(buffer.toString(), bold = bold, italic = italic, strike = strike)
            buffer.setLength(0)
        }

        while (index < source.length) {
            val ch = source[index]

            if (ch == '\\' && index + 1 < source.length) {
                buffer.append(source[index + 1])
                index += 2
                continue
            }

            if (ch == '`') {
                val end = source.indexOf('`', index + 1)
                if (end > index) {
                    flush()
                    spans += ArenaMdSpan(source.substring(index + 1, end), code = true)
                    index = end + 1
                    continue
                }
            }

            if (ch == '[') {
                val close = source.indexOf(']', index + 1)
                if (close > index && close + 1 < source.length && source[close + 1] == '(') {
                    val paren = source.indexOf(')', close + 2)
                    if (paren > close) {
                        flush()
                        val label = source.substring(index + 1, close)
                        val href = source.substring(close + 2, paren).trim()
                        // 链接文字本身可能还有强调，递归一层就够用。
                        inline(label).forEach { inner ->
                            spans += inner.copy(
                                bold = inner.bold || bold,
                                italic = inner.italic || italic,
                                strike = inner.strike || strike,
                                link = href,
                            )
                        }
                        index = paren + 1
                        continue
                    }
                }
            }

            if (ch == '*' && index + 1 < source.length && source[index + 1] == '*') {
                flush()
                bold = !bold
                index += 2
                continue
            }

            if (ch == '~' && index + 1 < source.length && source[index + 1] == '~') {
                flush()
                strike = !strike
                index += 2
                continue
            }

            if (ch == '*') {
                flush()
                italic = !italic
                index += 1
                continue
            }

            buffer.append(ch)
            index += 1
        }
        flush()
        return spans.filter { it.text.isNotEmpty() }
    }

    /** 去掉标记后的纯文本，用于折叠预览估算与无格式场景。 */
    fun plainText(source: String): String = buildString {
        parse(source).forEach { block ->
            val line = when (block) {
                is ArenaMdBlock.Paragraph -> spanText(block.text)
                is ArenaMdBlock.Heading -> spanText(block.text)
                is ArenaMdBlock.Bullet -> spanText(block.text)
                is ArenaMdBlock.Ordered -> block.marker + " " + spanText(block.text)
                is ArenaMdBlock.Quote -> spanText(block.text)
                is ArenaMdBlock.Code -> block.code
                is ArenaMdBlock.Table ->
                    (listOf(block.header) + block.rows).joinToString("\n") { row ->
                        row.joinToString("  ") { spanText(it) }
                    }
                ArenaMdBlock.Divider -> ""
            }
            if (line.isNotBlank()) {
                append(line)
                append('\n')
            }
        }
    }.trim()

    /**
     * 折叠预览：按估算行数截断到前若干块。
     *
     * 折叠态是默认视图，所以不能退回无格式纯文本 —— 预览里同样要保留结构。
     * 按块截断而不是按像素裁切，是为了不把某一行从中间切开。
     */
    fun preview(
        blocks: List<ArenaMdBlock>,
        lineBudget: Int,
        charsPerLine: Int = 22,
    ): List<ArenaMdBlock> {
        if (lineBudget <= 0 || blocks.isEmpty()) return blocks
        val safePerLine = charsPerLine.coerceAtLeast(1)
        val kept = mutableListOf<ArenaMdBlock>()
        var used = 0
        for (block in blocks) {
            // 至少保留一块，否则超长的首段会让预览整个空掉
            if (kept.isNotEmpty() && used >= lineBudget) break
            kept += block
            used += estimateLines(block, safePerLine)
        }
        return kept
    }

    private fun estimateLines(block: ArenaMdBlock, charsPerLine: Int): Int {
        fun wrapped(text: String) = (spanText(text).length + charsPerLine - 1) / charsPerLine
        return when (block) {
            is ArenaMdBlock.Paragraph -> wrapped(block.text).coerceAtLeast(1)
            is ArenaMdBlock.Heading -> wrapped(block.text).coerceAtLeast(1) + 1
            is ArenaMdBlock.Bullet -> wrapped(block.text).coerceAtLeast(1)
            is ArenaMdBlock.Ordered -> wrapped(block.text).coerceAtLeast(1)
            is ArenaMdBlock.Quote -> wrapped(block.text).coerceAtLeast(1)
            is ArenaMdBlock.Code -> block.code.lines().size + 2
            is ArenaMdBlock.Table -> block.rows.size + 2
            ArenaMdBlock.Divider -> 1
        }
    }

    private fun spanText(source: String): String =
        inline(source).joinToString("") { it.text }
}
