package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发出去的 prompt 不能带多余缩进。
 *
 * 这两个 builder 用 `"""…""".trimIndent()`，模板里缩进了 12 空格，但插进去的
 * `$others` / `$viewpoints` 是顶格的多行文本 —— `trimIndent()` 取的是**所有行**的
 * 公共最小缩进，于是被这些顶格行拉成 0，模板自己那 12 个空格一个都没被去掉。
 * 四个空格以上的行会被不少模型当成代码块，等于把问题和要求塞进了 ``` 里。
 */
class ArenaPromptShapeTest {

    private val responses = mapOf(
        ArenaService.DOUBAO to "建议先连续测一周再说。",
        ArenaService.KIMI to "已经属于 1 级高血压。",
    )

    private fun assertNoIndentedLines(label: String, prompt: String) {
        val offenders = prompt.lines().filter { it.isNotBlank() && it.first().isWhitespace() }
        assertTrue(
            "$label 有 ${offenders.size} 行带前导空白，第一行：${offenders.firstOrNull()?.take(40)}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun debatePromptHasNoStrayIndent() {
        val prompt = DebatePromptBuilder.build(
            originalQuestion = "父母血压 145/92 需要吃药吗？",
            target = ArenaService.DEEPSEEK,
            responses = responses,
            debateIndex = 1,
            guidance = "顺便说说饮食",
        )
        assertNoIndentedLines("debate", prompt)
    }

    @Test
    fun summaryPromptHasNoStrayIndentAtAnyDepth() {
        SummaryDepth.entries.forEach { depth ->
            val prompt = DiscussionSummaryPromptBuilder.build(
                originalQuestion = "父母血压 145/92 需要吃药吗？",
                history = listOf(
                    RoundRecord(
                        number = 1,
                        kind = RoundKind.INITIAL,
                        answerMode = AnswerMode.PARALLEL,
                        guidance = "",
                        results = emptyMap(),
                        startedAtMillis = 0,
                        finishedAtMillis = 1,
                    ),
                ),
                responses = responses,
                customInstruction = "讲通俗一点",
                depth = depth,
            )
            assertNoIndentedLines("summary($depth)", prompt)
        }
    }

    @Test
    fun debatePromptStartsWithItsOpeningLine() {
        val prompt = DebatePromptBuilder.build(
            originalQuestion = "问题",
            target = ArenaService.DEEPSEEK,
            responses = responses,
        )
        assertEquals("这是观点讨论第 1 轮。", prompt.lines().first())
    }

    @Test
    fun summaryPromptStartsByNamingTheCaptain() {
        val prompt = DiscussionSummaryPromptBuilder.build(
            originalQuestion = "问题",
            history = emptyList(),
            responses = responses,
        )
        assertTrue(prompt.lines().first().contains("队长"))
    }
}
