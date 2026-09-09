package com.tianlin.aiarena

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Product limits apply before copying and again to actual streamed bytes. */
object ArenaAttachmentPolicy {
    const val MAX_COUNT = 3
    const val MAX_FILE_BYTES = 10L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 20L * 1024 * 1024
    const val MAX_STORED_BYTES = 200L * 1024 * 1024
    val pickerMimeTypes = arrayOf("image/png", "image/jpeg", "application/pdf", "text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    fun validate(files: List<ArenaAttachment>): String? = when {
        files.size > MAX_COUNT -> "一次最多选择 3 个附件"
        files.any { it.sizeBytes <= 0 } -> "不能发送空文件"
        files.any { it.sizeBytes > MAX_FILE_BYTES } -> "单个附件不能超过 10 MB"
        files.sumOf { it.sizeBytes.coerceAtMost(MAX_FILE_BYTES + 1) } > MAX_TOTAL_BYTES -> "附件总大小不能超过 20 MB"
        files.any { it.mimeType !in pickerMimeTypes } -> "仅支持 PNG、JPEG、PDF、TXT 和 DOCX 文件"
        files.map { it.id }.distinct().size != files.size -> "不能重复添加同一个附件"
        files.map { it.name.lowercase() }.distinct().size != files.size -> "附件名称不能相同，请重命名后重新选择"
        else -> null
    }

    fun accepts(attachment: ArenaAttachment, acceptTypes: List<String>): Boolean {
        val accepts = acceptTypes.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        if (accepts.isEmpty()) return true
        return accepts.any { accepted ->
            accepted == "*/*" || accepted == attachment.mimeType.lowercase() ||
                (accepted.endsWith("/*") && attachment.mimeType.startsWith(accepted.substringBefore('/') + "/")) ||
                (accepted.startsWith('.') && attachment.name.lowercase().endsWith(accepted))
        }
    }
}

