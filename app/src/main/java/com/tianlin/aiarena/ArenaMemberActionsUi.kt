package com.tianlin.aiarena

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 状态词的颜色：进行中蓝、完成绿、出问题红、跳过 / 停止灰；标签和卡片共用一套。 */
@Composable
internal fun toneColors(tone: MemberActionPolicy.Tone): Pair<Color, Color> {
    val colors = ArenaStyle.colors
    return when (tone) {
        MemberActionPolicy.Tone.RUNNING -> colors.accent to colors.accentSoft
        MemberActionPolicy.Tone.DONE -> colors.success to colors.successSoft
        MemberActionPolicy.Tone.PROBLEM -> colors.error to colors.errorSoft
        MemberActionPolicy.Tone.NEUTRAL -> colors.muted to colors.surfaceAlt
    }
}

/** 卡片右上角的状态胶囊。 */
@Composable
internal fun MemberStatusPill(run: ParticipantRun, modifier: Modifier = Modifier) {
    val (fg, bg) = toneColors(MemberActionPolicy.tone(run))
    Text(MemberActionPolicy.statusWord(run), modifier.clip(RoundedCornerShape(10.dp)).background(bg)
        .padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = fg,
        fontWeight = FontWeight.SemiBold, maxLines = 1)
}

/**
 * 成员卡片上的四个逃生动作：重新提取 · 重新发送 · 跳过 · 换人。始终可见、每个至少 48dp 高；
 * 本轮这位成员还没有任务时只显示「换人」。点了做不了的动作时由控制器说明原因（不把按钮藏起来）。
 */
@Composable
internal fun MemberActionBar(
    service: ArenaService,
    run: ParticipantRun,
    isMember: Boolean,
    pendingSwap: ArenaService?,
    onReextract: () -> Unit,
    onResend: () -> Unit,
    onSkip: () -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmResend by remember { mutableStateOf(false) }
    if (confirmResend) ConfirmDialog(
        title = "重新发送给 ${service.shortName}？",
        text = "会把本轮 ${service.shortName} 该收到的内容再发一次，网页里可能出现重复提问。" +
            if (run.response.isNotBlank()) "已收到的回答会保留在「之前的回答」里，可以随时展开看。" else "请先打开网页确认是否已收到或仍在排队。",
        confirmLabel = "确认重新发送",
        onConfirm = { confirmResend = false; onResend() },
        onDismiss = { confirmResend = false },
    )
    val hasTask = MemberActionPolicy.hasTask(run)
    Column(modifier.fillMaxWidth().testTag("member-actions-${service.name}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (hasTask) {
                MemberAction("重新提取", "重新提取 ${service.shortName} 的回答", "action-reextract-${service.name}", Modifier.weight(1f), onClick = onReextract)
                MemberAction("重新发送", "重新发送给 ${service.shortName}", "action-resend-${service.name}", Modifier.weight(1f)) {
                    if (MemberActionPolicy.resendNeedsConfirm(run)) confirmResend = true else onResend()
                }
                MemberAction(if (run.skipped) "已跳过" else "跳过", "跳过 ${service.shortName}", "action-skip-${service.name}", Modifier.weight(1f),
                    enabled = !run.skipped, quiet = true, onClick = onSkip)
            }
            MemberAction(if (isMember) "换人" else "已换人", "给 ${service.shortName} 换人", "action-swap-${service.name}",
                if (hasTask) Modifier.weight(1f) else Modifier.fillMaxWidth(), enabled = isMember, accent = true, onClick = onSwap)
        }
        if (pendingSwap != null) {
            Text("已安排：下一轮起由 ${pendingSwap.shortName} 接替 ${service.shortName}（点「换人」可改或取消）",
                Modifier.padding(horizontal = 4.dp).testTag("pending-swap-${service.name}"),
                style = MaterialTheme.typography.bodySmall, color = ArenaStyle.colors.accent)
        }
    }
}

