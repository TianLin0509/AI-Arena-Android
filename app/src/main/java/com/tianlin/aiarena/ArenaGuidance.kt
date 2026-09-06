package com.tianlin.aiarena

import android.content.Context
import androidx.core.content.edit

/**
 * 出错时给长辈看的"怎么办"。
 *
 * 控制器和网页池写在 `detail` 里的是给开发者看的诊断文案（"网页输入框加载超时"、
 * "连续读取失败：…"）。家人看到这些只会困惑。这里把已知的失败原因翻译成
 * 一句白话 + 一个明确的下一步，按钮文案与结果卡片上的按钮一一对应。
 * 纯 Kotlin，可单测；认不出的原因给通用建议，永远不返回空字符串。
 */
object ArenaErrorHelp {

    data class Advice(
        /** 一句话说明发生了什么，不带术语。 */
        val what: String,
        /** 建议的下一步，引用界面上真实存在的按钮名。 */
        val next: String,
        /** 最合适的主动作：决定哪个按钮放在最前、用主色。 */
        val primary: Action,
    )

    enum class Action { RESEND, REEXTRACT, OPEN_PAGE, LOGIN, SKIP, RETRY_SUMMARY, NONE }

    fun explain(detail: String, serviceName: String): Advice {
        val d = detail
        return when {
            d.contains("已跳过") -> Advice(
                what = "这一家本轮跳过了。",
                next = "其他 AI 的回答不受影响；想让它回答，点「重发」。",
                primary = Action.NONE,
            )
            d.contains("尚未登录") || d.contains("需要在网页中登录") || d.contains("待登录") -> Advice(
                what = "$serviceName 还没有登录，所以问题发不出去。",
                next = "点「打开网页」登录（和平时用它一样），回来再点「重发」。",
                primary = Action.LOGIN,
            )
            d.contains("安全验证") -> Advice(
                what = "$serviceName 要求做一次安全验证（滑块或验证码）。",
                next = "点「打开网页」完成验证，再回来点「重新提取」。",
                primary = Action.OPEN_PAGE,
            )
            d.contains("网页进程已退出") || d.contains("进程") -> Advice(
                what = "$serviceName 的网页出了点问题，已经自动关掉。",
                next = "点「重发」会重新打开它再问一次；登录不会丢。",
                primary = Action.RESEND,
            )
            d.contains("应用重启") -> Advice(
                what = "上次 App 被关闭了，这一家的回答没有等到。",
                next = "点「重发」再问一次就好。",
                primary = Action.RESEND,
            )
            d.contains("还没来得及发送") -> Advice(
                what = "你停止了这一轮，$serviceName 还没来得及收到问题。",
                next = "点「重发」就会发给它；不需要的话点「跳过」。",
                primary = Action.RESEND,
            )
            d.contains("已停止") -> Advice(
                what = "你停止了等待，这一家的回答没有收完。",
                next = "网页那边可能还在生成：先点「重新提取」；没有内容再点「重发」。",
                primary = Action.REEXTRACT,
            )
            d.contains("输入框") || d.contains("发送失败") || d.contains("重发失败") ||
                d.contains("注入失败") || d.contains("发送无响应") -> Advice(
                what = "$serviceName 的网页没有正常响应，问题没有发进去。",
                next = "先点「重发」；还不行就点「打开网页」看看是否要重新登录或刷新。",
                primary = Action.RESEND,
            )
            d.contains("超时") || d.contains("迟迟没有回应") -> Advice(
                what = "等了很久，$serviceName 一直没有回答。",
                next = "可能是网页卡住了：点「打开网页」看看有没有要登录或验证；也可以直接「重发」。",
                primary = Action.OPEN_PAGE,
            )
            d.contains("读取") || d.contains("提取") -> Advice(
                what = "$serviceName 可能已经回答了，只是没有抓到内容。",
                next = "点「重新提取」再试一次；还是空的就点「重发」。",
                primary = Action.REEXTRACT,
            )
            d.contains("网络") || d.contains("加载失败") || d.contains("ERR_") ||
                d.contains("net::") || d.contains("无法访问") -> Advice(
                what = "网络好像没有连上，网页打不开。",
                next = "检查一下 Wi-Fi 或流量，然后点「重发」。",
                primary = Action.RESEND,
            )
            d.contains("上下文") || d.contains("过长") || d.contains("缩短") -> Advice(
                what = "这次要发的内容太长，$serviceName 装不下。",
                next = "回到提问页把问题缩短一些，或者「开始新问题」。",
                primary = Action.NONE,
            )
            else -> Advice(
                what = "$serviceName 这次没有回答成功。",
                next = "先点「重发」再试一次；还不行就点「跳过」，其他 AI 的回答不受影响。",
                primary = Action.RESEND,
            )
        }
    }

