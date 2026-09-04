package com.tianlin.aiarena

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class RoundtablePage {
    HOME,
    HISTORY,
    SETTINGS,
    MEMBERS,
    APPEARANCE,
    ;

    companion object {
        fun fromName(value: String?): RoundtablePage =
            entries.firstOrNull { it.name == value } ?: HOME
    }
}

private val QuestionExamples = listOf(
    "帮我比较几种家庭旅行方案",
    "这条新闻讲的是什么，通俗解释一下",
    "体检报告上这个指标偏高要紧吗",
    "帮我写一段给长辈的生日祝福",
)

@Composable
fun ArenaApp(
    pool: ArenaWebViewPool,
    debugInitialQuestion: String = "",
    voiceInputState: VoiceInputState? = null,
    voiceInputRequest: VoiceInputRequest? = null,
    speechState: SpeechPlaybackState? = null,
    speechPlaybackRequest: SpeechPlaybackRequest? = null,
    stopSpeech: (() -> Unit)? = null,
    copyText: TextCopyRequest? = null,
    shareText: TextShareRequest? = null,
    skin: ArenaSkin = ArenaSkin.default,
    onSkinChange: (ArenaSkin) -> Unit = {},
) {
    val colors = ArenaStyle.colors
    val context = LocalContext.current
    val accessibilityPreferences = remember(context) { AccessibilityPreferences(context) }
    val navigationPreferences = remember(context) { ArenaNavigationPreferences(context) }
    val sessionRepository = remember(context) { ArenaSessionStore(context) }
    val sessionController = remember(pool, sessionRepository) {
        ArenaSessionController(pool = pool, sessionRepository = sessionRepository)
    }
    // 只在启动时读一次；清除后用这个计数触发重读。
    var crashReportGeneration by remember { mutableIntStateOf(0) }
    val crashReport = remember(context, crashReportGeneration) {
        runCatching { ArenaCrashReporter.latest(context) }.getOrNull()
    }
    var largeTextEnabled by rememberSaveable {
        mutableStateOf(accessibilityPreferences.isLargeTextEnabled())
    }
    var selectedServiceName by rememberSaveable { mutableStateOf<String?>(null) }
    var showConnections by rememberSaveable { mutableStateOf(false) }
    var roundtableUnlocked by rememberSaveable {
        mutableStateOf(
            navigationPreferences.hasOpenedRoundtable() ||
                sessionController.originalQuestion.isNotBlank() ||
                sessionController.recentSessions.isNotEmpty(),
        )
    }
    var selectedMemberNames by rememberSaveable {
        val initialServices = if (sessionController.originalQuestion.isNotBlank()) {
            sessionController.sessionServices
        } else {
            pool.loadSelectedServices()
        }
        mutableStateOf(initialServices.joinToString(",") { it.name })
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val selectedService = selectedServiceName?.let(ArenaService::fromName)
    val selectedMembers = selectedMemberNames.split(',')
        .mapNotNull(ArenaService::fromName)
        .distinct()
        .let { if (it.size >= ArenaService.MIN_MEMBERS) it else ArenaService.defaultMembers }
    val selectedUsableCount = selectedMembers.count {
        pool.statuses[it]?.state?.isUsable() == true
    }
    val connectionGuideVisible = RoundtableNavigationPolicy.showConnectionGuide(
        usableCount = selectedUsableCount,
        connectionManagerRequested = showConnections,
        roundtableUnlocked = roundtableUnlocked,
    )

    fun returnToRoundtable() {
        roundtableUnlocked = true
        navigationPreferences.markRoundtableOpened()
        showConnections = false
        selectedServiceName = null
    }

    LaunchedEffect(selectedMemberNames) {
        pool.saveSelectedServices(selectedMembers)
    }

    LaunchedEffect(selectedUsableCount) {
        if (selectedUsableCount >= ArenaService.MIN_MEMBERS && !roundtableUnlocked) {
            roundtableUnlocked = true
        }
    }

    LaunchedEffect(roundtableUnlocked) {
        if (roundtableUnlocked) navigationPreferences.markRoundtableOpened()
    }

    LaunchedEffect(largeTextEnabled) {
        accessibilityPreferences.setLargeTextEnabled(largeTextEnabled)
        pool.setTextZoomPercent(TextScalePolicy.webViewTextZoom(largeTextEnabled))
    }

    LaunchedEffect(selectedServiceName) {
        if (selectedServiceName != null) stopSpeech?.invoke()
    }

    DisposableEffect(sessionController) {
        onDispose { sessionController.destroy() }
    }

    BackHandler(enabled = selectedService != null) {
        if (!pool.goBack(selectedService!!)) returnToRoundtable()
    }

    BackHandler(enabled = selectedService == null && connectionGuideVisible) {
        returnToRoundtable()
    }

    val systemDensity = LocalDensity.current
    val scaledDensity = Density(
        density = systemDensity.density,
        fontScale = TextScalePolicy.composeFontScale(systemDensity.fontScale, largeTextEnabled),
    )
    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        // 顶部 header 高度按实际测量值让位给 WebView，避免大字模式下写死高度被裁切。
        var providerHeaderPx by remember { mutableIntStateOf(0) }
        val providerHeaderHeight = if (selectedService == null) {
            0.dp
        } else {
            with(LocalDensity.current) { providerHeaderPx.toDp() }
        }

        Scaffold(
            containerColor = colors.page,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                ArenaBottomBar(
                    selected = selectedService,
                    onRoundtable = { returnToRoundtable() },
                    onService = { service ->
                        pool.open(service)
                        selectedServiceName = service.name
                    },
                    statuses = pool.statuses,
                    services = selectedMembers,
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                AndroidView(
                    factory = {
                        pool.container.apply {
                            (parent as? ViewGroup)?.removeView(this)
                        }
                    },
                    update = { pool.show(selectedService) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = providerHeaderHeight),
                )

                if (selectedService == null) {
                    RoundtableRoot(
                        pool = pool,
                        sessionController = sessionController,
                        debugInitialQuestion = debugInitialQuestion,
                        selectedServices = selectedMembers,
                        onSelectedServicesChange = { services ->
                            selectedMemberNames = services.joinToString(",") { it.name }
                        },
                        showConnectionGuide = connectionGuideVisible,
                        isManagingConnections = showConnections,
                        onPreviewHome = { returnToRoundtable() },
                        onManageConnections = { showConnections = true },
                        onOpenService = { service ->
                            pool.open(service)
                            selectedServiceName = service.name
                        },
                        snackbarHostState = snackbarHostState,
                        voiceInputState = voiceInputState,
                        voiceInputRequest = voiceInputRequest,
                        largeTextEnabled = largeTextEnabled,
                        onLargeTextChange = { largeTextEnabled = it },
                        speechState = speechState,
                        speechPlaybackRequest = speechPlaybackRequest,
                        stopSpeech = stopSpeech,
                        copyText = copyText,
                        shareText = shareText,
                        skin = skin,
                        onSkinChange = onSkinChange,
                        crashReport = crashReport,
                        onClearCrashReport = {
                            ArenaCrashReporter.clear(context)
                            crashReportGeneration += 1
                        },
                    )
                } else {
                    ProviderHeader(
                        service = selectedService,
                        status = pool.statuses[selectedService] ?: ServiceStatus(),
                        onBack = { returnToRoundtable() },
                        onReload = { pool.reload(selectedService) },
                        modifier = Modifier.onSizeChanged { providerHeaderPx = it.height },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundtableRoot(
    pool: ArenaWebViewPool,
    sessionController: ArenaSessionController,
    debugInitialQuestion: String,
    selectedServices: List<ArenaService>,
    onSelectedServicesChange: (List<ArenaService>) -> Unit,
    showConnectionGuide: Boolean,
    isManagingConnections: Boolean,
    onPreviewHome: () -> Unit,
    onManageConnections: () -> Unit,
    onOpenService: (ArenaService) -> Unit,
    snackbarHostState: SnackbarHostState,
    voiceInputState: VoiceInputState?,
    voiceInputRequest: VoiceInputRequest?,
    largeTextEnabled: Boolean,
    onLargeTextChange: (Boolean) -> Unit,
    speechState: SpeechPlaybackState?,
    speechPlaybackRequest: SpeechPlaybackRequest?,
    stopSpeech: (() -> Unit)?,
    copyText: TextCopyRequest?,
    shareText: TextShareRequest?,
    skin: ArenaSkin,
    onSkinChange: (ArenaSkin) -> Unit,
    crashReport: ArenaCrashReport?,
    onClearCrashReport: () -> Unit,
) {
    val usableCount = selectedServices.count {
        pool.statuses[it]?.state?.isUsable() == true
    }
    if (!showConnectionGuide) {
        DiscussionHome(
            pool = pool,
            sessionController = sessionController,
            debugInitialQuestion = debugInitialQuestion,
            selectedServices = selectedServices,
            onSelectedServicesChange = onSelectedServicesChange,
            usableCount = usableCount,
            onManageConnections = onManageConnections,
            onOpenService = onOpenService,
            snackbarHostState = snackbarHostState,
            voiceInputState = voiceInputState,
            voiceInputRequest = voiceInputRequest,
            largeTextEnabled = largeTextEnabled,
            onLargeTextChange = onLargeTextChange,
            speechState = speechState,
            speechPlaybackRequest = speechPlaybackRequest,
            stopSpeech = stopSpeech,
            copyText = copyText,
            shareText = shareText,
            skin = skin,
            onSkinChange = onSkinChange,
            crashReport = crashReport,
            onClearCrashReport = onClearCrashReport,
        )
    } else {
        ConnectionGuide(
            statuses = pool.statuses,
            usableCount = usableCount,
            services = selectedServices,
            onOpenService = onOpenService,
            onPreviewHome = onPreviewHome,
            isManagingConnections = isManagingConnections,
            largeTextEnabled = largeTextEnabled,
            onLargeTextChange = onLargeTextChange,
            speechState = speechState,
            stopSpeech = stopSpeech,
        )
    }
}

// ---------------------------------------------------------------------------
// 连接引导
// ---------------------------------------------------------------------------

@Composable
private fun ConnectionGuide(
    statuses: Map<ArenaService, ServiceStatus>,
    usableCount: Int,
    services: List<ArenaService>,
    onOpenService: (ArenaService) -> Unit,
    onPreviewHome: () -> Unit,
    isManagingConnections: Boolean,
    largeTextEnabled: Boolean,
    onLargeTextChange: (Boolean) -> Unit,
    speechState: SpeechPlaybackState?,
    stopSpeech: (() -> Unit)?,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val nextService = services.firstOrNull { statuses[it]?.state?.isUsable() != true }
    val progress = if (services.isEmpty()) 0f else usableCount.toFloat() / services.size

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
                    .padding(horizontal = metrics.gutter, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArenaTextAction(
                        text = "返回圆桌",
                        onClick = onPreviewHome,
                        color = colors.onHero,
                        contentDescriptionText = "返回 AI 圆桌主界面",
                    )
                    ArenaPill(
                        text = "$usableCount / ${services.size} 可使用",
                        foreground = colors.onHero,
                        background = colors.onHero.copy(alpha = 0.16f),
                        dot = false,
                    )
                }
                Text(
                    text = "连接 2 家即可开始",
                    color = colors.onHero,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = if (isManagingConnections) {
                        "选好成员后逐个登录，随时可以返回"
                    } else {
                        "登录一次，之后自动复用；登录信息只保存在本机"
                    },
                    color = colors.onHeroMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                ArenaProgressBar(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(),
                    track = colors.onHero.copy(alpha = 0.22f),
                    indicator = colors.onHero,
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.page),
            contentPadding = PaddingValues(
                start = metrics.gutter,
                end = metrics.gutter,
                top = 14.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            item {
                AccessibilityToolbar(
                    largeTextEnabled = largeTextEnabled,
                    onLargeTextChange = onLargeTextChange,
                    speechState = speechState,
                    stopSpeech = stopSpeech,
                )
            }

            items(services, key = { it.name }) { service ->
                ConnectionCard(
                    service = service,
                    status = statuses[service] ?: ServiceStatus(),
                    onClick = { onOpenService(service) },
                )
            }

            item {
                ArenaPrimaryButton(
                    text = nextService?.let { "连接 ${it.displayName}" } ?: "已全部连接",
                    onClick = { nextService?.let(onOpenService) },
                    enabled = nextService != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                ArenaSecondaryButton(
                    text = if (isManagingConnections) "完成，返回圆桌" else "暂不登录，先进入圆桌",
                    onClick = onPreviewHome,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    service: ArenaService,
    status: ServiceStatus,
    onClick: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val connected = status.state.isUsable()
    val borderColor by animateColorAsState(
        targetValue = if (connected) colors.success else colors.border,
        label = "connection-border",
    )
    ArenaCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${service.displayName}，${statusLabel(status.state)}" },
        borderColor = borderColor,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            BrandAvatar(service = service, size = 34.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = service.displayName,
                        color = colors.ink,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (service.experimental) {
                        ArenaPill(
                            text = "适配中",
                            foreground = colors.warning,
                            background = colors.warningSoft,
                            dot = false,
                        )
                    }
                }
                Text(
                    text = if (connected) status.detail else service.loginHint,
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                status.state == ConnectionState.LOADING -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = colors.accent,
                    strokeWidth = 3.dp,
                )
                connected -> StatusPill(status.state)
                else -> Surface(
                    color = colors.accent,
                    shape = RoundedCornerShape(metrics.controlCorner),
                ) {
                    Text(
                        text = if (status.state == ConnectionState.ERROR) "重试" else "连接",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                        color = colors.onAccent,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 圆桌主流程
// ---------------------------------------------------------------------------

@Composable
private fun DiscussionHome(
    pool: ArenaWebViewPool,
    sessionController: ArenaSessionController,
    debugInitialQuestion: String,
    selectedServices: List<ArenaService>,
    onSelectedServicesChange: (List<ArenaService>) -> Unit,
    usableCount: Int,
    onManageConnections: () -> Unit,
    onOpenService: (ArenaService) -> Unit,
    snackbarHostState: SnackbarHostState,
    voiceInputState: VoiceInputState?,
    voiceInputRequest: VoiceInputRequest?,
    largeTextEnabled: Boolean,
    onLargeTextChange: (Boolean) -> Unit,
    speechState: SpeechPlaybackState?,
    speechPlaybackRequest: SpeechPlaybackRequest?,
    stopSpeech: (() -> Unit)?,
    copyText: TextCopyRequest?,
    shareText: TextShareRequest?,
    skin: ArenaSkin,
    onSkinChange: (ArenaSkin) -> Unit,
    crashReport: ArenaCrashReport?,
    onClearCrashReport: () -> Unit,
) {
    var question by rememberSaveable {
        mutableStateOf(debugInitialQuestion.ifBlank { sessionController.originalQuestion })
    }
    var answerModeName by rememberSaveable { mutableStateOf(AnswerMode.PARALLEL.name) }
    var roundGuidance by rememberSaveable { mutableStateOf("") }
    var roundtablePageName by rememberSaveable { mutableStateOf(RoundtablePage.HOME.name) }
    var membersReturnPageName by rememberSaveable { mutableStateOf(RoundtablePage.HOME.name) }
    val expandedAnswers = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    val answerMode = AnswerMode.fromName(answerModeName)
    val usableServices = selectedServices.filter {
        pool.statuses[it]?.state?.isUsable() == true
    }
    val loginNeededServices = selectedServices.filter {
        when (pool.statuses[it]?.state ?: ConnectionState.NOT_LOADED) {
            ConnectionState.NOT_LOADED,
            ConnectionState.NEEDS_LOGIN,
            ConnectionState.ERROR,
            -> true
            ConnectionState.LOADING,
            ConnectionState.SIGNED_IN,
            -> false
        }
    }
    val sessionStage = sessionController.stage
    val completedCount = sessionController.completedCount
    val questionWithinLimit = question.length <= ArenaLimits.MAX_QUESTION_CHARS
    val voiceEvent = voiceInputState?.event
    val roundtablePage = RoundtablePage.fromName(roundtablePageName)

    LaunchedEffect(voiceEvent?.id) {
        val event = voiceEvent ?: return@LaunchedEffect
        val outcome = voiceInputState.take(event.id) ?: return@LaunchedEffect
        when (outcome) {
            is VoiceInputOutcome.Success -> {
                val merged = VoiceInputPolicy.merge(question, outcome.transcript)
                question = merged.text
                snackbarHostState.showSnackbar(
                    if (merged.truncated) "语音内容过长，已保留可容纳的部分" else "已添加语音内容",
                )
            }
            VoiceInputOutcome.Cancelled -> snackbarHostState.showSnackbar("已取消语音输入")
            is VoiceInputOutcome.Error -> snackbarHostState.showSnackbar(outcome.message)
        }
    }

    val toggleMember: (ArenaService) -> Unit = { service ->
        // 轮次进行中改成员会销毁正在收答案的 WebView，并让下一轮的参与者集合
        // 与界面上显示的成员对不上。直接挡在这里，比事后补偿可靠。
        if (sessionController.isBusy) {
            scope.launch { snackbarHostState.showSnackbar("本轮结束后才能调整成员") }
        } else {
            val next = if (service in selectedServices) {
                if (selectedServices.size <= ArenaService.MIN_MEMBERS) {
                    scope.launch { snackbarHostState.showSnackbar("至少保留 ${ArenaService.MIN_MEMBERS} 家 AI") }
                    selectedServices
                } else {
                    selectedServices - service
                }
            } else {
                if (selectedServices.size >= ArenaService.MAX_MEMBERS) {
                    scope.launch {
                        snackbarHostState.showSnackbar("手机端当前最多选择 ${ArenaService.MAX_MEMBERS} 家 AI")
                    }
                    selectedServices
                } else {
                    selectedServices + service
                }
            }
            if (next != selectedServices) onSelectedServicesChange(next)
        }
    }

    val restoreRecentSession: (String) -> Unit = { sessionId ->
        stopSpeech?.invoke()
        if (sessionController.restoreSession(sessionId)) {
            question = sessionController.originalQuestion
            roundGuidance = ""
            expandedAnswers.clear()
            roundtablePageName = RoundtablePage.HOME.name
            onSelectedServicesChange(sessionController.sessionServices)
            scope.launch { snackbarHostState.showSnackbar("已恢复本地讨论") }
        } else {
            scope.launch { snackbarHostState.showSnackbar("该历史记录无法恢复") }
        }
    }

    BackHandler(enabled = roundtablePage != RoundtablePage.HOME) {
        roundtablePageName = when (roundtablePage) {
            RoundtablePage.MEMBERS -> membersReturnPageName
            RoundtablePage.APPEARANCE -> RoundtablePage.SETTINGS.name
            else -> RoundtablePage.HOME.name
        }
    }

    Crossfade(
        targetState = roundtablePage,
        animationSpec = tween(durationMillis = 180),
        label = "roundtable-page",
    ) { page ->
        when (page) {
            RoundtablePage.HISTORY -> RoundtableHistoryPage(
                sessions = sessionController.recentSessions,
                warning = sessionController.storageWarning,
                onBack = { roundtablePageName = RoundtablePage.HOME.name },
                onRestore = restoreRecentSession,
            )

            RoundtablePage.SETTINGS -> RoundtableSettingsPage(
                selectedServices = selectedServices,
                usableCount = usableCount,
                crashReport = crashReport,
                onClearCrashReport = onClearCrashReport,
                onShareCrashReport = shareText?.let { share ->
                    { report: ArenaCrashReport ->
                        if (!share("AI 圆桌崩溃记录", report.text)) {
                            scope.launch { snackbarHostState.showSnackbar("当前设备没有可用的分享方式") }
                        }
                    }
                },
                largeTextEnabled = largeTextEnabled,
                onLargeTextChange = onLargeTextChange,
                speechState = speechState,
                stopSpeech = stopSpeech,
                skin = skin,
                onBack = { roundtablePageName = RoundtablePage.HOME.name },
                onAppearance = { roundtablePageName = RoundtablePage.APPEARANCE.name },
                onMembers = {
                    membersReturnPageName = RoundtablePage.SETTINGS.name
                    roundtablePageName = RoundtablePage.MEMBERS.name
                },
                onConnections = {
                    roundtablePageName = RoundtablePage.HOME.name
                    onManageConnections()
                },
            )

            RoundtablePage.APPEARANCE -> RoundtableAppearancePage(
                skin = skin,
                onSkinChange = onSkinChange,
                largeTextEnabled = largeTextEnabled,
                onLargeTextChange = onLargeTextChange,
                onBack = { roundtablePageName = RoundtablePage.SETTINGS.name },
            )

            RoundtablePage.MEMBERS -> RoundtableMembersPage(
                selectedServices = selectedServices,
                loginNeededServices = loginNeededServices,
                statuses = pool.statuses,
                onToggle = toggleMember,
                onOpenService = onOpenService,
                onBack = { roundtablePageName = membersReturnPageName },
            )

            RoundtablePage.HOME -> if (sessionStage == SessionStage.IDLE) {
                AskHome(
                    question = question,
                    onQuestionChange = { question = it },
                    questionWithinLimit = questionWithinLimit,
                    lengthAdvisory = QuestionLengthPolicy.advisory(question, selectedServices),
                    answerMode = answerMode,
                    onAnswerModeChange = { answerModeName = it.name },
                    selectedServices = selectedServices,
                    usableCount = usableCount,
                    onMembers = {
                        membersReturnPageName = RoundtablePage.HOME.name
                        roundtablePageName = RoundtablePage.MEMBERS.name
                    },
                    onHistory = { roundtablePageName = RoundtablePage.HISTORY.name },
                    onSettings = { roundtablePageName = RoundtablePage.SETTINGS.name },
                    onConnections = onManageConnections,
                    historyCount = sessionController.recentSessions.size,
                    voiceInputActive = voiceInputState?.active == true,
                    voiceInputEnabled = voiceInputRequest != null,
                    onVoiceInput = { voiceInputRequest?.invoke() },
                    onStart = {
                        expandedAnswers.clear()
                        if (sessionController.startInitial(question, usableServices, answerMode)) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "已按${answerMode.displayName}发送给 ${usableServices.size} 位 AI",
                                )
                            }
                        }
                    },
                )
            } else {
                RoundStage(
                    pool = pool,
                    sessionController = sessionController,
                    selectedServices = selectedServices,
                    usableServices = usableServices,
                    usableCount = usableCount,
                    completedCount = completedCount,
                    sessionStage = sessionStage,
                    answerMode = answerMode,
                    roundGuidance = roundGuidance,
                    onRoundGuidanceChange = { roundGuidance = it },
                    expandedAnswers = expandedAnswers,
                    onHistory = { roundtablePageName = RoundtablePage.HISTORY.name },
                    onSettings = { roundtablePageName = RoundtablePage.SETTINGS.name },
                    onOpenService = onOpenService,
                    snackbarHostState = snackbarHostState,
                    speechState = speechState,
                    speechPlaybackRequest = speechPlaybackRequest,
                    stopSpeech = stopSpeech,
                    copyText = copyText,
                    shareText = shareText,
                    onNewQuestion = {
                        stopSpeech?.invoke()
                        sessionController.reset()
                        question = ""
                        roundGuidance = ""
                        expandedAnswers.clear()
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 提问页
// ---------------------------------------------------------------------------

@Composable
private fun AskHome(
    question: String,
    onQuestionChange: (String) -> Unit,
    questionWithinLimit: Boolean,
    lengthAdvisory: String?,
    answerMode: AnswerMode,
    onAnswerModeChange: (AnswerMode) -> Unit,
    selectedServices: List<ArenaService>,
    usableCount: Int,
    onMembers: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onConnections: () -> Unit,
    historyCount: Int,
    voiceInputActive: Boolean,
    voiceInputEnabled: Boolean,
    onVoiceInput: () -> Unit,
    onStart: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val ready = usableCount >= ArenaService.MIN_MEMBERS
    val scrollState = rememberScrollState()

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
                    .padding(start = metrics.gutter, end = 6.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "圆桌",
                        color = colors.onHero,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    ArenaTextAction(
                        text = if (historyCount > 0) "历史 $historyCount" else "历史",
                        onClick = onHistory,
                        color = colors.onHero,
                        contentDescriptionText = "查看最近问题",
                    )
                    ArenaTextAction(
                        text = "设置",
                        onClick = onSettings,
                        color = colors.onHero,
                        contentDescriptionText = "打开设置",
                    )
                }

                ArenaHeading(
                    text = "今天想问点什么？",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                Text(
                    text = "多家 AI 同时回答，答完自动帮你对一遍。",
                    color = colors.onHeroMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = if (ready) onMembers else onConnections)
                        .semantics {
                            contentDescription = "当前成员 ${selectedServices.joinToString("、") { it.displayName }}，" +
                                "$usableCount 家可用，点击调整"
                        },
                    color = if (metrics.flatSurfaces) colors.accentSoft else colors.onHero.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(metrics.cardCorner),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BrandAvatarStack(services = selectedServices, size = 26.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = selectedServices.joinToString(" · ") { it.shortName },
                                color = colors.onHero,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (ready) {
                                    "$usableCount / ${selectedServices.size} 家可用"
                                } else {
                                    "还需连接 ${ArenaService.MIN_MEMBERS - usableCount} 家才能开始"
                                },
                                color = colors.onHeroMuted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = if (ready) "调整" else "去连接",
                            color = colors.onHero,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // 中段可滚动，主行动按钮固定在底部：屏幕再小、字号再大也不会被推到看不见的地方。
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = metrics.gutter, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            QuestionComposer(
                question = question,
                onQuestionChange = onQuestionChange,
                questionWithinLimit = questionWithinLimit,
                lengthAdvisory = lengthAdvisory,
                voiceInputActive = voiceInputActive,
                voiceInputEnabled = voiceInputEnabled,
                onVoiceInput = onVoiceInput,
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
                    )
                    QuestionExamples.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { example ->
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = metrics.minTouch)
                                        .clickable { onQuestionChange(example) },
                                    color = colors.surface,
                                    shape = RoundedCornerShape(metrics.controlCorner),
                                    border = BorderStroke(metrics.borderWidth, colors.border),
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        Text(
                                            text = example,
                                            color = colors.ink,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            AnswerModeSelector(
                selected = answerMode,
                enabled = true,
                onSelected = onAnswerModeChange,
            )

            Text(
                text = "AI 的回答可能不准确，健康、金融和政策类信息请再查证权威来源。",
                modifier = Modifier.fillMaxWidth(),
                color = colors.muted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colors.surface,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(
                    start = metrics.gutter,
                    end = metrics.gutter,
                    top = 10.dp,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ArenaPrimaryButton(
                    text = when {
                        !ready -> "连接两家 AI 后可开始"
                        !questionWithinLimit -> "问题过长，请缩短"
                        question.isBlank() -> "先写下你的问题"
                        else -> "开始讨论"
                    },
                    onClick = onStart,
                    enabled = ready && question.isNotBlank() && questionWithinLimit,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!ready) {
                    ArenaSecondaryButton(
                        text = "去连接 AI",
                        onClick = onConnections,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionComposer(
    question: String,
    onQuestionChange: (String) -> Unit,
    questionWithinLimit: Boolean,
    lengthAdvisory: String?,
    voiceInputActive: Boolean,
    voiceInputEnabled: Boolean,
    onVoiceInput: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val nearLimit = question.length > ArenaLimits.MAX_QUESTION_CHARS / 2

    ArenaCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = question,
                onValueChange = onQuestionChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 110.dp),
                placeholder = {
                    Text(
                        text = "写下你的问题，多家 AI 会一起回答…",
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
                minLines = 3,
                maxLines = 8,
            )
            HorizontalDivider(color = colors.border)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (voiceInputEnabled) {
                    ArenaTextAction(
                        text = if (voiceInputActive) "听写中…" else "语音输入",
                        onClick = onVoiceInput,
                        enabled = !voiceInputActive,
                        contentDescriptionText = "语音输入问题",
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
                        modifier = Modifier.padding(end = 8.dp),
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
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 进行中 / 结果页
// ---------------------------------------------------------------------------

@Composable
private fun RoundStage(
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
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onOpenService: (ArenaService) -> Unit,
    snackbarHostState: SnackbarHostState,
    speechState: SpeechPlaybackState?,
    speechPlaybackRequest: SpeechPlaybackRequest?,
    stopSpeech: (() -> Unit)?,
    copyText: TextCopyRequest?,
    shareText: TextShareRequest?,
    onNewQuestion: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    val scope = rememberCoroutineScope()
    val activeServices = selectedServices.filter { sessionController.runs[it]?.requestId?.isNotBlank() == true }
    val trackedServices = activeServices.ifEmpty { selectedServices }
    val settledCount = trackedServices.count {
        val phase = sessionController.runs[it]?.phase
        phase == ParticipantPhase.COMPLETE || phase == ParticipantPhase.ERROR
    }
    val roundProgress = if (trackedServices.isEmpty()) {
        null
    } else {
        settledCount.toFloat() / trackedServices.size
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
                    .padding(start = metrics.gutter, end = 6.dp, top = 10.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ArenaHeading(
                            text = sessionController.currentRoundKind?.displayName ?: "AI 圆桌",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                        )
                        Text(
                            text = sessionController.sessionMessage,
                            color = colors.onHeroMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ArenaTextAction(
                        text = "历史",
                        onClick = onHistory,
                        color = colors.onHero,
                        contentDescriptionText = "查看最近问题",
                    )
                    ArenaTextAction(
                        text = "设置",
                        onClick = onSettings,
                        color = colors.onHero,
                        contentDescriptionText = "打开设置",
                    )
                }
                ArenaProgressBar(
                    progress = if (sessionController.isBusy) null else roundProgress,
                    modifier = Modifier.fillMaxWidth(),
                    track = colors.onHero.copy(alpha = 0.22f),
                    indicator = colors.onHero,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArenaPill(
                        text = "第 ${sessionController.roundNumber} 轮 · ${answerMode.displayName}",
                        foreground = colors.onHero,
                        background = colors.onHero.copy(alpha = 0.16f),
                        dot = false,
                    )
                    ArenaPill(
                        text = "$settledCount / ${trackedServices.size} 已回复",
                        foreground = colors.onHero,
                        background = colors.onHero.copy(alpha = 0.16f),
                        dot = sessionController.isBusy,
                        pulsing = sessionController.isBusy,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = metrics.gutter,
                end = metrics.gutter,
                top = 14.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            item(key = "question") {
                ArenaCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "本次问题",
                            color = colors.muted,
                            style = MaterialTheme.typography.labelSmall,
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

            if (sessionController.isBusy) {
                item(key = "cancel") {
                    ArenaSecondaryButton(
                        text = "停止等待本轮",
                        onClick = sessionController::cancelCurrentRound,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (sessionController.history.isNotEmpty()) {
                item(key = "history-note") {
                    val latest = sessionController.history.last()
                    Text(
                        text = buildString {
                            append("已完成 ${latest.number} 轮 · 最新为${latest.kind.displayName}")
                            if (latest.number > sessionController.history.size) {
                                append(" · 仅保留最近 ${sessionController.history.size} 轮")
                            }
                        },
                        color = colors.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item(key = "results-title") {
                SectionTitle(text = "本轮结果")
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
                    )
                }
            }

            if (sessionStage == SessionStage.READY) {
                item(key = "footer-actions") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ArenaSecondaryButton(
                            text = if (sessionController.summary.phase == ParticipantPhase.COMPLETE) {
                                "重新总结"
                            } else {
                                "讨论总结"
                            },
                            onClick = {
                                if (sessionController.startSummary(usableServices, roundGuidance)) {
                                    onRoundGuidanceChange("")
                                }
                            },
                            enabled = completedCount >= ArenaService.MIN_MEMBERS && !sessionController.isBusy,
                            modifier = Modifier.weight(1f),
                        )
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
            }

            if (usableCount < ArenaService.MIN_MEMBERS) {
                item(key = "usable-warning") {
                    Text(
                        text = "当前只有 $usableCount 家可用，下一轮可能无法继续。",
                        color = colors.warning,
                        style = MaterialTheme.typography.bodySmall,
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
            SectionTitle(text = "继续这一轮")
            Text(
                text = "「独立迭代」把下面的话原样发给每家 AI；「观点讨论」会把其他 AI 的最新观点转给对方。",
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
                        text = "本轮想补充什么？独立迭代必填",
                        color = colors.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(metrics.controlCorner),
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
                BrandAvatar(service = service, size = 30.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = service.displayName,
                        color = colors.ink,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (run.requestId.isNotBlank() || run.detail != "等待开始") {
                            run.detail
                        } else {
                            status.detail
                        },
                        color = colors.muted,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (run.requestId.isNotBlank()) RunStatusPill(run.phase) else StatusPill(status.state)
            }

            if (active) {
                ArenaProgressBar(
                    progress = null,
                    modifier = Modifier.fillMaxWidth(),
                    height = 3.dp,
                    track = colors.surfaceAlt,
                )
            }

            if (run.response.isNotBlank()) {
                HorizontalDivider(color = colors.border)
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
                            text = "原回答约 ${run.originalResponseLength} 字，已截取前 " +
                                "${ArenaLimits.MAX_CAPTURED_RESPONSE_CHARS} 字；完整内容请打开原网页。",
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

            if (run.phase == ParticipantPhase.ERROR && run.requestId.isNotBlank()) {
                if (run.response.isBlank()) HorizontalDivider(color = colors.border)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ArenaTextAction(
                        text = "重发",
                        onClick = onRetrySend,
                        enabled = recoveryEnabled,
                        modifier = Modifier.weight(1f),
                        contentDescriptionText = "重新发送给 ${service.displayName}",
                    )
                    ArenaTextAction(
                        text = "重新提取",
                        onClick = onRetryExtraction,
                        enabled = recoveryEnabled && canReextract,
                        modifier = Modifier.weight(1.2f),
                        contentDescriptionText = "重新提取 ${service.displayName} 的回答",
                    )
                    ArenaTextAction(
                        text = "跳过",
                        onClick = onSkip,
                        enabled = recoveryEnabled,
                        modifier = Modifier.weight(1f),
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
    isSpeaking: Boolean,
    onSpeechToggle: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onShare: (() -> Unit)?,
) {
    val colors = ArenaStyle.colors
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
                    Text(
                        text = "讨论总结",
                        color = colors.ink,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = summary.judge?.let { "由 ${it.displayName} 生成" }.orEmpty(),
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
                    color = if (ok) colors.surfaceAlt else colors.warningSoft,
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

// ---------------------------------------------------------------------------
// 子页面
// ---------------------------------------------------------------------------

@Composable
private fun RoundtablePageHeader(
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 4.dp, end = metrics.gutter, top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ArenaTextAction(
                text = "‹ 返回",
                onClick = onBack,
                color = colors.onHero,
                contentDescriptionText = "返回上一页",
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = colors.onHero,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = subtitle,
                    color = colors.onHeroMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RoundtableHistoryPage(
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
            subtitle = if (sessions.isEmpty()) "只保存在本机" else "${sessions.size} 条 · 只保存在本机",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = metrics.gutter,
                end = metrics.gutter,
                top = 14.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            if (warning != null) {
                item(key = "warning") {
                    ArenaCard(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.errorSoft,
                        borderColor = colors.error,
                    ) {
                        Text(
                            text = warning,
                            modifier = Modifier.padding(12.dp),
                            color = colors.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (sessions.isEmpty()) {
                item(key = "empty") {
                    ArenaCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "还没有历史记录",
                                color = colors.ink,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "完成一次提问后，会话会自动出现在这里，随时可以继续讨论。",
                                color = colors.muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            } else {
                items(sessions, key = { it.id }) { session ->
                    ArenaCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "继续讨论：${session.title.ifBlank { "未命名问题" }}"
                            },
                        onClick = { onRestore(session.id) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = session.title.ifBlank { "未命名问题" },
                                    color = colors.ink,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${formatRecentTime(session.updatedAtMillis)} · " +
                                        "${session.roundCount} 轮 · ${session.serviceCount} 家",
                                    color = colors.muted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                text = "继续 ›",
                                color = colors.accent,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundtableSettingsPage(
    selectedServices: List<ArenaService>,
    usableCount: Int,
    crashReport: ArenaCrashReport?,
    onClearCrashReport: () -> Unit,
    onShareCrashReport: ((ArenaCrashReport) -> Unit)?,
    largeTextEnabled: Boolean,
    onLargeTextChange: (Boolean) -> Unit,
    speechState: SpeechPlaybackState?,
    stopSpeech: (() -> Unit)?,
    skin: ArenaSkin,
    onBack: () -> Unit,
    onAppearance: () -> Unit,
    onMembers: () -> Unit,
    onConnections: () -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.page),
    ) {
        RoundtablePageHeader(
            title = "设置",
            subtitle = "外观、辅助使用与 AI 连接",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = metrics.gutter,
                end = metrics.gutter,
                top = 14.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            item(key = "appearance") {
                SettingsActionCard(
                    title = "界面风格",
                    detail = "${skin.displayName} · ${skin.tagline}",
                    action = "更换",
                    onClick = onAppearance,
                )
            }
            item(key = "accessibility") {
                AccessibilityToolbar(
                    largeTextEnabled = largeTextEnabled,
                    onLargeTextChange = onLargeTextChange,
                    speechState = speechState,
                    stopSpeech = stopSpeech,
                )
            }
            item(key = "members") {
                SettingsActionCard(
                    title = "AI 成员",
                    detail = selectedServices.joinToString(" · ") { it.shortName },
                    action = "调整",
                    onClick = onMembers,
                )
            }
            item(key = "connections") {
                SettingsActionCard(
                    title = "连接管理",
                    detail = "$usableCount / ${selectedServices.size} 可用 · 登录一次后自动复用",
                    action = "查看",
                    onClick = onConnections,
                )
            }
            item(key = "privacy") {
                ArenaCard(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accentSoft,
                    borderColor = colors.accentSoft,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "隐私说明",
                            color = colors.accent,
                            style = MaterialTheme.typography.titleSmall,
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
            if (crashReport != null) {
                item(key = "crash") {
                    ArenaCard(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.errorSoft,
                        borderColor = colors.error,
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "上次异常退出",
                                color = colors.error,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "${ArenaCrashReporter.formatTime(crashReport.recordedAtMillis)}" +
                                    " · 记录只保存在本机，不会自动上传。导出后发给开发者可以帮助定位问题。",
                                color = colors.muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (onShareCrashReport != null) {
                                    ArenaTextAction(
                                        text = "导出",
                                        onClick = { onShareCrashReport(crashReport) },
                                        contentDescriptionText = "导出上次崩溃记录",
                                    )
                                }
                                ArenaTextAction(
                                    text = "清除",
                                    onClick = onClearCrashReport,
                                    color = colors.muted,
                                    contentDescriptionText = "清除崩溃记录",
                                )
                            }
                        }
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

@Composable
private fun RoundtableAppearancePage(
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
            contentPadding = PaddingValues(
                start = metrics.gutter,
                end = metrics.gutter,
                top = 14.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            item(key = "picker") {
                SkinPicker(selected = skin, onSelect = onSkinChange)
            }
            item(key = "preview-note") {
                ArenaCard(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accentSoft,
                    borderColor = colors.accentSoft,
                ) {
                    Text(
                        text = "选择后整个 App 立即切换，包括按钮大小、圆角和描边粗细。" +
                            "「长辈」风格会同时放大字号并加粗描边；「夜航」适合夜里看。",
                        modifier = Modifier.padding(14.dp),
                        color = colors.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item(key = "large-text") {
                ArenaCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "大字模式",
                                color = colors.ink,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = if (largeTextEnabled) {
                                    "已开启，AI 网页也会同步放大"
                                } else {
                                    "在当前风格基础上再放大一档"
                                },
                                color = colors.muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        ArenaTextAction(
                            text = if (largeTextEnabled) "大字：开" else "大字：关",
                            onClick = { onLargeTextChange(!largeTextEnabled) },
                            color = if (largeTextEnabled) colors.success else colors.accent,
                            contentDescriptionText = if (largeTextEnabled) "关闭大字模式" else "开启大字模式",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsActionCard(
    title: String,
    detail: String,
    action: String,
    onClick: () -> Unit,
) {
    val colors = ArenaStyle.colors
    ArenaCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$title，$action" },
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, color = colors.ink, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = detail,
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "$action ›",
                color = colors.accent,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun RoundtableMembersPage(
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
            subtitle = "选择 ${ArenaService.MIN_MEMBERS}-${ArenaService.MAX_MEMBERS} 家参与圆桌",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = metrics.gutter,
                end = metrics.gutter,
                top = 14.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            items(ArenaService.entries.toList(), key = { it.name }) { service ->
                MemberToggleRow(
                    service = service,
                    selected = service in selectedServices,
                    status = statuses[service] ?: ServiceStatus(),
                    onToggle = { onToggle(service) },
                )
            }
            item(key = "hint") {
                Text(
                    text = "已选 ${selectedServices.size} 家。选得越多，等待时间越长；" +
                        "推荐 2-3 家，观点差异最明显。",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
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
    val metrics = ArenaStyle.metrics
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.border,
        label = "member-border",
    )
    ArenaCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${service.displayName}，${if (selected) "已选择" else "未选择"}"
            },
        color = if (selected) colors.accentSoft else colors.surface,
        borderColor = borderColor,
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = metrics.minTouch)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrandAvatar(service = service, size = 32.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = service.displayName,
                        color = if (selected) colors.accent else colors.ink,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (service.experimental) {
                        ArenaPill(
                            text = "适配中",
                            foreground = colors.warning,
                            background = colors.warningSoft,
                            dot = false,
                        )
                    }
                }
                Text(
                    text = if (status.state.isUsable()) "已登录，可直接使用" else service.loginHint,
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ArenaPill(
                text = if (selected) "已选" else "未选",
                foreground = if (selected) colors.accent else colors.muted,
                background = if (selected) colors.surface else colors.surfaceAlt,
                dot = selected,
            )
        }
    }
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
                text = "登录一次后会自动复用。完成一家后点顶部“返回圆桌”，再继续下一家。",
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

@Composable
private fun AccessibilityToolbar(
    largeTextEnabled: Boolean,
    onLargeTextChange: (Boolean) -> Unit,
    speechState: SpeechPlaybackState?,
    stopSpeech: (() -> Unit)?,
) {
    val colors = ArenaStyle.colors
    ArenaCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "辅助使用",
                    color = colors.ink,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (largeTextEnabled) "大字已开启，网页也会放大" else "需要时可一键放大全局文字",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = speechState?.detail ?: "朗读：不可用",
                    color = if (speechState?.activeKey != null) colors.accent else colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                ArenaTextAction(
                    text = if (largeTextEnabled) "大字：开" else "大字：关",
                    onClick = { onLargeTextChange(!largeTextEnabled) },
                    color = if (largeTextEnabled) colors.success else colors.accent,
                    contentDescriptionText = if (largeTextEnabled) "关闭大字模式" else "开启大字模式",
                )
                if (speechState?.activeKey != null) {
                    ArenaTextAction(
                        text = "停止朗读",
                        onClick = { stopSpeech?.invoke() },
                        color = colors.error,
                        contentDescriptionText = "停止朗读",
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 共用小组件
// ---------------------------------------------------------------------------

@Composable
private fun AnswerModeSelector(
    selected: AnswerMode,
    enabled: Boolean,
    onSelected: (AnswerMode) -> Unit,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(text = "回答方式")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colors.surfaceAlt,
            shape = RoundedCornerShape(metrics.controlCorner),
            border = BorderStroke(metrics.borderWidth, colors.border),
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AnswerMode.entries.forEach { mode ->
                    val isSelected = mode == selected
                    val background by animateColorAsState(
                        targetValue = if (isSelected) colors.surface else Color.Transparent,
                        label = "mode-bg",
                    )
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = metrics.minTouch)
                            .semantics {
                                contentDescription =
                                    "${mode.displayName}，${if (isSelected) "已选择" else "未选择"}"
                            }
                            .clickable(enabled = enabled) { onSelected(mode) },
                        color = background,
                        shape = RoundedCornerShape(metrics.controlCorner - 4.dp),
                        shadowElevation = if (isSelected) 1.dp else 0.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = if (mode == AnswerMode.PARALLEL) "同时回答" else "依次回答",
                                color = if (isSelected) colors.accent else colors.muted,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = if (mode == AnswerMode.PARALLEL) "更快" else "更稳",
                                color = if (isSelected) colors.accent else colors.muted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunStatusPill(phase: ParticipantPhase) {
    val colors = ArenaStyle.colors
    val (background, foreground, label) = when (phase) {
        ParticipantPhase.IDLE -> Triple(colors.surfaceAlt, colors.muted, "等待")
        ParticipantPhase.SENDING -> Triple(colors.accentSoft, colors.accent, "发送中")
        ParticipantPhase.WAITING -> Triple(colors.accentSoft, colors.accent, "等待回答")
        ParticipantPhase.STREAMING -> Triple(colors.accentSoft, colors.accent, "回答中")
        ParticipantPhase.COMPLETE -> Triple(colors.successSoft, colors.success, "完成")
        ParticipantPhase.ERROR -> Triple(colors.errorSoft, colors.error, "失败")
    }
    val pulsing = phase == ParticipantPhase.SENDING ||
        phase == ParticipantPhase.WAITING ||
        phase == ParticipantPhase.STREAMING
    ArenaPill(text = label, foreground = foreground, background = background, pulsing = pulsing)
}

@Composable
private fun StatusPill(state: ConnectionState) {
    val colors = ArenaStyle.colors
    val (background, foreground) = when (state) {
        ConnectionState.SIGNED_IN -> colors.successSoft to colors.success
        ConnectionState.LOADING -> colors.accentSoft to colors.accent
        ConnectionState.ERROR -> colors.errorSoft to colors.error
        ConnectionState.NEEDS_LOGIN, ConnectionState.NOT_LOADED -> colors.warningSoft to colors.warning
    }
    ArenaPill(
        text = statusLabel(state),
        foreground = foreground,
        background = background,
        pulsing = state == ConnectionState.LOADING,
    )
}

@Composable
private fun ProviderHeader(
    service: ArenaService,
    status: ServiceStatus,
    onBack: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ArenaStyle.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.surface,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = 60.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArenaTextAction(
                text = "‹ 圆桌",
                onClick = onBack,
                contentDescriptionText = "返回 AI 圆桌主界面",
            )
            BrandAvatar(service = service, size = 26.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${service.displayName} 原网页",
                    color = colors.ink,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = status.detail,
                    color = colors.muted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusPill(status.state)
            ArenaTextAction(
                text = "刷新",
                onClick = onReload,
                contentDescriptionText = "刷新 ${service.displayName} 网页",
            )
        }
    }
}

@Composable
private fun ArenaBottomBar(
    selected: ArenaService?,
    onRoundtable: () -> Unit,
    onService: (ArenaService) -> Unit,
    statuses: Map<ArenaService, ServiceStatus>,
    services: List<ArenaService>,
) {
    val colors = ArenaStyle.colors
    NavigationBar(
        containerColor = colors.navSurface,
        tonalElevation = 0.dp,
    ) {
        NavigationBarItem(
            selected = selected == null,
            onClick = onRoundtable,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_roundtable),
                    contentDescription = "圆桌",
                    tint = if (selected == null) colors.accent else colors.muted,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = {
                Text(
                    text = "圆桌",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            },
            alwaysShowLabel = true,
            colors = navColors(),
        )
        services.forEach { service ->
            NavigationBarItem(
                selected = selected == service,
                onClick = { onService(service) },
                icon = {
                    Box {
                        BrandAvatar(service = service, size = 23.dp)
                        if (statuses[service]?.state?.isUsable() == true) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .align(Alignment.TopEnd)
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(colors.success),
                            )
                        }
                    }
                },
                label = {
                    Text(
                        text = service.shortName,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                },
                alwaysShowLabel = true,
                colors = navColors(),
            )
        }
    }
}

@Composable
private fun navColors(): NavigationBarItemColors {
    val colors = ArenaStyle.colors
    return NavigationBarItemDefaults.colors(
        selectedIconColor = colors.accent,
        selectedTextColor = colors.accent,
        indicatorColor = colors.accentSoft,
        unselectedIconColor = colors.muted,
        unselectedTextColor = colors.muted,
    )
}

private fun formatRecentTime(value: Long): String =
    if (value <= 0L) "时间未知" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(value))

private fun statusLabel(state: ConnectionState): String = when (state) {
    ConnectionState.NOT_LOADED -> "未打开"
    ConnectionState.LOADING -> "加载中"
    ConnectionState.NEEDS_LOGIN -> "待登录"
    ConnectionState.SIGNED_IN -> "可用"
    ConnectionState.ERROR -> "需重试"
}
