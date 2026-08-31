package com.tianlin.aiarena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：注入网页的脚本此前有几处会在真实站点上静默失效的问题，
 * 这里把"不能再退回去"的性质固定下来。
 */
class ArenaWebScriptHardeningTest {
    @Test
    fun sendButtonCandidatesAreAnOrderedListNotOneCommaSelector() {
        ArenaService.entries.forEach { service ->
            val candidates = ArenaWebViewPool.sendButtonSelectors(service)
            assertTrue("${service.name} 至少要有一个候选", candidates.isNotEmpty())
            candidates.forEach { candidate ->
                // 单个候选里不能再含逗号，否则又退化成"文档顺序第一个"的语义。
                assertFalse("${service.name} 的候选 $candidate 不应是逗号列表", candidate.contains(','))
            }
        }
    }

    @Test
    fun doubaoSendButtonPrefersTheSpecificContainerScopedControl() {
        val candidates = ArenaWebViewPool.sendButtonSelectors(ArenaService.DOUBAO)

        assertTrue(candidates.first().startsWith("#input-engine-container"))
        assertTrue(
            "宽泛的 button[class*='send'] 必须排在精确候选之后",
            candidates.indexOf("button[class*='send']") > 0,
        )
    }

    @Test
    fun sendButtonSelectorStringStillJoinsTheSameCandidates() {
        ArenaService.entries.forEach { service ->
            assertEquals(
                ArenaWebViewPool.sendButtonSelectors(service).joinToString(", "),
                ArenaWebViewPool.sendButtonSelector(service),
            )
        }
    }

    @Test
    fun injectedScriptsLookUpSelectorsInPriorityOrder() {
        val helper = ArenaWebViewPool.selectorHelperScript()

        assertTrue(helper.contains("arenaFirstMatch"))
        assertTrue("必须逐个 querySelector，而不是一次性交给逗号列表", helper.contains("for (const selector of selectors)"))
    }

    @Test
    fun stopButtonProbeIsScopedToButtonsOnly() {
        ArenaService.entries.forEach { service ->
            val script = ArenaWebResponseScript.build(service, "req_1")
            // 裸的 [class*=stop] 会匹配任何 class 含 stop 的元素，
            // 一旦页面常驻这类节点，streaming 恒为 true，该家必然走满 300 秒超时。
            assertFalse(
                "${service.name} 不应使用未限定标签的 [class*=stop]",
                Regex("""(^|[\s,\[(])\[class\*=stop]""").containsMatchIn(script),
            )
        }
    }

    @Test
    fun taggedAnchorIsActuallyUsedToScopeTheAnswer() {
        listOf(ArenaService.QWEN, ArenaService.YUANBAO, ArenaService.ZHIPU).forEach { service ->
            val script = ArenaWebResponseScript.build(service, "req_2")
            assertTrue("${service.name} 必须声明 tagged", script.contains("const tagged ="))
            assertTrue(
                "${service.name} 的 tagged 必须真的参与范围计算，而不是死代码",
                script.contains("scopeAfterTag(picked.nodes, tagged"),
            )
        }
    }

    @Test
    fun scopeHelperFallsBackToCountBaselineWhenTheAnchorIsGone() {
        val script = ArenaWebResponseScript.build(ArenaService.ZHIPU, "req_3")

        assertTrue(script.contains("DOCUMENT_POSITION_FOLLOWING"))
        assertTrue("虚拟列表回收掉标记后要能退回计数基线", script.contains("candidates.slice(baseline)"))
    }

    @Test
    fun qwenFallsBackToDomStreamingProbeWhenNetworkAnswerIsStillEmpty() {
        val script = ArenaWebResponseScript.build(ArenaService.QWEN, "req_4")

        // SSE 刚建 record、还没解析出内容时 answer 为空；此时不能把 streaming 停在 false，
        // 否则 DOM 文本连续两次不变就会被判完成，把半截答案当最终答案。
        assertTrue(script.contains("networkAnswer.length === 0"))
        assertFalse(script.contains("if (!networkRecord) {\n                  streaming"))
    }

    @Test
    fun truncationNeverSplitsASurrogatePair() {
        val script = ArenaWebResponseScript.build(ArenaService.DEEPSEEK, "req_5")

        assertTrue(script.contains("0xD800"))
        assertTrue(script.contains("0xDBFF"))
    }

    @Test
    fun qwenNetworkBufferIsBoundedAndParsedIncrementally() {
        val script = ArenaWebViewPool.qwenCaptureScript()

        assertFalse("2MB 缓冲每次全量重解析是 O(n^2)", script.contains("slice(-2000000)"))
        assertTrue("只解析新到达的完整行", script.contains("lastIndexOf('\\n')"))
        assertTrue(script.contains("200000"))
    }
}