    /** 讨论总结失败时的说明。 */
    fun explainSummary(detail: String, judgeName: String?): Advice {
        val who = judgeName ?: "负责总结的 AI"
        return when {
            detail.contains("安全验证") -> Advice(
                what = "$who 要求做一次安全验证。",
                next = "打开它的网页完成验证，再点「重新总结」。",
                primary = Action.OPEN_PAGE,
            )
            detail.contains("超时") -> Advice(
                what = "等了很久，$who 没有把总结写完。",
                next = "点「重新总结」再试一次；也可以换一家 AI 当总结员（在成员里调整顺序）。",
                primary = Action.RETRY_SUMMARY,
            )
            detail.contains("上下文") || detail.contains("缩短") -> Advice(
                what = "几家的回答加起来太长，$who 装不下。",
                next = "点「开始新问题」把问题问得更聚焦一些。",
                primary = Action.NONE,
            )
            else -> Advice(
                what = "这次总结没有成功。",
                next = "点「重新总结」再试一次。几家的回答都还在，不会丢。",
                primary = Action.RETRY_SUMMARY,
            )
        }
    }
}

/**
 * 把控制器里给开发者看的轮次状态翻译成一句给家人看的话。
 * 只描述"现在在等谁 / 谁答完了"，不出现"锚定""提取"之类的词。
 */
object RoundNarration {
    fun describe(
        busy: Boolean,
        kind: RoundKind?,
        roundNumber: Int,
        total: Int,
        completed: Int,
        failed: Int,
        waitingNames: List<String>,
    ): String {
        if (total == 0) return "准备中…"
        val kindLabel = kind?.displayName ?: "回答"
        if (busy) {
            return when {
                completed == 0 && waitingNames.size == total -> "问题已交给 $total 位 AI，正在等回答…"
                waitingNames.size == 1 -> "$completed 位已回答，还在等${waitingNames.first()}…"
                waitingNames.isNotEmpty() -> "$completed 位已回答，还在等${waitingNames.joinToString("、")}…"
                else -> "正在整理回答…"
            }
        }
        val prefix = if (roundNumber > 1) "第 $roundNumber 轮$kindLabel" else kindLabel
        return when {
            failed == 0 && completed == total -> "${prefix}完成，$total 位 AI 都回答了"
            completed == 0 -> "${prefix}没有收到回答，看看下面的提示"
            else -> "${prefix}完成：$completed 位回答了，$failed 位没成功"
        }
    }
}

/**
 * 引导相关的本地偏好：首次使用说明是否看过、崩溃提示是否已确认、默认回答方式。
 * 与登录态无关；卸载前一直保留。
 */
class ArenaGuidePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "arena_guide",
        Context.MODE_PRIVATE,
    )

    fun hasSeenOnboarding(): Boolean = preferences.getBoolean(KEY_ONBOARDING_SEEN, false)

    fun markOnboardingSeen() {
        preferences.edit { putBoolean(KEY_ONBOARDING_SEEN, true) }
    }

    fun isCrashAcknowledged(fileName: String): Boolean =
        preferences.getString(KEY_CRASH_ACK, "") == fileName

    fun acknowledgeCrash(fileName: String) {
        preferences.edit { putString(KEY_CRASH_ACK, fileName) }
    }

    fun loadAnswerMode(): AnswerMode = AnswerMode.fromName(preferences.getString(KEY_ANSWER_MODE, null))

    fun saveAnswerMode(mode: AnswerMode) {
        preferences.edit { putString(KEY_ANSWER_MODE, mode.name) }
    }

    /** 「继续追问」里"可以先写要求"的提示：用户点过「知道了」或自己写过要求之后就不再出现。 */
    fun hasSeenRoundGuidanceHint(): Boolean = preferences.getBoolean(KEY_ROUND_GUIDANCE_HINT_SEEN, false)

    fun markRoundGuidanceHintSeen() {
        preferences.edit { putBoolean(KEY_ROUND_GUIDANCE_HINT_SEEN, true) }
    }

    private companion object {
        const val KEY_ONBOARDING_SEEN = "onboarding_seen_v7"
        const val KEY_CRASH_ACK = "crash_acknowledged"
        const val KEY_ANSWER_MODE = "answer_mode"
        const val KEY_ROUND_GUIDANCE_HINT_SEEN = "round_guidance_hint_seen"
    }
}
