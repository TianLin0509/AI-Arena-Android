package com.tianlin.aiarena

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// 进行中 / 结果页
//
// 状态行原地展开各家回答；追问输入与三个操作集中放在成员列表下方。
// ---------------------------------------------------------------------------

/** 新会话始终在右上角；只有尚在收取回答时才确认停止，历史由 reset 保存。 */
@Composable
internal fun RoundHeader(title: String, narration: String, busy: Boolean, onNewSession: () -> Unit) {
    val colors = ArenaStyle.colors
    var confirmNewSession by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ArenaHeading(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
            TextButton(
                modifier = Modifier.testTag("new-session").heightIn(min = ArenaStyle.metrics.minTouch),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                onClick = { if (busy) confirmNewSession = true else onNewSession() },
            ) {
                Text(
                    text = "新会话",
                    color = colors.onHero,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                )
            }
        }
        Text(
            text = narration,
            color = colors.onHeroMuted,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (confirmNewSession) {
        ConfirmDialog(
            title = "开始新会话？",
            text = "将停止等待本轮回答，已收到的内容会保留在历史中。",
            confirmLabel = "开始新会话",
            onConfirm = { confirmNewSession = false; onNewSession() },
            onDismiss = { confirmNewSession = false },
        )
    }
}

@Composable
internal fun RoundStage(
    statuses: Map<ArenaService, ServiceStatus>,
    sessionController: ArenaSessionController,
    selectedServices: List<ArenaService>,
    usableCount: Int,
    completedCount: Int,
    sessionStage: SessionStage,
    answerMode: AnswerMode,
    roundGuidance: String,
    onRoundGuidanceChange: (String) -> Unit,
    expandedAnswers: MutableMap<String, Boolean>,
    onNewSession: () -> Unit,
    onOpenService: (ArenaService) -> Unit,
    snackbarHostState: SnackbarHostState,
    copyText: TextCopyRequest?,
    shareText: TextShareRequest?,
    offline: Boolean,
    /** 记住上次选的队长和总结深度。 */
    captainPreferences: ArenaCaptainPreferences,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val scope = rememberCoroutineScope()
    val members = selectedServices
    val activeServices = members.filter { sessionController.runs[it]?.requestId?.isNotBlank() == true }
    val trackedServices = activeServices.ifEmpty { members }
    val phases = trackedServices.associateWith { sessionController.runs[it]?.phase ?: ParticipantPhase.IDLE }
    val settledCount = phases.values.count { it == ParticipantPhase.COMPLETE || it == ParticipantPhase.ERROR }
    val roundCompleted = phases.values.count { it == ParticipantPhase.COMPLETE }
    val roundFailed = phases.values.count { it == ParticipantPhase.ERROR }
    val waitingNames = phases.filterValues {
        it == ParticipantPhase.QUEUED || it == ParticipantPhase.SENDING || it == ParticipantPhase.WAITING ||
            it == ParticipantPhase.STREAMING || it == ParticipantPhase.IDLE
    }.keys.map { it.shortName }
    val roundRunning = sessionStage == SessionStage.INITIAL ||
        sessionStage == SessionStage.ITERATION ||
        sessionStage == SessionStage.DEBATE
    val busy = sessionController.isBusy
    val narration = if (busy && !roundRunning) {
        // 总结 / 单家补救进行中：控制器的文案已经是"正在请 X 做标准总结"这类人话
        sessionController.sessionMessage
    } else {
        RoundNarration.describe(
            busy = roundRunning,
            kind = sessionController.currentRoundKind,
            roundNumber = sessionController.roundNumber,
            total = trackedServices.size,
            completed = roundCompleted,
            failed = roundFailed,
            waitingNames = waitingNames,
        )
    }
    // 控制器偶尔会写一些叙述之外的重要信息（已恢复上次讨论、引用被压缩、上下文超限）。
    val message = sessionController.sessionMessage
    val extraNote = message.takeIf {
        !busy && it != "等待开始" && !it.startsWith("第 ") && it != narration
    }

    // ---- 队长总结的两个选择：选队长、选深度（记住上次的） ----
    var captainName by rememberSaveable { mutableStateOf(captainPreferences.loadCaptain()?.name) }
    var depthName by rememberSaveable { mutableStateOf(captainPreferences.loadDepth().name) }
    val captain = CaptainPolicy.resolve(ArenaService.fromName(captainName), members)
    val depth = SummaryDepth.fromName(depthName)
    val summary = sessionController.summary
    val summarizing = summary.phase == ParticipantPhase.SENDING ||
        summary.phase == ParticipantPhase.WAITING ||
        summary.phase == ParticipantPhase.STREAMING
    val canSummarize = sessionStage == SessionStage.READY && completedCount >= ArenaService.MIN_MEMBERS && !busy
    // 历史里已有总结时直接展开；选择与展开状态跟随当前问题保存。
    var summaryExpanded by rememberSaveable(sessionController.askedAtMillis) {
        mutableStateOf(summary.phase != ParticipantPhase.IDLE)
    }

    val startSummary: () -> Unit = {
        captainPreferences.saveCaptain(captain)
        captainPreferences.saveDepth(depth)
        if (sessionController.startSummary(CaptainPolicy.judgePreference(members, captain), roundGuidance, depth)) {
            onRoundGuidanceChange("")
            summaryExpanded = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.page)
            .imePadding(),
    ) {
        ArenaHero(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = metrics.gutter, end = metrics.gutter, top = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RoundHeader(
                    title = sessionController.currentRoundKind?.displayName ?: "AI 圆桌",
                    narration = narration,
                    busy = busy,
                    onNewSession = onNewSession,
                )
                if (busy) {
                    ArenaProgressBar(
                        progress = if (roundRunning && trackedServices.isNotEmpty()) {
                            settledCount.toFloat() / trackedServices.size
                        } else {
                            null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        height = 4.dp,
                        track = if (metrics.flatSurfaces) colors.accentSoft else colors.onHero.copy(alpha = 0.22f),
                        indicator = if (metrics.flatSurfaces) colors.accent else colors.onHero,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = metrics.gutter,
                end = metrics.gutter,
                top = 6.dp,
                bottom = 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            if (offline) {
                item(key = "offline") {
                    ArenaNotice(
                        tone = NoticeTone.WARNING,
                        title = "网络没有连上",
                        // 轮次还在跑时「重发」是灰的（一次只跑一条自动化），得先「停止等待」。
                        // 断网实测：缓存过的网页会把问题"发出去"然后一直等，家人若不知道要先停就会卡住。
                        text = if (roundRunning) {
                            "AI 的回答需要联网。请打开 Wi-Fi 或手机流量，连上后先点「停止等待」，再对没成功的 AI 点「重发」。"
                        } else {
                            "AI 的回答需要联网。请打开 Wi-Fi 或手机流量，连上后再点「重发」。"
                        },
                    )
                }
            }
            if (extraNote != null) {
                item(key = "note") {
                    ArenaNotice(tone = NoticeTone.INFO, text = extraNote)
                }
            }

            item(key = "question") {
                ArenaCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "你的问题",
                            color = colors.muted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = sessionController.originalQuestion,
                            color = colors.ink,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (sessionController.askedAtMillis > 0L) {
                            Text(
                                text = "${formatAskedTime(sessionController.askedAtMillis)} 提问 · ${members.size} 家",
                                color = colors.muted,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            if (busy) {
                item(key = "cancel") {
                    ArenaSecondaryButton(
                        text = "停止等待",
                        onClick = sessionController::cancelCurrentRound,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item(key = "answers") {
                ArenaCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        members.forEachIndexed { index, service ->
                            if (index > 0) HorizontalDivider(color = colors.border)
                            val status = statuses[service] ?: ServiceStatus()
                            val run = sessionController.runs[service] ?: ParticipantRun()
                            ProviderResultCard(
                                service = service,
                                status = status,
                                run = run,
                                expanded = expandedAnswers[service.name] == true,
                                onExpandedChange = { expandedAnswers[service.name] = it },
                                onClick = { onOpenService(service) },
                                onCopy = copyText?.let { copy ->
                                    {
                                        val prepared = ShareTextPolicy.discussionSummary(
                                            sessionController.originalQuestion,
                                            run.response,
                                        )
                                        val copied = copy("${service.displayName} 的回答", prepared.text)
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                when {
                                                    !copied -> "复制失败"
                                                    prepared.truncated -> "回答过长，已截取后复制"
                                                    else -> "已复制 ${service.displayName} 的回答"
                                                },
                                            )
                                        }
                                    }
                                },
                                onShare = shareText?.let { share ->
                                    {
                                        val prepared = ShareTextPolicy.discussionSummary(
                                            sessionController.originalQuestion,
                                            run.response,
                                        )
                                        if (!share("${service.displayName} 的回答", prepared.text)) {
                                            scope.launch { snackbarHostState.showSnackbar("当前设备没有可用的分享方式") }
                                        }
                                    }
                                },
                                recoveryEnabled = sessionStage == SessionStage.READY && !sessionController.isBusy,
                                canReextract = run.requestId.isNotBlank() && !(
                                    run.detail.contains("输入框") ||
                                        run.detail.contains("发送失败") ||
                                        run.detail.contains("重发失败") ||
                                        run.detail.contains("尚未登录") ||
                                        run.detail.contains("注入失败") ||
                                        run.detail.contains("还没来得及发送")
                                    ),
                                onRetrySend = { sessionController.retrySend(service) },
                                onRetryExtraction = { sessionController.retryExtraction(service) },
                                onSkip = { sessionController.skipService(service) },
                            )
                        }
                    }
                }
            }
            item(key = "next-round") {
                NextRoundPanel(
                    guidance = roundGuidance,
                    onGuidanceChange = onRoundGuidanceChange,
                    inputEnabled = !busy,
                    enabled = sessionStage == SessionStage.READY && completedCount >= ArenaService.MIN_MEMBERS && !busy,
                    summaryExpanded = summaryExpanded,
                    onSummary = { summaryExpanded = !summaryExpanded },
                    onIterate = {
                        if (sessionController.startIteration(answerMode, roundGuidance)) onRoundGuidanceChange("")
                    },
                    onDebate = {
                        if (sessionController.startDebate(answerMode, roundGuidance)) onRoundGuidanceChange("")
                    },
                )
            }
            if (summaryExpanded) {
                item(key = "summary-picker") {
                    SummaryPickerCard(
                        members = members,
                        answered = members.filter { sessionController.runs[it]?.phase == ParticipantPhase.COMPLETE },
                        captain = captain,
                        onCaptainChange = { captainName = it.name },
                        depth = depth,
                        onDepthChange = { depthName = it.name },
                        captainModeReading = captain?.let { statuses[it]?.modeReading } ?: AiModeReading(),
                        onOpenCaptainPage = { captain?.let(onOpenService) },
                        canSummarize = canSummarize,
                        summarizing = summarizing,
                        summaryDone = summary.phase == ParticipantPhase.COMPLETE,
                        completedCount = completedCount,
                        onSummarize = startSummary,
                    )
                }
                if (summary.phase != ParticipantPhase.IDLE) {
                    item(key = "summary") {
                        DiscussionSummaryCard(
                            summary = summary,
                            judge = summary.judge,
                            onCopy = copyText?.let { copy ->
                                {
                                    val prepared = ShareTextPolicy.discussionSummary(
                                        sessionController.originalQuestion,
                                        summary.text,
                                    )
                                    val copied = copy("队长总结", prepared.text)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            when {
                                                !copied -> "复制失败"
                                                prepared.truncated -> "总结过长，已截取后复制"
                                                else -> "已复制队长总结"
                                            },
                                        )
                                    }
                                }
                            },
                            onShare = shareText?.let { share ->
                                {
                                    val prepared = ShareTextPolicy.discussionSummary(
                                        sessionController.originalQuestion,
                                        summary.text,
                                    )
                                    if (!share("队长总结", prepared.text)) {
                                        scope.launch { snackbarHostState.showSnackbar("当前设备没有可用的分享方式") }
                                    }
                                }
                            },
                            retryEnabled = canSummarize,
                            onRetry = startSummary,
                            onOpenJudge = summary.judge?.let { judge -> { onOpenService(judge) } },
                        )
                    }
                }
            }

            if (usableCount < ArenaService.MIN_MEMBERS) {
                item(key = "usable-warning") {
                    Text(
                        text = "现在只有 $usableCount 家已登录，下一轮可能无法继续。可以在「设置 → 登录状态」补登录。",
                        color = colors.warning,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }

    }
}

/** 总览卡里每家回答的第一行：去掉 Markdown 标记，只留一句话。 */
internal fun previewLine(markdown: String, maxChars: Int = 60): String {
    val line = markdown.lineSequence()
        .map { raw ->
            raw.trim()
                .trimStart('#', '>', '-', '*', '•', ' ')
                .replace("**", "")
                .replace("`", "")
                .trim()
        }
        .firstOrNull { it.isNotBlank() } ?: return ""
    return if (line.length > maxChars) line.take(maxChars) + "…" else line
}

/** 状态词的短文本，供状态展示与策略测试复用。 */
internal fun runStatusWord(run: ParticipantRun, status: ServiceStatus): String {
    if (run.requestId.isNotBlank() || run.detail != "等待开始") {
        return when (run.phase) {
            ParticipantPhase.IDLE -> "等待"
            ParticipantPhase.QUEUED -> "排队"
            ParticipantPhase.SENDING -> "发送中"
            ParticipantPhase.WAITING -> "等待中"
            ParticipantPhase.STREAMING -> "回答中"
            ParticipantPhase.COMPLETE -> "完成"
            ParticipantPhase.ERROR -> "没成功"
        }
    }
    return when (status.state) {
        ConnectionState.SIGNED_IN -> "就绪"
        ConnectionState.NEEDS_LOGIN -> "要登录"
        ConnectionState.LOADING -> "加载中"
        ConnectionState.ERROR -> "打不开"
        ConnectionState.NOT_LOADED -> "未打开"
    }
}

/**
 * 状态栏小字：回答期间读到的优先（对话页里才有模型名），否则用网页当前的读数；
 * 网页可用却什么都读不到时写"模式 未知"，网页还没打开就不写（那不是"未知"，是还没看）。
 */
internal fun modeCaption(run: ParticipantRun, status: ServiceStatus): String {
    val label = run.modeLabel.ifBlank { AiModePolicy.label(status.modeReading) }
    if (label.isNotBlank()) return label
    return if (status.state == ConnectionState.SIGNED_IN || run.requestId.isNotBlank()) "模式 未知" else ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewRow(
    selected: Boolean,
    onClick: () -> Unit,
    contentDescriptionText: String,
    leading: @Composable () -> Unit,
    title: String,
    caption: String,
    thinkingUsed: Boolean,
    preview: String,
    previewColor: Color,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ArenaStyle.colors
    val background by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else Color.Transparent,
        label = "overview-row",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = contentDescriptionText
                stateDescription = if (selected) "已展开" else "已折叠"
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    color = if (selected) colors.accent else colors.ink,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (thinkingUsed) ThinkingUsedPill()
            }
            if (caption.isNotBlank()) {
                Text(
                    text = caption,
                    color = colors.muted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (preview.isNotBlank()) {
                Text(
                    text = preview,
                    color = previewColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
        ArenaIcon(
            R.drawable.ic_chevron_right,
            tint = colors.muted,
            size = 14.dp,
            modifier = Modifier.rotate(if (selected) -90f else 90f),
        )
    }
}

/** 「已深度思考」小标签：站点自己在这条回答里放了思考过程块，才敢这么说。 */
@Composable
private fun ThinkingUsedPill() {
    val colors = ArenaStyle.colors
    ArenaPill(text = "已深度思考", foreground = colors.debate, background = colors.debateSoft, dot = false)
}

/**
 * 「队长总结」的两个选择：选队长（成员里任一家）、选深度（简明 / 标准 / 深入）。
 * 家人反馈旧总结"比较浅"：现在总结独立成一步，喂给队长的是完整回答，深度决定 prompt 和篇幅。
 */
@Composable
private fun SummaryPickerCard(
    members: List<ArenaService>,
    answered: List<ArenaService>,
    captain: ArenaService?,
    onCaptainChange: (ArenaService) -> Unit,
    depth: SummaryDepth,
    onDepthChange: (SummaryDepth) -> Unit,
    captainModeReading: AiModeReading,
    onOpenCaptainPage: () -> Unit,
    canSummarize: Boolean,
    summarizing: Boolean,
    summaryDone: Boolean,
    completedCount: Int,
    onSummarize: () -> Unit,
) {
    val colors = ArenaStyle.colors
    ArenaCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(text = "队长总结")
            Text(
                text = "选一位 AI 当队长，它会拿到几家的完整回答，替你整合成一条。谁答得最好就让谁当。",
                color = colors.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(text = "选队长", color = colors.ink, style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                members.forEach { service ->
                    CaptainChip(
                        service = service,
                        selected = CaptainPolicy.isCaptain(service, captain),
                        answered = service in answered,
                        enabled = !summarizing,
                        onClick = { onCaptainChange(service) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (captain != null && captain !in answered && answered.isNotEmpty()) {
                Text(
                    text = "${captain.displayName} 这一轮没答上来，会改由 ${answered.first().displayName} 来总结。",
                    color = colors.warning,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(text = "总结深度", color = colors.ink, style = MaterialTheme.typography.labelLarge)
            val depths = SummaryDepth.entries
            ArenaSegmented(
                options = depths.map { it.displayName },
                selectedIndex = depths.indexOf(depth).coerceAtLeast(0),
                onSelect = { onDepthChange(depths[it]) },
                enabled = !summarizing,
                captions = depths.map { it.caption },
                contentDescriptions = depths.map { "总结深度：${it.displayName}" },
            )
            Text(
                text = depth.explanation,
                color = colors.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            // 深入总结靠队长自己核对事实；明确读到它的深度思考关着时提醒一句（读不到不提醒）
            if (depth == SummaryDepth.DEEP && captain != null && AiModePolicy.thinkingOff(captainModeReading)) {
                ArenaNotice(
                    tone = NoticeTone.INFO,
                    title = "${captain.shortName} 现在没开深度思考",
                    text = "深入总结要逐条核对事实，建议先在它的网页里打开「深度思考」再总结，会更细致。不开也能做。",
                    actionLabel = "跳转网页去打开",
                    onAction = onOpenCaptainPage,
                    actionContentDescription = "跳转到 ${captain.displayName} 网页打开深度思考",
                )
            }
            val captainName = captain?.displayName ?: "队长"
            ArenaPrimaryButton(
                text = when {
                    summarizing -> "正在总结…"
                    summaryDone -> "让 $captainName 重新做${depth.displayName}总结"
                    else -> "让 $captainName 做${depth.displayName}总结"
                },
                onClick = onSummarize,
                enabled = canSummarize && captain != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "开始队长总结" },
                containerColor = colors.debateSoft,
                contentColor = colors.debate,
            )
            if (completedCount < ArenaService.MIN_MEMBERS && !summarizing) {
                Text(
                    text = "至少要有 ${ArenaService.MIN_MEMBERS} 家回答成功，才能做队长总结。",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CaptainChip(
    service: ArenaService,
    selected: Boolean,
    answered: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics {
            contentDescription = "${service.displayName}，${if (selected) "当前队长" else "设为队长"}"
        },
        shape = RoundedCornerShape(metrics.controlCorner),
        color = if (selected) colors.accentSoft else colors.surfaceAlt,
        contentColor = if (selected) colors.accent else colors.ink,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BrandAvatar(service = service, size = 24.dp)
            Text(
                text = service.shortName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                // 窄屏 / 大字号下缩字号而不是截断
                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
            )
            Text(
                text = when {
                    selected -> "队长"
                    answered -> "可选"
                    else -> "没回答"
                },
                color = when {
                    selected -> colors.accent
                    answered -> colors.muted
                    else -> colors.warning
                },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun NextRoundPanel(
    guidance: String,
    onGuidanceChange: (String) -> Unit,
    inputEnabled: Boolean,
    enabled: Boolean,
    summaryExpanded: Boolean,
    onSummary: () -> Unit,
    onIterate: () -> Unit,
    onDebate: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("继续追问", color = colors.ink, style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = guidance,
                onValueChange = { onGuidanceChange(it.take(ArenaLimits.MAX_GUIDANCE_CHARS)) },
                enabled = inputEnabled,
                modifier = Modifier.weight(1f).testTag("round-guidance")
                    .semantics { contentDescription = "继续追问" },
                placeholder = {
                    Text(
                        "补充你的要求，或直接点击下方按钮。",
                        color = colors.muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 12.sp,
                            maxFontSize = MaterialTheme.typography.bodySmall.fontSize,
                            stepSize = 0.5.sp,
                        ),
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(metrics.controlCorner),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.card,
                    unfocusedContainerColor = colors.card,
                    disabledContainerColor = colors.card,
                    focusedIndicatorColor = colors.accent,
                    unfocusedIndicatorColor = colors.border,
                    disabledIndicatorColor = colors.border,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.ink,
                    unfocusedTextColor = colors.ink,
                ),
                minLines = 1,
                maxLines = 2,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundAction("队长总结", onSummary, true, Modifier.weight(1f).testTag("summary-toggle")
                .semantics { stateDescription = if (summaryExpanded) "已展开" else "已折叠" }, primary = true)
            RoundAction("观点讨论", onDebate, enabled, Modifier.weight(1f).testTag("round-debate"))
            RoundAction("独立迭代", onIterate, enabled && guidance.isNotBlank(), Modifier.weight(1f).testTag("round-iterate"))
        }
    }
}

@Composable
private fun RoundAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier,
    primary: Boolean = false,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = metrics.minTouch),
        shape = RoundedCornerShape(metrics.controlCorner),
        color = if (primary) colors.accent else colors.debateSoft,
        contentColor = (if (primary) colors.onAccent else colors.debate).copy(alpha = if (enabled) 1f else 0.4f),
    ) {
        Box(Modifier.padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize, stepSize = 0.5.sp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderResultCard(
    service: ArenaService,
    status: ServiceStatus,
    run: ParticipantRun,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onCopy: (() -> Unit)?,
    onShare: (() -> Unit)?,
    recoveryEnabled: Boolean,
    canReextract: Boolean,
    onRetrySend: () -> Unit,
    onRetryExtraction: () -> Unit,
    onSkip: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val rowRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val changeExpanded: (Boolean) -> Unit = { value ->
        onExpandedChange(value)
        if (!value) {
            scope.launch {
                // Folding a long answer must return the reader to its own status row.
                withFrameNanos { }
                rowRequester.bringIntoView()
            }
        }
    }
    val active = run.phase == ParticipantPhase.QUEUED ||
        run.phase == ParticipantPhase.SENDING ||
        run.phase == ParticipantPhase.WAITING ||
        run.phase == ParticipantPhase.STREAMING
    val started = run.requestId.isNotBlank()
    val stalled = run.phase == ParticipantPhase.WAITING && run.detail.contains("迟迟没有回应")
    val failed = run.phase == ParticipantPhase.ERROR && started
    // 千问这类站点会弹滑块 / 验证码，App 只能等；不明说的话家人会以为卡住了（用户反馈 2026-09-06）
    val securityChallenge = run.phase == ParticipantPhase.WAITING && run.detail.contains("安全验证")
    Column(modifier = Modifier.fillMaxWidth()) {
        OverviewRow(
            selected = expanded,
            onClick = { changeExpanded(!expanded) },
            contentDescriptionText = "${service.displayName} 的回答",
            leading = { BrandAvatar(service = service, size = 30.dp) },
            title = service.displayName,
            caption = modeCaption(run, status),
            thinkingUsed = run.thinkingUsed,
            preview = previewLine(run.response).ifBlank {
                if (started || run.detail != "等待开始") run.detail else status.detail
            },
            previewColor = if (failed) colors.error else colors.muted,
            trailing = { if (started) RunStatusPill(run.phase) else StatusPill(status.state) },
            modifier = Modifier.testTag("answer-row-${service.name}").bringIntoViewRequester(rowRequester),
        )
        if (expanded) {
            if (active) {
                ArenaProgressBar(
                    progress = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    height = 3.dp,
                    track = colors.surfaceAlt,
                )
            }

            if (run.response.isNotBlank()) {
                HorizontalDivider(color = colors.border, modifier = Modifier.padding(horizontal = 16.dp))
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MarkdownText(
                        markdown = run.response,
                        color = colors.ink,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth()
                            .testTag("answer-body-${service.name}")
                            .clickable(role = Role.Button, onClickLabel = "收起 ${service.displayName} 的回答") { changeExpanded(false) },
                    )
                    if (run.responseTruncated) {
                        Text(
                            text = "原回答约 ${run.originalResponseLength} 字，这里只保留了前 " +
                                "${ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS} 字；完整内容点「跳转网页」看。",
                            color = colors.warning,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // 动作按内容定宽、放不下就换行：用户手机上「跳转网页」曾被裁成"跳转"（2026-09-06）
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ArenaTextAction(
                            text = "收起",
                            onClick = { changeExpanded(false) },
                            contentDescriptionText = "收起 ${service.displayName} 的回答",
                        )
                        if (onCopy != null) {
                            ArenaTextAction(
                                text = "复制",
                                onClick = onCopy,
                                contentDescriptionText = "复制 ${service.displayName} 的回答",
                            )
                        }
                        if (onShare != null) {
                            ArenaTextAction(
                                text = "分享",
                                onClick = onShare,
                                contentDescriptionText = "分享 ${service.displayName} 的回答",
                            )
                        }
                        ArenaTextAction(
                            text = "跳转网页",
                            onClick = onClick,
                            color = colors.muted,
                            contentDescriptionText = "跳转到 ${service.displayName} 网页",
                        )
                    }
                }
            }

            if (run.response.isBlank()) {
                ArenaTextAction(
                    text = "跳转网页",
                    onClick = onClick,
                    contentDescriptionText = "跳转到 ${service.displayName} 网页",
                )
            }
            if (securityChallenge) {
                ArenaNotice(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    tone = NoticeTone.WARNING,
                    title = "${service.shortName} 要你做一次安全验证",
                    text = "点「跳转网页去验证」，在它的网页里按提示完成滑块或验证码。做完回到这里会自动继续；" +
                        "要是一直没动静，再点「重新提取」。",
                    actionLabel = "跳转网页去验证",
                    onAction = onClick,
                    secondaryLabel = "重新提取",
                    onSecondary = onRetryExtraction,
                    actionContentDescription = "跳转到 ${service.displayName} 网页完成安全验证",
                )
            }
            if (failed || stalled) {
                val advice = ArenaErrorHelp.explain(run.detail, service.displayName)
                ErrorAdviceBox(
                    advice = advice,
                    tone = if (stalled) NoticeTone.WARNING else NoticeTone.ERROR,
                    rawDetail = run.detail,
                    stalled = stalled,
                    service = service,
                    recoveryEnabled = recoveryEnabled,
                    canReextract = canReextract,
                    onRetrySend = onRetrySend,
                    onRetryExtraction = onRetryExtraction,
                    onSkip = onSkip,
                    onOpenPage = onClick,
                )
            }
        }
    }
}

/**
 * 出错时的"怎么办"：一句白话原因 + 一句下一步，按钮按建议排序，主动作用填色按钮。
 * 原始诊断文案缩小放在最下面，方便把截图发给开发者。
 */
@Composable
private fun ErrorAdviceBox(
    advice: ArenaErrorHelp.Advice,
    tone: NoticeTone,
    rawDetail: String,
    stalled: Boolean,
    service: ArenaService,
    recoveryEnabled: Boolean,
    canReextract: Boolean,
    onRetrySend: () -> Unit,
    onRetryExtraction: () -> Unit,
    onSkip: () -> Unit,
    onOpenPage: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val foreground = if (tone == NoticeTone.WARNING) colors.warning else colors.error
    val background = if (tone == NoticeTone.WARNING) colors.warningSoft else colors.errorSoft
    Column(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = background,
            shape = RoundedCornerShape(metrics.controlCorner),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ArenaIcon(
                        if (tone == NoticeTone.WARNING) R.drawable.ic_warning else R.drawable.ic_error,
                        tint = foreground,
                        size = 20.dp,
                    )
                    Text(
                        text = if (stalled) "等得有点久" else "怎么办？",
                        color = foreground,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(text = advice.what, color = colors.ink, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (stalled) "${advice.next} 也可以点上面的「停止等待」，然后再「重发」。" else advice.next,
                    color = colors.ink,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "原因：$rawDetail",
                    color = colors.muted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (stalled) {
            ArenaSecondaryButton(
                text = "打开 ${service.shortName} 的网页看看",
                onClick = onOpenPage,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            val primary = advice.primary
            // 主动作独占一行、其余动作另起一行：以前挤在一行按权重分宽度，手机上「重新提取」会被裁成"重新"，
            // 用户根本猜不出那是什么（2026-09-05 用户反馈）。
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (primary) {
                    ArenaErrorHelp.Action.LOGIN, ArenaErrorHelp.Action.OPEN_PAGE -> ArenaSecondaryButton(
                        text = if (primary == ArenaErrorHelp.Action.LOGIN) "跳转网页登录" else "跳转网页",
                        onClick = onOpenPage,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ArenaErrorHelp.Action.REEXTRACT -> ArenaSecondaryButton(
                        text = "重新提取",
                        onClick = onRetryExtraction,
                        enabled = recoveryEnabled && canReextract,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    else -> ArenaSecondaryButton(
                        text = "重发",
                        onClick = onRetrySend,
                        enabled = recoveryEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (primary != ArenaErrorHelp.Action.RESEND && primary != ArenaErrorHelp.Action.NONE) {
                        ArenaTextAction(
                            text = "重发",
                            onClick = onRetrySend,
                            enabled = recoveryEnabled,
                            contentDescriptionText = "重新发送给 ${service.displayName}",
                        )
                    }
                    if (primary != ArenaErrorHelp.Action.REEXTRACT && canReextract) {
                        ArenaTextAction(
                            text = "重新提取",
                            onClick = onRetryExtraction,
                            enabled = recoveryEnabled,
                            contentDescriptionText = "重新提取 ${service.displayName} 的回答",
                        )
                    }
                    if (primary == ArenaErrorHelp.Action.NONE || primary == ArenaErrorHelp.Action.RESEND ||
                        primary == ArenaErrorHelp.Action.REEXTRACT
                    ) {
                        ArenaTextAction(
                            text = "跳转网页",
                            onClick = onOpenPage,
                            contentDescriptionText = "跳转到 ${service.displayName} 网页",
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    ArenaTextAction(
                        text = "跳过",
                        onClick = onSkip,
                        enabled = recoveryEnabled,
                        color = colors.muted,
                        contentDescriptionText = "跳过 ${service.displayName}",
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscussionSummaryCard(
    summary: DiscussionSummary,
    judge: ArenaService?,
    onCopy: (() -> Unit)?,
    onShare: (() -> Unit)?,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
    onOpenJudge: (() -> Unit)?,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    ArenaCard(
        modifier = Modifier.fillMaxWidth(),
        color = colors.summarySurface,
        borderColor = colors.summaryBorder,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 谁写的总结就放谁的头像（用户反馈 2026-09-06）
                if (judge != null) BrandAvatar(service = judge, size = 36.dp)
                Column(Modifier.weight(1f)) {
                    ArenaHeading(
                        text = "队长总结",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.ink,
                    )
                    Text(
                        text = summary.judge?.let { "由 ${it.displayName} 做的${summary.depth.displayName}总结" }.orEmpty(),
                        color = colors.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                RunStatusPill(summary.phase)
            }
            val placeholder = summary.phase == ParticipantPhase.COMPLETE &&
                SummarySanityPolicy.looksLikePlaceholder(summary.text)
            if (placeholder) {
                // 队长把内容写进了文件 / 只回了一句反问：App 读不到，别让家人以为这就是总结
                ArenaNotice(
                    tone = NoticeTone.WARNING,
                    title = "总结好像没有正文",
                    text = "${judge?.shortName ?: "队长"} 可能把内容写进了文件，或者只回了一句话，App 读不到文件里的字。" +
                        "点「跳转网页」看原文，或者换一位队长再「重新总结」。",
                    actionLabel = if (onOpenJudge != null) "跳转网页" else null,
                    onAction = onOpenJudge,
                    secondaryLabel = if (retryEnabled) "重新总结" else null,
                    onSecondary = if (retryEnabled) onRetry else null,
                    actionContentDescription = judge?.let { "跳转到 ${it.displayName} 网页" },
                )
            }
            // 用户反馈（2026-09-06）：交叉核验卡没必要，只要原汁原味的队长回答
            if (summary.text.isNotBlank()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (onCopy != null) {
                        ArenaTextAction(text = "复制", onClick = onCopy, contentDescriptionText = "复制队长总结")
                    }
                    if (onShare != null) {
                        ArenaTextAction(text = "分享", onClick = onShare, contentDescriptionText = "分享队长总结")
                    }
                    if (judge != null && onOpenJudge != null) {
                        // 总结读起来不对劲（比如队长把内容写成了文件）时，一步跳到它的网页看原文
                        ArenaTextAction(
                            text = "跳转${judge.shortName}网页",
                            onClick = onOpenJudge,
                            color = colors.muted,
                            contentDescriptionText = "跳转到 ${judge.displayName} 网页",
                        )
                    }
                }
                HorizontalDivider(color = colors.summaryBorder)
                MarkdownText(
                    markdown = summary.text,
                    color = colors.ink,
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else if (summary.phase == ParticipantPhase.ERROR) {
                val advice = ArenaErrorHelp.explainSummary(summary.detail, summary.judge?.displayName)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.errorSoft,
                    shape = RoundedCornerShape(metrics.controlCorner),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(text = "怎么办？", color = colors.error, style = MaterialTheme.typography.titleSmall)
                        Text(text = advice.what, color = colors.ink, style = MaterialTheme.typography.bodyMedium)
                        Text(text = advice.next, color = colors.ink, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "原因：${summary.detail}",
                            color = colors.muted,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (advice.primary == ArenaErrorHelp.Action.OPEN_PAGE && onOpenJudge != null) {
                        ArenaSecondaryButton(
                            text = "跳转网页",
                            onClick = onOpenJudge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ArenaSecondaryButton(
                        text = "重新总结",
                        onClick = onRetry,
                        enabled = retryEnabled,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Text(
                    text = summary.detail,
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
