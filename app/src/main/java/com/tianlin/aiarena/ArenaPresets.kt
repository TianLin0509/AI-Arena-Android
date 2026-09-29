package com.tianlin.aiarena

import android.content.Context
import androidx.core.content.edit

/**
 * 预设提示词（参考 AI圆桌 Lite 0.3.0）：观点讨论两种方式、工作流、队长总结各有一份模板，
 * 用户可以在界面上看到、编辑、恢复默认。花括号里的是占位，发送时再填入真实材料；
 * 用户自己在输入框写的补充永远附在预设之后，界面上也按这个顺序展示。
 */
enum class PresetKey(val displayName: String, val required: List<String>) {
    DEBATE("观点讨论 · 互挑错", listOf("{队友回答}")),
    COLLAB("观点讨论 · 取长补短", listOf("{队友回答}")),
    RELAY("工作流 · 第 2 位起", listOf("{问题}", "{前面回答}")),
    SUMMARY("队长总结", listOf("{各家回答}")),
    ;

    companion object {
        fun fromName(value: String?): PresetKey? = entries.firstOrNull { it.name == value }
    }
}

/** 观点讨论的两种方式：互相挑错（辩论）或取长补短（协作）。 */
enum class DebateStyle(val displayName: String, val preset: PresetKey) {
    DEBATE("互挑错", PresetKey.DEBATE),
    COLLAB("取长补短", PresetKey.COLLAB),
    ;

    companion object {
        fun fromName(value: String?): DebateStyle = entries.firstOrNull { it.name == value } ?: DEBATE
    }
}

/** 当前生效的模板来源；界面编辑后写入 [ArenaPresetStore]，测试和默认场景用 [DefaultPresets]。 */
fun interface PresetSource {
    fun template(key: PresetKey): String
}

object DefaultPresets : PresetSource {
    private const val IN_BODY = "请直接写在这条对话里，不要生成文档或文件。"

    val templates: Map<PresetKey, String> = mapOf(
        PresetKey.DEBATE to "{轮次引导}\n\n原始问题：\n{原问题}\n\n以下是其他 AI 的最新回答：\n{队友回答}\n\n" +
            "请逐一讨论这些观点：明确指出你认同和不认同的部分，给出理由，修正可能的错误或遗漏，并形成你这一轮更可靠的结论。" +
            "不要只复述其他回答。总长度控制在 200 个汉字以内，最后单独给出一句综合结论。$IN_BODY",
        PresetKey.COLLAB to "{轮次引导}\n\n原始问题：\n{原问题}\n\n以下是队友们对同一问题的回答：\n{队友回答}\n\n" +
            "你们是协作关系，目标是共同得出最优方案。请吸收队友回答中的亮点和你没想到的角度，补充你认为重要而尚未覆盖的内容，" +
            "整合各方优势，给出一个更完善的综合回答。$IN_BODY",
        PresetKey.RELAY to "{问题}\n\n前面的成员已按顺序回答了这个问题。请认真阅读后给出你的回答：可以补充新的角度、纠正或反驳，" +
            "重点写出前面还没有覆盖的内容。$IN_BODY\n\n{前面回答}",
        PresetKey.SUMMARY to "你是这次多 AI 讨论的队长，请替一位普通家庭用户做一份总结。\n\n原始问题：\n{原问题}\n\n讨论轮次：\n{讨论经过}\n\n" +
            "各 AI 的完整回答：\n{各家回答}\n\n{总结要求}\n" +
            "要求：用长辈也能懂的白话，不用术语；不要照抄任何一家的原文；只依据上面真实出现的内容，不要声称材料里没有的事实；" +
            "不确定就明确说「不确定」，不要编。请直接把总结写在这条对话里（普通文字 + 小标题即可），不要生成文档、文件、附件或表格，" +
            "写完就结束，不要再反问「需要我……吗」。",
    )

    override fun template(key: PresetKey): String = templates.getValue(key)
}

/** 本机保存的自定义预设；没改过的键返回默认版本。 */
class ArenaPresetStore(context: Context) : PresetSource {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun template(key: PresetKey): String = custom(key) ?: DefaultPresets.template(key)

    fun custom(key: PresetKey): String? = preferences.getString(key.name, null)?.takeIf { ArenaPresets.validate(key, it) == null }

    /** 返回 null 表示已保存；否则是给用户看的原因。传 null 恢复默认。 */
    fun save(key: PresetKey, text: String?): String? {
        if (text == null) {
            preferences.edit { remove(key.name) }
            return null
        }
        ArenaPresets.validate(key, text)?.let { return it }
        preferences.edit { putString(key.name, if (text == DefaultPresets.template(key)) null else text) }
        return null
    }

    private companion object {
        const val PREFERENCES_NAME = "arena_presets"
    }
}

