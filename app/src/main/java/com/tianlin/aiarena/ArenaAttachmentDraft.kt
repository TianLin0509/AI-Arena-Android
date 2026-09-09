package com.tianlin.aiarena

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 选择回调只属于发起它的草稿；新会话、发出本轮或销毁界面后晚到结果均作废。 */
internal class AttachmentDraft(initial: List<ArenaAttachment> = emptyList()) {
    var attachments by mutableStateOf(initial)
        private set
    var picking by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var generation = 0L

    fun choose(request: ((Result<List<ArenaAttachment>>) -> Unit) -> Unit) {
        if (picking) return
        picking = true
        error = null
        val token = ++generation
        try {
            request { result ->
                if (token == generation) {
                    generation++
                    picking = false
                    result.onSuccess { selected ->
                        if (selected.isNotEmpty()) {
                            val combined = (attachments + selected).distinctBy { it.id }
                            val invalid = ArenaAttachmentPolicy.validate(combined)
                            if (invalid == null) attachments = combined else error = invalid
                        }
                    }.onFailure { error = it.message ?: "附件未能添加，请重新选择" }
                }
            }
        } catch (failure: Exception) {
            if (token == generation) {
                picking = false
                error = failure.message ?: "无法打开文件选择器"
            }
        }
    }

    fun remove(id: String) { attachments = attachments.filterNot { it.id == id } }
    fun invalidate() { generation++; picking = false }
    fun clear() { invalidate(); attachments = emptyList(); error = null }

    companion object {
        val Saver = listSaver<AttachmentDraft, String>(
            save = { draft -> draft.attachments.flatMap { listOf(it.id, it.name, it.mimeType, it.sizeBytes.toString(), it.sha256) } },
            restore = { flat -> AttachmentDraft(flat.chunked(5).map { ArenaAttachment(it[0], it[1], it[2], it[3].toLong(), it[4]) }) },
        )
    }
}

internal object AttachmentPromptPolicy {
    const val DEFAULT_QUESTION = "请阅读附件，概括主要内容并给出你的分析和建议。"
    fun withDefault(text: String, attachments: List<ArenaAttachment>): String =
        if (text.isBlank() && attachments.isNotEmpty()) DEFAULT_QUESTION else text
}

@Composable
internal fun AttachmentComposer(
    attachments: List<ArenaAttachment>,
    picking: Boolean,
    enabled: Boolean,
    onChoose: () -> Unit,
    onRemove: (String) -> Unit,
    error: String? = null,
    summaryHint: Boolean = false,
) {
    val colors = ArenaStyle.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ArenaTextAction(
            text = if (picking) "正在添加附件…" else "添加照片 / 文件",
            onClick = onChoose,
            enabled = enabled && !picking,
            modifier = Modifier.testTag("choose-attachments"),
        )
        attachments.forEach { attachment ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(attachment.name, Modifier.weight(1f), color = colors.ink, style = MaterialTheme.typography.bodySmall)
                ArenaTextAction("移除", { onRemove(attachment.id) }, enabled = enabled && !picking,
                    contentDescriptionText = "移除附件 ${attachment.name}")
            }
        }
        Text(
            if (attachments.isEmpty()) "最多 3 个 · 单个 10 MB · 合计 20 MB；支持照片、PDF、TXT、DOCX。"
            else if (summaryHint) "讨论 / 迭代会发给本轮各家 AI；总结仅发给队长。不写要求时将请 AI 阅读并分析附件。"
            else "所选附件将发给参与的各家 AI。不写问题时将请 AI 阅读并分析附件。",
            color = colors.muted, style = MaterialTheme.typography.labelSmall,
        )
        if (error != null) Text(error, color = colors.error, style = MaterialTheme.typography.bodySmall)
    }
}
