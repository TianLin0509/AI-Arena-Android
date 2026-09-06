package com.tianlin.aiarena

import android.content.Context
import androidx.core.content.edit

/**
 * 队长 = 「队长总结」时替用户把几家的回答整合成一条的那位。
 *
 * 0.8 ～ 0.10 的队长是"观点讨论轮里拿一套不同 prompt 的固定角色"，家人试用后反馈那条整合
 * "比较浅"——根因是它塞在讨论轮里、限 400 字，而且只引用别家回答的片段。
 * 0.11 起按用户拍板（2026-09-06）改成：
 *
 * - 初始回答人人平等，不再有队长徽章和置顶；谁都能当队长。
 * - 总结独立成一步：结果页「队长总结」里选队长（成员中任一家）、选深度（[SummaryDepth]），
 *   喂给队长的是几家的**完整回答**，篇幅上限按深度放开。
 *
 * 这里只剩两件事：默认选谁、总结时的执笔顺序。
 */
object CaptainPolicy {

    /**
     * 默认队长：用户上次选的那位；没选过、或那位已不在成员里，就取第一位成员。
     * 成员不足两家时返回 null——只有一家的时候"整合"没有意义。
     * **只看成员列表，不看谁已登录**：启动时"已登录"会从 0 家跳到 3 家，拿它当依据队长会跟着抖。
     */
    fun resolve(stored: ArenaService?, members: List<ArenaService>): ArenaService? {
        if (members.size < ArenaService.MIN_MEMBERS) return null
        return stored?.takeIf { it in members } ?: members.first()
    }

    fun isCaptain(service: ArenaService, captain: ArenaService?): Boolean =
        captain != null && service == captain

    /**
     * 总结的执笔优先级：选中的队长排最前，其余按成员顺序。
     * 队长这一轮没答上来时 [ArenaSessionController.startSummary] 会顺着这个顺序往后找，这里只负责排序。
     */
    fun judgePreference(services: List<ArenaService>, captain: ArenaService?): List<ArenaService> {
        if (captain == null || captain !in services) return services
        return listOf(captain) + services.filterNot { it == captain }
    }
}

/**
 * 队长与总结深度的本地记忆：家人选过一次，下次默认还是它。与登录态无关，卸载前一直保留。
 */
class ArenaCaptainPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "arena_captain",
        Context.MODE_PRIVATE,
    )

    /** 用户主动选过的队长；没选过返回 null，由 [CaptainPolicy.resolve] 取第一位。 */
    fun loadCaptain(): ArenaService? = ArenaService.fromName(preferences.getString(KEY_CAPTAIN, null))

    fun saveCaptain(service: ArenaService?) {
        preferences.edit {
            if (service == null) remove(KEY_CAPTAIN) else putString(KEY_CAPTAIN, service.name)
        }
    }

    /** 上次选的总结深度；没选过默认「标准」（用户拍板的默认档）。 */
    fun loadDepth(): SummaryDepth = SummaryDepth.fromName(preferences.getString(KEY_DEPTH, null))

    fun saveDepth(depth: SummaryDepth) {
        preferences.edit { putString(KEY_DEPTH, depth.name) }
    }

    private companion object {
        const val KEY_CAPTAIN = "captain"
        const val KEY_DEPTH = "summary_depth"
    }
}
