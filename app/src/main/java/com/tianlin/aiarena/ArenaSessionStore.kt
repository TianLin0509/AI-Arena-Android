package com.tianlin.aiarena

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ArenaSessionSnapshot(
    val id: String,
    val originalQuestion: String,
    /** 这个问题是什么时候提的（毫秒）。老文件没有这个字段时从会话 id 里的时间戳推回来。 */
    val askedAtMillis: Long = 0L,
    val roundNumber: Int,
    val currentRoundKind: RoundKind?,
    val currentAnswerMode: AnswerMode,
    val services: List<ArenaService>,
    val runs: Map<ArenaService, ParticipantRun>,
    val history: List<RoundRecord>,
    val summary: DiscussionSummary,
    val lastRoundPrompts: Map<ArenaService, String> = emptyMap(),
    /**
     * 各家网页里这条讨论对应的对话地址。打开历史会话时把每家网页切回去，
     * 后续「观点讨论」才是接着当时那条对话说，而不是发进不知哪一条里。
     */
    val conversationUrls: Map<ArenaService, String> = emptyMap(),
    /** 当前这一轮的队长（轮次进行中还没进 history 时也要知道）。 */
    val currentRoundCaptain: ArenaService? = null,
    val updatedAtMillis: Long,
)

data class RecentArenaSession(
    val id: String,
    val title: String,
    val updatedAtMillis: Long,
    val askedAtMillis: Long = 0L,
    val roundCount: Int,
    val serviceCount: Int,
)

interface ArenaSessionRepository {
    fun newSessionId(): String
    fun save(snapshot: ArenaSessionSnapshot)
    fun load(id: String): ArenaSessionSnapshot?
    fun loadActive(): ArenaSessionSnapshot?
    fun setActiveSession(id: String?)
    fun listRecent(limit: Int = 8): List<RecentArenaSession>

    /** 从索引里剔除一条并删掉它的文件。用于清理点开必然失败的死条目。 */
    fun forget(id: String)
}

