package com.tianlin.aiarena

import android.content.Context
import androidx.core.content.edit

/**
 * 队长模式。
 *
 * 用户反馈的原话：一个人看不过来那么多信息，需要有个队长负责总结，这样只看队长那一条就行。
 * 所以队长不是"多一个角色"，而是**把阅读量从 N 条压到 1 条**：
 *
 * - 观点讨论轮：队长拿到的 prompt 换成"先收拢队员共识与分歧、再给结论"，
 *   它的回答本身就是整合结果，而不是又一份平行观点。
 * - 讨论总结：默认由队长来写（谁整合谁负责到底）。
 * - 界面：队长卡片置顶并带徽章，其他家在讨论轮后默认收得更短。
 *
 * 参考 Chrome 扩展 AI 圆桌派的 `captain-mode.js`：那边队长固定是 `participants[0]`，
 * 这里让用户可选（家人可能只信任其中某一家），但默认同样是第一位成员。
 */
object CaptainPolicy {

    /**
     * 谁是队长（用于显示和派活）。
     *
     * **只看成员列表，不看谁已登录**。启动时几个 WebView 是逐个加载的，"已登录"会从
     * 0 家跳到 1 家再到 3 家；如果拿它当依据，队长会在这几秒里反复出现又消失，
     * 卡片顺序跟着抖（2026-09-05 实测：usable 只剩一家时队长直接变 null，徽章消失）。
     * 队长是"圆桌里的角色"，本来就该稳定。
     *
     * 存的那位被移出成员列表时退回第一位，不让队长模式静默失效。
     * 成员不足两家时返回 null —— 只有一家的时候"整合"没有意义。
     */
    fun resolve(stored: ArenaService?, members: List<ArenaService>): ArenaService? {
        if (members.size < ArenaService.MIN_MEMBERS) return null
        return stored?.takeIf { it in members } ?: members.first()
    }

    /**
     * 本轮真正动手整合的那位。
     *
     * 指定的队长这一轮没答上来（没登录、失败、被跳过）时交给第一位参与者，
     * 否则用户开了队长模式却没人整合，等于白开。
     */
    fun forRound(captain: ArenaService?, participants: List<ArenaService>): ArenaService? {
        if (captain == null || participants.isEmpty()) return null
        return captain.takeIf { it in participants } ?: participants.first()
    }

    fun isCaptain(service: ArenaService, captain: ArenaService?): Boolean =
        captain != null && service == captain

    /**
     * 把成员排成"队长在最前"。用户只想看一条时，那一条必须是第一屏就能看到的。
     * 队长模式关掉（captain 为 null）时保持原顺序不动。
     */
    fun order(members: List<ArenaService>, captain: ArenaService?): List<ArenaService> {
        if (captain == null || captain !in members) return members
        return listOf(captain) + members.filterNot { it == captain }
    }

    /**
     * 讨论总结的评委优先级：队长优先，其次按原顺序。
     * 队长没登录时 [ArenaSessionController.startSummary] 会自己往后找，这里只负责排序。
     */
    fun judgePreference(services: List<ArenaService>, captain: ArenaService?): List<ArenaService> =
        order(services, captain)

    /** 非队长成员在讨论轮后的折叠行数：比常规更短，但仍能看出讲了什么。 */
    const val TEAMMATE_COLLAPSED_LINES = 2
    const val DEFAULT_COLLAPSED_LINES = 6
}

/**
 * 队长选择与开关的本地持久化。与登录态无关，卸载前一直保留。
 */
class ArenaCaptainPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "arena_captain",
        Context.MODE_PRIVATE,
    )

    /** 默认开：这是用户明确要的默认行为，不是可选增强。 */
    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
    }

    /** 用户主动选过的队长；没选过返回 null，由 [CaptainPolicy.resolve] 取第一位。 */
    fun loadCaptain(): ArenaService? = ArenaService.fromName(preferences.getString(KEY_CAPTAIN, null))

    fun saveCaptain(service: ArenaService?) {
        preferences.edit {
            if (service == null) remove(KEY_CAPTAIN) else putString(KEY_CAPTAIN, service.name)
        }
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_CAPTAIN = "captain"
    }
}
