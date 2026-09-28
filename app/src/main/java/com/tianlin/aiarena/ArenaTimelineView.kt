package com.tianlin.aiarena

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 回答页 Tab 里的时光机：默认只占一行「时光机 · 此前 N 轮」，点开后是一条竖向时间线，
 * 每个节点是一轮（第几轮、提问/追问/讨论、时间、问题），再点节点展开那一轮的回答。
 */
@Composable
internal fun SimpleTimeline(
    tabKey: String,
    sessionKey: Long,
    rounds: List<RoundRecord>,
    question: (RoundRecord) -> String,
    body: @Composable (RoundRecord) -> Unit,
) {
    if (rounds.isEmpty()) return
    val colors = ArenaStyle.colors
    var open by rememberSaveable(sessionKey, tabKey) { mutableStateOf(false) }
    var expanded by rememberSaveable(sessionKey, tabKey) { mutableIntStateOf(-1) }
    Column(Modifier.fillMaxWidth().testTag("timeline-$tabKey")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { open = !open }
                .semantics { contentDescription = if (open) "收起时光机" else "展开时光机，查看此前 ${rounds.size} 轮" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArenaIcon(R.drawable.ic_history, tint = colors.accent, size = 16.dp)
            Text("时光机", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = colors.ink)
            Text(" · 此前 ${rounds.size} 轮", style = MaterialTheme.typography.labelMedium, color = colors.muted)
            Spacer(Modifier.weight(1f))
            Text(if (open) "收起" else "展开", style = MaterialTheme.typography.labelSmall, color = colors.accent)
        }
        if (open) rounds.forEachIndexed { index, round ->
            val isOpen = expanded == round.number
            val last = index == rounds.lastIndex
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(16.dp).fillMaxHeight()) {
                    Box(Modifier.align(Alignment.TopCenter).width(1.dp).then(if (last) Modifier.height(12.dp) else Modifier.fillMaxHeight())
                        .background(colors.border))
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(7.dp)
                        .background(if (isOpen) colors.accent else colors.muted, CircleShape))
                }
                Column(Modifier.weight(1f).padding(start = 6.dp, bottom = 12.dp)) {
                    Column(Modifier.fillMaxWidth().clickable { expanded = if (isOpen) -1 else round.number }
                        .testTag("timeline-node-$tabKey-${round.number}")
                        .semantics { contentDescription = (if (isOpen) "收起" else "查看") + ArenaTimeline.nodeTitle(round) }) {
                        Text(ArenaTimeline.nodeTitle(round), style = MaterialTheme.typography.labelSmall, color = colors.muted)
                        Text(question(round), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall,
                            color = colors.ink, maxLines = if (isOpen) 8 else 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (isOpen) Column(Modifier.padding(top = 8.dp)) {
                        body(round)
                        Text("收起第 ${round.number} 轮", Modifier.heightIn(min = 40.dp).clickable { expanded = -1 }.padding(top = 10.dp),
                            style = MaterialTheme.typography.labelSmall, color = colors.accent)
                    }
                }
            }
        }
    }
}

/** 时光机里某位成员某一轮的回答：只读，可选字、可复制。 */
@Composable
internal fun TimelineAnswer(service: ArenaService, run: ParticipantRun?, onCopy: ((String) -> Unit)?) {
    val note = ArenaTimeline.runNote(run)
    if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = ArenaStyle.colors.muted)
    if (run != null && run.response.isNotBlank()) {
        SelectionContainer(Modifier.testTag("timeline-answer-${service.name}")) {
            MarkdownText(run.response, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
        }
        if (onCopy != null) SimpleIcon(R.drawable.ic_copy, "复制 ${service.shortName} 这一轮的回答", { onCopy(run.response) })
    }
}

/** 时光机里某一轮归档的综合答案。 */
@Composable
internal fun TimelineSummary(summary: DiscussionSummary, onCopy: ((String) -> Unit)?) {
    summary.judge?.let { judge ->
        Text("由 ${judge.shortName} 整理 · ${summary.depth.displayName}", style = MaterialTheme.typography.labelSmall,
            color = ArenaStyle.colors.muted)
    }
    SelectionContainer(Modifier.testTag("timeline-summary")) {
        MarkdownText(summary.text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
    }
    if (onCopy != null) SimpleIcon(R.drawable.ic_copy, "复制这一轮的综合答案", { onCopy(summary.text) })
}