class ArenaSessionStore internal constructor(
    context: Context,
    directoryName: String = DIRECTORY_NAME,
    preferencesName: String = PREFERENCES_NAME,
) : ArenaSessionRepository {
    private val root = File(context.applicationContext.filesDir, directoryName).apply { mkdirs() }
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    @Synchronized
    override fun newSessionId(): String =
        "session_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"

    @Synchronized
    override fun save(snapshot: ArenaSessionSnapshot) {
        if (!isValidId(snapshot.id) || snapshot.originalQuestion.isBlank()) return
        root.mkdirs()
        writeAtomically(sessionFile(snapshot.id), ArenaSessionJson.encode(snapshot).toString())
        val next = buildList {
            add(snapshot.toRecent())
            addAll(readIndex().filterNot { it.id == snapshot.id })
        }.sortedByDescending { it.updatedAtMillis }
        writeIndex(next.take(MAX_SESSIONS))
        next.drop(MAX_SESSIONS).forEach { stale -> sessionFile(stale.id).delete() }
    }

    @Synchronized
    override fun load(id: String): ArenaSessionSnapshot? {
        if (!isValidId(id)) return null
        val file = sessionFile(id)
        if (!file.isFile) return null
        return runCatching { ArenaSessionJson.decode(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull()
    }

    @Synchronized
    override fun loadActive(): ArenaSessionSnapshot? =
        preferences.getString(KEY_ACTIVE_SESSION, null)?.let(::load)

    override fun setActiveSession(id: String?) {
        preferences.edit {
            if (id == null) remove(KEY_ACTIVE_SESSION) else putString(KEY_ACTIVE_SESSION, id)
        }
    }

    @Synchronized
    override fun listRecent(limit: Int): List<RecentArenaSession> =
        readIndex().sortedByDescending { it.updatedAtMillis }.take(limit.coerceAtLeast(0))

    @Synchronized
    override fun forget(id: String) {
        if (!isValidId(id)) return
        writeIndex(readIndex().filterNot { it.id == id })
        sessionFile(id).delete()
        if (preferences.getString(KEY_ACTIVE_SESSION, null) == id) setActiveSession(null)
    }

    private fun sessionFile(id: String): File = File(root, "$id.json")

    private fun readIndex(): List<RecentArenaSession> {
        val file = File(root, INDEX_FILE_NAME)
        if (!file.isFile) return scanSessionFiles()
        return runCatching {
            val items = JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("sessions") ?: JSONArray()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (!isValidId(id)) continue
                    add(
                        RecentArenaSession(
                            id = id,
                            title = item.optString("title").take(MAX_TITLE_CHARACTERS),
                            updatedAtMillis = item.optLong("updatedAtMillis"),
                            askedAtMillis = item.optLong("askedAtMillis").takeIf { it > 0L } ?: sessionIdTimestamp(id),
                            roundCount = item.optInt("roundCount"),
                            serviceCount = item.optInt("serviceCount"),
                        ),
                    )
                }
            }
        }.getOrElse { scanSessionFiles() }
            .filter { item -> sessionFile(item.id).isFile }
    }

    /**
      * 索引损坏时的自愈路径。先按文件修改时间排序并截断到 [MAX_SESSIONS]，再解析——
      * 否则会把目录里所有会话（每份可达数百 KB）全量解码一遍，只为拿一个标题。
      */
    private fun scanSessionFiles(): List<RecentArenaSession> = root.listFiles()
        .orEmpty()
        .asSequence()
        .filter { file -> file.isFile && file.name.startsWith("session_") && file.extension == "json" }
        .sortedByDescending { file -> file.lastModified() }
        .take(MAX_SESSIONS)
        .mapNotNull { file ->
            runCatching {
                ArenaSessionJson.decode(JSONObject(file.readText(Charsets.UTF_8))).toRecent()
            }.getOrNull()
        }
        .sortedByDescending { it.updatedAtMillis }
        .toList()

    private fun writeIndex(items: List<RecentArenaSession>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("updatedAtMillis", item.updatedAtMillis)
                    .put("askedAtMillis", item.askedAtMillis)
                    .put("roundCount", item.roundCount)
                    .put("serviceCount", item.serviceCount),
            )
        }
        writeAtomically(File(root, INDEX_FILE_NAME), JSONObject().put("sessions", array).toString())
    }

    private fun writeAtomically(target: File, text: String) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        // 不要先 delete 再 rename：POSIX 的 rename 覆盖同名文件本身就是原子的，
        // 而"先删后改名"会开出一个窗口——进程若在这中间被系统杀掉，整份会话直接消失。
        if (!temporary.renameTo(target)) {
            try {
                FileOutputStream(target).use { stream ->
                    stream.write(text.toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
            } finally {
                temporary.delete()
            }
        }
    }

    private fun ArenaSessionSnapshot.toRecent(): RecentArenaSession = RecentArenaSession(
        id = id,
        title = originalQuestion.lineSequence().firstOrNull().orEmpty().trim().take(MAX_TITLE_CHARACTERS),
        updatedAtMillis = updatedAtMillis,
        askedAtMillis = askedAtMillis.takeIf { it > 0L } ?: sessionIdTimestamp(id),
        roundCount = roundNumber,
        serviceCount = services.size,
    )

    private fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

    private companion object {
        const val DIRECTORY_NAME = "arena_sessions"
        const val INDEX_FILE_NAME = "index.json"
        const val PREFERENCES_NAME = "arena_session_pointer"
        const val KEY_ACTIVE_SESSION = "active_session"
        const val MAX_SESSIONS = 20
        const val MAX_TITLE_CHARACTERS = 48
        val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,80}")
    }
}

/** 会话 id 形如 `session_<毫秒>_<随机>`：没有记录提问时间的老会话就拿这个时间戳当提问时间。 */
internal fun sessionIdTimestamp(id: String): Long =
    Regex("^session_(\\d{10,})_").find(id)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

