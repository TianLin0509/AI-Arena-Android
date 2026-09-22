package com.tianlin.aiarena

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** A compact header shared by the question and answer pages. */
@Composable
internal fun SimpleHeader(busy: Boolean, onNew: () -> Unit, onNavigate: (RoundtablePage) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    if (confirm) ConfirmDialog(
        title = "开始新会话？", text = "将停止等待本轮回答，已收到的内容会保留在历史中。",
        confirmLabel = "开始新会话", onConfirm = { confirm = false; onNew() }, onDismiss = { confirm = false },
    )
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box {
            SimpleIcon(R.drawable.ic_menu, "历史与设置", { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("历史对话") }, onClick = { menu = false; onNavigate(RoundtablePage.HISTORY) })
                DropdownMenuItem(text = { Text("设置") }, onClick = { menu = false; onNavigate(RoundtablePage.SETTINGS) })
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text("AI 圆桌", style = MaterialTheme.typography.titleSmall)
        }
        SimpleIcon(R.drawable.ic_add, "新提问", { if (busy) confirm = true else onNew() }, Modifier.testTag("new-session"))
    }
}

@Composable
internal fun SimpleIcon(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp).semantics { contentDescription = label }) {
        ArenaIcon(icon, tint = if (enabled) ArenaStyle.colors.ink else ArenaStyle.colors.muted, size = 20.dp)
    }
}

@Composable
internal fun SimpleAvatar(service: ArenaService, onOpen: () -> Unit) {
    IconButton(onClick = onOpen, modifier = Modifier.size(48.dp).testTag("avatar-${service.name}")
        .semantics { contentDescription = "打开 ${service.shortName} 网页" }) {
        BrandAvatar(service = service, size = 32.dp)
    }
}

