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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
            entries.firstOrNull { it.name == value }?.let { if (it == APPEARANCE) SETTINGS else it } ?: HOME
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
    copyText: TextCopyRequest? = null,
    shareText: TextShareRequest? = null,
    /** 用系统浏览器打开外链（下载新版 APK）。返回 false 表示没有可用浏览器。 */
    openExternalUrl: ((String) -> Boolean)? = null,
    /** 「重启应用」：由 Activity 提供，见 ArenaRestart。为 null 时设置页不显示该项。 */
    restartApp: (() -> Unit)? = null,
    skin: ArenaSkin = ArenaSkin.default,
    onSkinChange: (ArenaSkin) -> Unit = {},
) {
    val contentStateHolder = rememberSaveableStateHolder()
    val colors = ArenaStyle.colors
    val context = LocalContext.current
    val accessibilityPreferences = remember(context) { AccessibilityPreferences(context) }
    val navigationPreferences = remember(context) { ArenaNavigationPreferences(context) }
    val guidePreferences = remember(context) { ArenaGuidePreferences(context) }
    val sessionRepository = remember(context) { ArenaSessionStore(context) }
    val sessionController = remember(pool, sessionRepository) {
        ArenaSessionController(pool = ArenaTextOnlyGateway(pool), sessionRepository = sessionRepository)
    }
    val questionDraft = rememberSaveable { mutableStateOf(debugInitialQuestion.ifBlank { sessionController.originalQuestion }) }
    val guidanceDraft = rememberSaveable { mutableStateOf("") }
    val network = remember(context) { ArenaNetworkMonitor(context) }
    DisposableEffect(network) {
        network.start()
        onDispose { network.stop() }
    }
    // 网络恢复后把当时没加载出来的网页重载一遍：家人看到"网络没有连上"消失，
    // 接着点「重发」就能直接发，不用先去「打开网页」手动刷新。
    LaunchedEffect(network.isOnline) {
        if (network.isOnline) pool.reloadFailed()
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


        Scaffold(
            containerColor = colors.page,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
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

                    else -> contentStateHolder.SaveableStateProvider("roundtable") { RoundtableRoot(
                        pool = pool,
                        sessionController = sessionController,
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
                        questionDraft = questionDraft,
                        guidanceDraft = guidanceDraft,
                        largeTextEnabled = largeTextEnabled,
                        onLargeTextChange = { largeTextEnabled = it },
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
                    ) }
                }
            }
        }
    }
}

