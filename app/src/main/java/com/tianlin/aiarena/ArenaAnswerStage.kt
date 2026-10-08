package com.tianlin.aiarena

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 成员胶囊标签：头像 + 名字 + 状态小圆点（进行中蓝、完成绿、出问题红、跳过灰）；选中蓝底描边。 */
@Composable
internal fun SimpleAnswerTabs(members: List<ArenaService>, selected: String, runs: Map<ArenaService, ParticipantRun>, onSelect: (String) -> Unit) {
    val colors = ArenaStyle.colors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        (members.map { it.name } + "summary").forEach { key ->
            val service = ArenaService.fromName(key)
            val on = key == selected
            val run = service?.let { runs[it] }
            Surface(Modifier.clip(RoundedCornerShape(20.dp)).clickable { onSelect(key) }
                .semantics { this.selected = on }.testTag("answer-tab-$key"),
                shape = RoundedCornerShape(20.dp), color = if (on) colors.accentSoft else colors.page,
                border = BorderStroke(1.dp, if (on) colors.accent.copy(alpha = 0.55f) else colors.border)) {
                Row(Modifier.heightIn(min = 48.dp).padding(start = 8.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        if (service != null) BrandAvatar(service, size = 26.dp)
                        else ArenaIcon(R.drawable.ic_logo, tint = androidx.compose.ui.graphics.Color.Unspecified, size = 26.dp)
                        ToneDot(run, Modifier.align(Alignment.TopEnd))
                    }
                    Text(service?.shortName ?: "综合", Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.labelLarge, color = if (on) colors.accent else colors.ink,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

/**
 * 本轮一家的回答：头像名字 + 状态胶囊，四个逃生动作（重新提取 / 重新发送 / 跳过 / 换人）始终在名字下面，
 * 然后是等待进度、Markdown 正文、失败说明和「之前的回答」。
 */
@Composable
internal fun SimpleAnswer(
    service: ArenaService, run: ParticipantRun, status: ServiceStatus, onOpen: () -> Unit,
    onCopy: (() -> Unit)?, onShare: (() -> Unit)?, busy: Boolean,
    onReextract: () -> Unit, onResend: () -> Unit, onSummary: () -> Unit,
    draft: String? = null, onReplaceDraft: (String) -> Unit = {},
    onSkip: () -> Unit = {}, onSwap: () -> Unit = {}, isMember: Boolean = true, pendingSwap: ArenaService? = null,
    progress: (@Composable () -> Unit)? = null,
) {
    var confirmDraft by remember { mutableStateOf<String?>(null) }
    var details by rememberSaveable { mutableStateOf(false) }
    val colors = ArenaStyle.colors
    confirmDraft?.let { text ->
        ConfirmDialog("清空这段草稿并重发？",
            "${service.shortName} 输入框里的这段文字会被本轮问题替换：\n\n「${text.take(300)}${if (text.length > 300) "…" else ""}」\n\n只替换这一段；网页里的文字若已改变会停下，不会发送。",
            "清空并重发", onConfirm = { confirmDraft = null; onReplaceDraft(text) }, onDismiss = { confirmDraft = null })
    }
    val satOut = run.phase == ParticipantPhase.IDLE && run.detail == "本轮未参与"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SimpleAvatar(service, onOpen)
            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                Text(service.displayName, style = MaterialTheme.typography.titleMedium, color = colors.ink, fontWeight = FontWeight.SemiBold)
                val caption = run.modeLabel.ifBlank { AiModePolicy.label(status.modeReading) }
                if (caption.isNotBlank()) Text(caption + if (run.thinkingUsed) " · 已深度思考" else "", style = MaterialTheme.typography.labelMedium,
                    color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (MemberActionPolicy.hasTask(run) || satOut) MemberStatusPill(run, Modifier.testTag("member-status-${service.name}"))
        }
        MemberActionBar(service, run, isMember, pendingSwap, onReextract = onReextract, onResend = onResend, onSkip = onSkip, onSwap = onSwap)
        if (satOut) {
            Text("这一轮没有发给 ${service.shortName}：上一轮它没有被采用的回答（没答完或被跳过）。之前的内容在右上角时光机里。",
                Modifier.testTag("sat-out-${service.name}"),
                style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        progress?.invoke()
        if (run.skipped && run.response.isNotBlank()) {
            Text(if (run.replacedBy != null) "已换成 ${run.replacedBy.shortName}，下面是 ${service.shortName} 被换下前收到的内容，本轮不采用。"
                else "已跳过：下面的回答保留可看，本轮之后的讨论、激发和总结不带它。",
                style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        if (run.response.isNotBlank()) SelectionContainer(Modifier.testTag("simple-answer-${service.name}")) {
            MarkdownText(run.response, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
        }
        if (run.responseTruncated) {
            SimpleNotice("原回答约 ${run.originalResponseLength} 字，圆桌仅保留前 ${ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS} 字；点击头像查看完整原文。")
        }
        if (run.phase == ParticipantPhase.WAITING && (run.detail.contains("安全验证") || run.detail.contains("迟迟没有回应"))) {
            SimpleNotice(run.detail)
            TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text("打开网页处理") }
        }
        if (run.phase == ParticipantPhase.ERROR && !run.skipped) {
            val advice = ArenaErrorHelp.explain(run.detail, service.shortName)
            Surface(Modifier.fillMaxWidth(), color = if (run.stopped) colors.surfaceAlt else colors.errorSoft, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(advice.what, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
                    Text(advice.next, style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
            }
            if (draft != null) {
                Surface(Modifier.fillMaxWidth(), color = colors.card, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("网页输入框里没发出的文字", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                        Text(draft, Modifier.padding(top = 4.dp).testTag("blocking-draft-${service.name}"),
                            style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = { confirmDraft = draft }, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("replace-draft-${service.name}")) { Text("清空这段草稿并重发") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text("打开网页") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (details) "收起详情" else "错误详情", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
            }
            if (details) Text(run.detail, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        PreviousAnswers(service, run.previousResponses)
        if (run.response.isNotBlank()) AnswerActions(service.shortName, onCopy, onShare, onOpen)
    }
}

/** 复制 / 分享 / 原网页：描边按钮，老人也点得准。 */
@Composable
internal fun AnswerActions(name: String, onCopy: (() -> Unit)?, onShare: (() -> Unit)?, onOpen: (() -> Unit)?) {
    val colors = ArenaStyle.colors
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable
        fun action(icon: Int, label: String, description: String, onClick: () -> Unit) {
            OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics { contentDescription = description },
                shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, colors.border),
                contentPadding = PaddingValues(horizontal = 6.dp)) {
                ArenaIcon(icon, tint = colors.ink, size = 16.dp)
                Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = colors.ink)
            }
        }
        if (onCopy != null) action(R.drawable.ic_copy, "复制", "复制 $name 的回答", onCopy)
        if (onShare != null) action(R.drawable.ic_share, "分享", "分享 $name 的回答", onShare)
        if (onOpen != null) action(R.drawable.ic_open_in_new, "原网页", "打开 $name 网页查看原文", onOpen)
    }
}

/**
 * 回答页。顶部成员胶囊；中间是当前（或时光机选中的）一轮；底部是四种工作模式的输入区。
 * 回看往轮时只读：隐藏输入区，顶部提示条可一键回到最新。
 */
@Composable
internal fun SimpleRoundStage(
    statuses: Map<ArenaService, ServiceStatus>, sessionController: ArenaSessionController,
    roundGuidance: String, onRoundGuidanceChange: (String) -> Unit, onNewSession: () -> Unit,
    onNavigate: (RoundtablePage) -> Unit, onOpenService: (ArenaService) -> Unit,
    snackbarHostState: SnackbarHostState, copyText: TextCopyRequest?, shareText: TextShareRequest?,
    offline: Boolean, captainPreferences: ArenaCaptainPreferences,
    attachmentDraft: AttachmentDraft? = null, onChooseAttachments: (() -> Unit)? = null,
    presetStore: ArenaPresetStore? = null,
) {
    val colors = ArenaStyle.colors
    val context = LocalContext.current
    val store = presetStore ?: remember(context) { ArenaPresetStore(context) }
    var presetVersion by remember { mutableIntStateOf(0) }
    val navigationPreferences = remember(context) { ArenaNavigationPreferences(context) }
    // 底部提问区收起 / 展开由用户自己决定，并记住到下次；新一轮迭代前再展开用。
    var composerCollapsed by rememberSaveable { mutableStateOf(navigationPreferences.isComposerCollapsed()) }
    val scope = rememberCoroutineScope()
    val sessionKey = sessionController.askedAtMillis
    var viewing by rememberSaveable(sessionKey, stateSaver = TimeSelectionSaver) { mutableStateOf<TimeSelection?>(null) }
    var drawer by rememberSaveable { mutableStateOf(false) }
    var modeName by rememberSaveable { mutableStateOf(RoundMode.ITERATE.name) }
    val mode = RoundMode.fromName(modeName)
    var styleName by rememberSaveable { mutableStateOf(DebateStyle.DEBATE.name) }
    val style = DebateStyle.fromName(styleName)
    var captainName by rememberSaveable { mutableStateOf(captainPreferences.loadCaptain()?.name) }
    var depthName by rememberSaveable { mutableStateOf(captainPreferences.loadDepth().name) }
    val depth = SummaryDepth.fromName(depthName)
    var editing by remember { mutableStateOf<PresetKey?>(null) }

    val liveRound = sessionController.roundNumber
    val pastRound = viewing?.takeIf { it.round != liveRound || it.summary }?.let { sel -> sessionController.history.firstOrNull { it.number == sel.round } }
    val reviewing = viewing != null && (pastRound != null || viewing?.summary == true)
    val members = pastRound?.results?.filter { it.value.phase != ParticipantPhase.IDLE || it.value.response.isNotBlank() }?.keys
        ?.let { keys -> ArenaService.entries.filter { it in keys } }?.ifEmpty { null } ?: sessionController.roundTabs
    var selected by rememberSaveable(sessionKey, viewing?.round, viewing?.summary) {
        mutableStateOf(if (viewing?.summary == true) "summary" else members.first().name)
    }
    val current = selected.takeIf { it == "summary" || members.any { m -> m.name == it } } ?: members.first().name
    val busy = sessionController.isBusy
    // 下一轮会参加的成员：本轮回答被采用的成员（预约的换人已替换）+ 新换上来的成员。
    val nextMembers = sessionController.nextRoundMembers()
    // 独立迭代只剩一家也能继续问（逃生通道）；工作流要两位成员；讨论、激发、总结要两份完整回答。
    val enough = when (mode) {
        RoundMode.ITERATE -> nextMembers.isNotEmpty()
        RoundMode.RELAY -> nextMembers.size >= ArenaService.MIN_MEMBERS
        else -> sessionController.completedCount >= ArenaService.MIN_MEMBERS
    }
    val ready = sessionController.stage == SessionStage.READY && enough && !busy
    val completedMembers = sessionController.sessionServices.filter {
        sessionController.runs[it]?.let { run -> run.phase == ParticipantPhase.COMPLETE && !run.skipped } == true
    }
    var orderNames by rememberSaveable(sessionKey) { mutableStateOf(emptyList<String>()) }
    val relayOrder = orderNames.mapNotNull(ArenaService::fromName).filter { it in nextMembers }
        .let { saved -> saved + nextMembers.filterNot { it in saved } }
    var swapping by rememberSaveable { mutableStateOf<String?>(null) }
    val captain = CaptainPolicy.resolve(ArenaService.fromName(captainName), sessionController.sessionServices)
    fun notifyFailure(success: Boolean) { if (!success) scope.launch { snackbarHostState.showSnackbar(sessionController.sessionMessage) } }
    // 逃生动作成功和失败都说一句：成功时说明发生了什么，失败时说明为什么做不了。
    fun announce(@Suppress("UNUSED_PARAMETER") success: Boolean) { scope.launch { snackbarHostState.showSnackbar(sessionController.sessionMessage) } }

    // Copies and shares pair the text with the question it actually answered (current round, a reviewed round, or the topic).
    val shareQuestion = when {
        current == "summary" -> sessionController.originalQuestion
        pastRound != null -> ArenaTimeline.roundQuestion(pastRound, sessionController.originalQuestion)
        else -> sessionController.currentQuestion
    }
    val copyFrom: ((String, String) -> Unit)? = copyText?.let { copy -> { label, text -> scope.launch {
        val prepared = ShareTextPolicy.discussionSummary(shareQuestion, text)
        snackbarHostState.showSnackbar(if (!copy(label, prepared.text)) "复制失败" else if (prepared.truncated) "内容过长，已截取后复制" else "已复制")
    }; Unit } }
    val shareFrom: ((String, String) -> Unit)? = shareText?.let { share -> { label, text -> scope.launch {
        val prepared = ShareTextPolicy.discussionSummary(shareQuestion, text)
        if (!share(label, prepared.text)) snackbarHostState.showSnackbar("分享失败")
        else if (prepared.truncated) snackbarHostState.showSnackbar("内容过长，已截取后分享")
    }; Unit } }
    val shareSession: (() -> Unit)? = shareText?.let { share -> {
        val prepared = ShareTextPolicy.fullSession(sessionController.originalQuestion, sessionController.askedAtMillis,
            sessionController.history.toList(), sessionController.summary)
        scope.launch {
            if (!share("AI 圆桌整场讨论", prepared.text)) snackbarHostState.showSnackbar("分享失败")
            else if (prepared.truncated) snackbarHostState.showSnackbar("内容过长，已截取后分享")
        }; Unit
    } }

    swapping?.let(ArenaService::fromName)?.let { from ->
        SwapMemberDialog(
            from = from,
            run = sessionController.runs[from] ?: ParticipantRun(),
            candidates = sessionController.swapCandidates(from),
            statuses = statuses,
            pending = sessionController.pendingSwaps[from],
            onPick = { to ->
                swapping = null
                val ok = sessionController.swap(from, to)
                scope.launch { snackbarHostState.showSnackbar(sessionController.sessionMessage) }
                if (ok && sessionController.runs[to]?.requestId?.isNotBlank() == true) selected = to.name
            },
            onCancelPending = {
                swapping = null
                sessionController.swap(from, from)
                scope.launch { snackbarHostState.showSnackbar(sessionController.sessionMessage) }
            },
            onDismiss = { swapping = null },
        )
    }

    editing?.let { key ->
        PresetEditorDialog(key, store, onDone = { message -> editing = null; presetVersion++; scope.launch { snackbarHostState.showSnackbar(message) } },
            onDismiss = { editing = null })
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(colors.page).navigationBarsPadding().imePadding()) {
            SimpleHeader(busy, onNewSession, onNavigate, onShareSession = shareSession.takeIf { sessionController.history.isNotEmpty() },
                onTimeMachine = { drawer = true })
            SimpleAnswerTabs(members, current, pastRound?.results ?: sessionController.runs) { selected = it }
            if (reviewing) Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp).testTag("reviewing-banner"),
                color = colors.accentSoft, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("正在回看第 ${viewing?.round} 轮" + (pastRound?.let { " · " + ArenaTimeline.kindLabel(it) } ?: ""),
                        Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.accent)
                    TextButton(onClick = { viewing = null }, modifier = Modifier.testTag("back-to-latest")) { Text("回到最新") }
                }
            }
            if (offline) SimpleNotice("网络未连接；已收到的回答仍可阅读。")
            sessionController.storageWarning?.let { SimpleNotice(it) }
            if (!reviewing && listOf("压缩", "截取", "预算", "上限").any { sessionController.sessionMessage.contains(it) }) {
                SimpleNotice(sessionController.sessionMessage)
            }
            val scrollStates = rememberSaveableStateHolder()
            scrollStates.SaveableStateProvider("$sessionKey-${viewing?.round}-$current") {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("answer-scroll"), state = rememberLazyListState(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item("question") {
                        QuestionCard(
                            label = when {
                                current == "summary" -> summaryBasis(sessionController, pastRound)
                                pastRound != null -> ArenaTimeline.nodeTitle(pastRound)
                                liveRound > 0 -> "第 $liveRound 轮 · " + ArenaTimeline.kindLabel(sessionController.currentRoundKind ?: RoundKind.INITIAL,
                                    sessionController.currentRoundRelay, sessionController.currentRoundStyle) +
                                    (sessionController.currentRoundStartedAtMillis.takeIf { it > 0L }?.let { " · " + formatAskedTime(it) } ?: "")
                                else -> ""
                            },
                            text = when {
                                current == "summary" -> "讨论主题：${sessionController.originalQuestion}"
                                pastRound != null -> ArenaTimeline.roundQuestion(pastRound, sessionController.originalQuestion)
                                else -> sessionController.currentQuestion
                            },
                            attachments = when {
                                current == "summary" -> (if (reviewing) pastRound?.summary?.attachments else sessionController.summary.attachments).orEmpty()
                                pastRound != null -> pastRound.attachments
                                else -> sessionController.lastRoundAttachments
                            },
                        )
                    }
                    if (current == "summary") item("summary") {
                        val shown = if (reviewing) pastRound?.summary ?: sessionController.summary.takeIf { it.roundNumber == viewing?.round } else sessionController.summary
                        SummaryPanel(sessionController, shown ?: DiscussionSummary(), reviewing, onOpenService,
                            copyFrom, shareFrom, onUseSummaryMode = { modeName = RoundMode.SUMMARY.name; viewing = null },
                            onRetryOriginal = { notifyFailure(sessionController.retrySummary()) })
                    } else item("answer") {
                        val service = members.first { it.name == current }
                        val run = (pastRound?.results ?: sessionController.runs)[service] ?: ParticipantRun()
                        Column {
                            if (reviewing) {
                                ReadOnlyAnswer(service, run, onOpen = { onOpenService(service) },
                                    onCopy = copyFrom?.let { f -> { f("${service.shortName} 第 ${viewing?.round} 轮回答", run.response) } },
                                    onShare = shareFrom?.let { f -> { f("${service.shortName} 第 ${viewing?.round} 轮回答", run.response) } })
                            } else SimpleAnswer(service, run, statuses[service] ?: ServiceStatus(), { onOpenService(service) },
                                onCopy = copyFrom?.let { f -> { f("${service.shortName} 的回答", run.response) } },
                                onShare = shareFrom?.let { f -> { f("${service.shortName} 的回答", run.response) } }, busy = busy,
                                onReextract = { announce(sessionController.reextract(service)) },
                                onResend = { announce(sessionController.resend(service)) },
                                onSummary = { modeName = RoundMode.SUMMARY.name },
                                draft = sessionController.blockingDraft(service),
                                onReplaceDraft = { text -> notifyFailure(sessionController.retrySendReplacingDraft(service, text)) },
                                onSkip = { announce(sessionController.skip(service)) },
                                onSwap = { swapping = service.name },
                                isMember = service in sessionController.sessionServices,
                                pendingSwap = sessionController.pendingSwaps[service],
                                progress = { SimpleWaitProgress(sessionController, service, run, onOpen = { onOpenService(service) }) })
                        }
                    }
                }
            }
            if (reviewing) {
                Surface(color = colors.page) {
                    Button(onClick = { viewing = null }, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)) { Text("回到最新一轮继续") }
                }
            } else {
                val presetParts = if (mode.preset) {
                    presetVersion
                    val key = when (mode) {
                        RoundMode.RELAY -> PresetKey.RELAY
                        RoundMode.SUMMARY -> PresetKey.SUMMARY
                        RoundMode.INSPIRE -> PresetKey.INSPIRE
                        else -> style.preset
                    }
                    val debateIndex = if (mode == RoundMode.INSPIRE) sessionController.history.count { it.kind == RoundKind.INSPIRE } + 1
                        else sessionController.history.count { it.kind == RoundKind.DEBATE && (it.style ?: DebateStyle.DEBATE) == style } + 1
                    key to ArenaPresets.view(key, store, peers = (completedMembers.size - 1).coerceAtLeast(1), answers = completedMembers.size,
                        style = style, debateIndex = debateIndex, depth = depth, members = relayOrder.size.coerceAtLeast(2))
                } else null
                ModeComposer(
                    collapsed = composerCollapsed,
                    onCollapsedChange = { composerCollapsed = it; navigationPreferences.setComposerCollapsed(it) },
                    mode = mode, onMode = { modeName = it.name }, text = roundGuidance, onText = onRoundGuidanceChange,
                    ready = ready, busy = busy, attachmentDraft = attachmentDraft, onChooseAttachments = onChooseAttachments,
                    attachmentNotice = ArenaAttachmentSupport.notice(
                        if (mode == RoundMode.SUMMARY) listOfNotNull(CaptainPolicy.judgePreference(sessionController.sessionServices, captain).firstOrNull { it in completedMembers })
                        else completedMembers, attachmentDraft?.attachments.orEmpty()),
                    scope = if (busy) "本轮进行中，答完后再继续" else when (mode) {
                        RoundMode.SUMMARY -> {
                            val judge = CaptainPolicy.judgePreference(sessionController.sessionServices, captain).firstOrNull { it in completedMembers }
                            if (judge != null && captain != null && judge != captain) "${captain.shortName} 本轮未完成，将由 ${judge.shortName} 整理"
                            else "只发给队长${judge?.let { " ${it.shortName}" }.orEmpty()}"
                        }
                        RoundMode.RELAY -> "按顺序接力，${relayOrder.size} 家"
                        RoundMode.ITERATE -> if (!busy && nextMembers.isEmpty()) "本轮还没有答完的成员，先在成员卡片上重新提取或重新发送"
                            else "发给 ${membersLabel(nextMembers, sessionController.pendingSwaps.values.toSet())}"
                        else -> if (!busy && completedMembers.size < ArenaService.MIN_MEMBERS)
                            "至少 ${ArenaService.MIN_MEMBERS} 份完整回答才能${mode.label}，先在成员卡片上重新提取或重新发送；只想继续问可用「独立迭代」"
                        else "发给 ${membersLabel(nextMembers, sessionController.pendingSwaps.values.toSet())}，每位收到别人的完整回答"
                    },
                    options = {
                        when (mode) {
                            RoundMode.RELAY -> RelayOrderRow(relayOrder, !busy) { index ->
                                val list = relayOrder.toMutableList(); list[index - 1] = list[index].also { list[index] = list[index - 1] }
                                orderNames = list.map { it.name }
                            }
                            RoundMode.DISCUSS -> ChipChoice("讨论方式", DebateStyle.entries.map { it.name to it.displayName }, style.name, !busy) { styleName = it }
                            RoundMode.SUMMARY -> SummaryOptionsRow(sessionController.sessionServices, captain, depth, !busy,
                                onCaptain = { captainName = it.name; captainPreferences.saveCaptain(it) },
                                onDepth = { depthName = it.name; captainPreferences.saveDepth(it) })
                            RoundMode.ITERATE -> if (ready && roundGuidance.isBlank()) QuickFollowUpRow(onRoundGuidanceChange)
                            RoundMode.INSPIRE -> Unit
                        }
                    },
                    preset = presetParts?.let { (key, parts) -> { PresetCard(key, parts, store.custom(key) != null, !busy) { editing = key } } },
                    onStop = sessionController::cancelCurrentRound,
                    onSend = send@{
                        val files = attachmentDraft?.attachments.orEmpty()
                        if (roundGuidance.length > ArenaLimits.MAX_GUIDANCE_CHARS) {
                            scope.launch { snackbarHostState.showSnackbar("补充超过 ${ArenaLimits.MAX_GUIDANCE_CHARS} 字，请缩短后重试") }
                            return@send
                        }
                        if (mode in setOf(RoundMode.ITERATE, RoundMode.RELAY) && roundGuidance.isBlank() && files.isEmpty()) {
                            scope.launch { snackbarHostState.showSnackbar("先写下问题或添加附件") }
                            return@send
                        }
                        val started = when (mode) {
                            RoundMode.ITERATE -> sessionController.startIteration(AnswerMode.PARALLEL, roundGuidance, files)
                            RoundMode.RELAY -> sessionController.startIteration(AnswerMode.SERIAL, roundGuidance, files, relayOrder = relayOrder)
                            RoundMode.DISCUSS -> sessionController.startDebate(AnswerMode.PARALLEL, roundGuidance, files, style)
                            RoundMode.INSPIRE -> sessionController.startInspire(AnswerMode.PARALLEL, roundGuidance, files)
                            RoundMode.SUMMARY -> sessionController.startSummary(CaptainPolicy.judgePreference(sessionController.sessionServices, captain),
                                roundGuidance, depth, attachments = files).also { if (it) selected = "summary" }
                        }
                        if (started) { onRoundGuidanceChange(""); attachmentDraft?.clear(); if (mode != RoundMode.SUMMARY) selected = sessionController.roundTabs.first().name }
                        else notifyFailure(false)
                    },
                )
            }
        }
        TimeMachineDrawer(open = drawer, question = sessionController.originalQuestion,
            entries = ArenaTimeMachine.entries(sessionController.history.toList(), sessionController.originalQuestion, liveRound,
                sessionController.currentRoundKind, sessionController.currentRoundRelay, sessionController.currentRoundStyle,
                sessionController.currentRoundStartedAtMillis, sessionController.runs, sessionController.sessionServices,
                sessionController.summary, busy),
            selected = viewing, onSelect = { viewing = it; if (it?.summary == true) selected = "summary" }, onClose = { drawer = false })
    }
}

internal val TimeSelectionSaver = androidx.compose.runtime.saveable.Saver<TimeSelection?, String>(
    save = { value -> value?.let { "${it.round}:${it.summary}" }.orEmpty() },
    restore = { raw -> raw.split(':').takeIf { it.size == 2 }?.let { TimeSelection(it[0].toIntOrNull() ?: 0, it[1] == "true") } },
)

private fun summaryBasis(controller: ArenaSessionController, past: RoundRecord?): String {
    val basis = (past?.summary ?: controller.summary).roundNumber.takeIf { it > 0 } ?: past?.number
    return basis?.let { "依据第 $it 轮回答整理" } ?: "综合答案"
}

@Composable
private fun QuestionCard(label: String, text: String, attachments: List<ArenaAttachment>) {
    val colors = ArenaStyle.colors
    Surface(Modifier.fillMaxWidth(), color = colors.accentSoft, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            if (label.isNotBlank()) Text(label, Modifier.padding(bottom = 6.dp).testTag("current-round-label"),
                style = MaterialTheme.typography.labelMedium, color = colors.muted)
            SelectionContainer {
                Text(text, Modifier.testTag("current-question"), style = MaterialTheme.typography.bodyLarge, color = colors.ink,
                    fontWeight = FontWeight.Medium)
            }
            if (attachments.isNotEmpty()) Text("本轮附件：${attachments.joinToString("、") { it.name }}",
                Modifier.padding(top = 6.dp).testTag("round-attachment-names"), style = MaterialTheme.typography.labelSmall, color = colors.muted)
        }
    }
}

/** 往轮回答：只读，可选字、复制、分享、打开原网页；没成功的写明原因。 */
@Composable
private fun ReadOnlyAnswer(service: ArenaService, run: ParticipantRun, onOpen: () -> Unit, onCopy: (() -> Unit)?, onShare: (() -> Unit)?) {
    val colors = ArenaStyle.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        SimpleAvatar(service, onOpen)
        Text(service.shortName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = colors.ink)
    }
    val note = ArenaTimeline.runNote(run)
    if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = colors.muted)
    if (run.response.isNotBlank()) {
        SelectionContainer(Modifier.testTag("timeline-answer-${service.name}")) {
            MarkdownText(run.response, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
        }
    }
    PreviousAnswers(service, run.previousResponses)
    if (run.response.isNotBlank()) AnswerActions(service.shortName, onCopy, onShare, onOpen)
}

/** 综合 Tab：展示结果；生成统一走输入区的「队长总结」。 */
@Composable
private fun SummaryPanel(controller: ArenaSessionController, summary: DiscussionSummary, reviewing: Boolean,
                         onOpen: (ArenaService) -> Unit, copy: ((String, String) -> Unit)?, share: ((String, String) -> Unit)?,
                         onUseSummaryMode: () -> Unit, onRetryOriginal: () -> Unit) {
    var confirmRetry by remember { mutableStateOf(false) }
    if (confirmRetry) ConfirmDialog("按原内容重试综合？",
        "将再次发送上次的完整问题和 ${summary.attachments.size} 个附件。请先打开原网页确认是否已经收到，避免重复发送。", "确认重试",
        onConfirm = { confirmRetry = false; onRetryOriginal() }, onDismiss = { confirmRetry = false })
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (summary.phase == ParticipantPhase.IDLE) {
            HintCard("还没有综合答案。在下方选「队长总结」，挑一位队长和深度，把几家的回答理成一条。",
                if (reviewing) null else "选择队长总结", onUseSummaryMode)
            return@Column
        }
        SimpleSummaryResult(summary, onOpen)
        if (!reviewing) {
            summary.judge?.let { judge ->
                SimpleWaitProgress(controller, judge, ParticipantRun(phase = summary.phase, requestId = summary.requestId, detail = summary.detail),
                    { onOpen(judge) }, summary = true)
            }
            if (summary.prompt.isNotBlank() && summary.attachments.isNotEmpty()) TextButton(onClick = { confirmRetry = true },
                enabled = !controller.isBusy) { Text("按原内容重试") }
        }
        if (summary.text.isNotBlank()) AnswerActions("综合答案", copy?.let { f -> { f("综合答案", summary.text) } },
            share?.let { f -> { f("综合答案", summary.text) } }, summary.judge?.let { judge -> { onOpen(judge) } })
    }
}

@Composable
internal fun SimpleSummaryResult(summary: DiscussionSummary, onOpen: (ArenaService) -> Unit) {
    if (summary.phase == ParticipantPhase.IDLE) return
    val colors = ArenaStyle.colors
    summary.judge?.let { judge ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            SimpleAvatar(judge) { onOpen(judge) }
            Column {
                Text("由 ${judge.shortName} 整理", style = MaterialTheme.typography.titleSmall, color = colors.ink)
                Text("队长总结 · ${summary.depth.displayName}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
            }
        }
    }
    if (summary.text.isNotBlank()) SelectionContainer { MarkdownText(summary.text) }
    val needsHelp = summary.detail.contains("安全验证") || summary.detail.contains("迟迟没有回应")
    val placeholder = summary.phase == ParticipantPhase.COMPLETE && SummarySanityPolicy.looksLikePlaceholder(summary.text)
    when {
        summary.phase == ParticipantPhase.ERROR || needsHelp -> SimpleNotice(summary.detail)
        placeholder -> SimpleNotice("总结好像没有正文，请打开原网页查看，或换一家 AI 重新生成。")
        summary.phase != ParticipantPhase.COMPLETE -> Text("正在整理…", style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
    if (summary.phase == ParticipantPhase.ERROR || needsHelp || placeholder) summary.judge?.let { judge ->
        TextButton(onClick = { onOpen(judge) }) { Text("打开 ${judge.shortName} 网页") }
    }
}

@Composable
internal fun SimpleWaitProgress(controller: ArenaSessionController, service: ArenaService, run: ParticipantRun,
                                onOpen: () -> Unit, summary: Boolean = false, onSkip: (() -> Unit)? = null) {
    val active = run.phase in setOf(ParticipantPhase.QUEUED, ParticipantPhase.SENDING, ParticipantPhase.WAITING, ParticipantPhase.STREAMING)
    var now by remember(run.requestId) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(run.requestId, active) {
        while (active) { now = SystemClock.elapsedRealtime(); delay(1_000L) }
    }
    val progress = if (summary) controller.summaryWaitingProgress(now) else controller.waitingProgress(service, now)
    if (!active || progress == null) return
    val colors = ArenaStyle.colors
    Surface(Modifier.fillMaxWidth().testTag("wait-progress-${service.name}"), color = colors.card, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                Text(progress.title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text(progress.elapsed, style = MaterialTheme.typography.labelSmall)
            }
            Text(progress.expectation, style = MaterialTheme.typography.labelSmall, color = colors.muted)
            Text(progress.limit, style = MaterialTheme.typography.labelSmall, color = colors.muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (progress.needsAttention) TextButton(onClick = onOpen) { Text("等待较久，打开官网核对", style = MaterialTheme.typography.labelSmall) }
                Spacer(Modifier.weight(1f))
                // 逃生通道：这一家明显卡住时只停它，其他成员照常答完。
                if (onSkip != null) TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp).testTag("skip-${service.name}")) {
                    Text("跳过这家", style = MaterialTheme.typography.labelMedium, color = colors.muted)
                }
            }
        }
    }
}

/** 「发给 3 家 AI（含新换上的 Kimi）」。 */
internal fun membersLabel(members: List<ArenaService>, newcomers: Set<ArenaService>): String {
    val joined = members.filter { it in newcomers }
    return "${members.size} 家 AI" + if (joined.isEmpty()) "" else "（含新换上的 ${joined.joinToString("、") { it.shortName }}）"
}
