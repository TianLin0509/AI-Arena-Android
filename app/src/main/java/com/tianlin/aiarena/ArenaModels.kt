package com.tianlin.aiarena

enum class ArenaService(
    val displayName: String,
    val shortName: String,
    val url: String,
    val loginHint: String,
    val iconRes: Int? = null,
    val brandGlyph: String? = null,
    val brandColor: Long = 0xFF1D6078,
    val experimental: Boolean = false,
) {
    DEEPSEEK(
        displayName = "DeepSeek",
        shortName = "DeepSeek",
        url = "https://chat.deepseek.com/",
        loginHint = "手机号或邮箱登录",
        iconRes = R.drawable.ic_deepseek,
    ),
    DOUBAO(
        displayName = "豆包",
        shortName = "豆包",
        url = "https://www.doubao.com/chat/",
        loginHint = "优先尝试抖音快捷登录",
        iconRes = R.drawable.ic_doubao,
    ),
    KIMI(
        displayName = "Kimi",
        shortName = "Kimi",
        url = "https://www.kimi.com/",
        loginHint = "微信或手机号登录",
        iconRes = R.drawable.ic_kimi,
    ),
    QWEN(
        displayName = "千问",
        shortName = "千问",
        url = "https://www.qianwen.com/",
        loginHint = "支付宝或手机号登录",
        brandGlyph = "千",
        brandColor = 0xFF6B55D9,
        experimental = true,
    ),
    YUANBAO(
        displayName = "元宝",
        shortName = "元宝",
        url = "https://yuanbao.tencent.com/chat/",
        loginHint = "微信或 QQ 快捷登录",
        brandGlyph = "元",
        brandColor = 0xFF2F74D0,
        experimental = true,
    ),
    ZHIPU(
        displayName = "智谱",
        shortName = "智谱",
        url = "https://chatglm.cn/",
        loginHint = "微信或手机号登录",
        brandGlyph = "智",
        brandColor = 0xFF295FB8,
        experimental = true,
    );

    companion object {
        val defaultMembers = listOf(DEEPSEEK, DOUBAO, KIMI)
        const val MIN_MEMBERS = 2
        const val MAX_MEMBERS = 4
    }
}

enum class ConnectionState {
    NOT_LOADED,
    LOADING,
    NEEDS_LOGIN,
    SIGNED_IN,
    ERROR,
}

fun ConnectionState.isUsable(): Boolean =
    this == ConnectionState.SIGNED_IN

data class ServiceStatus(
    val state: ConnectionState = ConnectionState.NOT_LOADED,
    val detail: String = "尚未打开",
    val url: String = "",
)

data class LoginTrustDecision(
    val state: ConnectionState,
    val confirmedSignedIn: Boolean,
)

object LoginTrustPolicy {
    fun duringNavigation(previouslyConfirmed: Boolean): LoginTrustDecision =
        if (previouslyConfirmed) {
            LoginTrustDecision(ConnectionState.SIGNED_IN, confirmedSignedIn = true)
        } else {
            LoginTrustDecision(ConnectionState.LOADING, confirmedSignedIn = false)
        }

    fun afterProbe(
        probeSignedIn: Boolean,
        pageVisibleToUser: Boolean,
        previouslyConfirmed: Boolean,
        explicitLoginVisible: Boolean = false,
        consecutiveExplicitLoginProbes: Int = 0,
    ): LoginTrustDecision = when {
        probeSignedIn -> LoginTrustDecision(ConnectionState.SIGNED_IN, confirmedSignedIn = true)
        explicitLoginVisible && (pageVisibleToUser || consecutiveExplicitLoginProbes >= 2) ->
            LoginTrustDecision(ConnectionState.NEEDS_LOGIN, confirmedSignedIn = false)
        pageVisibleToUser -> LoginTrustDecision(ConnectionState.NEEDS_LOGIN, confirmedSignedIn = false)
        previouslyConfirmed -> LoginTrustDecision(ConnectionState.SIGNED_IN, confirmedSignedIn = true)
        else -> LoginTrustDecision(ConnectionState.LOADING, confirmedSignedIn = false)
    }
}

enum class ParticipantPhase {
    IDLE,
    SENDING,
    WAITING,
    STREAMING,
    COMPLETE,
    ERROR,
}

data class ParticipantRun(
    val phase: ParticipantPhase = ParticipantPhase.IDLE,
    val requestId: String = "",
    val response: String = "",
    val detail: String = "等待开始",
    val responseTruncated: Boolean = false,
    val originalResponseLength: Int = response.length,
)

enum class AnswerMode(val displayName: String, val description: String) {
    PARALLEL(
        displayName = "并行回答",
        description = "快速依次送达，三家生成过程相互重叠",
    ),
    SERIAL(
        displayName = "串行回答",
        description = "上一家回答结束后，再发送给下一家",
    ),
}

enum class RoundKind(val displayName: String) {
    INITIAL("初始回答"),
    ITERATION("独立迭代"),
    DEBATE("观点讨论"),
}

enum class SessionStage {
    IDLE,
    INITIAL,
    ITERATION,
    DEBATE,
    READY,
}

data class RoundRecord(
    val number: Int,
    val kind: RoundKind,
    val answerMode: AnswerMode,
    val guidance: String,
    val results: Map<ArenaService, ParticipantRun>,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
)

data class DiscussionSummary(
    val phase: ParticipantPhase = ParticipantPhase.IDLE,
    val judge: ArenaService? = null,
    val requestId: String = "",
    val text: String = "",
    val detail: String = "尚未总结",
)

data class SendOutcome(
    val success: Boolean,
    val requestId: String,
    val detail: String,
)

data class ResponseSnapshot(
    val found: Boolean,
    val text: String,
    val streaming: Boolean,
    val detail: String = "",
    val truncated: Boolean = false,
    val originalLength: Int = text.length,
    val securityChallenge: Boolean = false,
)

interface ArenaGateway {
    fun sendPrompt(
        service: ArenaService,
        prompt: String,
        requestId: String,
        callback: (SendOutcome) -> Unit,
    )

    fun readResponse(
        service: ArenaService,
        requestId: String,
        callback: (ResponseSnapshot) -> Unit,
    )
}

data class ControllerTiming(
    val pollIntervalMillis: Long = 1_500L,
    val readCallbackTimeoutMillis: Long = 10_000L,
    val responseTimeoutMillis: Long = 300_000L,
    val maxConsecutiveReadErrors: Int = 5,
    val requiredStablePolls: Int = 2,
)

object ArenaLimits {
    const val MAX_QUESTION_CHARS = 24_000
    const val MAX_GUIDANCE_CHARS = 2_000
    const val MAX_QUOTED_RESPONSE_CHARS = 2_000
    const val MAX_CAPTURED_RESPONSE_CHARS = 12_000
    const val MAX_STORED_PROMPT_CHARS = 32_000
    const val MAX_HISTORY_ROUNDS = 8
}

sealed interface ArenaDestination {
    data object Roundtable : ArenaDestination
    data class Provider(val service: ArenaService) : ArenaDestination
}
