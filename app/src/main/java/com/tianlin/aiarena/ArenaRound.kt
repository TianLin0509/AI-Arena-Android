package com.tianlin.aiarena

import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// 进行中 / 结果页
//
// 0.11 起改成"总览卡 + 底部分段切换"（用户 2026-09-06 在四套 mock 里选的 C 方案）：
// 顶部总览卡把每家的状态、模式小字和第一句预览放在一屏里，不点也知道大概；
// 底部分段控件在拇指范围内切到某一家的完整回答或「队长总结」。
// ---------------------------------------------------------------------------

/** 底部分段控件里「队长总结」那一格的 key；成员格用 [ArenaService.name]。 */
private const val SUMMARY_TAB = "summary"

@Composable
internal fun RoundStage(
    pool: ArenaWebViewPool,
    sessionController: ArenaSessionController,
    selectedServices: List<ArenaService>,
    usableCount: Int,
    completedCount: Int,
    sessionStage: SessionStage,
    answerMode: AnswerMode,
    roundGuidance: String,
    onRoundGuidanceChange: (String) -> Unit,
    expandedAnswers: MutableMap<String, Boolean>,
    onOpenService: (ArenaService) -> Unit,
    snackbarHostState: SnackbarHostState,
    copyText: TextCopyRequest?,
    shareText: TextShareRequest?,
    offline: Boolean,
    /** 记住上次选的队长和总结深度。 */
    captainPreferences: ArenaCaptainPreferences,
    onNewQuestion: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val scope = rememberCoroutineScope()
    var confirmNewQuestion by remember { mutableStateOf(false) }
    if (confirmNewQuestion) {
        ConfirmDialog(
            title = "放弃这一轮，开始新问题？",
            text = "还在等 AI 回答。现在开始新问题会停止等待，已经收到的回答会保留在历史里。",
            confirmLabel = "开始新问题",
            onConfirm = {
                confirmNewQuestion = false
                onNewQuestion()
            },
            onDismiss = { confirmNewQuestion = false },
        )
    }
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
    val summaryStatus = when {
        summary.phase == ParticipantPhase.COMPLETE -> "已总结"
        summarizing -> "总结中"
        summary.phase == ParticipantPhase.ERROR -> "没成功"
        canSummarize -> "可做"
        else -> "待答完"
    }

    // ---- 当前看哪一格：新一轮开始切到第一家；打开带总结的历史直接看总结 ----
    var selectedTab by rememberSaveable(sessionController.askedAtMillis) {
        mutableStateOf(
            if (summary.phase == ParticipantPhase.COMPLETE) SUMMARY_TAB else members.firstOrNull()?.name ?: SUMMARY_TAB,
        )
    }
    LaunchedEffect(sessionController.roundNumber, roundRunning) {
        if (roundRunning) selectedTab = members.firstOrNull()?.name ?: SUMMARY_TAB
    }
    val currentTab = if (selectedTab == SUMMARY_TAB || members.any { it.name == selectedTab }) {
        selectedTab
    } else {
        members.firstOrNull()?.name ?: SUMMARY_TAB
    }
    val currentService = members.firstOrNull { it.name == currentTab }

    val startSummary: () -> Unit = {
        captainPreferences.saveCaptain(captain)
        captainPreferences.saveDepth(depth)
        if (sessionController.startSummary(CaptainPolicy.judgePreference(members, captain), roundGuidance, depth)) {
            onRoundGuidanceChange("")
            selectedTab = SUMMARY_TAB
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ArenaHeading(
                            text = sessionController.currentRoundKind?.displayName ?: "AI 圆桌",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                        )
                        Text(
                            text = narration,
                            color = colors.onHeroMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 「新问题」是最常用的动作，放在结果页顶部随手可点；正在等回答时先确认一下
                    ArenaSecondaryButton(
                        text = "新问题",
                        onClick = { if (busy) confirmNewQuestion = true else onNewQuestion() },
                        modifier = Modifier.semantics { contentDescription = "开始新问题" },
                        leading = { ArenaIcon(R.drawable.ic_add, tint = colors.accent, size = 20.dp) },
                    )
                }
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

            item(key = "overview") {
                OverviewCard(
                    members = members,
                    pool = pool,
                    sessionController = sessionController,
                    summaryStatus = summaryStatus,
                    summarySubtitle = when {
                        summary.phase == ParticipantPhase.COMPLETE ->
                            "由 ${summary.judge?.displayName ?: "队长"} 做的${summary.depth.displayName}总结"
                        summarizing -> summary.detail
                        summary.phase == ParticipantPhase.ERROR -> "没成功，可以换个队长再试"
                        canSummarize -> "答完了，选队长和深度就能做"
                        else -> "至少 ${ArenaService.MIN_MEMBERS} 家答完后可做，可选队长和深度"
                    },
                    selectedTab = currentTab,
                    onSelect = { selectedTab = it },
                )
            }

            if (currentService != null) {
                val service = currentService
                val status = pool.statuses[service] ?: ServiceStatus()
                val run = sessionController.runs[service] ?: ParticipantRun()
                item(key = "run-${service.name}") {
                    ProviderResultCard(
                        service = service,
                        status = status,
                        run = run,
                        collapsedLines = DEFAULT_COLLAPSED_LINES,
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
                if (sessionStage == SessionStage.READY && completedCount >= ArenaService.MIN_MEMBERS) {
                    item(key = "next-round") {
                        NextRoundPanel(
                            guidance = roundGuidance,
                            onGuidanceChange = onRoundGuidanceChange,
                            enabled = !sessionController.isBusy,
                            onIterate = {
                                if (sessionController.startIteration(answerMode, roundGuidance)) {
                                    onRoundGuidanceChange("")
                                }
                            },
                            onDebate = {
                                if (sessionController.startDebate(answerMode, roundGuidance)) {
                                    onRoundGuidanceChange("")
                                }
                            },
                        )
                    }
                }
            } else {
                item(key = "summary-picker") {
                    SummaryPickerCard(
                        members = members,
                        answered = members.filter { sessionController.runs[it]?.phase == ParticipantPhase.COMPLETE },
                        captain = captain,
                        onCaptainChange = { captainName = it.name },
                        depth = depth,
                        onDepthChange = { depthName = it.name },
                        captainModeReading = captain?.let { pool.statuses[it]?.modeReading } ?: AiModeReading(),
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
                            trustSignal = DiscussionTrustPolicy.analyze(
                                question = sessionController.originalQuestion,
                                summary = summary.text,
                                providerCount = completedCount,
                            ),
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

            if (sessionStage == SessionStage.READY) {
                item(key = "footer-actions") {
                    ArenaSecondaryButton(
                        text = "开始新问题",
                        onClick = onNewQuestion,
                        // READY 阶段仍可能有总结或单家补救在跑；此时重置会与在途的网页自动化撞在一起。
                        enabled = !sessionController.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
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

        ResultTabBar(
            members = members,
            pool = pool,
            sessionController = sessionController,
            summaryStatus = summaryStatus,
            selectedTab = currentTab,
            onSelect = { selectedTab = it },
        )
    }
}

/** 单家回答默认折叠的行数。Tab 布局下一屏只放一家，可以比以前（6 行）放得开。 */
private const val DEFAULT_COLLAPSED_LINES = 12

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

/** 底部分段控件和总览卡上的状态词：比 [RunStatusPill] 更短，放得进一格。 */
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

@Composable
private fun OverviewCard(
    members: List<ArenaService>,
    pool: ArenaWebViewPool,
    sessionController: ArenaSessionController,
    summaryStatus: String,
    summarySubtitle: String,
    selectedTab: String,
    onSelect: (String) -> Unit,
) {
    val colors = ArenaStyle.colors
    ArenaCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            members.forEachIndexed { index, service ->
                if (index > 0) HorizontalDivider(color = colors.border, modifier = Modifier.padding(horizontal = 14.dp))
                val status = pool.statuses[service] ?: ServiceStatus()
                val run = sessionController.runs[service] ?: ParticipantRun()
                val started = run.requestId.isNotBlank()
                val preview = previewLine(run.response).ifBlank {
                    if (started || run.detail != "等待开始") run.detail else status.detail
                }
                OverviewRow(
                    selected = selectedTab == service.name,
                    onClick = { onSelect(service.name) },
                    contentDescriptionText = "查看 ${service.displayName} 的回答",
                    leading = { BrandAvatar(service = service, size = 30.dp) },
                    title = service.displayName,
                    caption = modeCaption(run, status),
                    thinkingUsed = run.thinkingUsed,
                    preview = preview,
                    previewColor = if (run.phase == ParticipantPhase.ERROR) colors.error else colors.muted,
                    trailing = { if (started) RunStatusPill(run.phase) else StatusPill(status.state) },
                )
            }
            HorizontalDivider(color = colors.border, modifier = Modifier.padding(horizontal = 14.dp))
            OverviewRow(
                selected = selectedTab == SUMMARY_TAB,
                onClick = { onSelect(SUMMARY_TAB) },
                contentDescriptionText = "查看队长总结",
                leading = { SummaryAvatar(size = 30.dp) },
                title = "队长总结",
                caption = "",
                thinkingUsed = false,
                preview = summarySubtitle,
                previewColor = colors.muted,
                trailing = { SummaryStatusPill(summaryStatus) },
            )
        }
    }
}

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
) {
    val colors = ArenaStyle.colors
    val background by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else Color.Transparent,
        label = "overview-row",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .semantics { contentDescription = contentDescriptionText }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
    }
}

/** 「已深度思考」小标签：站点自己在这条回答里放了思考过程块，才敢这么说。 */
@Composable
private fun ThinkingUsedPill() {
    val colors = ArenaStyle.colors
    ArenaPill(text = "已深度思考", foreground = colors.debate, background = colors.debateSoft, dot = false)
}

@Composable
private fun SummaryAvatar(size: androidx.compose.ui.unit.Dp) {
    val colors = ArenaStyle.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(99.dp))
            .background(colors.debateSoft),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "总",
            color = colors.debate,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SummaryStatusPill(status: String) {
    val colors = ArenaStyle.colors
    val (background, foreground) = when (status) {
        "已总结" -> colors.successSoft to colors.success
        "总结中" -> colors.accentSoft to colors.accent
        "没成功" -> colors.errorSoft to colors.error
        "可做" -> colors.debateSoft to colors.debate
        else -> colors.surfaceAlt to colors.muted
    }
    ArenaPill(text = status, foreground = foreground, background = background, pulsing = status == "总结中")
}

/**
 * 底部分段切换：每家一格 + 「总结」一格，格里写名字和一个状态词。
 * 放在底栏正上方、拇指范围内（长辈常见的单手握法）。
 */
@Composable
private fun ResultTabBar(
    members: List<ArenaService>,
    pool: ArenaWebViewPool,
    sessionController: ArenaSessionController,
    summaryStatus: String,
    selectedTab: String,
    onSelect: (String) -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val compact = members.size >= 4
    Surface(color = colors.card, tonalElevation = 0.dp) {
        Column {
            HorizontalDivider(color = colors.border)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                members.forEach { service ->
                    val run = sessionController.runs[service] ?: ParticipantRun()
                    val status = pool.statuses[service] ?: ServiceStatus()
                    val word = runStatusWord(run, status)
                    ResultTabCell(
                        label = service.shortName,
                        caption = word,
                        captionColor = when {
                            run.requestId.isBlank() && run.detail == "等待开始" -> colors.muted
                            run.phase == ParticipantPhase.ERROR -> colors.error
                            run.phase == ParticipantPhase.COMPLETE -> colors.success
                            run.phase == ParticipantPhase.IDLE -> colors.muted
                            else -> colors.accent
                        },
                        selected = selectedTab == service.name,
                        compact = compact,
                        onClick = { onSelect(service.name) },
                        contentDescriptionText = "切换到 ${service.displayName} 的回答",
                        modifier = Modifier.weight(1f),
                    )
                }
                ResultTabCell(
                    label = "总结",
                    caption = summaryStatus,
                    captionColor = when (summaryStatus) {
                        "已总结" -> colors.success
                        "总结中" -> colors.accent
                        "没成功" -> colors.error
                        "可做" -> colors.debate
                        else -> colors.muted
                    },
                    selected = selectedTab == SUMMARY_TAB,
                    compact = compact,
                    onClick = { onSelect(SUMMARY_TAB) },
                    contentDescriptionText = "切换到队长总结",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ResultTabCell(
    label: String,
    caption: String,
    captionColor: Color,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    contentDescriptionText: String,
    modifier: Modifier = Modifier,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val background by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else Color.Transparent,
        label = "tab-bg",
    )
    Surface(
        modifier = modifier
            .heightIn(min = metrics.minTouch)
            .semantics { contentDescription = contentDescriptionText }
            .clip(RoundedCornerShape(metrics.controlCorner))
            .clickable(onClick = onClick),
        color = background,
        shape = RoundedCornerShape(metrics.controlCorner),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                color = if (selected) colors.accent else colors.ink,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = caption,
                color = captionColor,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
            )
        }
    }
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
            } else if (canSummarize) {
                // 「继续追问」（观点讨论 / 独立迭代）在每家回答下面；停在总结页的人未必知道
                Text(
                    text = "想让几家先互相讨论再总结：切到任一家的回答，下面有「观点讨论」。",
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
                overflow = TextOverflow.Ellipsis,
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

private val GUIDANCE_EXAMPLES = listOf("说得再简单些", "重点比较优缺点", "给出具体做法")

@Composable
private fun GuidanceChip(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ArenaStyle.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = "填入要求：$text" },
        shape = RoundedCornerShape(50),
        color = colors.surface,
        contentColor = colors.accent,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NextRoundPanel(
    guidance: String,
    onGuidanceChange: (String) -> Unit,
    enabled: Boolean,
    onIterate: () -> Unit,
    onDebate: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val context = LocalContext.current
    val guidePreferences = remember(context) { ArenaGuidePreferences(context) }
    // 很多人不知道「观点讨论」前可以先写自己的要求（直接点也能跑）。第一次到这里给一条提示，
    // 用户点「知道了」或自己写过要求之后就不再打扰（用户反馈 2026-09-05）。
    var hintSeen by remember { mutableStateOf(guidePreferences.hasSeenRoundGuidanceHint()) }
    val dismissHint = {
        if (!hintSeen) {
            hintSeen = true
            guidePreferences.markRoundGuidanceHintSeen()
        }
    }
    ArenaCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(text = "继续追问")
            if (!hintSeen) {
                ArenaNotice(
                    tone = NoticeTone.INFO,
                    title = "可以先提要求，再让它们讨论",
                    text = "在下面写一句你的要求（比如「说得再简单些」），AI 讨论时会照着做。不写也能直接点「观点讨论」。",
                    actionLabel = "知道了",
                    onAction = dismissHint,
                )
            }
            Text(
                text = "「独立迭代」把下面这句话原样发给每家 AI；「观点讨论」会把其他 AI 的观点转给对方，让它们互相评论。" +
                    "想要一条整合好的结论，去底部的「总结」做队长总结。",
                color = colors.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = guidance,
                onValueChange = {
                    onGuidanceChange(it.take(ArenaLimits.MAX_GUIDANCE_CHARS))
                    if (it.isNotBlank()) dismissHint()
                },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 84.dp),
                placeholder = {
                    Text(
                        text = "你的要求（选填），例如：说得再简单些 / 重点比较优缺点",
                        color = colors.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(metrics.controlCorner),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    disabledContainerColor = colors.surface,
                    focusedIndicatorColor = colors.accent,
                    unfocusedIndicatorColor = if (metrics.flatSurfaces) colors.surface else colors.border,
                    disabledIndicatorColor = colors.surface,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.ink,
                    unfocusedTextColor = colors.ink,
                ),
                maxLines = 4,
            )
            // 三个现成的要求：点一下就填进去，长辈不用自己想措辞
            if (guidance.isBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GUIDANCE_EXAMPLES.forEach { example ->
                        GuidanceChip(
                            text = example,
                            enabled = enabled,
                            onClick = {
                                onGuidanceChange(example)
                                dismissHint()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ArenaPrimaryButton(
                    text = "独立迭代",
                    onClick = onIterate,
                    enabled = guidance.isNotBlank() && enabled,
                    modifier = Modifier.weight(1f),
                )
                ArenaPrimaryButton(
                    text = "观点讨论",
                    onClick = onDebate,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    containerColor = colors.debateSoft,
                    contentColor = colors.debate,
                )
            }
        }
    }
}

@Composable
private fun ProviderResultCard(
    service: ArenaService,
    status: ServiceStatus,
    run: ParticipantRun,
    collapsedLines: Int,
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
    val active = run.phase == ParticipantPhase.QUEUED ||
        run.phase == ParticipantPhase.SENDING ||
        run.phase == ParticipantPhase.WAITING ||
        run.phase == ParticipantPhase.STREAMING
    val accentBorder by animateColorAsState(
        targetValue = when {
            run.phase == ParticipantPhase.ERROR -> colors.error
            run.phase == ParticipantPhase.COMPLETE -> colors.success
            active -> colors.accent
            else -> colors.border
        },
        label = "result-border",
    )
    val started = run.requestId.isNotBlank()
    val stalled = run.phase == ParticipantPhase.WAITING && run.detail.contains("迟迟没有回应")
    val failed = run.phase == ParticipantPhase.ERROR && started
    // 千问这类站点会弹滑块 / 验证码，App 只能等；不明说的话家人会以为卡住了（用户反馈 2026-09-06）
    val securityChallenge = run.phase == ParticipantPhase.WAITING && run.detail.contains("安全验证")
    val mode = modeCaption(run, status)

    ArenaCard(modifier = Modifier.fillMaxWidth(), borderColor = accentBorder) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BrandAvatar(service = service, size = 34.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = service.displayName,
                            color = colors.ink,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (run.thinkingUsed) ThinkingUsedPill()
                    }
                    Text(
                        text = when {
                            stalled -> if (run.response.isNotBlank()) "回答了一部分，后面一直没动静" else "等了很久还没有回答"
                            failed -> if (run.response.isNotBlank()) "只收到一部分回答" else "这次没有回答成功"
                            started || run.detail != "等待开始" -> run.detail
                            else -> status.detail
                        },
                        color = if (failed) colors.error else colors.muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (mode.isNotBlank()) {
                        Text(
                            text = mode,
                            color = colors.muted,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (started) RunStatusPill(run.phase) else StatusPill(status.state)
            }

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
                    // 折叠态同样走 Markdown 渲染：它是默认视图，不能退回无格式纯文本。
                    MarkdownText(
                        markdown = run.response,
                        color = colors.ink,
                        style = MaterialTheme.typography.bodyLarge,
                        collapsedLines = if (expanded) null else collapsedLines,
                    )
                    if (run.responseTruncated) {
                        Text(
                            text = "原回答约 ${run.originalResponseLength} 字，这里只保留了前 " +
                                "${ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS} 字；完整内容点「跳转网页」看。",
                            color = colors.warning,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ArenaTextAction(
                            text = if (expanded) "收起" else "展开全文",
                            onClick = { onExpandedChange(!expanded) },
                            contentDescriptionText = if (expanded) {
                                "收起 ${service.displayName} 的回答"
                            } else {
                                "展开 ${service.displayName} 的完整回答"
                            },
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
                        Spacer(Modifier.weight(1f))
                        ArenaTextAction(
                            text = "跳转网页",
                            onClick = onClick,
                            color = colors.muted,
                            contentDescriptionText = "跳转到 ${service.displayName} 网页",
                        )
                    }
                }
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

@Composable
private fun DiscussionSummaryCard(
    summary: DiscussionSummary,
    trustSignal: DiscussionTrustSignal,
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
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
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
            if (summary.text.isNotBlank()) {
                TrustSignalPanel(trustSignal, summary.depth)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (onCopy != null) {
                        ArenaTextAction(
                            text = "复制",
                            onClick = onCopy,
                            modifier = Modifier.weight(1f),
                            contentDescriptionText = "复制队长总结",
                        )
                    }
                    if (onShare != null) {
                        ArenaTextAction(
                            text = "分享",
                            onClick = onShare,
                            modifier = Modifier.weight(1f),
                            contentDescriptionText = "分享队长总结",
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

@Composable
private fun TrustSignalPanel(signal: DiscussionTrustSignal, depth: SummaryDepth) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(metrics.controlCorner))
            .background(colors.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "交叉核验",
            color = colors.accent,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        // 「简明」档本来就不要求写共识 / 分歧两段，不能拿这两个标签去挑它的毛病
        val chips = buildList {
            add(Triple("${signal.providerCount} 家观点", true, colors.accent))
            if (depth != SummaryDepth.BRIEF) {
                add(
                    Triple(
                        if (signal.consensusReviewed) "共识已提炼" else "未标出共识",
                        signal.consensusReviewed,
                        colors.success,
                    ),
                )
                add(
                    Triple(
                        if (signal.differencesReviewed) "分歧已检查" else "未标出分歧",
                        signal.differencesReviewed,
                        colors.success,
                    ),
                )
            } else {
                add(Triple("简明总结", true, colors.accent))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            chips.forEach { (label, ok, tint) ->
                Surface(
                    modifier = Modifier.weight(1f),
                    color = if (ok) colors.card else colors.warningSoft,
                    shape = RoundedCornerShape(metrics.chipCorner),
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                        color = if (ok) tint else colors.warning,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
        Text(
            text = "待核验提醒：${signal.verificationReminderCount} 处 · ${signal.domainCaution}",
            color = if (signal.verificationReminderCount > 0) colors.warning else colors.muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