/** 预设在界面上的一段：普通文字，或一个占位胶囊（例如「队友1的回答」）。 */
data class PresetPart(val text: String, val slot: Boolean)

object ArenaPresets {
    const val MAX_TEMPLATE_CHARS = 4_000
    private val SLOT = Regex("\\{(轮次引导|原问题|队友回答|问题|前面回答|讨论经过|各家回答|总结要求)\\}")

    private val DEBATE_HINTS = listOf(
        "请仔细阅读其他 AI 的初始回答，找出核心分歧和共识。",
        "经过上一轮交锋，请聚焦于仍存在分歧的关键点，深化你的论证或修正你的观点。",
        "请避免重复已达成共识的内容，集中攻克剩余分歧点，给出最终立场。",
    )
    private val COLLAB_HINTS = listOf(
        "请仔细阅读队友们的回答，找出各自的亮点和你没想到的角度。",
        "队友们已经互相补充了一轮，请在此基础上进一步整合，查漏补缺。",
        "方案已趋于成熟，请做最终打磨：精简冗余，强化核心结论，形成一份完整方案。",
    )

    fun validate(key: PresetKey, text: String): String? {
        if (text.isBlank()) return "预设提示词不能为空；要恢复默认请点「恢复默认」。"
        if (text.length > MAX_TEMPLATE_CHARS) return "预设提示词超过 $MAX_TEMPLATE_CHARS 字，请精简。"
        val missing = key.required.filterNot { text.contains(it) }
        if (missing.isNotEmpty()) return "预设提示词必须保留 ${missing.joinToString("、")}，否则收不到讨论材料。"
        return null
    }

    /** 第几轮讨论的引导语；同一种方式单独计数。 */
    fun roundHint(style: DebateStyle, index: Int): String = when (style) {
        DebateStyle.DEBATE -> "这是观点讨论第 $index 轮。\n" + (DEBATE_HINTS.getOrNull(index - 1) ?: "请只针对仍有分歧的核心问题发表精炼观点。")
        DebateStyle.COLLAB -> "这是第 $index 轮协作。\n" + (COLLAB_HINTS.getOrNull(index - 1) ?: "请继续整合，聚焦尚未完善的部分。")
    }

    /** 单遍替换：问题或回答里恰好出现「{各家回答}」这类文字也不会被二次替换。 */
    fun fill(template: String, values: Map<String, String>): String =
        SLOT.replace(template) { match -> values[match.groupValues[1]] ?: match.value }

    /** 用户的补充永远附在预设之后。 */
    fun withSupplement(prompt: String, supplement: String): String =
        supplement.trim().take(ArenaLimits.MAX_GUIDANCE_CHARS).let { if (it.isBlank()) prompt else "$prompt\n\n用户补充要求：\n$it" }

    /**
     * 界面展示用：真实材料换成「队友1的回答」这类胶囊，引导语和总结要求直接展开，
     * 让用户看清每家会收到什么，而不暴露一大段别家回答。
     */
    fun view(key: PresetKey, source: PresetSource, peers: Int, answers: Int, style: DebateStyle, debateIndex: Int,
             depth: SummaryDepth, members: Int): List<PresetPart> {
        val slots = mapOf(
            "{队友回答}" to List(peers.coerceAtLeast(1)) { "队友${it + 1}的回答" },
            "{各家回答}" to List(answers.coerceAtLeast(2)) { "成员${it + 1}的回答" },
            "{前面回答}" to List((members - 1).coerceAtLeast(1)) { "第${it + 1}位的回答" },
            "{问题}" to listOf("你的问题"),
            "{原问题}" to listOf("原问题"),
            "{讨论经过}" to listOf("讨论经过"),
        )
        val text = fill(source.template(key), mapOf("轮次引导" to roundHint(style, debateIndex), "总结要求" to summaryStructure(depth, answers)))
        val parts = mutableListOf<PresetPart>()
        var rest = text
        while (rest.isNotEmpty()) {
            val hit = slots.keys.map { it to rest.indexOf(it) }.filter { it.second >= 0 }.minByOrNull { it.second }
            if (hit == null) { parts += PresetPart(rest, false); break }
            if (hit.second > 0) parts += PresetPart(rest.substring(0, hit.second), false)
            slots.getValue(hit.first).forEach { parts += PresetPart(it, true) }
            rest = rest.substring(hit.second + hit.first.length)
        }
        return parts
    }