@Composable
internal fun SimpleComposer(
    text: String, onChange: (String) -> Unit, hint: String, scope: String,
    enabled: Boolean, busy: Boolean = false, onSend: () -> Unit,
    onStop: () -> Unit = {}, onDiscuss: (() -> Unit)? = null, onSummary: (() -> Unit)? = null,
) {
    var more by remember { mutableStateOf(false) }
    val colors = ArenaStyle.colors
    Surface(color = colors.page) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            color = colors.card, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                TextField(value = text, onValueChange = onChange, placeholder = { Text(hint) },
                    modifier = Modifier.fillMaxWidth().testTag("simple-composer"), minLines = 1, maxLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = TextFieldDefaults.colors(focusedContainerColor = colors.card, unfocusedContainerColor = colors.card,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(scope, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    if (onDiscuss != null) Box {
                        SimpleIcon(R.drawable.ic_more, "更多讨论方式", { more = true })
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text("让 AI 互相讨论") }, enabled = enabled && !busy,
                                onClick = { more = false; onDiscuss() })
                            if (onSummary != null) DropdownMenuItem(text = { Text("综合一下") }, onClick = { more = false; onSummary() })
                        }
                    }
                    IconButton(onClick = if (busy) onStop else onSend, enabled = busy || enabled,
                        modifier = Modifier.size(48.dp).testTag("simple-send")
                            .semantics { contentDescription = if (busy) "停止等待" else "发送问题" }) {
                        Box(Modifier.size(34.dp).background(if (busy || enabled) colors.accent else colors.surfaceAlt, CircleShape), contentAlignment = Alignment.Center) {
                            ArenaIcon(if (busy) R.drawable.ic_close else R.drawable.ic_send,
                                tint = if (busy || enabled) colors.onAccent else colors.muted, size = 19.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SimpleAskHome(
    question: String, onQuestionChange: (String) -> Unit, selectedServices: List<ArenaService>, usableCount: Int,
    onMembers: () -> Unit, onConnections: () -> Unit, onOpenService: (ArenaService) -> Unit,
    onNavigate: (RoundtablePage) -> Unit, onStart: () -> Unit, onNeedQuestion: () -> Unit, onTooLong: () -> Unit,
    lengthAdvisory: String?, offline: Boolean, crashNotice: ArenaCrashReport?, onCrashDismiss: () -> Unit,
    pendingConnectionCount: Int = 0,
) {
    val colors = ArenaStyle.colors
    Column(Modifier.fillMaxSize().background(colors.page).navigationBarsPadding().imePadding()) {
        SimpleHeader(false, { onQuestionChange("") }, onNavigate)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (offline) SimpleNotice("网络未连接，请检查 Wi-Fi 或手机流量。")
            if (crashNotice != null) {
                TextButton(onClick = onCrashDismiss) { Text("上次异常退出，历史已保留 · 知道了", style = MaterialTheme.typography.bodySmall) }
            }
            ArenaIcon(R.drawable.ic_roundtable, tint = colors.accent, size = 38.dp)
            Spacer(Modifier.height(16.dp))
            Text("想听听不同的答案？", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("一个问题，一起问。", color = colors.muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                selectedServices.forEach { service -> SimpleAvatar(service) { onOpenService(service) } }
                TextButton(onClick = onMembers) { Text("更换", style = MaterialTheme.typography.bodySmall) }
            }
            if (usableCount + pendingConnectionCount < ArenaService.MIN_MEMBERS) TextButton(onClick = onConnections) { Text("先登录至少两家 AI") }
            if (question.isBlank()) TextButton(onClick = { onQuestionChange("每天只有 30 分钟，怎么把英语口语练起来？") }) {
                Text("每天 30 分钟，怎么练好口语？", style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }
        if (lengthAdvisory != null) SimpleNotice(lengthAdvisory)
        SimpleComposer(question, onQuestionChange, "问一个问题…", selectedServices.joinToString(" · ") { it.shortName }, true,
            onSend = {
                when {
                    question.isBlank() -> onNeedQuestion()
                    question.length > ArenaLimits.MAX_QUESTION_CHARS -> onTooLong()
                    usableCount + pendingConnectionCount < ArenaService.MIN_MEMBERS -> onConnections()
                    else -> onStart()
                }
            })
    }
}

@Composable
private fun SimpleNotice(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall, color = ArenaStyle.colors.warning)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SimpleAnswerTabs(members: List<ArenaService>, selected: String, phases: Map<ArenaService, ParticipantPhase>, onSelect: (String) -> Unit) {
    val keys = members.map { it.name } + "summary"
    ScrollableTabRow(selectedTabIndex = keys.indexOf(selected).coerceAtLeast(0), edgePadding = 8.dp,
        containerColor = ArenaStyle.colors.page, contentColor = ArenaStyle.colors.accent,
        indicator = { positions ->
            Box(Modifier.tabIndicatorOffset(positions[keys.indexOf(selected).coerceAtLeast(0)]).height(2.dp), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.width(26.dp).fillMaxHeight().background(ArenaStyle.colors.accent, RoundedCornerShape(2.dp)))
            }
        },
        divider = { HorizontalDivider(color = ArenaStyle.colors.border, thickness = 0.5.dp) }) {
        keys.forEachIndexed { index, key ->
            val failed = members.getOrNull(index)?.let { phases[it] == ParticipantPhase.ERROR } == true
            Tab(selected = selected == key, onClick = { onSelect(key) }, modifier = Modifier.testTag("answer-tab-$key"),
                text = { Text((members.getOrNull(index)?.shortName ?: "综合") + if (failed) " · !" else "",
                    style = MaterialTheme.typography.labelMedium, fontWeight = if (selected == key) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected == key) ArenaStyle.colors.ink else ArenaStyle.colors.muted) })
        }
    }
}

@Composable
internal fun SimpleAnswer(
    service: ArenaService, run: ParticipantRun, status: ServiceStatus, onOpen: () -> Unit,
    onCopy: (() -> Unit)?, onShare: (() -> Unit)?, busy: Boolean,
    onReextract: () -> Unit, onResend: () -> Unit, onSummary: () -> Unit,
) {
    var confirmResend by remember { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    val colors = ArenaStyle.colors
    if (confirmResend) ConfirmDialog("重新发送给 ${service.shortName}？", "会再次发送本轮问题。请先打开网页确认是否已收到或仍在排队，避免重复发送。", "确认重发",
        onConfirm = { confirmResend = false; onResend() }, onDismiss = { confirmResend = false })
    Row(verticalAlignment = Alignment.CenterVertically) {
        SimpleAvatar(service, onOpen)
        Text(service.shortName, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        if (run.phase != ParticipantPhase.COMPLETE) Text(runStatusWord(run, status), style = MaterialTheme.typography.labelSmall,
            color = if (run.phase == ParticipantPhase.ERROR) colors.error else colors.muted)
    }
    if (run.response.isNotBlank()) SelectionContainer(Modifier.testTag("simple-answer-${service.name}")) {
        MarkdownText(run.response, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
    }
    if (run.responseTruncated) {
        SimpleNotice("原回答约 ${run.originalResponseLength} 字，圆桌仅保留前 ${ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS} 字；点击头像查看完整原文。")
    }
    if (run.phase == ParticipantPhase.WAITING && (run.detail.contains("安全验证") || run.detail.contains("迟迟没有回应"))) {
        SimpleNotice(run.detail)
        TextButton(onClick = onOpen) { Text("打开网页处理") }
    }
    if (run.phase == ParticipantPhase.ERROR) {
        val advice = ArenaErrorHelp.explain(run.detail, service.shortName)
        SimpleNotice(advice.what + " " + advice.next)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onOpen) { Text("打开网页") }
            TextButton(onClick = onReextract, enabled = !busy && run.requestId.isNotBlank()) { Text("重新读取") }
        }
        TextButton(onClick = { confirmResend = true }, enabled = !busy) { Text("重发本轮问题") }
        TextButton(onClick = { details = !details }) { Text(if (details) "收起详情" else "错误详情", style = MaterialTheme.typography.bodySmall) }
        if (details) Text(run.detail, style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
    if (run.response.isNotBlank()) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onCopy != null) SimpleIcon(R.drawable.ic_copy, "复制 ${service.shortName} 的回答", onCopy)
        if (onShare != null) TextButton(onClick = onShare) { Text("分享", style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = onSummary) { Text("综合一下", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun SimpleRoundStage(
    statuses: Map<ArenaService, ServiceStatus>, sessionController: ArenaSessionController,
    roundGuidance: String, onRoundGuidanceChange: (String) -> Unit, onNewSession: () -> Unit,
    onNavigate: (RoundtablePage) -> Unit, onOpenService: (ArenaService) -> Unit,
    snackbarHostState: SnackbarHostState, copyText: TextCopyRequest?, shareText: TextShareRequest?,
    offline: Boolean, captainPreferences: ArenaCaptainPreferences,
) {
    val colors = ArenaStyle.colors
    val members = sessionController.sessionServices
    var selected by rememberSaveable(sessionController.askedAtMillis) { mutableStateOf(members.first().name) }
    val current = selected.takeIf { it == "summary" || members.any { m -> m.name == it } } ?: members.first().name
    val currentQuestion = sessionController.currentQuestion
    val busy = sessionController.isBusy
    val ready = sessionController.stage == SessionStage.READY && sessionController.completedCount >= ArenaService.MIN_MEMBERS && !busy
    val scope = rememberCoroutineScope()
    val scrollStates = rememberSaveableStateHolder()
    fun notifyFailure(success: Boolean) { if (!success) scope.launch { snackbarHostState.showSnackbar(sessionController.sessionMessage) } }
    val summarize: () -> Unit = { selected = "summary" }
    Column(Modifier.fillMaxSize().background(colors.page).navigationBarsPadding().imePadding()) {
        SimpleHeader(busy, onNewSession, onNavigate)
        SimpleAnswerTabs(members, current, sessionController.runs.mapValues { it.value.phase }) { selected = it }
        if (offline) SimpleNotice("网络未连接；已收到的回答仍可阅读。")
        sessionController.storageWarning?.let { SimpleNotice(it) }
        if (listOf("压缩", "截取", "预算", "上限").any { sessionController.sessionMessage.contains(it) }) {
            SimpleNotice(sessionController.sessionMessage)
        }
        scrollStates.SaveableStateProvider("${sessionController.askedAtMillis}-$current") {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("answer-scroll"), state = rememberLazyListState(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("question") {
                    Surface(Modifier.fillMaxWidth().padding(start = 20.dp), color = colors.card, shape = RoundedCornerShape(14.dp)) {
                        SelectionContainer {
                            Text(if (current == "summary") "讨论主题：${sessionController.originalQuestion}" else currentQuestion,
                                Modifier.padding(13.dp).testTag("current-question"), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (current == "summary") item("summary") {
                    SimpleSummary(sessionController, members, captainPreferences, ready, roundGuidance,
                        onStarted = { onRoundGuidanceChange("") }, onOpenService, copyText, shareText, snackbarHostState)
                } else item("answer") {
                    val service = members.first { it.name == current }
                    val run = sessionController.runs[service] ?: ParticipantRun()
                    Column {
                        SimpleAnswer(service, run, statuses[service] ?: ServiceStatus(), { onOpenService(service) },
                            onCopy = copyText?.let { copy -> { scope.launch {
                                val prepared = ShareTextPolicy.discussionSummary(currentQuestion, run.response)
                                val ok = copy("${service.shortName} 的回答", prepared.text)
                                snackbarHostState.showSnackbar(if (!ok) "复制失败" else if (prepared.truncated) "回答过长，已截取后复制" else "已复制")
                            }; Unit } },
                            onShare = shareText?.let { share -> { scope.launch {
                                val prepared = ShareTextPolicy.discussionSummary(currentQuestion, run.response)
                                if (!share("${service.shortName} 的回答", prepared.text)) snackbarHostState.showSnackbar("分享失败")
                                else if (prepared.truncated) snackbarHostState.showSnackbar("回答过长，已截取后分享")
                            }; Unit } }, busy = busy,
                            onReextract = { notifyFailure(sessionController.retryExtraction(service)) },
                            onResend = {
                                if (sessionController.lastRoundAttachments.isNotEmpty()) scope.launch {
                                    snackbarHostState.showSnackbar("这条历史包含附件，请在原网页手动上传并发送。圆桌不会自动重传。")
                                } else notifyFailure(sessionController.retrySend(service))
                            }, onSummary = summarize)
                    }
                }
            }
        }
        SimpleComposer(roundGuidance, onRoundGuidanceChange, "继续追问…",
            "发给本轮成功的 ${sessionController.completedCount} 家 AI", ready, busy,
            onSend = {
                if (roundGuidance.isBlank()) scope.launch { snackbarHostState.showSnackbar("先写下想追问的内容") }
                else if (sessionController.startIteration(AnswerMode.PARALLEL, roundGuidance)) onRoundGuidanceChange("")
                else notifyFailure(false)
            }, onStop = sessionController::cancelCurrentRound,
            onDiscuss = {
                if (roundGuidance.length > ArenaLimits.MAX_GUIDANCE_CHARS) scope.launch {
                    snackbarHostState.showSnackbar("本轮要求超过 ${ArenaLimits.MAX_GUIDANCE_CHARS} 字，请缩短后重试")
                }
                else if (sessionController.startDebate(AnswerMode.PARALLEL, roundGuidance)) onRoundGuidanceChange("")
                else notifyFailure(false)
            }, onSummary = summarize)
    }
}

@Composable
internal fun SimpleSummary(
    controller: ArenaSessionController, members: List<ArenaService>, preferences: ArenaCaptainPreferences,
    ready: Boolean, guidance: String, onStarted: () -> Unit, onOpen: (ArenaService) -> Unit,
    copy: TextCopyRequest?, share: TextShareRequest?, snackbar: SnackbarHostState,
) {
    var captainName by rememberSaveable { mutableStateOf(preferences.loadCaptain()?.name) }
    var depthName by rememberSaveable { mutableStateOf(preferences.loadDepth().name) }
    var options by rememberSaveable { mutableStateOf(false) }
    val captain = CaptainPolicy.resolve(ArenaService.fromName(captainName), members)
    val depth = SummaryDepth.fromName(depthName)
    val summary = controller.summary
    val nextJudge = CaptainPolicy.judgePreference(members, captain).firstOrNull {
        controller.runs[it]?.let { run -> run.phase == ParticipantPhase.COMPLETE && run.response.isNotBlank() } == true
    }
    val scope = rememberCoroutineScope()
    val start = {
        preferences.saveCaptain(captain); preferences.saveDepth(depth)
        if (guidance.length > ArenaLimits.MAX_GUIDANCE_CHARS) scope.launch {
            snackbar.showSnackbar("本轮要求超过 ${ArenaLimits.MAX_GUIDANCE_CHARS} 字，请缩短后重试")
        }
        else if (controller.startSummary(CaptainPolicy.judgePreference(members, captain), guidance, depth)) onStarted()
        else scope.launch { snackbar.showSnackbar(controller.sessionMessage) }
        Unit
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (summary.phase == ParticipantPhase.IDLE) {
            Spacer(Modifier.height(16.dp))
            Text("把几家的答案，理成一条", style = MaterialTheme.typography.titleMedium)
            Text("需要时再生成，保留共识，也留下分歧。", style = MaterialTheme.typography.bodySmall, color = ArenaStyle.colors.muted)
        }
        TextButton(onClick = { options = !options }, enabled = !controller.isBusy) {
            Text("下次由 ${nextJudge?.shortName ?: captain?.shortName ?: "AI"} 整理 · ${depth.displayName}", style = MaterialTheme.typography.bodySmall)
        }
        if (ready && nextJudge != null && nextJudge != captain) {
            SimpleNotice("${captain?.shortName} 本轮未完成，将由 ${nextJudge.shortName} 整理。")
        }
        if (options) {
            members.forEach { service ->
                TextButton(onClick = { captainName = service.name }, enabled = !controller.isBusy) {
                    Text((if (captain == service) "✓ " else "") + service.shortName)
                }
            }
            SummaryDepth.entries.forEach { item ->
                TextButton(onClick = { depthName = item.name }, enabled = !controller.isBusy) {
                    Text((if (depth == item) "✓ " else "") + item.displayName)
                }
            }
        }
        SimpleSummaryResult(summary, onOpen)
        Button(onClick = start, enabled = ready) { Text(if (summary.phase == ParticipantPhase.IDLE) "生成综合答案" else "重新生成") }
        if (!ready && !controller.isBusy) Text("至少两家回答完成后可生成。", style = MaterialTheme.typography.bodySmall)
        if (summary.text.isNotBlank()) Row {
            if (copy != null) TextButton(onClick = {
                val prepared = ShareTextPolicy.discussionSummary(controller.originalQuestion, summary.text)
                val ok = copy("综合答案", prepared.text)
                scope.launch { snackbar.showSnackbar(if (!ok) "复制失败" else if (prepared.truncated) "内容过长，已截取后复制" else "已复制") }
            }) { Text("复制") }
            if (share != null) TextButton(onClick = {
                val prepared = ShareTextPolicy.discussionSummary(controller.originalQuestion, summary.text)
                val ok = share("综合答案", prepared.text)
                if (!ok || prepared.truncated) scope.launch { snackbar.showSnackbar(if (!ok) "分享失败" else "内容过长，已截取后分享") }
            }) { Text("分享") }
        }
    }
}

@Composable
internal fun SimpleSummaryResult(summary: DiscussionSummary, onOpen: (ArenaService) -> Unit) {
    if (summary.phase == ParticipantPhase.IDLE) return
    summary.judge?.let { judge ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            SimpleAvatar(judge) { onOpen(judge) }
            Text("由 ${judge.shortName} 整理 · ${summary.depth.displayName}", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (summary.text.isNotBlank()) SelectionContainer { MarkdownText(summary.text) }
    val needsHelp = summary.detail.contains("安全验证") || summary.detail.contains("迟迟没有回应")
    val placeholder = summary.phase == ParticipantPhase.COMPLETE && SummarySanityPolicy.looksLikePlaceholder(summary.text)
    when {
        summary.phase == ParticipantPhase.ERROR || needsHelp -> SimpleNotice(summary.detail)
        placeholder -> SimpleNotice("总结好像没有正文，请打开原网页查看，或换一家 AI 重新生成。")
        summary.phase != ParticipantPhase.COMPLETE -> Text("正在整理…", style = MaterialTheme.typography.bodySmall, color = ArenaStyle.colors.muted)
    }
    if (summary.phase == ParticipantPhase.ERROR || needsHelp || placeholder) summary.judge?.let { judge ->
        TextButton(onClick = { onOpen(judge) }) { Text("打开 ${judge.shortName} 网页") }
    }
}

@Composable
internal fun SimpleSettingsPage(
    selectedServices: List<ArenaService>, largeTextEnabled: Boolean, onLargeTextChange: (Boolean) -> Unit,
    onBack: () -> Unit, onMembers: () -> Unit, onConnections: () -> Unit, onReloadPages: () -> Unit,
    onResetSession: () -> Unit, onRestartApp: (() -> Unit)?, onShowOnboarding: () -> Unit,
    updateResult: ArenaUpdateResult?, updateChecking: Boolean, onCheckUpdate: () -> Unit,
    onInstallUpdate: (ArenaUpdateInfo) -> Unit, crashReport: ArenaCrashReport?, onClearCrashReport: () -> Unit,
    onShareCrashReport: ((ArenaCrashReport) -> Unit)?,
) {
    var more by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    if (confirm != null) ConfirmDialog(title = "${confirm}？", text = "历史记录和已登录的 AI 都保留。", confirmLabel = "确认",
        onConfirm = { val action = confirm; confirm = null; if (action == "重启应用") onRestartApp?.invoke() else onResetSession() }, onDismiss = { confirm = null })
    Column(Modifier.fillMaxSize().background(ArenaStyle.colors.page).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars), verticalAlignment = Alignment.CenterVertically) {
            SimpleIcon(R.drawable.ic_arrow_back, "返回圆桌", onBack)
            Text("设置", style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            SimpleSettingRow("AI 成员", selectedServices.joinToString(" · ") { it.shortName }, onMembers)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("大字阅读", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(largeTextEnabled, onLargeTextChange, modifier = Modifier.semantics { contentDescription = "大字阅读" })
            }
            HorizontalDivider(color = ArenaStyle.colors.border)
            SimpleSettingRow(if (more) "收起更多设置" else "更多设置", "", { more = !more })
            if (more) {
                SimpleSettingRow("账号与登录", "", onConnections)
                SimpleSettingRow("帮助与故障处理", "", { help = !help })
                if (help) {
                    Text("点击 AI 头像打开网页，可在网页里手动上传文件。文件只供那一家使用。", style = MaterialTheme.typography.bodySmall, color = ArenaStyle.colors.muted)
                    SimpleSettingRow("重新加载 AI 网页", "", onReloadPages)
                    SimpleSettingRow("清除卡住的讨论", "", { confirm = "清除卡住的讨论" })
                    if (onRestartApp != null) SimpleSettingRow("重启应用", "", { confirm = "重启应用" })
                    SimpleSettingRow("使用说明", "", onShowOnboarding)
                    if (crashReport != null) {
                        if (onShareCrashReport != null) SimpleSettingRow("导出崩溃记录", "", { onShareCrashReport(crashReport) })
                        SimpleSettingRow("清除崩溃记录", "", onClearCrashReport)
                    }
                }
                SimpleSettingRow("关于 AI 圆桌", "v${BuildConfig.VERSION_NAME}", { about = !about })
                if (about) {
                    val available = (updateResult as? ArenaUpdateResult.Available)?.info
                    SimpleSettingRow(if (available != null) "安装 v${available.versionName}" else "检查更新",
                        if (updateChecking) "检查中…" else "", { if (available != null) onInstallUpdate(available) else onCheckUpdate() })
                    val message = when (updateResult) {
                        is ArenaUpdateResult.Failed -> updateResult.reason
                        is ArenaUpdateResult.UpToDate -> "已是最新版本"
                        else -> ""
                    }
                    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
                    Text("无需注册圆桌账号。历史和网页登录保存在本机；问题会发送到所选 AI 的官方网站。AI 回答可能有误，请核实重要信息。",
                        Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = ArenaStyle.colors.muted)
                }
            }
        }
    }
}

@Composable
private fun SimpleSettingRow(title: String, detail: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = ArenaStyle.colors.ink)
        if (detail.isNotBlank()) Text(detail, Modifier.widthIn(max = 150.dp), style = MaterialTheme.typography.bodySmall,
            color = ArenaStyle.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        ArenaIcon(R.drawable.ic_chevron_right, tint = ArenaStyle.colors.muted, size = 16.dp)
    }
}