internal object ArenaSessionJson {
    fun encode(snapshot: ArenaSessionSnapshot): JSONObject = JSONObject()
        .put("version", 1)
        .put("id", snapshot.id)
        .put("originalQuestion", snapshot.originalQuestion)
        .put("askedAtMillis", snapshot.askedAtMillis)
        .put("roundNumber", snapshot.roundNumber)
        .put("currentRoundKind", snapshot.currentRoundKind?.name ?: JSONObject.NULL)
        .put("currentAnswerMode", snapshot.currentAnswerMode.name)
        .put("services", JSONArray(snapshot.services.map { it.name }))
        .put("runs", encodeRuns(snapshot.runs))
        .put("history", JSONArray().also { array -> snapshot.history.forEach { array.put(encodeRound(it)) } })
        .put("summary", encodeSummary(snapshot.summary))
        .put("lastRoundPrompts", JSONObject().also { prompts ->
            snapshot.lastRoundPrompts.forEach { (service, prompt) -> prompts.put(service.name, prompt) }
        })
        // 可选字段：老版本读到会忽略，新版本读不到就当空
        .put("conversationUrls", JSONObject().also { urls ->
            snapshot.conversationUrls.forEach { (service, url) -> urls.put(service.name, url) }
        })
        .put("currentRoundCaptain", snapshot.currentRoundCaptain?.name ?: JSONObject.NULL)
        .put("updatedAtMillis", snapshot.updatedAtMillis)

    /** 只认识到 [SCHEMA_VERSION] 为止的文件；更高版本宁可当作不可读，也不要静默丢字段。 */
    const val SCHEMA_VERSION = 1

    fun decode(json: JSONObject): ArenaSessionSnapshot {
        val version = json.optInt("version", 1)
        require(version <= SCHEMA_VERSION) { "不支持的会话文件版本：$version" }
        val services = json.optJSONArray("services").enumList<ArenaService>()
        val runsObject = json.optJSONObject("runs") ?: JSONObject()
        val runs = ArenaService.entries.associateWith { service ->
            runsObject.optJSONObject(service.name)?.let(::decodeRun) ?: ParticipantRun()
        }
        val historyArray = json.optJSONArray("history") ?: JSONArray()
        val history = buildList {
            for (index in 0 until historyArray.length()) {
                historyArray.optJSONObject(index)?.let { add(decodeRound(it)) }
            }
        }.takeLast(ArenaLimits.MAX_HISTORY_ROUNDS)
        return ArenaSessionSnapshot(
            id = json.getString("id"),
            originalQuestion = json.optString("originalQuestion").take(ArenaLimits.MAX_QUESTION_CHARS),
            askedAtMillis = json.optLong("askedAtMillis").takeIf { it > 0L } ?: sessionIdTimestamp(json.getString("id")),
            roundNumber = json.optInt("roundNumber").coerceAtLeast(0),
            currentRoundKind = json.optString("currentRoundKind").enumOrNull<RoundKind>(),
            currentAnswerMode = json.optString("currentAnswerMode").enumOrNull<AnswerMode>() ?: AnswerMode.PARALLEL,
            services = services.ifEmpty { ArenaService.defaultMembers },
            runs = runs,
            history = history,
            summary = json.optJSONObject("summary")?.let(::decodeSummary) ?: DiscussionSummary(),
            lastRoundPrompts = json.optJSONObject("lastRoundPrompts")?.let { prompts ->
                ArenaService.entries.mapNotNull { service ->
                    prompts.optString(service.name).takeIf { it.isNotBlank() }?.let { prompt ->
                        service to prompt.take(ArenaLimits.MAX_STORED_PROMPT_CHARS)
                    }
                }.toMap()
            }.orEmpty(),
            conversationUrls = json.optJSONObject("conversationUrls")?.let { urls ->
                ArenaService.entries.mapNotNull { service ->
                    urls.optString(service.name).takeIf { it.startsWith("https://") }?.let { url ->
                        service to url.take(MAX_CONVERSATION_URL_CHARS)
                    }
                }.toMap()
            }.orEmpty(),
            currentRoundCaptain = json.optString("currentRoundCaptain").enumOrNull<ArenaService>(),
            updatedAtMillis = json.optLong("updatedAtMillis"),
        )
    }

