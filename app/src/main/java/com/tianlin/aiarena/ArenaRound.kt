package com.tianlin.aiarena

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// 进行中 / 结果页
// ---------------------------------------------------------------------------

@Composable
internal fun RoundStage(
    pool: ArenaWebViewPool,
    sessionController: ArenaSessionController,
    selectedServices: List<ArenaService>,
    usableServices: List<ArenaService>,
    usableCount: Int,
    completedCount: Int,
    sessionStage: SessionStage,
    answerMode: AnswerMode,
    roundGuidance: String,
    onRoundGuidanceChange: (String) -> Unit,
    expandedAnswers: MutableMap<String, Boolean>,
    onOpenService: (ArenaService) -> Unit,
    snackbarHostState: SnackbarHostState,
    speechState: SpeechPlaybackState?,
    speechPlaybackRequest: SpeechPlaybackRequest?,
    stopSpeech: (() -> Unit)?,
    copyText: TextCopyRequest?,
    shareText: TextShareRequest?,
    offline: Boolean,
    onNewQuestion: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val scope = rememberCoroutineScope()
    val activeServices = selectedServices.filter { sessionController.runs[it]?.requestId?.isNotBlank() == true }
    val trackedServices = activeServices.ifEmpty { selectedServices }
    val phases = trackedServices.associateWith { sessionController.runs[it]?.phase ?: ParticipantPhase.IDLE }
    val settledCount = phases.values.count { it == ParticipantPhase.COMPLETE || it == ParticipantPhase.ERROR }
    val roundCompleted = phases.values.count { it == ParticipantPhase.COMPLETE }
    val roundFailed = phases.values.count { it == ParticipantPhase.ERROR }
    val waitingNames = phases.filterValues {
        it == ParticipantPhase.SENDING || it == ParticipantPhase.WAITING || it == ParticipantPhase.STREAMING || it == ParticipantPhase.IDLE
    }.keys.map { it.shortName }
    val roundRunning = sessionStage == SessionStage.INITIAL ||
        sessionStage == SessionStage.ITERATION ||
        sessionStage == SessionStage.DEBATE
    val busy = sessionController.isBusy
    val narration = if (busy && !roundRunning) {
        // 总结 / 单家补救进行中：控制器的文案已经是"正在请 X 总结"这类人话
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = metrics.gutter,
                end = metrics.gutter,
                top = 6.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            if (offline) {
                item(key = "offline") {
                    ArenaNotice(
                        tone = NoticeTone.WARNING,
                        title = "网络没有连上",
                        text = "AI 的回答需要联网。请打开 Wi-Fi 或手机流量，连上后再点「重发」。",
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

            item(key = "results-title") {
                Text(
                    text = "各家的回答",
                    color = colors.muted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                )
            }

            items(selectedServices, key = { "run-${it.name}" }) { service ->
                val status = pool.statuses[service] ?: ServiceStatus()
                val run = sessionController.runs[service] ?: ParticipantRun()
                ProviderResultCard(
                    service = service,
                    status = status,
                    run = run,
                    expanded = expandedAnswers[service.name] == true,
                    onExpandedChange = { expandedAnswers[service.name] = it },
                    onClick = { onOpenService(service) },
                    isSpeaking = speechState?.activeKey == "answer:${service.name}",
                    onSpeechToggle = speechPlaybackRequest?.let { request ->
                        { request("answer:${service.name}", run.response) }
                    },
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
                    recoveryEnabled = sessionStage == SessionStage.READY && !sessionController.isBusy,
                    canReextract = run.requestId.isNotBlank() && !(
                        run.detail.contains("输入框") ||
                            run.detail.contains("发送失败") ||
                            run.detail.contains("重发失败") ||
                            run.detail.contains("尚未登录") ||
                            run.detail.contains("注入失败")
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

            if (sessionController.summary.phase != ParticipantPhase.IDLE) {
                item(key = "summary") {
                    DiscussionSummaryCard(
                        summary = sessionController.summary,
                        trustSignal = DiscussionTrustPolicy.analyze(
                            question = sessionController.originalQuestion,
                            summary = sessionController.summary.text,
                            providerCount = completedCount,
                        ),
                        isSpeaking = speechState?.activeKey == "summary:${sessionController.summary.requestId}",
                        onSpeechToggle = speechPlaybackRequest?.let { request ->
                            {
                                request(
                                    "summary:${sessionController.summary.requestId}",
                                    sessionController.summary.text,
                                )
                            }
                        },
                        onCopy = copyText?.let { copy ->
                            {
                                val prepared = ShareTextPolicy.discussionSummary(
                                    sessionController.originalQuestion,
                                    sessionController.summary.text,
                                )
                                val copied = copy("讨论总结", prepared.text)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        when {
                                            !copied -> "复制失败"
                                            prepared.truncated -> "总结过长，已截取后复制"
                                            else -> "已复制讨论总结"
                                        },
                                    )
                                }
                            }
                        },
                        onShare = shareText?.let { share ->
                            {
                                val prepared = ShareTextPolicy.discussionSummary(
                                    sessionController.originalQuestion,
                                    sessionController.summary.text,
                                )
                                if (!share("讨论总结", prepared.text)) {
                                    scope.launch { snackbarHostState.showSnackbar("当前设备没有可用的分享方式") }
                                }
                            }
                        },
                        retryEnabled = sessionStage == SessionStage.READY &&
                            completedCount >= ArenaService.MIN_MEMBERS &&
                            !sessionController.isBusy,
                        onRetry = {
                            if (sessionController.startSummary(usableServices, roundGuidance)) {
                                onRoundGuidanceChange("")
                            }
                        },
                        onOpenJudge = sessionController.summary.judge?.let { judge -> { onOpenService(judge) } },
                    )
                }
            }

            if (sessionStage == SessionStage.READY) {
                item(key = "footer-actions") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val summaryDone = sessionController.summary.phase == ParticipantPhase.COMPLETE
                        val canSummarize = completedCount >= ArenaService.MIN_MEMBERS && !sessionController.isBusy
                        if (summaryDone) {
                            ArenaSecondaryButton(
                                text = "重新总结",
                                onClick = {
                                    if (sessionController.startSummary(usableServices, roundGuidance)) {
                                        onRoundGuidanceChange("")
                                    }
                                },
                                enabled = canSummarize,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            ArenaPrimaryButton(
                                text = "讨论总结",
                                onClick = {
                                    if (sessionController.startSummary(usableServices, roundGuidance)) {
                                        onRoundGuidanceChange("")
                                    }
                                },
                                enabled = canSummarize,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        ArenaSecondaryButton(
                            text = "开始新问题",
                            onClick = onNewQuestion,
                            // READY 阶段仍可能有总结或单家补救在跑；此时重置会与
                            // 在途的网页自动化撞在一起。
                            enabled = !sessionController.isBusy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (completedCount < ArenaService.MIN_MEMBERS && !sessionController.isBusy) {
                    item(key = "summary-hint") {
                        Text(
                            text = "至少要有 ${ArenaService.MIN_MEMBERS} 家回答成功，才能做讨论总结。",
                            color = colors.muted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 6.dp),
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
    ArenaCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(text = "继续追问")
            Text(
                text = "「独立迭代」把下面这句话原样发给每家 AI；「观点讨论」会把其他 AI 的观点转给对方，让它们互相评论。",
                color = colors.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = guidance,
                onValueChange = { onGuidanceChange(it.take(ArenaLimits.MAX_GUIDANCE_CHARS)) },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 84.dp),
                placeholder = {
                    Text(
                        text = "想补充什么？独立迭代必填，观点讨论可不填",
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
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    isSpeaking: Boolean,
    onSpeechToggle: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    recoveryEnabled: Boolean,
    canReextract: Boolean,
    onRetrySend: () -> Unit,
    onRetryExtraction: () -> Unit,
    onSkip: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val active = run.phase == ParticipantPhase.SENDING ||
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
    val collapsedLines = 6
    val started = run.requestId.isNotBlank()
    val stalled = run.phase == ParticipantPhase.WAITING && run.detail.contains("迟迟没有回应")
    val failed = run.phase == ParticipantPhase.ERROR && started

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
                    Text(
                        text = service.displayName,
                        color = colors.ink,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = when {
                            failed || stalled -> if (run.response.isNotBlank()) "只收到一部分回答" else "这次没有回答成功"
                            started || run.detail != "等待开始" -> run.detail
                            else -> status.detail
                        },
                        color = if (failed) colors.error else colors.muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                                "${ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS} 字；完整内容点「原网页」看。",
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
                        if (onSpeechToggle != null) {
                            ArenaTextAction(
                                text = if (isSpeaking) "停止" else "朗读",
                                onClick = onSpeechToggle,
                                contentDescriptionText = if (isSpeaking) {
                                    "停止朗读"
                                } else {
                                    "朗读 ${service.displayName} 的回答"
                                },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        ArenaTextAction(
                            text = "原网页",
                            onClick = onClick,
                            color = colors.muted,
                            contentDescriptionText = "打开 ${service.displayName} 原网页",
                        )
                    }
                }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (primary) {
                    ArenaErrorHelp.Action.LOGIN, ArenaErrorHelp.Action.OPEN_PAGE -> ArenaSecondaryButton(
                        text = if (primary == ArenaErrorHelp.Action.LOGIN) "打开网页登录" else "打开网页",
                        onClick = onOpenPage,
                        modifier = Modifier.weight(1.4f),
                    )
                    ArenaErrorHelp.Action.REEXTRACT -> ArenaSecondaryButton(
                        text = "重新提取",
                        onClick = onRetryExtraction,
                        enabled = recoveryEnabled && canReextract,
                        modifier = Modifier.weight(1.4f),
                    )
                    else -> ArenaSecondaryButton(
                        text = "重发",
                        onClick = onRetrySend,
                        enabled = recoveryEnabled,
                        modifier = Modifier.weight(1.2f),
                    )
                }
                if (primary != ArenaErrorHelp.Action.RESEND && primary != ArenaErrorHelp.Action.NONE) {
                    ArenaTextAction(
                        text = "重发",
                        onClick = onRetrySend,
                        enabled = recoveryEnabled,
                        modifier = Modifier.weight(1f),
                        contentDescriptionText = "重新发送给 ${service.displayName}",
                    )
                }
                if (primary == ArenaErrorHelp.Action.NONE) {
                    ArenaTextAction(
                        text = "打开网页",
                        onClick = onOpenPage,
                        modifier = Modifier.weight(1f),
                        contentDescriptionText = "打开 ${service.displayName} 原网页",
                    )
                }
                if (primary != ArenaErrorHelp.Action.REEXTRACT && canReextract) {
                    ArenaTextAction(
                        text = "重新提取",
                        onClick = onRetryExtraction,
                        enabled = recoveryEnabled,
                        modifier = Modifier.weight(1.1f),
                        contentDescriptionText = "重新提取 ${service.displayName} 的回答",
                    )
                }
                ArenaTextAction(
                    text = "跳过",
                    onClick = onSkip,
                    enabled = recoveryEnabled,
                    modifier = Modifier.weight(0.9f),
                    color = colors.muted,
                    contentDescriptionText = "跳过 ${service.displayName}",
                )
            }
        }
    }
}

@Composable
private fun DiscussionSummaryCard(
    summary: DiscussionSummary,
    trustSignal: DiscussionTrustSignal,
    isSpeaking: Boolean,
    onSpeechToggle: (() -> Unit)?,
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
                        text = "讨论总结",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.ink,
                    )
                    Text(
                        text = summary.judge?.let { "由 ${it.displayName} 对比几家的回答后写成" }.orEmpty(),
                        color = colors.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                RunStatusPill(summary.phase)
            }
            if (summary.text.isNotBlank()) {
                TrustSignalPanel(trustSignal)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (onCopy != null) {
                        ArenaTextAction(
                            text = "复制",
                            onClick = onCopy,
                            modifier = Modifier.weight(1f),
                            contentDescriptionText = "复制讨论总结",
                        )
                    }
                    if (onShare != null) {
                        ArenaTextAction(
                            text = "分享",
                            onClick = onShare,
                            modifier = Modifier.weight(1f),
                            contentDescriptionText = "分享讨论总结",
                        )
                    }
                    if (onSpeechToggle != null) {
                        ArenaTextAction(
                            text = if (isSpeaking) "停止" else "朗读",
                            onClick = onSpeechToggle,
                            modifier = Modifier.weight(1f),
                            contentDescriptionText = if (isSpeaking) "停止朗读" else "朗读讨论总结",
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
                            text = "打开网页",
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
private fun TrustSignalPanel(signal: DiscussionTrustSignal) {
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                Triple("${signal.providerCount} 家观点", true, colors.accent),
                Triple(
                    if (signal.consensusReviewed) "共识已提炼" else "未标出共识",
                    signal.consensusReviewed,
                    colors.success,
                ),
                Triple(
                    if (signal.differencesReviewed) "分歧已检查" else "未标出分歧",
                    signal.differencesReviewed,
                    colors.success,
                ),
            ).forEach { (label, ok, tint) ->
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
