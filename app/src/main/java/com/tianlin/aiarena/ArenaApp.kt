package com.tianlin.aiarena

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class RoundtablePage {
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

internal val QuestionExamples = listOf(
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
    /** 用系统浏览器打开外链（下载新版 APK）。返回 false 表示没有可用浏览器。 */
    openExternalUrl: ((String) -> Boolean)? = null,
    /** 「重启应用」：由 Activity 提供，见 ArenaRestart。为 null 时设置页不显示该项。 */
    restartApp: (() -> Unit)? = null,
    skin: ArenaSkin = ArenaSkin.default,
    onSkinChange: (ArenaSkin) -> Unit = {},
) {
    val colors = ArenaStyle.colors
    val context = LocalContext.current
    val accessibilityPreferences = remember(context) { AccessibilityPreferences(context) }
    val navigationPreferences = remember(context) { ArenaNavigationPreferences(context) }
    val guidePreferences = remember(context) { ArenaGuidePreferences(context) }
    val sessionRepository = remember(context) { ArenaSessionStore(context) }
    val sessionController = remember(pool, sessionRepository) {
        ArenaSessionController(pool = pool, sessionRepository = sessionRepository)
    }
    val network = remember(context) { ArenaNetworkMonitor(context) }
    DisposableEffect(network) {
        network.start()
        onDispose { network.stop() }
    }
    // 只在启动时读一次；清除后用这个计数触发重读。
    var crashReportGeneration by remember { mutableIntStateOf(0) }
    val crashReport = remember(context, crashReportGeneration) {
        runCatching { ArenaCrashReporter.latest(context) }.getOrNull()
    }
    var acknowledgedCrash by remember {
        mutableStateOf(crashReport?.fileName?.takeIf { guidePreferences.isCrashAcknowledged(it) })
    }
    val unacknowledgedCrash = crashReport?.takeIf { it.fileName != acknowledgedCrash }
    // 应用内更新检查：自动一天最多一次，设置页手动点不限。结果缓存在本机，
    // 下次冷启动到点了会再查。网络请求在 IO 线程，主线程只收结果。
    var updateResult by remember { mutableStateOf(ArenaUpdateChecker.cachedResult(context)) }
    var updateChecking by remember { mutableStateOf(false) }
    var dismissedUpdateCode by remember { mutableIntStateOf(ArenaUpdateChecker.dismissedVersionCode(context)) }
    val updateScope = rememberCoroutineScope()
    val checkForUpdate: (Boolean) -> Unit = { manual ->
        if (!updateChecking) {
            updateChecking = true
            updateScope.launch {
                val result = withContext(Dispatchers.IO) { ArenaUpdateChecker.fetch(cacheInto = context) }
                updateResult = result
                updateChecking = false
                if (!manual) ArenaUpdateChecker.markAutoChecked(context)
            }
        }
    }
    LaunchedEffect(Unit) {
        if (ArenaUpdateChecker.shouldAutoCheck(context)) checkForUpdate(false)
    }
    val availableUpdate = (updateResult as? ArenaUpdateResult.Available)?.info
    val installUpdate: (ArenaUpdateInfo) -> Unit = { info ->
        // 打不开浏览器就退回下载页地址；至少让用户能复制链接
        if (openExternalUrl?.invoke(info.apkUrl) != true) {
            openExternalUrl?.invoke(ArenaUpdateChecker.DOWNLOAD_PAGE_URL)
        }
    }
    val dismissUpdate: (ArenaUpdateInfo) -> Unit = { info ->
        ArenaUpdateChecker.dismiss(context, info.versionCode)
        dismissedUpdateCode = info.versionCode
    }
    var largeTextEnabled by rememberSaveable {
        mutableStateOf(accessibilityPreferences.isLargeTextEnabled())
    }
    var onboardingVisible by rememberSaveable { mutableStateOf(!guidePreferences.hasSeenOnboarding()) }
    var selectedServiceName by rememberSaveable { mutableStateOf<String?>(null) }
    var showConnections by rememberSaveable { mutableStateOf(false) }
    /** 从登录引导页打开某家网页时记一下，返回时回引导页而不是首页，好接着登录下一家。 */
    var returnToGuide by rememberSaveable { mutableStateOf(false) }
    var pageName by rememberSaveable { mutableStateOf(RoundtablePage.HOME.name) }
    var membersReturnPageName by rememberSaveable { mutableStateOf(RoundtablePage.HOME.name) }
    val page = RoundtablePage.fromName(pageName)
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
        returnToGuide = false
    }

    fun openService(service: ArenaService) {
        returnToGuide = connectionGuideVisible
        pool.open(service)
        selectedServiceName = service.name
    }

    fun leaveService() {
        if (returnToGuide) {
            // 引导页还在（要么用户主动管理连接，要么还没登录够两家）：回去接着下一家。
            selectedServiceName = null
            returnToGuide = false
        } else {
            returnToRoundtable()
        }
    }

    fun selectTab(target: RoundtablePage) {
        returnToRoundtable()
        pageName = target.name
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

    // 三个返回键处理器互斥：网页页 > 登录引导 > 子页面。
    BackHandler(enabled = selectedService == null && !connectionGuideVisible && page != RoundtablePage.HOME) {
        pageName = when (page) {
            RoundtablePage.MEMBERS -> membersReturnPageName
            RoundtablePage.APPEARANCE -> RoundtablePage.SETTINGS.name
            else -> RoundtablePage.HOME.name
        }
    }

    BackHandler(enabled = selectedService == null && connectionGuideVisible) {
        returnToRoundtable()
    }

    BackHandler(enabled = selectedService != null) {
        if (!pool.goBack(selectedService!!)) leaveService()
    }

    val restart: (() -> Unit)? = restartApp?.let { trigger ->
        {
            // 进程马上就没了：会话同步落盘，Cookie 在 trigger 里 flush。
            runCatching { sessionController.destroy() }
            trigger()
        }
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

        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val bottomBarVisible = !imeVisible && selectedService == null && !onboardingVisible

        Scaffold(
            containerColor = colors.page,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                // 键盘弹出时底栏本来就被完全挡住，但它仍然占着 innerPadding.bottom，
                // 而内容侧又叠了一层 imePadding() —— 两份底部内边距叠加会把中间可滚区
                // 挤到只剩一行。键盘打开时直接不摆它；看网页和首次引导时也不摆。
                if (bottomBarVisible) {
                    ArenaBottomBar(
                        page = if (connectionGuideVisible) RoundtablePage.HOME else page,
                        historyCount = sessionController.recentSessions.size,
                        onSelect = ::selectTab,
                    )
                }
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

                when {
                    selectedService != null -> ProviderHeader(
                        service = selectedService,
                        status = pool.statuses[selectedService] ?: ServiceStatus(),
                        onBack = ::leaveService,
                        onReload = { pool.reload(selectedService) },
                        modifier = Modifier.onSizeChanged { providerHeaderPx = it.height },
                    )

                    onboardingVisible -> OnboardingPage(
                        onDone = {
                            guidePreferences.markOnboardingSeen()
                            onboardingVisible = false
                        },
                    )

                    else -> RoundtableRoot(
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
                        onManageConnections = {
                            pageName = RoundtablePage.HOME.name
                            showConnections = true
                        },
                        onOpenService = ::openService,
                        page = page,
                        onPageChange = { pageName = it.name },
                        membersReturnPage = RoundtablePage.fromName(membersReturnPageName),
                        onMembersReturnPageChange = { membersReturnPageName = it.name },
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
                        offline = !network.isOnline,
                        crashReport = crashReport,
                        unacknowledgedCrash = unacknowledgedCrash,
                        onAcknowledgeCrash = {
                            crashReport?.let { report ->
                                guidePreferences.acknowledgeCrash(report.fileName)
                                acknowledgedCrash = report.fileName
                            }
                        },
                        onClearCrashReport = {
                            ArenaCrashReporter.clear(context)
                            crashReportGeneration += 1
                        },
                        updateResult = updateResult,
                        updateChecking = updateChecking,
                        bannerUpdate = availableUpdate?.takeIf { it.versionCode != dismissedUpdateCode },
                        onCheckUpdate = { checkForUpdate(true) },
                        onInstallUpdate = installUpdate,
                        onDismissUpdate = dismissUpdate,
                        onReloadPages = {
                            val count = pool.reloadAll()
                            count
                        },
                        onRestartApp = restart,
                        onShowOnboarding = { onboardingVisible = true },
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
    page: RoundtablePage,
    onPageChange: (RoundtablePage) -> Unit,
    membersReturnPage: RoundtablePage,
    onMembersReturnPageChange: (RoundtablePage) -> Unit,
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
    offline: Boolean,
    crashReport: ArenaCrashReport?,
    unacknowledgedCrash: ArenaCrashReport?,
    onAcknowledgeCrash: () -> Unit,
    onClearCrashReport: () -> Unit,
    updateResult: ArenaUpdateResult?,
    updateChecking: Boolean,
    /** 首页横幅只展示未被「以后」掉的新版本；设置页始终展示。 */
    bannerUpdate: ArenaUpdateInfo?,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (ArenaUpdateInfo) -> Unit,
    onDismissUpdate: (ArenaUpdateInfo) -> Unit,
    onReloadPages: () -> Int,
    onRestartApp: (() -> Unit)?,
    onShowOnboarding: () -> Unit,
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
            page = page,
            onPageChange = onPageChange,
            membersReturnPage = membersReturnPage,
            onMembersReturnPageChange = onMembersReturnPageChange,
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
            offline = offline,
            crashReport = crashReport,
            unacknowledgedCrash = unacknowledgedCrash,
            onAcknowledgeCrash = onAcknowledgeCrash,
            onClearCrashReport = onClearCrashReport,
            updateResult = updateResult,
            updateChecking = updateChecking,
            bannerUpdate = bannerUpdate,
            onCheckUpdate = onCheckUpdate,
            onInstallUpdate = onInstallUpdate,
            onDismissUpdate = onDismissUpdate,
            onReloadPages = onReloadPages,
            onRestartApp = onRestartApp,
            onShowOnboarding = onShowOnboarding,
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
            offline = offline,
        )
    }
}

// ---------------------------------------------------------------------------
// 圆桌主流程：提问 / 结果 / 子页面之间的切换与共享状态
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
    page: RoundtablePage,
    onPageChange: (RoundtablePage) -> Unit,
    membersReturnPage: RoundtablePage,
    onMembersReturnPageChange: (RoundtablePage) -> Unit,
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
    offline: Boolean,
    crashReport: ArenaCrashReport?,
    unacknowledgedCrash: ArenaCrashReport?,
    onAcknowledgeCrash: () -> Unit,
    onClearCrashReport: () -> Unit,
    updateResult: ArenaUpdateResult?,
    updateChecking: Boolean,
    bannerUpdate: ArenaUpdateInfo?,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (ArenaUpdateInfo) -> Unit,
    onDismissUpdate: (ArenaUpdateInfo) -> Unit,
    onReloadPages: () -> Int,
    onRestartApp: (() -> Unit)?,
    onShowOnboarding: () -> Unit,
) {
    val context = LocalContext.current
    val guidePreferences = remember(context) { ArenaGuidePreferences(context) }
    var question by rememberSaveable {
        mutableStateOf(debugInitialQuestion.ifBlank { sessionController.originalQuestion })
    }
    var answerModeName by rememberSaveable { mutableStateOf(guidePreferences.loadAnswerMode().name) }
    var roundGuidance by rememberSaveable { mutableStateOf("") }
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

    /** 回到干净的提问页：结果页「开始新问题」、设置页「清除卡住的讨论」、崩溃提示「重新开始」共用。 */
    val startFresh: () -> Unit = {
        stopSpeech?.invoke()
        sessionController.reset()
        question = ""
        roundGuidance = ""
        expandedAnswers.clear()
    }

    val restoreRecentSession: (String) -> Unit = { sessionId ->
        stopSpeech?.invoke()
        val outcome = sessionController.restoreSession(sessionId)
        if (outcome == RestoreOutcome.OK || outcome == RestoreOutcome.OK_AFTER_STOP) {
            question = sessionController.originalQuestion
            roundGuidance = ""
            expandedAnswers.clear()
            onPageChange(RoundtablePage.HOME)
            onSelectedServicesChange(sessionController.sessionServices)
        }
        val message = when (outcome) {
            RestoreOutcome.OK -> "已打开这条讨论"
            RestoreOutcome.OK_AFTER_STOP -> "已停止进行中的一轮，并打开这条讨论"
            RestoreOutcome.UNREADABLE -> "这条记录的文件已损坏，已从列表移除"
            RestoreOutcome.NO_STORAGE -> "本地存储不可用，无法打开历史"
        }
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Crossfade(
        targetState = page,
        animationSpec = tween(durationMillis = 180),
        label = "roundtable-page",
    ) { current ->
        when (current) {
            RoundtablePage.HISTORY -> RoundtableHistoryPage(
                sessions = sessionController.recentSessions,
                warning = sessionController.storageWarning,
                onBack = { onPageChange(RoundtablePage.HOME) },
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
                updateResult = updateResult,
                updateChecking = updateChecking,
                onCheckUpdate = onCheckUpdate,
                onInstallUpdate = onInstallUpdate,
                largeTextEnabled = largeTextEnabled,
                onLargeTextChange = onLargeTextChange,
                speechState = speechState,
                stopSpeech = stopSpeech,
                skin = skin,
                answerMode = answerMode,
                onAnswerModeChange = { mode ->
                    answerModeName = mode.name
                    guidePreferences.saveAnswerMode(mode)
                },
                onBack = { onPageChange(RoundtablePage.HOME) },
                onAppearance = { onPageChange(RoundtablePage.APPEARANCE) },
                onMembers = {
                    onMembersReturnPageChange(RoundtablePage.SETTINGS)
                    onPageChange(RoundtablePage.MEMBERS)
                },
                onConnections = onManageConnections,
                onReloadPages = {
                    if (sessionController.isBusy) {
                        scope.launch { snackbarHostState.showSnackbar("本轮结束后再刷新，否则正在收的回答会丢") }
                    } else {
                        val count = onReloadPages()
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (count > 0) "正在重新加载 $count 个 AI 网页，登录不会丢" else "现在没有可以刷新的网页",
                            )
                        }
                    }
                },
                onResetSession = {
                    startFresh()
                    onPageChange(RoundtablePage.HOME)
                    scope.launch { snackbarHostState.showSnackbar("已清除，回到了提问页") }
                },
                onRestartApp = onRestartApp,
                onShowOnboarding = onShowOnboarding,
            )

            RoundtablePage.APPEARANCE -> RoundtableAppearancePage(
                skin = skin,
                onSkinChange = onSkinChange,
                largeTextEnabled = largeTextEnabled,
                onLargeTextChange = onLargeTextChange,
                onBack = { onPageChange(RoundtablePage.SETTINGS) },
            )

            RoundtablePage.MEMBERS -> RoundtableMembersPage(
                selectedServices = selectedServices,
                loginNeededServices = loginNeededServices,
                statuses = pool.statuses,
                onToggle = toggleMember,
                onOpenService = onOpenService,
                onBack = { onPageChange(membersReturnPage) },
            )

            RoundtablePage.HOME -> if (sessionStage == SessionStage.IDLE) {
                AskHome(
                    question = question,
                    onQuestionChange = { question = it },
                    questionWithinLimit = questionWithinLimit,
                    lengthAdvisory = QuestionLengthPolicy.advisory(question, selectedServices),
                    selectedServices = selectedServices,
                    usableCount = usableCount,
                    onMembers = {
                        onMembersReturnPageChange(RoundtablePage.HOME)
                        onPageChange(RoundtablePage.MEMBERS)
                    },
                    onConnections = onManageConnections,
                    voiceInputActive = voiceInputState?.active == true,
                    voiceInputEnabled = voiceInputRequest != null,
                    onVoiceInput = { voiceInputRequest?.invoke() },
                    offline = offline,
                    crashNotice = unacknowledgedCrash,
                    onCrashRestart = {
                        startFresh()
                        onAcknowledgeCrash()
                    },
                    onCrashDismiss = onAcknowledgeCrash,
                    availableUpdate = bannerUpdate,
                    onInstallUpdate = onInstallUpdate,
                    onDismissUpdate = onDismissUpdate,
                    onNeedQuestion = {
                        scope.launch { snackbarHostState.showSnackbar("先在上面写下你的问题，或者点「语音输入」说出来") }
                    },
                    onTooLong = {
                        scope.launch {
                            snackbarHostState.showSnackbar("问题超过 ${ArenaLimits.MAX_QUESTION_CHARS} 字了，删掉一些再发")
                        }
                    },
                    onStart = {
                        expandedAnswers.clear()
                        if (sessionController.startInitial(question, usableServices, answerMode)) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "已发给 ${usableServices.size} 位 AI，正在等回答",
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
                    onOpenService = onOpenService,
                    snackbarHostState = snackbarHostState,
                    speechState = speechState,
                    speechPlaybackRequest = speechPlaybackRequest,
                    stopSpeech = stopSpeech,
                    copyText = copyText,
                    shareText = shareText,
                    offline = offline,
                    onNewQuestion = startFresh,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 某家 AI 的原网页：顶栏 + 登录引导条
// ---------------------------------------------------------------------------

@Composable
private fun ProviderHeader(
    service: ArenaService,
    status: ServiceStatus,
    onBack: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ArenaStyle.colors
    val metrics = ArenaStyle.metrics
    // 这次打开期间是否见过"需要登录"：见过、之后又变成可用，就是刚登录成功。
    var sawNeedsLogin by remember(service) { mutableStateOf(false) }
    LaunchedEffect(status.state) {
        if (status.state == ConnectionState.NEEDS_LOGIN) sawNeedsLogin = true
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.surface,
        shadowElevation = if (metrics.flatSurfaces) 0.dp else 4.dp,
    ) {
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArenaBackButton(
                    onClick = onBack,
                    label = "返回圆桌",
                    contentDescriptionText = "返回 AI 圆桌主界面",
                )
                BrandAvatar(service = service, size = 26.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = service.displayName,
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
            if (metrics.flatSurfaces) HorizontalDivider(color = colors.border)
            val hint: (@Composable () -> Unit)? = when {
                status.state == ConnectionState.SIGNED_IN && sawNeedsLogin -> {
                    {
                        ArenaNotice(
                            tone = NoticeTone.SUCCESS,
                            title = "${service.displayName} 已登录",
                            text = "登录信息已记住，以后不用再登。可以回圆桌继续了。",
                            actionLabel = "返回圆桌",
                            onAction = onBack,
                            actionContentDescription = "登录完成，返回圆桌",
                        )
                    }
                }
                status.state == ConnectionState.NEEDS_LOGIN -> {
                    {
                        ArenaNotice(
                            tone = NoticeTone.INFO,
                            title = "在下面的网页里登录 ${service.displayName}",
                            text = "${service.loginHint}，和平时用它一样。登录成功后这里会提示你返回。",
                        )
                    }
                }
                status.state == ConnectionState.ERROR -> {
                    {
                        ArenaNotice(
                            tone = NoticeTone.ERROR,
                            title = "网页没有打开",
                            text = ArenaErrorHelp.explain(status.detail, service.displayName).let { "${it.what} ${it.next}" }
                                .replace("「重发」", "「重新加载」"),
                            actionLabel = "重新加载",
                            onAction = onReload,
                        )
                    }
                }
                else -> null
            }
            if (hint != null) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { hint() }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 底部三个入口：圆桌 / 历史 / 设置
// ---------------------------------------------------------------------------

@Composable
private fun ArenaBottomBar(
    page: RoundtablePage,
    historyCount: Int,
    onSelect: (RoundtablePage) -> Unit,
) {
    val colors = ArenaStyle.colors
    val settingsSelected = page == RoundtablePage.SETTINGS || page == RoundtablePage.APPEARANCE
    NavigationBar(
        containerColor = colors.navSurface,
        tonalElevation = 0.dp,
    ) {
        NavigationBarItem(
            selected = page == RoundtablePage.HOME || page == RoundtablePage.MEMBERS,
            onClick = { onSelect(RoundtablePage.HOME) },
            icon = {
                ArenaIcon(
                    R.drawable.ic_roundtable,
                    tint = if (page == RoundtablePage.HOME) colors.accent else colors.muted,
                    size = 26.dp,
                )
            },
            label = { BottomLabel("圆桌", "回到圆桌") },
            alwaysShowLabel = true,
            colors = navColors(),
        )
        NavigationBarItem(
            selected = page == RoundtablePage.HISTORY,
            onClick = { onSelect(RoundtablePage.HISTORY) },
            icon = {
                ArenaIcon(
                    R.drawable.ic_history,
                    tint = if (page == RoundtablePage.HISTORY) colors.accent else colors.muted,
                    size = 26.dp,
                )
            },
            label = { BottomLabel(if (historyCount > 0) "历史 $historyCount" else "历史", "查看最近问题") },
            alwaysShowLabel = true,
            colors = navColors(),
        )
        NavigationBarItem(
            selected = settingsSelected,
            onClick = { onSelect(RoundtablePage.SETTINGS) },
            icon = {
                ArenaIcon(
                    R.drawable.ic_settings,
                    tint = if (settingsSelected) colors.accent else colors.muted,
                    size = 26.dp,
                )
            },
            label = { BottomLabel("设置", "打开设置") },
            alwaysShowLabel = true,
            colors = navColors(),
        )
    }
}

/**
 * 底栏文字。读屏描述放在文字上而不是图标上：Material 的 NavigationBarItem 会把图标描述
 * 合并进条目，但 uiautomator 看不到合并后的节点（真机 QA 脚本靠这个描述找入口）。
 */
@Composable
private fun BottomLabel(text: String, description: String) {
    Text(
        text = text,
        modifier = Modifier.semantics { contentDescription = description },
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
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

internal fun formatRecentTime(value: Long): String =
    if (value <= 0L) "时间未知" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(value))

internal fun statusLabel(state: ConnectionState): String = when (state) {
    ConnectionState.NOT_LOADED -> "未打开"
    ConnectionState.LOADING -> "加载中"
    ConnectionState.NEEDS_LOGIN -> "待登录"
    ConnectionState.SIGNED_IN -> "已登录"
    ConnectionState.ERROR -> "需重试"
}

@Composable
internal fun StatusPill(state: ConnectionState) {
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
internal fun RunStatusPill(phase: ParticipantPhase) {
    val colors = ArenaStyle.colors
    val (background, foreground, label) = when (phase) {
        ParticipantPhase.IDLE -> Triple(colors.surfaceAlt, colors.muted, "等待")
        ParticipantPhase.SENDING -> Triple(colors.accentSoft, colors.accent, "发送中")
        ParticipantPhase.WAITING -> Triple(colors.accentSoft, colors.accent, "等待回答")
        ParticipantPhase.STREAMING -> Triple(colors.accentSoft, colors.accent, "回答中")
        ParticipantPhase.COMPLETE -> Triple(colors.successSoft, colors.success, "完成")
        ParticipantPhase.ERROR -> Triple(colors.errorSoft, colors.error, "没成功")
    }
    val pulsing = phase == ParticipantPhase.SENDING ||
        phase == ParticipantPhase.WAITING ||
        phase == ParticipantPhase.STREAMING
    ArenaPill(text = label, foreground = foreground, background = background, pulsing = pulsing)
}

