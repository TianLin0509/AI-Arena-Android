package com.tianlin.aiarena

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// 提问页
// ---------------------------------------------------------------------------

@Composable
internal fun AskHome(
    question: String,
    onQuestionChange: (String) -> Unit,
    questionWithinLimit: Boolean,
    lengthAdvisory: String?,
    selectedServices: List<ArenaService>,
    usableCount: Int,
    onMembers: () -> Unit,
    onConnections: () -> Unit,
    voiceInputActive: Boolean,
    voiceInputEnabled: Boolean,
    onVoiceInput: () -> Unit,
    offline: Boolean,
    crashNotice: ArenaCrashReport?,
    onCrashRestart: () -> Unit,
    onCrashDismiss: () -> Unit,
    onNeedQuestion: () -> Unit,
    onTooLong: () -> Unit,
    onStart: () -> Unit,
    availableUpdate: ArenaUpdateInfo? = null,
    onInstallUpdate: (ArenaUpdateInfo) -> Unit = {},
    onDismissUpdate: (ArenaUpdateInfo) -> Unit = {},
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val ready = usableCount >= ArenaService.MIN_MEMBERS
    val missing = (ArenaService.MIN_MEMBERS - usableCount).coerceAtLeast(0)
    val scrollState = rememberScrollState()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val composerFocus = remember { FocusRequester() }

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
                    .padding(start = metrics.gutter, end = metrics.gutter, top = 22.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ArenaHeading(
                    text = "今天想问点什么？",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    text = "几家 AI 一起回答，答完还能互相核对。",
                    color = colors.onHeroMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                MemberLine(
                    services = selectedServices,
                    usableCount = usableCount,
                    ready = ready,
                    onClick = if (ready) onMembers else onConnections,
                )
            }
        }

        // 中段可滚动，主行动按钮固定在底部：屏幕再小、字号再大也不会被推到看不见的地方。
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = metrics.gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            if (offline) {
                ArenaNotice(
                    tone = NoticeTone.WARNING,
                    title = "网络没有连上",
                    text = "请打开 Wi-Fi 或手机流量。连上以后这条提示会自动消失。",
                )
            }
            if (crashNotice != null) {
                ArenaNotice(
                    tone = NoticeTone.INFO,
                    title = "上次没有正常退出",
                    text = "别担心，已登录的 AI 都还在。如果界面看起来不对，可以重新开始。",
                    actionLabel = "重新开始",
                    onAction = onCrashRestart,
                    secondaryLabel = "知道了",
                    onSecondary = onCrashDismiss,
                )
            }
            if (availableUpdate != null) {
                ArenaNotice(
                    tone = NoticeTone.INFO,
                    title = "新版本 v${availableUpdate.versionName} 可以安装",
                    text = availableUpdate.notes.ifBlank { "直接覆盖安装，已登录的 AI 不用重新登录。" },
                    actionLabel = "安装",
                    onAction = { onInstallUpdate(availableUpdate) },
                    secondaryLabel = "以后",
                    onSecondary = { onDismissUpdate(availableUpdate) },
                    actionContentDescription = "下载并安装新版本",
                )
            }
            QuestionComposer(
                question = question,
                onQuestionChange = onQuestionChange,
                questionWithinLimit = questionWithinLimit,
                lengthAdvisory = lengthAdvisory,
                voiceInputActive = voiceInputActive,
                voiceInputEnabled = voiceInputEnabled,
                onVoiceInput = onVoiceInput,
                compact = imeVisible,
                focusRequester = composerFocus,
            )

            AnimatedVisibility(
                visible = question.isBlank(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "可以这样问",
                        color = colors.muted,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                    )
                    QuestionExamples.forEach { example ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = metrics.minTouch)
                                .clip(RoundedCornerShape(metrics.controlCorner))
                                .clickable { onQuestionChange(example) },
                            color = colors.card,
                            shape = RoundedCornerShape(metrics.controlCorner),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = example,
                                    color = colors.ink,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                ArenaIcon(
                                    R.drawable.ic_chevron_right,
                                    tint = colors.muted.copy(alpha = 0.6f),
                                    size = 20.dp,
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = "AI 的回答可能不准确，健康、金融和政策类信息请再查证权威来源。",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp),
                color = colors.muted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colors.page,
            shadowElevation = if (metrics.flatSurfaces) 0.dp else 8.dp,
        ) {
            Column {
                if (metrics.flatSurfaces) HorizontalDivider(color = colors.border)
                Column(
                    modifier = Modifier.padding(
                        start = metrics.gutter,
                        end = metrics.gutter,
                        top = 10.dp,
                        bottom = 12.dp,
                    ),
                ) {
                    // 主按钮永远可以点：点了以后要么开始，要么带你去做"下一步该做的事"。
                    // 灰掉的大按钮对长辈来说等于"这个 App 坏了"。
                    ArenaPrimaryButton(
                        text = when {
                            !ready -> "先登录 AI（还差 $missing 家）"
                            !questionWithinLimit -> "问题太长，请缩短"
                            else -> "开始讨论"
                        },
                        onClick = {
                            when {
                                !ready -> onConnections()
                                !questionWithinLimit -> onTooLong()
                                question.isBlank() -> {
                                    runCatching { composerFocus.requestFocus() }
                                    onNeedQuestion()
                                }
                                else -> onStart()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 首页标题下方的一行：谁在圆桌上、几家已登录。整行可点去调整。 */
@Composable
private fun MemberLine(
    services: List<ArenaService>,
    usableCount: Int,
    ready: Boolean,
    onClick: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val missing = ArenaService.MIN_MEMBERS - usableCount
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(metrics.controlCorner))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "当前成员 ${services.joinToString("、") { it.displayName }}，" +
                    "$usableCount 家可用，点击调整"
            }
            .heightIn(min = metrics.minTouch)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BrandAvatarStack(services = services, size = 26.dp)
        // 窄屏手机上三个名字加状态会挤不下：让名字换行，别用省略号把最后一家吃掉。
        Text(
            text = services.joinToString(" · ") { it.shortName },
            color = colors.onHero,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = if (ready) "$usableCount 家已登录" else "还差 $missing 家未登录",
            color = if (ready) colors.success else colors.warning,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
        ArenaIcon(R.drawable.ic_chevron_right, tint = colors.onHeroMuted, size = 20.dp)
    }
}

@Composable
internal fun QuestionComposer(
    question: String,
    onQuestionChange: (String) -> Unit,
    questionWithinLimit: Boolean,
    lengthAdvisory: String?,
    voiceInputActive: Boolean,
    voiceInputEnabled: Boolean,
    onVoiceInput: () -> Unit,
    /** 键盘占掉大半屏时压矮一档，好让分隔线和语音/清空那行也留在可视区内。 */
    compact: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val nearLimit = question.length > ArenaLimits.MAX_QUESTION_CHARS / 2

    ArenaCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = question,
                onValueChange = onQuestionChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (compact) 64.dp else 120.dp)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                placeholder = {
                    Text(
                        text = "写下你的问题…",
                        color = colors.muted,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge,
                isError = !questionWithinLimit,
                shape = RoundedCornerShape(metrics.controlCorner),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.ink,
                    unfocusedTextColor = colors.ink,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                minLines = if (compact) 2 else 3,
                maxLines = if (compact) 4 else 8,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (voiceInputEnabled) {
                    ArenaSecondaryButton(
                        text = if (voiceInputActive) "听写中…" else "语音输入",
                        onClick = onVoiceInput,
                        enabled = !voiceInputActive,
                        modifier = Modifier.semantics { contentDescription = "语音输入问题" },
                        leading = { ArenaIcon(R.drawable.ic_mic, tint = colors.accent, size = 20.dp) },
                    )
                }
                if (question.isNotEmpty()) {
                    ArenaTextAction(
                        text = "清空",
                        onClick = { onQuestionChange("") },
                        color = colors.muted,
                        contentDescriptionText = "清空问题输入框",
                    )
                }
                Spacer(Modifier.weight(1f))
                if (nearLimit || !questionWithinLimit) {
                    Text(
                        text = "${question.length} / ${ArenaLimits.MAX_QUESTION_CHARS}",
                        color = if (questionWithinLimit) colors.muted else colors.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            if (lengthAdvisory != null) {
                // 24,000 字能发出去，但"观点讨论""讨论总结"要把原问题和别家回答塞进
                // 同一条 prompt，受更紧的上下文预算限制。提前提示，别等到点按钮才失败。
                Text(
                    text = lengthAdvisory,
                    color = if (questionWithinLimit) colors.warning else colors.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 登录引导：按步骤登录 2 家以上
// ---------------------------------------------------------------------------

@Composable
internal fun ConnectionGuide(
    statuses: Map<ArenaService, ServiceStatus>,
    usableCount: Int,
    services: List<ArenaService>,
    onOpenService: (ArenaService) -> Unit,
    onPreviewHome: () -> Unit,
    isManagingConnections: Boolean,
    largeTextEnabled: Boolean,
    onLargeTextChange: (Boolean) -> Unit,
    offline: Boolean,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val nextService = services.firstOrNull { statuses[it]?.state?.isUsable() != true }
    val progress = if (services.isEmpty()) 0f else usableCount.toFloat() / services.size
    val enough = usableCount >= ArenaService.MIN_MEMBERS

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.page),
    ) {
        ArenaHero(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 8.dp, end = metrics.gutter, top = 6.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArenaBackButton(
                        onClick = onPreviewHome,
                        label = "返回圆桌",
                        color = colors.onHero,
                        contentDescriptionText = "返回 AI 圆桌主界面",
                    )
                    ArenaPill(
                        text = "$usableCount / ${services.size} 已登录",
                        foreground = if (enough) colors.success else colors.onHero,
                        background = if (enough) colors.successSoft else colors.onHero.copy(alpha = 0.12f),
                        dot = false,
                    )
                }
                Column(
                    modifier = Modifier.padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ArenaHeading(
                        text = if (enough) "已经可以开始了" else "先登录 ${ArenaService.MIN_MEMBERS} 家 AI",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = if (isManagingConnections) {
                            "选好成员后逐个登录，随时可以返回。"
                        } else {
                            "只需登录一次，以后自动记住。登录信息只保存在这台手机上。"
                        },
                        color = colors.onHeroMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ArenaProgressBar(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth(),
                        track = if (metrics.flatSurfaces) colors.accentSoft else colors.onHero.copy(alpha = 0.22f),
                        indicator = if (metrics.flatSurfaces) colors.accent else colors.onHero,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.page),
            contentPadding = PaddingValues(
                start = metrics.gutter,
                end = metrics.gutter,
                top = 8.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            if (offline) {
                item(key = "offline") {
                    ArenaNotice(
                        tone = NoticeTone.WARNING,
                        title = "网络没有连上",
                        text = "登录需要联网。请先打开 Wi-Fi 或手机流量。",
                    )
                }
            }

            item(key = "services") {
                ArenaGroup {
                    services.forEachIndexed { index, service ->
                        if (index > 0) ArenaRowDivider(startIndent = 62.dp)
                        ConnectionRow(
                            step = index + 1,
                            service = service,
                            status = statuses[service] ?: ServiceStatus(),
                            isNext = service == nextService,
                            onClick = { onOpenService(service) },
                        )
                    }
                }
            }

            item(key = "primary") {
                ArenaPrimaryButton(
                    text = when {
                        nextService != null -> "登录 ${nextService.displayName}"
                        else -> "已全部登录，返回圆桌"
                    },
                    onClick = { if (nextService != null) onOpenService(nextService) else onPreviewHome() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item(key = "howto") {
                ArenaGroup(title = "怎么登录？") {
                    listOf(
                        "点上面的「去登录」，会打开这家 AI 自己的网页。",
                        "在网页里像平时一样登录：手机号验证码、微信或支付宝都可以。",
                        "登录成功后，顶部会出现绿色提示，点「返回圆桌」就好。",
                    ).forEachIndexed { index, text ->
                        if (index > 0) ArenaRowDivider(startIndent = 58.dp)
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ArenaStepBadge(number = index + 1, active = index == 0)
                            Text(
                                text = text,
                                color = colors.ink,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            item(key = "large-text") {
                ArenaGroup {
                    ArenaSwitchRow(
                        title = "大字模式",
                        detail = if (largeTextEnabled) "已开启，AI 网页也会同步放大" else "看不清？整体再放大一档",
                        checked = largeTextEnabled,
                        onCheckedChange = onLargeTextChange,
                        contentDescriptionText = if (largeTextEnabled) "关闭大字模式" else "开启大字模式",
                    )
                }
            }

            item(key = "secondary") {
                ArenaTextAction(
                    text = if (isManagingConnections) "完成，返回圆桌" else "暂不登录，先进去看看",
                    onClick = onPreviewHome,
                    color = colors.muted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ConnectionRow(
    step: Int,
    service: ArenaService,
    status: ServiceStatus,
    isNext: Boolean,
    onClick: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val connected = status.state.isUsable()
    ArenaRow(
        title = service.displayName,
        detail = when {
            connected -> "已登录，以后自动记住"
            status.state == ConnectionState.ERROR -> "网页没有打开，点一下重试"
            status.state == ConnectionState.LOADING -> "正在打开网页…"
            else -> service.loginHint
        },
        detailColor = when {
            connected -> colors.success
            status.state == ConnectionState.ERROR -> colors.error
            else -> colors.muted
        },
        leading = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ArenaStepBadge(number = step, done = connected, active = isNext)
                BrandAvatar(service = service, size = 32.dp)
            }
        },
        trailing = {
            when {
                status.state == ConnectionState.LOADING -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = colors.accent,
                    strokeWidth = 3.dp,
                )
                connected -> ArenaIcon(R.drawable.ic_check_circle, tint = colors.success, size = 24.dp)
                else -> Text(
                    text = if (status.state == ConnectionState.ERROR) "重试" else "去登录",
                    color = if (status.state == ConnectionState.ERROR) colors.error else colors.accent,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        chevron = !connected && status.state != ConnectionState.LOADING,
        onClick = onClick,
        contentDescriptionText = "${service.displayName}，${statusLabel(status.state)}",
    )
}

// ---------------------------------------------------------------------------
// 首次使用说明
// ---------------------------------------------------------------------------

@Composable
internal fun OnboardingPage(onDone: () -> Unit) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.page)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = metrics.gutter + 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(48.dp))
            BrandAvatarStack(services = ArenaService.defaultMembers, size = 36.dp)
            ArenaHeading(
                text = "AI 圆桌",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "一个问题，问几家 AI，再让它们互相对答案。",
                color = colors.muted,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            listOf(
                Triple("登录你常用的 AI", "DeepSeek、豆包、Kimi 等，用手机号或微信登录。只需登录一次，以后自动记住。", 1),
                Triple("写下问题，点「开始讨论」", "几家 AI 会同时回答，一条一条看，还能复制、分享。", 2),
                Triple("让它们互相核对", "点「讨论总结」，会有一家 AI 帮你比较分歧、提炼共识。", 3),
            ).forEach { (title, detail, number) ->
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ArenaStepBadge(number = number, active = true, size = 34.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = title,
                            color = colors.ink,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = detail,
                            color = colors.muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Column(
            modifier = Modifier.padding(horizontal = metrics.gutter, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "不需要注册账号。登录信息、问题和回答都只保存在这台手机上。",
                color = colors.muted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            ArenaPrimaryButton(
                text = "开始使用",
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "开始使用 AI 圆桌" },
            )
        }
    }
}
