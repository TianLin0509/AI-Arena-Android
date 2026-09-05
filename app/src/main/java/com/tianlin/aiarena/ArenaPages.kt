package com.tianlin.aiarena

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// 子页面：统一的页头
// ---------------------------------------------------------------------------

@Composable
internal fun RoundtablePageHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    ArenaHero(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 8.dp, end = metrics.gutter, top = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ArenaBackButton(onClick = onBack, color = colors.onHero)
            Column(Modifier.padding(start = 12.dp)) {
                ArenaHeading(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                )
                Text(
                    text = subtitle,
                    color = colors.onHeroMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val PageContentPadding: @Composable () -> PaddingValues = {
    val metrics = ArenaStyle.metrics
    PaddingValues(start = metrics.gutter, end = metrics.gutter, top = 10.dp, bottom = 28.dp)
}

// ---------------------------------------------------------------------------
// 历史
// ---------------------------------------------------------------------------

@Composable
internal fun RoundtableHistoryPage(
    sessions: List<RecentArenaSession>,
    warning: String?,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.page),
    ) {
        RoundtablePageHeader(
            title = "最近问题",
            subtitle = if (sessions.isEmpty()) "只保存在这台手机上" else "${sessions.size} 条 · 只保存在这台手机上",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PageContentPadding(),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            if (warning != null) {
                item(key = "warning") {
                    ArenaNotice(tone = NoticeTone.ERROR, text = warning)
                }
            }
            if (sessions.isEmpty()) {
                item(key = "empty") {
                    ArenaCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "还没有问过问题",
                                color = colors.ink,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "问过的问题会自动记在这里，随时可以点开接着讨论。",
                                color = colors.muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            } else {
                item(key = "list") {
                    ArenaGroup {
                        sessions.forEachIndexed { index, session ->
                            if (index > 0) ArenaRowDivider()
                            ArenaRow(
                                title = session.title.ifBlank { "未命名问题" },
                                detail = "${formatRecentTime(session.updatedAtMillis)} · " +
                                    "${session.roundCount} 轮 · ${session.serviceCount} 家",
                                trailingText = "继续",
                                trailingColor = colors.accent,
                                onClick = { onRestore(session.id) },
                                contentDescriptionText = "继续讨论：${session.title.ifBlank { "未命名问题" }}",
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 设置
// ---------------------------------------------------------------------------

@Composable
internal fun RoundtableSettingsPage(
    selectedServices: List<ArenaService>,
    usableCount: Int,
    crashReport: ArenaCrashReport?,
    onClearCrashReport: () -> Unit,
    onShareCrashReport: ((ArenaCrashReport) -> Unit)?,
    updateResult: ArenaUpdateResult?,
    updateChecking: Boolean,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (ArenaUpdateInfo) -> Unit,
    largeTextEnabled: Boolean,
    onLargeTextChange: (Boolean) -> Unit,
    speechState: SpeechPlaybackState?,
    stopSpeech: (() -> Unit)?,
    skin: ArenaSkin,
    answerMode: AnswerMode,
    onAnswerModeChange: (AnswerMode) -> Unit,
    onBack: () -> Unit,
    onAppearance: () -> Unit,
    onMembers: () -> Unit,
    onConnections: () -> Unit,
    onReloadPages: () -> Unit,
    onResetSession: () -> Unit,
    onRestartApp: (() -> Unit)?,
    onShowOnboarding: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    var confirmReset by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }

    if (confirmReset) {
        ConfirmDialog(
            title = "清除卡住的讨论？",
            text = "会回到提问页。历史记录和已登录的 AI 都保留。",
            confirmLabel = "清除",
            onConfirm = {
                confirmReset = false
                onResetSession()
            },
            onDismiss = { confirmReset = false },
        )
    }
    if (confirmRestart) {
        ConfirmDialog(
            title = "重启应用？",
            text = "会关掉再重新打开，大约一秒钟。登录不会丢。",
            confirmLabel = "重启",
            onConfirm = {
                confirmRestart = false
                onRestartApp?.invoke()
            },
            onDismiss = { confirmRestart = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.page),
    ) {
        RoundtablePageHeader(
            title = "设置",
            subtitle = "外观、AI 成员、遇到问题时的修复",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PageContentPadding(),
            verticalArrangement = Arrangement.spacedBy(metrics.gap + 4.dp),
        ) {
            item(key = "appearance") {
                ArenaGroup(title = "外观") {
                    ArenaRow(
                        title = "界面风格",
                        detail = "${skin.displayName} · ${skin.tagline}",
                        onClick = onAppearance,
                        contentDescriptionText = "界面风格，更换",
                    )
                    ArenaRowDivider()
                    ArenaSwitchRow(
                        title = "大字模式",
                        detail = if (largeTextEnabled) "已开启，AI 网页也会同步放大" else "整体再放大一档，AI 网页也会放大",
                        checked = largeTextEnabled,
                        onCheckedChange = onLargeTextChange,
                        contentDescriptionText = if (largeTextEnabled) "关闭大字模式" else "开启大字模式",
                    )
                    ArenaRowDivider()
                    ArenaRow(
                        title = "朗读",
                        detail = speechState?.detail ?: "朗读：不可用",
                        chevron = false,
                        trailing = if (speechState?.activeKey != null && stopSpeech != null) {
                            {
                                ArenaTextAction(
                                    text = "停止朗读",
                                    onClick = stopSpeech,
                                    color = colors.error,
                                    contentDescriptionText = "停止朗读",
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }

            item(key = "ai") {
                ArenaGroup(title = "AI") {
                    ArenaRow(
                        title = "AI 成员",
                        detail = selectedServices.joinToString(" · ") { it.shortName },
                        leading = { BrandAvatarStack(services = selectedServices.take(3), size = 22.dp) },
                        onClick = onMembers,
                        contentDescriptionText = "AI 成员，调整",
                    )
                    ArenaRowDivider()
                    ArenaRow(
                        title = "登录状态",
                        detail = "$usableCount / ${selectedServices.size} 家已登录 · 登录一次后自动记住",
                        detailColor = if (usableCount >= ArenaService.MIN_MEMBERS) colors.success else colors.warning,
                        onClick = onConnections,
                        contentDescriptionText = "连接管理，查看",
                    )
                    ArenaRowDivider()
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "回答方式",
                            color = colors.ink,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        ArenaSegmented(
                            options = listOf("同时回答", "依次回答"),
                            captions = listOf("更快", "更稳"),
                            selectedIndex = if (answerMode == AnswerMode.PARALLEL) 0 else 1,
                            onSelect = { index ->
                                onAnswerModeChange(if (index == 0) AnswerMode.PARALLEL else AnswerMode.SERIAL)
                            },
                            contentDescriptions = AnswerMode.entries.map { mode ->
                                "${mode.displayName}，${if (mode == answerMode) "已选择" else "未选择"}"
                            },
                        )
                        Text(
                            text = "网页偶尔不稳定时，选「依次回答」更容易成功。",
                            color = colors.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item(key = "repair") {
                ArenaGroup(title = "遇到问题？按顺序试") {
                    ArenaRow(
                        title = "重新加载 AI 网页",
                        detail = "网页卡住、白屏时先试这个。登录不会丢。",
                        leading = { ArenaIcon(R.drawable.ic_refresh, tint = colors.accent, size = 24.dp) },
                        chevron = false,
                        onClick = onReloadPages,
                        contentDescriptionText = "重新加载所有 AI 网页",
                    )
                    ArenaRowDivider(startIndent = 52.dp)
                    ArenaRow(
                        title = "清除卡住的讨论",
                        detail = "回到提问页。历史记录和登录都保留。",
                        leading = { ArenaIcon(R.drawable.ic_close, tint = colors.accent, size = 24.dp) },
                        chevron = false,
                        onClick = { confirmReset = true },
                        contentDescriptionText = "清除卡住的讨论",
                    )
                    if (onRestartApp != null) {
                        ArenaRowDivider(startIndent = 52.dp)
                        ArenaRow(
                            title = "重启应用",
                            detail = "关掉再打开，大多数问题都能解决。",
                            leading = { ArenaIcon(R.drawable.ic_open_in_new, tint = colors.accent, size = 24.dp) },
                            chevron = false,
                            onClick = { confirmRestart = true },
                            contentDescriptionText = "重启应用",
                        )
                    }
                    ArenaRowDivider(startIndent = 52.dp)
                    ArenaRow(
                        title = "使用说明",
                        detail = "再看一遍三步引导",
                        leading = { ArenaIcon(R.drawable.ic_info, tint = colors.accent, size = 24.dp) },
                        onClick = onShowOnboarding,
                        contentDescriptionText = "查看使用说明",
                    )
                }
            }

            if (crashReport != null) {
                item(key = "crash") {
                    ArenaNotice(
                        tone = NoticeTone.ERROR,
                        title = "上次异常退出",
                        text = "${ArenaCrashReporter.formatTime(crashReport.recordedAtMillis)}" +
                            " · 记录只保存在本机，不会自动上传。导出后发给开发者可以帮助定位问题。",
                        actionLabel = if (onShareCrashReport != null) "导出" else "清除",
                        onAction = if (onShareCrashReport != null) {
                            { onShareCrashReport(crashReport) }
                        } else {
                            onClearCrashReport
                        },
                        secondaryLabel = if (onShareCrashReport != null) "清除" else null,
                        onSecondary = if (onShareCrashReport != null) onClearCrashReport else null,
                        actionContentDescription = if (onShareCrashReport != null) "导出上次崩溃记录" else "清除崩溃记录",
                    )
                }
            }

            item(key = "about") {
                val available = (updateResult as? ArenaUpdateResult.Available)?.info
                ArenaGroup(title = "关于") {
                    ArenaRow(
                        title = if (available != null) "有新版本 v${available.versionName}" else "版本更新",
                        titleColor = if (available != null) colors.accent else colors.ink,
                        detail = when (val result = updateResult) {
                            null -> if (updateChecking) "正在检查…" else "当前 v${BuildConfig.VERSION_NAME}。新版本发布在自建站上，不经过应用商店。"
                            is ArenaUpdateResult.Available -> listOfNotNull(
                                result.info.sizeLabel.takeIf { it.isNotBlank() }?.let { "大小 $it" },
                                result.info.releasedAt.takeIf { it.isNotBlank() }?.let { "发布于 $it" },
                                result.info.notes.takeIf { it.isNotBlank() },
                            ).joinToString(" · ").ifBlank { "覆盖安装，已登录的 AI 不用重新登录" }
                            is ArenaUpdateResult.UpToDate -> "已是最新版本 v${BuildConfig.VERSION_NAME}"
                            is ArenaUpdateResult.Failed -> result.reason
                        },
                        trailingText = when {
                            updateChecking -> "检查中…"
                            available != null -> "下载安装"
                            else -> "检查更新"
                        },
                        trailingColor = colors.accent,
                        chevron = false,
                        onClick = {
                            if (available != null) onInstallUpdate(available) else onCheckUpdate()
                        },
                        contentDescriptionText = if (available != null) "下载并安装新版本" else "检查是否有新版本",
                    )
                    ArenaRowDivider()
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "隐私说明",
                            color = colors.ink,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "无需注册圆桌账号；成员选择、讨论记录和网页登录信息都只保存在本机，" +
                                "App 不会读取或上传 Cookie。",
                            color = colors.muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "本应用不是任何 AI 厂商的官方客户端，它通过各家的网页版工作。" +
                                "自动化操作可能被厂商判定为异常访问，请知悉风险。",
                            color = colors.muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item(key = "version") {
                Text(
                    text = "AI 圆桌 v${BuildConfig.VERSION_NAME} · 版本代码 ${BuildConfig.VERSION_CODE}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 唯一允许的弹窗：只用于"清除 / 重启"这两个需要再确认一下的动作。 */
@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(metrics.cardCorner),
        title = { Text(text = title, color = colors.ink, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text = text, color = colors.muted, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            ArenaPrimaryButton(text = confirmLabel, onClick = onConfirm)
        },
        dismissButton = {
            ArenaTextAction(text = "取消", onClick = onDismiss, color = colors.muted)
        },
    )
}

// ---------------------------------------------------------------------------
// 界面风格
// ---------------------------------------------------------------------------

@Composable
internal fun RoundtableAppearancePage(
    skin: ArenaSkin,
    onSkinChange: (ArenaSkin) -> Unit,
    largeTextEnabled: Boolean,
    onLargeTextChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.page),
    ) {
        RoundtablePageHeader(
            title = "界面风格",
            subtitle = "换一套配色和排版，立即生效",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PageContentPadding(),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            item(key = "picker") {
                SkinPicker(selected = skin, onSelect = onSkinChange)
            }
            item(key = "preview-note") {
                Text(
                    text = "选择后整个 App 立即切换，包括按钮大小、圆角和描边粗细。" +
                        "「长辈」风格会同时放大字号并加粗描边；「夜航」适合夜里看。",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item(key = "large-text") {
                ArenaGroup {
                    ArenaSwitchRow(
                        title = "大字模式",
                        detail = if (largeTextEnabled) {
                            "已开启，AI 网页也会同步放大"
                        } else {
                            "在当前风格基础上再放大一档"
                        },
                        checked = largeTextEnabled,
                        onCheckedChange = onLargeTextChange,
                        contentDescriptionText = if (largeTextEnabled) "关闭大字模式" else "开启大字模式",
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 成员
// ---------------------------------------------------------------------------

@Composable
internal fun RoundtableMembersPage(
    selectedServices: List<ArenaService>,
    loginNeededServices: List<ArenaService>,
    statuses: Map<ArenaService, ServiceStatus>,
    onToggle: (ArenaService) -> Unit,
    onOpenService: (ArenaService) -> Unit,
    onBack: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.page),
    ) {
        RoundtablePageHeader(
            title = "选择 AI 成员",
            subtitle = "选 ${ArenaService.MIN_MEMBERS}-${ArenaService.MAX_MEMBERS} 家一起回答，已选 ${selectedServices.size} 家",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PageContentPadding(),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            item(key = "members") {
                ArenaGroup {
                    ArenaService.entries.forEachIndexed { index, service ->
                        if (index > 0) ArenaRowDivider(startIndent = 62.dp)
                        MemberToggleRow(
                            service = service,
                            selected = service in selectedServices,
                            status = statuses[service] ?: ServiceStatus(),
                            onToggle = { onToggle(service) },
                        )
                    }
                }
            }
            item(key = "hint") {
                Text(
                    text = "选得越多，等待时间越长；推荐 2-3 家，观点差异最明显。",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            if (loginNeededServices.isNotEmpty()) {
                item(key = "login-needed") {
                    LoginNeededPanel(
                        services = loginNeededServices,
                        statuses = statuses,
                        onOpenService = onOpenService,
                    )
                }
            }
            item(key = "done") {
                ArenaPrimaryButton(
                    text = "完成选择",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(metrics.gap))
            }
        }
    }
}

@Composable
private fun MemberToggleRow(
    service: ArenaService,
    selected: Boolean,
    status: ServiceStatus,
    onToggle: () -> Unit,
) {
    val colors = ArenaStyle.colors
    ArenaRow(
        title = service.displayName,
        titleColor = if (selected) colors.accent else colors.ink,
        detail = if (status.state.isUsable()) "已登录，可直接使用" else service.loginHint,
        leading = { BrandAvatar(service = service, size = 34.dp) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (service.experimental) {
                    ArenaPill(
                        text = "适配中",
                        foreground = colors.warning,
                        background = colors.warningSoft,
                        dot = false,
                    )
                }
                if (selected) {
                    ArenaIcon(R.drawable.ic_check_circle, tint = colors.accent, size = 26.dp)
                } else {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(colors.surfaceAlt),
                    )
                }
            }
        },
        chevron = false,
        onClick = onToggle,
        contentDescriptionText = "${service.displayName}，${if (selected) "已选择" else "未选择"}",
    )
}

@Composable
private fun LoginNeededPanel(
    services: List<ArenaService>,
    statuses: Map<ArenaService, ServiceStatus>,
    onOpenService: (ArenaService) -> Unit,
) {
    val colors = ArenaStyle.colors
    ArenaCard(
        modifier = Modifier.fillMaxWidth(),
        color = colors.warningSoft,
        borderColor = colors.warningSoft,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "还有 ${services.size} 家需要登录",
                color = colors.ink,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "登录一次后会自动记住。登录成功后点顶部「返回圆桌」，再继续下一家。",
                color = colors.muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            services.chunked(2).forEach { rowServices ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowServices.forEach { service ->
                        val state = statuses[service]?.state ?: ConnectionState.NOT_LOADED
                        ArenaSecondaryButton(
                            text = "${if (state == ConnectionState.ERROR) "重试" else "登录"} ${service.shortName}",
                            onClick = { onOpenService(service) },
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription = "打开 ${service.displayName} 登录页面"
                                },
                            leading = { BrandAvatar(service = service, size = 20.dp) },
                        )
                    }
                    if (rowServices.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