@Composable
private fun RoundtableRoot(
    pool: ArenaWebViewPool,
    sessionController: ArenaSessionController,
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
    questionDraft: MutableState<String>,
    guidanceDraft: MutableState<String>,
    largeTextEnabled: Boolean,
    onLargeTextChange: (Boolean) -> Unit,
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
            questionDraft = questionDraft,
            guidanceDraft = guidanceDraft,
            largeTextEnabled = largeTextEnabled,
            onLargeTextChange = onLargeTextChange,
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
    questionDraft: MutableState<String>,
    guidanceDraft: MutableState<String>,
    largeTextEnabled: Boolean,
    onLargeTextChange: (Boolean) -> Unit,
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
    var question by questionDraft
    // 队长与总结深度的记忆：结果页「队长总结」用它记住上次的选择。
    val captainPreferences = remember(context) { ArenaCaptainPreferences(context) }
    var roundGuidance by guidanceDraft
    val scope = rememberCoroutineScope()
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

    /** 回到干净的提问页：顶部「新会话」、设置恢复与崩溃恢复共用；reset 先保存旧讨论。 */
    val startFresh: () -> Boolean = {
        val resetSucceeded = sessionController.reset()
        if (resetSucceeded) {
            question = ""
            roundGuidance = ""
        } else {
            scope.launch { snackbarHostState.showSnackbar(sessionController.storageWarning ?: "当前讨论未能保存，暂未开始新会话") }
        }
        resetSucceeded
    }

    val restoreRecentSession: (String) -> Unit = { sessionId ->
        val outcome = sessionController.restoreSession(sessionId)
        if (outcome == RestoreOutcome.OK || outcome == RestoreOutcome.OK_AFTER_STOP) {
            question = sessionController.originalQuestion
            roundGuidance = ""
            onPageChange(RoundtablePage.HOME)
            onSelectedServicesChange(sessionController.sessionServices)
        }
        val message = when (outcome) {
            RestoreOutcome.OK -> "已打开这条讨论"
            RestoreOutcome.OK_AFTER_STOP -> "已停止进行中的一轮，并打开这条讨论"
            RestoreOutcome.UNREADABLE -> "这条记录的文件已损坏，已从列表移除"
            RestoreOutcome.NO_STORAGE -> "本地存储不可用，无法打开历史"
            RestoreOutcome.SAVE_FAILED -> "当前讨论未能保存，已保留当前内容，暂未切换历史"
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

            RoundtablePage.SETTINGS -> SimpleSettingsPage(
                selectedServices = selectedServices,
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
                onBack = { onPageChange(RoundtablePage.HOME) },
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
                    if (startFresh()) {
                        onPageChange(RoundtablePage.HOME)
                        scope.launch { snackbarHostState.showSnackbar("已清除，回到了提问页") }
                    }
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
                SimpleAskHome(
                    question = question, onQuestionChange = { question = it },
                    selectedServices = selectedServices, usableCount = usableCount,
                    onMembers = { onMembersReturnPageChange(RoundtablePage.HOME); onPageChange(RoundtablePage.MEMBERS) },
                    onConnections = onManageConnections, onOpenService = onOpenService, onNavigate = onPageChange,
                    lengthAdvisory = QuestionLengthPolicy.advisory(question, selectedServices), offline = offline,
                    crashNotice = unacknowledgedCrash, onCrashDismiss = onAcknowledgeCrash,
                    onNeedQuestion = { scope.launch { snackbarHostState.showSnackbar("先写下问题") } },
                    onTooLong = { scope.launch { snackbarHostState.showSnackbar("问题超过 ${ArenaLimits.MAX_QUESTION_CHARS} 字了") } },
                    onStart = {
                        if (!sessionController.startInitial(question, usableServices, AnswerMode.PARALLEL)) {
                            scope.launch { snackbarHostState.showSnackbar(sessionController.sessionMessage) }
                        }
                    },
                )
            } else {
                SimpleRoundStage(
                    statuses = pool.statuses, sessionController = sessionController,
                    roundGuidance = roundGuidance, onRoundGuidanceChange = { roundGuidance = it },
                    onNewSession = { startFresh() }, onNavigate = onPageChange, onOpenService = onOpenService,
                    snackbarHostState = snackbarHostState, copyText = copyText, shareText = shareText,
                    offline = offline, captainPreferences = captainPreferences,
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
    Surface(modifier = modifier.fillMaxWidth(), color = colors.page) {
        Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SimpleIcon(R.drawable.ic_arrow_back, "返回 AI 圆桌主界面", onBack)
                BrandAvatar(service = service, size = 26.dp)
                Text(service.shortName, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleSmall)
                SimpleIcon(R.drawable.ic_refresh, "刷新 ${service.shortName} 网页", onReload)
            }
            when (status.state) {
                ConnectionState.NEEDS_LOGIN -> Text("请在下方官网登录，完成后返回圆桌。", Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.muted)
                ConnectionState.ERROR -> Text(ArenaErrorHelp.explain(status.detail, service.shortName).what,
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = colors.error)
                else -> Unit
            }
            HorizontalDivider(color = colors.border, thickness = 0.5.dp)
        }
    }
}

// ---------------------------------------------------------------------------
// 底部三个入口：圆桌 / 历史 / 设置
// ---------------------------------------------------------------------------

internal fun formatRecentTime(value: Long): String =
    if (value <= 0L) "时间未知" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(value))

/** 提问时间的人话写法：今天 / 昨天 只给时刻，今年给月日，更早给年月日。 */
internal fun formatAskedTime(value: Long, now: Long = System.currentTimeMillis()): String {
    if (value <= 0L) return "时间未知"
    val day = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
    val clock = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(value))
    val askedDay = day.format(Date(value))
    val today = day.format(Date(now))
    val yesterday = day.format(Date(now - 86_400_000L))
    return when {
        askedDay == today -> "今天 $clock"
        askedDay == yesterday -> "昨天 $clock"
        askedDay.take(4) == today.take(4) -> SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(value))
        else -> SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(value))
    }
}

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
        ParticipantPhase.QUEUED -> Triple(colors.accentSoft, colors.accent, "已排队")
        ParticipantPhase.SENDING -> Triple(colors.accentSoft, colors.accent, "发送中")
        ParticipantPhase.WAITING -> Triple(colors.accentSoft, colors.accent, "等待回答")
        ParticipantPhase.STREAMING -> Triple(colors.accentSoft, colors.accent, "回答中")
        ParticipantPhase.COMPLETE -> Triple(colors.successSoft, colors.success, "完成")
        ParticipantPhase.ERROR -> Triple(colors.errorSoft, colors.error, "没成功")
    }
    val pulsing = phase == ParticipantPhase.QUEUED ||
        phase == ParticipantPhase.SENDING ||
        phase == ParticipantPhase.WAITING ||
        phase == ParticipantPhase.STREAMING
    ArenaPill(text = label, foreground = foreground, background = background, pulsing = pulsing)
}