    fun summaryStructure(depth: SummaryDepth, answers: Int): String {
        val body = when (depth) {
            SummaryDepth.BRIEF -> "1. 一句话结论。\n2. $answers 家各一句话点评：谁说得最靠谱、谁有明显漏洞。\n3. 最该做的一件事。"
            SummaryDepth.STANDARD -> "1. 结论：用 2-3 句话给出最终答案。\n2. 共识：几家都同意的要点。\n" +
                "3. 分歧：写明谁说了什么、你更倾向哪个、为什么。\n4. 建议：给用户的 2-4 条可执行建议，以及哪些地方需要再核实。"
            SummaryDepth.DEEP -> "1. 事实核对：把几份回答里出现的关键事实（数字、时间、政策、名称、药物剂量）列出来，" +
                "逐条标注「几家一致 / 有分歧 / 只有一家提到」，分歧处写明各自怎么说、你更相信哪个、为什么。\n" +
                "2. 结论：用 2-3 句话给出你的最终答案。\n3. 依据与风险：结论依据什么；哪些地方可能因人而异、可能过时、或需要向医生 / 官方核实。\n" +
                "4. 怎么做：分步骤的行动清单，每步一行。"
        }
        return "请做一份${depth.displayName}总结，按这个顺序写，总长不超过 ${depth.maxChars} 字：\n$body"
    }
}

internal object PromptSections {
    fun quote(responses: Map<ArenaService, String>, quoteLimit: Int, label: (ArenaService) -> String): String =
        responses.entries.joinToString("\n\n") { (service, response) ->
            "${label(service)}\n${response.take(quoteLimit.coerceAtLeast(0))}"
        }

    fun otherResponses(target: ArenaService, responses: Map<ArenaService, String>, quoteLimit: Int = ArenaLimits.MAX_QUOTED_RESPONSE_CHARS): String =
        quote(responses.filterKeys { it != target }, quoteLimit) { "【${it.displayName} 的回答】" }
}

object DebatePromptBuilder {
    /** 观点讨论：把其他 AI 的回答按所选方式的预设转给这一家；用户补充附在最后。 */
    fun build(
        originalQuestion: String,
        target: ArenaService,
        responses: Map<ArenaService, String>,
        debateIndex: Int = 1,
        guidance: String = "",
        quoteLimit: Int = ArenaLimits.MAX_QUOTED_RESPONSE_CHARS,
        style: DebateStyle = DebateStyle.DEBATE,
        presets: PresetSource = DefaultPresets,
    ): String = ArenaPresets.withSupplement(
        ArenaPresets.fill(presets.template(style.preset), mapOf(
            "轮次引导" to ArenaPresets.roundHint(style, debateIndex),
            "原问题" to originalQuestion,
            "问题" to originalQuestion,
            "队友回答" to PromptSections.otherResponses(target, responses, quoteLimit),
        )),
        guidance,
    )
}

object RelayPromptBuilder {
    /** 工作流第 2 位起：问题 + 前面各位已完成的完整回答（按顺序）。前面没有可用回答时只发问题本身。 */
    fun build(
        question: String,
        earlier: Map<ArenaService, String>,
        quoteLimit: Int = ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS,
        presets: PresetSource = DefaultPresets,
    ): String {
        if (earlier.isEmpty()) return question
        return ArenaPresets.fill(presets.template(PresetKey.RELAY), mapOf(
            "问题" to question,
            "原问题" to question,
            "前面回答" to PromptSections.quote(earlier, quoteLimit) { "【${it.displayName} 的回答】" },
        ))
    }
}

object DiscussionSummaryPromptBuilder {
    /** 「队长总结」：三档深度只换「总结要求」一段；喂进来的回答按完整长度给，只在超预算时压缩。 */
    fun build(
        originalQuestion: String,
        history: List<RoundRecord>,
        responses: Map<ArenaService, String>,
        customInstruction: String = "",
        quoteLimit: Int = ArenaLimits.MAX_QUOTED_RESPONSE_CHARS,
        depth: SummaryDepth = SummaryDepth.STANDARD,
        presets: PresetSource = DefaultPresets,
    ): String {
        val outline = history.joinToString("\n") { round ->
            buildString {
                append("- 第 ${round.number} 轮：${ArenaTimeline.kindLabel(round)}")
                if (round.guidance.isNotBlank()) {
                    val label = if (round.kind == RoundKind.ITERATION) "本轮问题" else "用户补充"
                    append("；$label：${round.guidance.take(240)}")
                }
            }
        }
        return ArenaPresets.withSupplement(
            ArenaPresets.fill(presets.template(PresetKey.SUMMARY), mapOf(
                "原问题" to originalQuestion,
                "问题" to originalQuestion,
                "讨论经过" to outline.ifBlank { "- 第 1 轮：提问" },
                "各家回答" to "共 ${responses.size} 份完整回答（来自 ${responses.keys.joinToString("、") { it.displayName }}）。\n\n" +
                    PromptSections.quote(responses, quoteLimit) { "【${it.displayName} 的完整回答】" },
                "总结要求" to ArenaPresets.summaryStructure(depth, responses.size),
            )),
            customInstruction,
        )
    }
}