/** Files stay in the app sandbox for history and retries; only a lease exposes a selected file. */
class ArenaAttachmentStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "arena_attachments")
    internal fun storedIds(): Set<String> = directory.listFiles()?.filter { it.extension == "bin" }?.map { it.nameWithoutExtension }?.toSet().orEmpty()

    fun importDocuments(uris: List<Uri>, retainedIds: Set<String>? = null): List<ArenaAttachment> = synchronized(copyLock) {
        require(uris.size <= ArenaAttachmentPolicy.MAX_COUNT) { "一次最多选择 3 个附件" }
        if (uris.isEmpty()) return@synchronized emptyList()
        check(directory.isDirectory || directory.mkdirs()) { "无法创建附件存储目录" }
        if (retainedIds != null) collectUnreferenced(retainedIds)
        val created = mutableListOf<String>()
        var total = 0L
        val occupied = directory.listFiles()?.filter { it.extension == "bin" }?.sumOf { it.length() } ?: 0L
        try {
            uris.distinct().map { uri ->
                require(uri.scheme == "content") { "请选择系统文件列表中的附件" }
                require(uri.authority != "${appContext.packageName}.attachments") { "不能从应用内部附件地址导入文件" }
                val resolver = appContext.contentResolver
                var name = "附件"
                var declaredSize = -1L
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it).orEmpty() }
                        cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { declaredSize = cursor.getLong(it) }
                    }
                }
                name = name.substringAfterLast('/').substringAfterLast('\\').filter { !it.isISOControl() }.take(180).ifBlank { "附件" }
                val mime = mimeFor(name, resolver.getType(uri).orEmpty())
                require(mime in ArenaAttachmentPolicy.pickerMimeTypes) { "${name}：仅支持 PNG、JPEG、PDF、TXT 和 DOCX" }
                require(declaredSize <= ArenaAttachmentPolicy.MAX_FILE_BYTES) { "${name} 超过 10 MB" }
                val id = UUID.randomUUID().toString()
                created += id
                val target = dataFile(id)
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            count += read
                            total += read
                            require(count <= ArenaAttachmentPolicy.MAX_FILE_BYTES) { "${name} 超过 10 MB" }
                            require(total <= ArenaAttachmentPolicy.MAX_TOTAL_BYTES) { "附件总大小超过 20 MB" }
                            require(occupied + total <= ArenaAttachmentPolicy.MAX_STORED_BYTES) { "本机附件空间已满（200 MB），暂时无法添加新附件；已有附件仍可重试" }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                } ?: throw IOException("无法读取 ${name}，请重新选择")
                require(count > 0) { "${name} 是空文件" }
                val attachment = ArenaAttachment(id, name, mime, count, digest.digest().joinToString("") { "%02x".format(it) })
                metadataFile(id).writeText(JSONObject().put("id", id).put("name", name).put("mimeType", mime)
                    .put("sizeBytes", count).put("sha256", attachment.sha256).toString(), Charsets.UTF_8)
                attachment
            }.also { files -> ArenaAttachmentPolicy.validate(files)?.let { throw IllegalArgumentException(it) } }
        } catch (error: Exception) {
            created.forEach { id -> dataFile(id).delete(); metadataFile(id).delete() }
            throw error
        }
    }

    fun verify(attachment: ArenaAttachment): File {
        val file = dataFile(attachment.id)
        val metadata = metadataFile(attachment.id)
        require(file.isFile && metadata.isFile) { "附件 ${attachment.name} 已不可用，请重新选择" }
        val recorded = JSONObject(metadata.readText(Charsets.UTF_8))
        require(recorded.getString("name") == attachment.name && recorded.getString("mimeType") == attachment.mimeType &&
            recorded.getLong("sizeBytes") == attachment.sizeBytes && recorded.getString("sha256") == attachment.sha256 &&
            file.length() == attachment.sizeBytes) { "附件 ${attachment.name} 的信息已变化，请重新选择" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == attachment.sha256) { "附件 ${attachment.name} 校验失败，请重新选择" }
        return file
    }

    /** Only for a newly imported batch whose picker was cancelled before the UI received it. */
    internal fun discardImported(files: List<ArenaAttachment>) = synchronized(copyLock) {
        files.forEach { attachment ->
            dataFile(attachment.id).delete()
            metadataFile(attachment.id).delete()
        }
    }

    /** A corrupt/unknown session makes this a no-op. Never infer retention from history UI's 20-row limit. */
    private fun collectUnreferenced(retainedIds: Set<String>) {
        val retained = retainedIds.toMutableSet().apply { addAll(ArenaAttachmentLeases.retainedIds()) }
        fun collect(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    val child = value.get(key)
                    if (key == "attachments" || key == "lastRoundAttachments") {
                        require(child is JSONArray) { "Unknown attachment reference format" }
                        repeat(child.length()) { index ->
                            val item = child.getJSONObject(index)
                            val id = item.getString("id")
                            dataFile(id)
                            retained += id
                        }
                    } else collect(child)
                }
                is JSONArray -> repeat(value.length()) { collect(value.get(it)) }
            }
        }
        val sessions = File(appContext.filesDir, "arena_sessions")
        try {
            if (sessions.exists()) {
                val entries = requireNotNull(sessions.listFiles()) { "Cannot enumerate session references" }
                entries.filter { it.name.startsWith("session_") && it.extension == "json" }.forEach { session ->
                    require(session.length() <= 16L * 1024 * 1024) { "Session reference file is too large" }
                    val json = JSONObject(session.readText(Charsets.UTF_8))
                    require(json.getInt("version") in 1..ArenaSessionJson.SCHEMA_VERSION) { "Unknown session schema" }
                    require(json.getString("id") == session.nameWithoutExtension && json.get("originalQuestion") is String) { "Incomplete session identity" }
                    require(json.get("services") is JSONArray && json.get("runs") is JSONObject && json.get("history") is JSONArray && json.get("summary") is JSONObject) { "Incomplete session references" }
                    collect(json)
                }
            }
        } catch (_: Exception) {
            return // Keeping files is safe when reference discovery is incomplete.
        }
        val candidates = directory.listFiles() ?: return
        candidates.filter { it.extension == "bin" || it.extension == "json" }.forEach { file ->
            if (file.nameWithoutExtension !in retained && file.nameWithoutExtension.matches(Regex("[0-9a-f-]{36}"))) {
                if (!file.delete() && file.exists()) throw IOException("无法回收未引用的附件，请稍后重试")
            }
        }
    }

    internal fun dataFile(id: String): File {
        require(id.matches(Regex("[0-9a-fA-F-]{36}")) && UUID.fromString(id).toString() == id.lowercase()) { "无效附件编号" }
        return File(directory, "$id.bin")
    }
    private fun metadataFile(id: String) = File(directory, "${dataFile(id).nameWithoutExtension}.json")

    private fun mimeFor(name: String, reported: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        else -> reported.substringBefore(';').lowercase()
    }

    companion object { private val copyLock = Any() }
}
