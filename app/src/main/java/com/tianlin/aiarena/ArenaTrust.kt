package com.tianlin.aiarena

data class DiscussionTrustSignal(
    val providerCount: Int,
    val consensusReviewed: Boolean,
    val differencesReviewed: Boolean,
    val verificationReminderCount: Int,
    val domainCaution: String,
)

object DiscussionTrustPolicy {
    private val verificationTerms = listOf(
        "核验",
        "核实",
        "确认",
        "查询",
        "查看官网",
        "以实际为准",
        "以说明书为准",
        "咨询",
        "不确定",
        "因地而异",
    )
    private val healthTerms = listOf("药", "服药", "病", "医", "症状", "体检", "健康", "剂量")
    private val financialTerms = listOf("投资", "理财", "股票", "收益", "保本", "借款")

    fun analyze(question: String, summary: String, providerCount: Int): DiscussionTrustSignal {
        val normalized = summary.trim()
        val verificationLines = normalized.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .count { line -> verificationTerms.any(line::contains) }
            .coerceAtMost(9)
        val domainCaution = when {
            healthTerms.any(question::contains) -> "健康/用药问题请再咨询医生或药师"
            financialTerms.any(question::contains) -> "金融决策请核对风险并咨询持牌人士"
            else -> "多 AI 一致不等于事实，关键信息仍需核实"
        }
        return DiscussionTrustSignal(
            providerCount = providerCount.coerceAtLeast(0),
            // 「深入」总结按 prompt 写的是"几家一致 / 有分歧"，没有"共识"两个字，也算把共识核过了
            consensusReviewed = normalized.contains("共识") || normalized.contains("一致"),
            differencesReviewed = normalized.contains("分歧") || normalized.contains("不同"),
            verificationReminderCount = verificationLines,
            domainCaution = domainCaution,
        )
    }
}

/**
 * 总结"看起来没写正文"的判定。
 *
 * 用户手机上实测（2026-09-06）：豆包把总结写成了"文档"卡片，对话里只剩一句
 * "我将结合三份 AI 回答的核心内容……需要我帮你压缩到更精炼的 300 字内版本吗？"。
 * App 只能读对话里的文字，读不到文档正文，家人看到的就像没总结——而且那句话里有"共识 / 分歧"
 * 两个词，交叉核验卡还会误报"共识已提炼"。很短、或不长且带"需要我 / 文档 / 已生成"这类话的，
 * 当作可疑：卡片上明说，给「跳转网页看原文」和「重新总结」。prompt 里也已明说"写在对话里，不要生成文件"。
 */
object SummarySanityPolicy {
    private val placeholderPhrases = listOf(
        "需要我", "是否需要", "要不要我", "文档", "文件", "附件", "已生成", "已为你", "已为您", "已整理", "请查收", "点击查看",
    )
    const val SHORT_CHARS = 60
    const val PLACEHOLDER_MAX_CHARS = 160

    fun looksLikePlaceholder(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.length < SHORT_CHARS) return true
        return trimmed.length < PLACEHOLDER_MAX_CHARS && placeholderPhrases.any(trimmed::contains)
    }
}

data class BudgetedPrompt(
    val text: String,
    val compressed: Boolean,
    val originalLength: Int,
    val budget: Int,
    val quoteLimit: Int,
)

object PromptBudgetPolicy {
    const val DEFAULT_BUDGET = 16_000
    const val QWEN_BUDGET = 8_000
    const val MIN_QUOTE_CHARACTERS = 300

    fun budgetFor(service: ArenaService): Int =
        if (service == ArenaService.QWEN) QWEN_BUDGET else DEFAULT_BUDGET

    fun fit(
        service: ArenaService,
        initialQuoteLimit: Int = ArenaLimits.MAX_QUOTED_RESPONSE_CHARS,
        builder: (quoteLimit: Int) -> String,
    ): BudgetedPrompt? {
        val budget = budgetFor(service)
        val first = builder(initialQuoteLimit)
        if (first.length <= budget) {
            return BudgetedPrompt(first, compressed = false, first.length, budget, initialQuoteLimit)
        }
        var quoteLimit = initialQuoteLimit
        var candidate = first
        while (candidate.length > budget && quoteLimit > MIN_QUOTE_CHARACTERS) {
            quoteLimit = (quoteLimit * 3 / 4).coerceAtLeast(MIN_QUOTE_CHARACTERS)
            candidate = builder(quoteLimit)
        }
        if (candidate.length > budget) return null
        return BudgetedPrompt(
            text = candidate,
            compressed = true,
            originalLength = first.length,
            budget = budget,
            quoteLimit = quoteLimit,
        )
    }
}

/**
 * 提问长度提醒。
 *
 * 初始回答直接把原问题发给各家网页，24,000 字都能发出去；但"观点讨论"和"讨论总结"
 * 要把原问题连同别家回答一起塞进同一条 prompt，受 [PromptBudgetPolicy] 预算限制
 * （千问只有 8,000）。所以存在一个区间：问题发得出去，但后续讨论一定失败。
 * 这里在用户还在输入时就提前算出该区间并给出提示，而不是等到点"观点讨论"才报错。
 */
object QuestionLengthPolicy {
    /** 参与成员中最紧的预算，减去模板占用后剩给原问题的字数。 */
    fun advisoryLimit(services: List<ArenaService>): Int {
        val budget = services.minOfOrNull(PromptBudgetPolicy::budgetFor)
            ?: PromptBudgetPolicy.DEFAULT_BUDGET
        return (budget - ArenaLimits.PROMPT_TEMPLATE_RESERVE).coerceAtLeast(0)
    }

    fun exceedsHardLimit(question: String): Boolean =
        question.length > ArenaLimits.MAX_QUESTION_CHARS

    /** 返回给用户看的提示；没有问题时返回 null。 */
    fun advisory(question: String, services: List<ArenaService>): String? {
        if (exceedsHardLimit(question)) {
            return "问题超过 ${ArenaLimits.MAX_QUESTION_CHARS} 字，请缩短后再发送。"
        }
        val limit = advisoryLimit(services)
        if (question.length <= limit) return null
        val tightest = services.minByOrNull(PromptBudgetPolicy::budgetFor)
        val who = tightest?.displayName?.let { "（${it}的上下文最紧）" }.orEmpty()
        return "问题较长，初始回答可以发送，但「观点讨论」和「队长总结」可能因超出上下文而失败$who。" +
            "建议压到 $limit 字以内。"
    }
}
