package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出错说明与轮次叙述：给家人看的文案不能出现开发者术语，也不能为空。
 */
class ArenaGuidanceTest {

    private val jargon = listOf("锚定", "提取器", "DOM", "selector", "注入", "requestId", "回调")

    @Test
    fun everyKnownDetailGetsPlainAdvice() {
        val details = listOf(
            "豆包 尚未登录",
            "千问安全验证等待超时，请完成验证后再次点击重新提取",
            "网页进程已退出，点重新加载恢复",
            "应用重启，已停止等待；已保留现有内容",
            "已停止等待；网页可能仍在生成",
            "豆包 网页输入框加载超时",
            "重发失败：发送按钮不可用",
            "等待回答超时",
            "等待回答 · 95秒 · 迟迟没有回应，可点「原网页」看看是否需要登录或完成验证",
            "连续读取失败：TypeError",
            "重新提取失败：empty",
            "net::ERR_INTERNET_DISCONNECTED",
            "页面加载失败",
            "已跳过本轮",
            "完全没见过的原因",
        )
        details.forEach { detail ->
            val advice = ArenaErrorHelp.explain(detail, "豆包")
            assertTrue("what 为空: $detail", advice.what.isNotBlank())
            assertTrue("next 为空: $detail", advice.next.isNotBlank())
            jargon.forEach { word ->
                assertFalse("出现术语 $word: ${advice.what} ${advice.next}", (advice.what + advice.next).contains(word))
            }
        }
    }

    @Test
    fun loginProblemPointsToLogin() {
        val advice = ArenaErrorHelp.explain("DeepSeek 尚未登录", "DeepSeek")
        assertEquals(ArenaErrorHelp.Action.LOGIN, advice.primary)
        assertTrue(advice.next.contains("打开网页"))
    }

    @Test
    fun securityChallengeAsksToOpenPageThenReextract() {
        val advice = ArenaErrorHelp.explain("千问安全验证等待超时，请完成验证后点击重新提取", "千问")
        assertEquals(ArenaErrorHelp.Action.OPEN_PAGE, advice.primary)
        assertTrue(advice.next.contains("重新提取"))
    }

    @Test
    fun readFailureSuggestsReextractFirst() {
        val advice = ArenaErrorHelp.explain("连续读取失败：TypeError", "Kimi")
        assertEquals(ArenaErrorHelp.Action.REEXTRACT, advice.primary)
    }

    @Test
    fun offlineIsExplainedAsNetwork() {
        val advice = ArenaErrorHelp.explain("net::ERR_INTERNET_DISCONNECTED", "Kimi")
        assertTrue(advice.what.contains("网络"))
        assertTrue(advice.next.contains("Wi-Fi"))
    }

    @Test
    fun skippedHasNoPrimaryAction() {
        assertEquals(ArenaErrorHelp.Action.NONE, ArenaErrorHelp.explain("已跳过本轮", "Kimi").primary)
    }

    @Test
    fun summaryAdviceNamesTheJudge() {
        val advice = ArenaErrorHelp.explainSummary("等待总结超时", "DeepSeek")
        assertTrue(advice.what.contains("DeepSeek"))
        assertEquals(ArenaErrorHelp.Action.RETRY_SUMMARY, advice.primary)
    }

    @Test
    fun narrationWhileWaiting() {
        val text = RoundNarration.describe(
            busy = true, kind = RoundKind.INITIAL, roundNumber = 1,
            total = 3, completed = 2, failed = 0, waitingNames = listOf("豆包"),
        )
        assertEquals("2 位已回答，还在等豆包…", text)
    }

    @Test
    fun narrationWhenJustSent() {
        val text = RoundNarration.describe(
            busy = true, kind = RoundKind.INITIAL, roundNumber = 1,
            total = 3, completed = 0, failed = 0, waitingNames = listOf("A", "B", "C"),
        )
        assertEquals("已经发给 3 位 AI，正在等它们回答…", text)
    }

    @Test
    fun narrationWhenAllDone() {
        val text = RoundNarration.describe(
            busy = false, kind = RoundKind.INITIAL, roundNumber = 1,
            total = 3, completed = 3, failed = 0, waitingNames = emptyList(),
        )
        assertEquals("初始回答完成，3 位 AI 都回答了", text)
    }

    @Test
    fun narrationWithFailuresInLaterRound() {
        val text = RoundNarration.describe(
            busy = false, kind = RoundKind.DEBATE, roundNumber = 2,
            total = 3, completed = 2, failed = 1, waitingNames = emptyList(),
        )
        assertEquals("第 2 轮观点讨论完成：2 位回答了，1 位没成功", text)
    }
}