@Composable
private fun MemberAction(
    label: String,
    description: String,
    tag: String,
    modifier: Modifier,
    enabled: Boolean = true,
    quiet: Boolean = false,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val content = when {
        !enabled -> colors.muted
        accent -> colors.accent
        quiet -> colors.muted
        else -> colors.ink
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description }.testTag(tag),
        shape = RoundedCornerShape(12.dp),
        color = if (accent && enabled) colors.accentSoft else colors.page,
        border = BorderStroke(1.dp, if (accent && enabled) colors.accent.copy(alpha = 0.35f) else colors.border),
    ) {
        Box(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = content, fontWeight = FontWeight.SemiBold,
                maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize, stepSize = 0.5.sp))
        }
    }
}

/** 选另一家 AI 接替：先用一句话说清会发生哪一种（本轮马上接手 / 下一轮起），再列可选成员。 */
@Composable
internal fun SwapMemberDialog(
    from: ArenaService,
    run: ParticipantRun,
    candidates: List<ArenaService>,
    statuses: Map<ArenaService, ServiceStatus>,
    pending: ArenaService?,
    onPick: (ArenaService) -> Unit,
    onCancelPending: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ArenaStyle.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.page,
        title = { Text("给 ${from.shortName} 换人", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(MemberActionPolicy.swapExplanation(from, run), Modifier.testTag("swap-explanation"),
                    style = MaterialTheme.typography.bodyMedium, color = colors.ink)
                if (candidates.isEmpty()) {
                    Text("所有 AI 都已经在圆桌里了，没有可换的成员。", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    candidates.forEach { candidate ->
                        val status = statuses[candidate] ?: ServiceStatus()
                        val note = buildList {
                            add(when (status.state) {
                                ConnectionState.SIGNED_IN -> "已登录"
                                ConnectionState.NEEDS_LOGIN -> if (status.guest) "可免登录提问" else "需要先登录"
                                ConnectionState.ERROR -> "网页打不开"
                                else -> "还没打开过，接手前会自动打开"
                            })
                            if (candidate.overseas) add("需境外网络")
                            if (candidate == pending) add("已安排")
                        }.joinToString(" · ")
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (candidate == pending) colors.accentSoft else colors.card)
                            .clickable(role = Role.Button) { onPick(candidate) }
                            .semantics { contentDescription = "换成 ${candidate.displayName}" }
                            .testTag("swap-to-${candidate.name}")
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            BrandAvatar(candidate, size = 30.dp)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(candidate.displayName, style = MaterialTheme.typography.titleSmall, color = colors.ink)
                                Text(note, style = MaterialTheme.typography.labelMedium,
                                    color = if (status.state == ConnectionState.NEEDS_LOGIN && !status.guest) colors.warning else colors.muted)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (pending != null) TextButton(onClick = onCancelPending, modifier = Modifier.heightIn(min = 48.dp).testTag("swap-cancel-pending")) {
                Text("取消换人，保留 ${from.shortName}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") } },
    )
}

/** 「重新发送」前收到的回答：默认收起，点开逐份查看，绝不丢。 */
@Composable
internal fun PreviousAnswers(service: ArenaService, previous: List<String>) {
    if (previous.isEmpty()) return
    val colors = ArenaStyle.colors
    var open by rememberSaveable(service.name, previous.size) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("previous-answers-${service.name}"),
        shape = RoundedCornerShape(12.dp), color = colors.card) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }
                .semantics { contentDescription = if (open) "收起之前的回答" else "展开之前的回答" },
                verticalAlignment = Alignment.CenterVertically) {
                Text("之前的回答（${previous.size} 份）", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.ink)
                Text(if (open) "收起" else "展开", style = MaterialTheme.typography.labelMedium, color = colors.accent)
            }
            if (open) previous.asReversed().forEachIndexed { index, text ->
                Text(if (index == 0) "重新发送前的回答" else "更早的回答（第 ${previous.size - index} 份）",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = colors.muted)
                SelectionContainer(Modifier.padding(bottom = 8.dp)) {
                    MarkdownText(text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** 标签上的小圆点：进行中蓝、出问题红、跳过灰、完成绿。 */
@Composable
internal fun ToneDot(run: ParticipantRun?, modifier: Modifier = Modifier) {
    if (run == null || (run.phase == ParticipantPhase.IDLE && !run.skipped)) return
    val (fg, _) = toneColors(MemberActionPolicy.tone(run))
    Box(modifier.size(9.dp).background(fg, CircleShape).semantics { contentDescription = MemberActionPolicy.statusWord(run) })
}