    private const val MAX_CONVERSATION_URL_CHARS = 2_000

    private fun encodeRuns(runs: Map<ArenaService, ParticipantRun>): JSONObject = JSONObject().also { json ->
        ArenaService.entries.forEach { service -> json.put(service.name, encodeRun(runs[service] ?: ParticipantRun())) }
    }

    private fun encodeRun(run: ParticipantRun): JSONObject = JSONObject()
        .put("phase", run.phase.name)
        .put("requestId", run.requestId)
        .put("response", run.response)
        .put("detail", run.detail)
        .put("responseTruncated", run.responseTruncated)
        .put("originalResponseLength", run.originalResponseLength)
        .put("modeLabel", run.modeLabel)
        .put("thinkingUsed", run.thinkingUsed)

    private fun decodeRun(json: JSONObject): ParticipantRun = ParticipantRun(
        phase = json.optString("phase").enumOrNull<ParticipantPhase>() ?: ParticipantPhase.IDLE,
        requestId = json.optString("requestId"),
        response = json.optString("response").take(ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS),
        detail = json.optString("detail").take(200),
        responseTruncated = json.optBoolean("responseTruncated"),
        originalResponseLength = json.optInt("originalResponseLength"),
        modeLabel = json.optString("modeLabel").take(80),
        thinkingUsed = json.optBoolean("thinkingUsed"),
    )

    private fun encodeRound(round: RoundRecord): JSONObject = JSONObject()
        .put("number", round.number)
        .put("kind", round.kind.name)
        .put("answerMode", round.answerMode.name)
        .put("guidance", round.guidance)
        .put("results", encodeRuns(round.results))
        .put("startedAtMillis", round.startedAtMillis)
        .put("finishedAtMillis", round.finishedAtMillis)
        .put("captain", round.captain?.name ?: JSONObject.NULL)

    private fun decodeRound(json: JSONObject): RoundRecord = RoundRecord(
        number = json.optInt("number"),
        kind = json.optString("kind").enumOrNull<RoundKind>() ?: RoundKind.INITIAL,
        answerMode = json.optString("answerMode").enumOrNull<AnswerMode>() ?: AnswerMode.PARALLEL,
        guidance = json.optString("guidance").take(ArenaLimits.MAX_GUIDANCE_CHARS),
        results = ArenaService.entries.associateWith { service ->
            json.optJSONObject("results")?.optJSONObject(service.name)?.let(::decodeRun) ?: ParticipantRun()
        },
        startedAtMillis = json.optLong("startedAtMillis"),
        finishedAtMillis = json.optLong("finishedAtMillis"),
        captain = json.optString("captain").enumOrNull<ArenaService>(),
    )

    private fun encodeSummary(summary: DiscussionSummary): JSONObject = JSONObject()
        .put("phase", summary.phase.name)
        .put("judge", summary.judge?.name ?: JSONObject.NULL)
        .put("requestId", summary.requestId)
        .put("text", summary.text)
        .put("detail", summary.detail)
        .put("depth", summary.depth.name)

    private fun decodeSummary(json: JSONObject): DiscussionSummary = DiscussionSummary(
        phase = json.optString("phase").enumOrNull<ParticipantPhase>() ?: ParticipantPhase.IDLE,
        judge = json.optString("judge").enumOrNull<ArenaService>(),
        requestId = json.optString("requestId"),
        text = json.optString("text").take(ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS),
        detail = json.optString("detail").take(200),
        depth = SummaryDepth.fromName(json.optString("depth")),
    )

    private inline fun <reified T : Enum<T>> String.enumOrNull(): T? =
        enumValues<T>().firstOrNull { it.name == this }

    private inline fun <reified T : Enum<T>> JSONArray?.enumList(): List<T> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optString(index).enumOrNull<T>()?.let(::add)
        }.distinct()
    }
}
