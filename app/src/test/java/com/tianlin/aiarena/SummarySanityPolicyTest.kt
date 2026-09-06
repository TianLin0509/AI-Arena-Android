package com.tianlin.aiarena

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 总结"没正文"的兜底。用户手机上豆包把总结写进了文档卡片，对话里只剩一句反问，
 * 家人看到的就像没总结，交叉核验卡还因为那句话里有"共识 / 分歧"两个词误报。
 */
class SummarySanityPolicyTest {

    @Test
    fun doubaoDocumentPlaceholderIsCaught() {
        // 用户截图里的原话（2026-09-06）
        val text = "我将结合三份 AI 回答的核心内容，用通俗白话梳理共识、分歧，搭配落地建议，严格贴合要求精简成文。" +
            "需要我帮你压缩到更精炼的 300 字内版本，方便快速查阅吗？"
        assertTrue(SummarySanityPolicy.looksLikePlaceholder(text))
    }

    @Test
    fun veryShortTextIsSuspiciousEvenWithoutMetaPhrases() {
        assertTrue(SummarySanityPolicy.looksLikePlaceholder("已整理好，见上方。"))
        assertTrue(SummarySanityPolicy.looksLikePlaceholder("好的。"))
    }

    @Test
    fun realBriefSummaryIsNotFlagged() {
        // 「简明」档的合法输出：约 150 字、有结论和点评，没有"需要我 / 文档"这类话
        val brief = "结论：健康老人每天走 6000 到 8000 步比较合适，体弱的从 3000 步起步。" +
            "点评：DeepSeek 给了世卫组织的区间，最稳；豆包强调别硬凑一万步，务实；Kimi 补了每分钟 100 到 120 步的节奏，最细。" +
            "最该做的一件事：先连续一周每天走 4000 步，不喘不累再往上加。"
        assertFalse(SummarySanityPolicy.looksLikePlaceholder(brief))
    }

    @Test
    fun longTextMentioningFilesIsNotFlagged() {
        // 正文里顺带提到"文件"不算：只有短文本才可疑
        val long = "结论：先调作息。".repeat(20) + "共识：四家都同意规律作息。" + "分歧：豆包建议躺 20 分钟睡不着就起身。" +
            "建议：如需记录可以写在文件里。"
        assertFalse(SummarySanityPolicy.looksLikePlaceholder(long))
    }

    @Test
    fun blankIsNotAPlaceholder() {
        // 空文本由"总结失败"那条路处理，不归这里
        assertFalse(SummarySanityPolicy.looksLikePlaceholder("   "))
    }
}
