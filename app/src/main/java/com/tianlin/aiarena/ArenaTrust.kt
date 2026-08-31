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
            consensusReviewed = normalized.contains("共识"),
            differencesReviewed = normalized.contains("分歧") || normalized.contains("不同"),
            verificationReminderCount = verificationLines,
            domainCaution = domainCaution,
        )
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
