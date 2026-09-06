package com.tianlin.aiarena

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 把抓回来的 Markdown 渲染成原生排版。
 *
 * 刻意**不**去还原各家网页自己的样式：六家站点长得互不相同，各留各的会让圆桌
 * 变成大杂烩，而圆桌的价值恰恰是横向对比。这里保留的是**结构**
 * （标题 / 列表 / 加粗 / 代码 / 表格 / 引用角标），排版统一到当前皮肤。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = ArenaStyle.colors.ink,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    /** 非空时按估算行数截断成折叠预览；预览里同样保留格式。 */
    collapsedLines: Int? = null,
) {
    val colors = ArenaStyle.colors
    val blocks = remember(markdown, collapsedLines) {
        val parsed = ArenaMarkdown.parse(markdown)
        if (collapsedLines == null) parsed else ArenaMarkdown.preview(parsed, collapsedLines)
    }
    if (blocks.isEmpty()) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ArenaMdBlock.Paragraph ->
                    Text(annotate(block.text, colors), color = color, style = style)

                is ArenaMdBlock.Heading -> Text(
                    text = annotate(block.text, colors),
                    color = color,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )

                is ArenaMdBlock.Bullet -> MarkerRow(
                    indent = block.depth,
                    marker = when (block.depth) {
                        0 -> "\u2022"
                        1 -> "\u25E6"
                        else -> "\u25AA"
                    },
                    markerWidth = 18,
                    text = annotate(block.text, colors),
                    color = color,
                    style = style,
                )

                is ArenaMdBlock.Ordered -> MarkerRow(
                    indent = block.depth,
                    marker = block.marker,
                    markerWidth = 26,
                    text = annotate(block.text, colors),
                    color = color,
                    style = style,
                )

                is ArenaMdBlock.Quote -> Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(colors.accent.copy(alpha = 0.55f), RoundedCornerShape(2.dp)),
                    )
                    Text(annotate(block.text, colors), color = colors.muted, style = style)
                }

                is ArenaMdBlock.Code -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceAlt)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (block.language.isNotBlank()) {
                        Text(
                            text = block.language,
                            color = colors.muted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        text = block.code,
                        color = color,
                        style = style.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        softWrap = false,
                    )
                }

                is ArenaMdBlock.Table -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    TableRow(block.header, colors, style, header = true)
                    block.rows.forEach { row ->
                        HorizontalDivider(color = colors.border)
                        TableRow(row, colors, style, header = false, columns = block.header.size)
                    }
                }

                ArenaMdBlock.Divider -> HorizontalDivider(color = colors.border)
            }
        }
    }
}

@Composable
private fun MarkerRow(
    indent: Int,
    marker: String,
    markerWidth: Int,
    text: AnnotatedString,
    color: Color,
    style: TextStyle,
) {
    Row(modifier = Modifier.padding(start = (indent * 16).dp)) {
        Text(
            text = marker,
            color = ArenaStyle.colors.muted,
            style = style,
            modifier = Modifier.width(markerWidth.dp),
        )
        Text(text = text, color = color, style = style, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    colors: ArenaPalette,
    style: TextStyle,
    header: Boolean,
    columns: Int = cells.size,
) {
    Row(
        modifier = Modifier.padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        repeat(columns) { index ->
            Text(
                text = annotate(cells.getOrNull(index).orEmpty(), colors),
                color = colors.ink,
                style = if (header) style.copy(fontWeight = FontWeight.Bold) else style,
                modifier = Modifier
                    .width(132.dp)
                    .padding(end = 10.dp),
                overflow = TextOverflow.Visible,
            )
        }
    }
}

/** 行内片段 → AnnotatedString。链接只上色不可点：要看原文有「跳转网页」入口。 */
private fun annotate(source: String, colors: ArenaPalette): AnnotatedString = buildAnnotatedString {
    ArenaMarkdown.inline(source).forEach { span ->
        val decoration = when {
            span.strike && span.link != null ->
                TextDecoration.combine(listOf(TextDecoration.LineThrough, TextDecoration.Underline))
            span.strike -> TextDecoration.LineThrough
            span.link != null -> TextDecoration.Underline
            else -> null
        }
        withStyle(
            SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                color = when {
                    span.link != null -> colors.accent
                    span.code -> colors.accent
                    else -> Color.Unspecified
                },
                textDecoration = decoration,
            ),
        ) {
            append(span.text)
        }
    }
}
