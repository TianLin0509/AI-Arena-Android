package com.tianlin.aiarena

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 时光机里正在看的东西：某一轮的回答，或某一轮归档的综合答案。null 表示看最新。 */
data class TimeSelection(val round: Int, val summary: Boolean)

/** 时光机的一个节点。 */
data class TimeEntry(
    val selection: TimeSelection,
    val label: String,
    val kind: TimeKind,
    val atMillis: Long,
    val detail: String,
    val excerpt: String,
    val latest: Boolean,
    val running: Boolean,
)

enum class TimeKind(val hue: Long) {
    ASK(0xFF6B7280), ITERATE(0xFFE07A1F), RELAY(0xFF1F9D57), DISCUSS(0xFF7C4DDB), SUMMARY(0xFF4268ED)
}

object ArenaTimeMachine {
    private fun kindOf(kind: RoundKind?, relay: Boolean): TimeKind = when {
        relay -> TimeKind.RELAY
        kind == RoundKind.ITERATION -> TimeKind.ITERATE
        kind == RoundKind.DEBATE -> TimeKind.DISCUSS
        else -> TimeKind.ASK
    }

    private fun countOf(results: Map<ArenaService, ParticipantRun>): String {
        val joined = results.values.filter { it.phase != ParticipantPhase.IDLE || it.response.isNotBlank() }
        return "${joined.count { it.phase == ParticipantPhase.COMPLETE }}/${joined.size} 份回答"
    }

    /**
     * 全部节点，最新的在最上面：每轮一个节点，做过综合答案的轮次再多一个「队长总结」节点。
     * 进行中的轮次还不在 history 里，单独补上。
     */
    fun entries(history: List<RoundRecord>, originalQuestion: String, currentRound: Int, currentKind: RoundKind?,
                currentRelay: Boolean, currentStyle: DebateStyle?, currentStartedAt: Long,
                liveRuns: Map<ArenaService, ParticipantRun>, members: List<ArenaService>, summary: DiscussionSummary,
                busy: Boolean): List<TimeEntry> {
        val out = mutableListOf<TimeEntry>()
        val rounds = history.sortedBy { it.number }
        rounds.forEach { round ->
            out += TimeEntry(TimeSelection(round.number, false), ArenaTimeline.kindLabel(round).substringBefore(" · "),
                kindOf(round.kind, round.relay), round.startedAtMillis, countOf(round.results),
                ArenaTimeline.roundQuestion(round, originalQuestion), latest = false, running = false)
            round.summary?.takeIf { it.text.isNotBlank() }?.let { archived ->
                out += TimeEntry(TimeSelection(round.number, true), "队长总结", TimeKind.SUMMARY, round.finishedAtMillis,
                    archived.judge?.let { "由 ${it.shortName} 整理" }.orEmpty(), archived.text, latest = false, running = false)
            }
        }
        if (currentRound > 0 && rounds.none { it.number == currentRound }) {
            out += TimeEntry(TimeSelection(currentRound, false), ArenaTimeline.kindLabel(currentKind ?: RoundKind.INITIAL, currentRelay, currentStyle).substringBefore(" · "),
                kindOf(currentKind, currentRelay), currentStartedAt, countOf(members.associateWith { liveRuns[it] ?: ParticipantRun() }),
                "进行中", latest = false, running = busy)
        }
        if (summary.phase != ParticipantPhase.IDLE && summary.judge != null) {
            val base = summary.roundNumber.takeIf { it > 0 } ?: currentRound
            out += TimeEntry(TimeSelection(base, true), "队长总结", TimeKind.SUMMARY, System.currentTimeMillis(),
                "由 ${summary.judge.shortName} 整理", summary.text.ifBlank { summary.detail }, latest = false,
                running = summary.phase != ParticipantPhase.COMPLETE && summary.phase != ParticipantPhase.ERROR)
        }
        val newestFirst = out.reversed()
        return newestFirst.mapIndexed { index, entry -> if (index == 0) entry.copy(latest = true) else entry }
    }

    fun clock(millis: Long): String = if (millis <= 0L) "--:--" else SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(millis))
}

/** 右侧滑出的时光机侧栏：平时不占回答页的空间，点顶栏的「时光机」打开。 */
@Composable
internal fun TimeMachineDrawer(open: Boolean, question: String, entries: List<TimeEntry>, selected: TimeSelection?,
                               onSelect: (TimeSelection?) -> Unit, onClose: () -> Unit) {
    val colors = ArenaStyle.colors
    if (open) BackHandler(onBack = onClose)
    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .semantics { contentDescription = "关闭时光机" })
    }
    AnimatedVisibility(open, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it },
        modifier = Modifier.fillMaxHeight()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Surface(Modifier.fillMaxHeight().fillMaxWidth(0.86f).testTag("time-machine"), color = colors.page,
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp), shadowElevation = 8.dp) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("时光机", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.ink)
                        SimpleIcon(R.drawable.ic_close, "关闭时光机", onClose)
                    }
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(14.dp), color = colors.accentSoft) {
                        Text(question, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = colors.ink,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(entries, key = { "${it.selection.round}-${it.selection.summary}" }) { entry ->
                            val isSelected = if (selected == null) entry.latest else entry.selection == selected
                            TimeRow(entry, isSelected, entry === entries.last()) {
                                onSelect(if (entry.latest) null else entry.selection); onClose()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeRow(entry: TimeEntry, selected: Boolean, last: Boolean, onClick: () -> Unit) {
    val colors = ArenaStyle.colors
    val hue = Color(entry.kind.hue)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        .background(if (selected) colors.accentSoft else Color.Transparent)
        .clickable(onClick = onClick)
        .semantics { contentDescription = "第 ${entry.selection.round} 轮 ${entry.label}，${ArenaTimeMachine.clock(entry.atMillis)}" }
        .testTag("time-entry-${entry.selection.round}-${if (entry.selection.summary) "summary" else "round"}")) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(if (selected) colors.accent else Color.Transparent))
        Text(ArenaTimeMachine.clock(entry.atMillis), Modifier.width(52.dp).padding(start = 10.dp, top = 16.dp),
            style = MaterialTheme.typography.labelMedium, color = colors.muted)
        Box(Modifier.width(18.dp).fillMaxHeight()) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = if (entry.latest) 21.dp else 0.dp).width(1.dp)
                .then(if (last) Modifier.height(22.dp) else Modifier.fillMaxHeight()).background(colors.border))
            val filled = entry.latest || selected
            Box(Modifier.align(Alignment.TopCenter).padding(top = 18.dp).size(10.dp)
                .background(if (filled) colors.accent else colors.page, CircleShape)
                .border(1.5.dp, if (filled) colors.accent else colors.muted, CircleShape))
        }
        Column(Modifier.weight(1f).padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.label, Modifier.background(hue.copy(alpha = 0.13f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium, color = hue, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (entry.running) Text("进行中", style = MaterialTheme.typography.labelSmall, color = colors.accent)
                else if (entry.latest) Text("最新", Modifier.background(colors.accent, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, color = colors.onAccent)
            }
            if (entry.detail.isNotBlank()) Text(entry.detail, style = MaterialTheme.typography.labelSmall, color = colors.muted)
            Text(entry.excerpt.replace(Regex("[#*>`|_]+"), "").replace(Regex("\\s+"), " ").trim(), style = MaterialTheme.typography.bodySmall,
                color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
