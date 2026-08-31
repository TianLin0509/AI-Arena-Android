package com.tianlin.aiarena

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PageBackground = Color(0xFFF6F7F9)
private val Ink = Color(0xFF17232D)
private val Muted = Color(0xFF5B6B77)
private val Accent = Color(0xFF1D6078)
private val AccentSoft = Color(0xFFE7F2F5)
private val Border = Color(0xFFDDE4E8)
private val Success = Color(0xFF257A50)
private val SuccessSoft = Color(0xFFE8F5EE)
private val Warning = Color(0xFF9A5B18)
private val WarningSoft = Color(0xFFFFF3E4)
private val Debate = Color(0xFF71558B)
private val DebateSoft = Color(0xFFF0EAF5)
private val Error = Color(0xFFB33A3A)
private val ErrorSoft = Color(0xFFFCEBE9)

private enum class RoundtablePage {
    HOME,
    HISTORY,
    SETTINGS,
    MEMBERS,
}

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
) {
    val context = LocalContext.current
    val accessibilityPreferences = remember(context) { AccessibilityPreferences(context) }
    val navigationPreferences = remember(context) { ArenaNavigationPreferences(context) }
    val sessionRepository = remember(context) { ArenaSessionStore(context) }
    val sessionController = remember(pool, sessionRepository) {
        ArenaSessionController(pool = pool, sessionRepository = sessionRepository)
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
    val selectedService = selectedServiceName?.let(ArenaService::valueOf)
    val selectedMembers = selectedMemberNames.split(',')
        .mapNotNull { name -> ArenaService.entries.firstOrNull { it.name == name } }
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
    Scaffold(
        containerColor = PageBackground,
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
                update = { container ->
                    pool.show(selectedService)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (selectedService == null) 0.dp else 68.dp),
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
                )
            } else {
                ProviderHeader(
                    service = selectedService,
                    status = pool.statuses[selectedService] ?: ServiceStatus(),
                    onBack = { returnToRoundtable() },
                    onReload = { pool.reload(selectedService) },
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
    val nextService = services.firstOrNull {
        statuses[it]?.state?.isUsable() != true
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onPreviewHome,
                    modifier = Modifier
                        .height(48.dp)
                        .semantics { contentDescription = "返回 AI 圆桌主界面" },
                ) {
                    Text("返回圆桌", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Surface(
                    color = AccentSoft,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = "$usableCount / ${services.size} 可使用",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("连接 AI", color = Ink, style = MaterialTheme.typography.titleLarge)
                Text(
                    if (isManagingConnections) "选好成员后逐个登录，随时可以返回" else "登录一次，之后自动复用",
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            AccessibilityToolbar(
                largeTextEnabled = largeTextEnabled,
                onLargeTextChange = onLargeTextChange,
                speechState = speechState,
                stopSpeech = stopSpeech,
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "连接 2 家即可开始",
                    color = Ink,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "无需注册圆桌账号，登录信息只保存在本机。",
                    color = Muted,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        items(services.size) { index ->
            val service = services[index]
            ConnectionCard(
                service = service,
                status = statuses[service] ?: ServiceStatus(),
                onClick = { onOpenService(service) },
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                services.forEach { service ->
                    val connected = statuses[service]?.state?.isUsable() == true
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(if (connected) Accent else Color(0xFFDDE6EC)),
                    )
                }
            }
        }

        item {
            Button(
                onClick = { nextService?.let(onOpenService) },
                enabled = nextService != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    disabledContainerColor = Color(0xFFE4E8EB),
                    disabledContentColor = Color(0xFF8A969F),
                ),
            ) {
                Text(
                    text = nextService?.let { "连接 ${it.displayName}" } ?: "已全部连接",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        item {
            OutlinedButton(
                onClick = onPreviewHome,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Accent),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
            ) {
                Text(
                    if (isManagingConnections) "完成，返回圆桌" else "暂不登录，先进入圆桌",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${service.displayName}，${statusLabel(status.state)}" }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            BrandIcon(service = service, modifier = Modifier.size(32.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(service.displayName, color = Ink, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (status.state.isUsable()) status.detail else service.loginHint,
                    color = Muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (status.state == ConnectionState.LOADING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Accent,
                    strokeWidth = 3.dp,
                )
            } else if (status.state.isUsable()) {
                StatusPill(status.state)
            } else {
                Surface(color = Accent, shape = RoundedCornerShape(13.dp)) {
                    Text(
                        text = if (status.state == ConnectionState.ERROR) "重试" else "连接",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

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
) {
    var question by rememberSaveable {
        mutableStateOf(debugInitialQuestion.ifBlank { sessionController.originalQuestion })
    }
    var answerModeName by rememberSaveable { mutableStateOf(AnswerMode.PARALLEL.name) }
    var roundGuidance by rememberSaveable { mutableStateOf("") }
    var roundtablePageName by rememberSaveable { mutableStateOf(RoundtablePage.HOME.name) }
    var membersReturnPageName by rememberSaveable { mutableStateOf(RoundtablePage.HOME.name) }
    val scope = rememberCoroutineScope()
    val answerMode = AnswerMode.valueOf(answerModeName)
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
    val roundtablePage = RoundtablePage.valueOf(roundtablePageName)

    LaunchedEffect(voiceEvent?.id) {
        val event = voiceEvent ?: return@LaunchedEffect
        val outcome = voiceInputState.take(event.id) ?: return@LaunchedEffect
        when (outcome) {
            is VoiceInputOutcome.Success -> {
                val merged = VoiceInputPolicy.merge(question, outcome.transcript)
                question = merged.text
                snackbarHostState.showSnackbar(
                    if (merged.truncated) {
                        "语音内容过长，已保留可容纳的部分"
                    } else {
                        "已添加语音内容"
                    },
                )
            }
            VoiceInputOutcome.Cancelled -> snackbarHostState.showSnackbar("已取消语音输入")
            is VoiceInputOutcome.Error -> snackbarHostState.showSnackbar(outcome.message)
        }
    }

    val toggleMember: (ArenaService) -> Unit = { service ->
        val next = if (service in selectedServices) {
            if (selectedServices.size <= ArenaService.MIN_MEMBERS) {
                scope.launch { snackbarHostState.showSnackbar("至少保留 ${ArenaService.MIN_MEMBERS} 家 AI") }
                selectedServices
            } else {
                selectedServices - service
            }
        } else {
            if (selectedServices.size >= ArenaService.MAX_MEMBERS) {
                scope.launch { snackbarHostState.showSnackbar("手机端当前最多选择 ${ArenaService.MAX_MEMBERS} 家 AI") }
                selectedServices
            } else {
                selectedServices + service
            }
        }
        if (next != selectedServices) onSelectedServicesChange(next)
    }

    val restoreRecentSession: (String) -> Unit = { sessionId ->
        stopSpeech?.invoke()
        if (sessionController.restoreSession(sessionId)) {
            question = sessionController.originalQuestion
            roundGuidance = ""
            roundtablePageName = RoundtablePage.HOME.name
            onSelectedServicesChange(sessionController.sessionServices)
            scope.launch { snackbarHostState.showSnackbar("已恢复本地讨论") }
        } else {
            scope.launch { snackbarHostState.showSnackbar("该历史记录无法恢复") }
        }
    }

    BackHandler(enabled = roundtablePage != RoundtablePage.HOME) {
        roundtablePageName = if (roundtablePage == RoundtablePage.MEMBERS) {
            membersReturnPageName
        } else {
            RoundtablePage.HOME.name
        }
    }

    when (roundtablePage) {
        RoundtablePage.HISTORY -> {
            RoundtableHistoryPage(
                sessions = sessionController.recentSessions,
                warning = sessionController.storageWarning,
                onBack = { roundtablePageName = RoundtablePage.HOME.name },
                onRestore = restoreRecentSession,
            )
            return
        }
        RoundtablePage.SETTINGS -> {
            RoundtableSettingsPage(
                selectedServices = selectedServices,
                usableCount = usableCount,
                largeTextEnabled = largeTextEnabled,
                onLargeTextChange = onLargeTextChange,
                speechState = speechState,
                stopSpeech = stopSpeech,
                onBack = { roundtablePageName = RoundtablePage.HOME.name },
                onMembers = {
                    membersReturnPageName = RoundtablePage.SETTINGS.name
                    roundtablePageName = RoundtablePage.MEMBERS.name
                },
                onConnections = {
                    roundtablePageName = RoundtablePage.HOME.name
                    onManageConnections()
                },
            )
            return
        }
        RoundtablePage.MEMBERS -> {
            RoundtableMembersPage(
                selectedServices = selectedServices,
                loginNeededServices = loginNeededServices,
                statuses = pool.statuses,
                onToggle = toggleMember,
                onOpenService = onOpenService,
                onBack = { roundtablePageName = membersReturnPageName },
            )
            return
        }
        RoundtablePage.HOME -> Unit
    }

    if (sessionStage == SessionStage.IDLE) {
        CleanQuestionHome(
            question = question,
            onQuestionChange = { question = it },
            questionWithinLimit = questionWithinLimit,
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
            voiceInputActive = voiceInputState?.active == true,
            voiceInputEnabled = voiceInputRequest != null,
            onVoiceInput = { voiceInputRequest?.invoke() },
            onStart = {
                if (sessionController.startInitial(question, usableServices, answerMode)) {
                    scope.launch {
                        snackbarHostState.showSnackbar("已按${answerMode.displayName}发送给 ${usableServices.size} 位 AI")
                    }
                }
            },
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground)
            .imePadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        "AI 圆桌",
                        color = Ink,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Surface(
                        color = if (usableCount >= 2) SuccessSoft else WarningSoft,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            text = if (usableCount >= 2) "$usableCount / ${selectedServices.size} 可用" else "等待连接 AI",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = if (usableCount >= 2) Success else Warning,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        onClick = { roundtablePageName = RoundtablePage.HISTORY.name },
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text("历史", color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(
                        onClick = { roundtablePageName = RoundtablePage.SETTINGS.name },
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text("设置", color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("本次问题", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        sessionController.originalQuestion,
                        color = Ink,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (sessionStage != SessionStage.IDLE) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = AccentSoft,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(Accent),
                        )
                        Text(
                            text = sessionController.sessionMessage,
                            color = Accent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        if (sessionController.isBusy) {
            item {
                TextButton(
                    onClick = sessionController::cancelCurrentRound,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                ) {
                    Text("停止等待本轮", color = Error, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (sessionController.history.isNotEmpty()) {
            item {
                val latestCompleted = sessionController.history.last()
                val latestCompletedRound = latestCompleted.number
                Text(
                    text = buildString {
                        append("已完成 $latestCompletedRound 轮 · 最新为${latestCompleted.kind.displayName}")
                        if (latestCompletedRound > sessionController.history.size) {
                            append(" · 保留最近 ${sessionController.history.size} 轮")
                        }
                    },
                    color = Muted,
                    fontSize = 13.sp,
                )
            }
        }

        item {
            Text(
                text = if (sessionStage == SessionStage.IDLE) "参与本轮" else "本轮结果",
                modifier = Modifier.padding(top = 5.dp),
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        items(selectedServices.size) { index ->
            val service = selectedServices[index]
            val status = pool.statuses[service] ?: ServiceStatus()
            val run = sessionController.runs[service] ?: ParticipantRun()
            ProviderStatusRow(
                service = service,
                status = status,
                run = run,
                onClick = { onOpenService(service) },
                isSpeaking = speechState?.activeKey == "answer:${service.name}",
                onSpeechToggle = speechPlaybackRequest?.let { request ->
                    { request("answer:${service.name}", run.response) }
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

        if (sessionStage == SessionStage.READY && completedCount >= 2) {
            item {
                OutlinedTextField(
                    value = roundGuidance,
                    onValueChange = { roundGuidance = it.take(ArenaLimits.MAX_GUIDANCE_CHARS) },
                    enabled = !sessionController.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(92.dp),
                    label = { Text("本轮 Prompt（独立迭代必填）") },
                    placeholder = {
                        Text("独立迭代直接发送这里的内容；观点讨论可作为补充要求", color = Muted)
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = RoundedCornerShape(14.dp),
                    maxLines = 3,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (sessionController.startIteration(answerMode, roundGuidance)) roundGuidance = ""
                        },
                        enabled = roundGuidance.isNotBlank() && !sessionController.isBusy,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) {
                        Text("独立迭代", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            if (sessionController.startDebate(answerMode, roundGuidance)) roundGuidance = ""
                        },
                        enabled = !sessionController.isBusy,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DebateSoft,
                            contentColor = Debate,
                        ),
                    ) {
                        Text("观点讨论", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (sessionController.summary.phase != ParticipantPhase.IDLE) {
            item {
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
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            if (sessionController.startSummary(usableServices, roundGuidance)) roundGuidance = ""
                        },
                        enabled = completedCount >= 2 && !sessionController.isBusy,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text(
                            if (sessionController.summary.phase == ParticipantPhase.COMPLETE) "重新总结" else "讨论总结",
                            color = Accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    TextButton(
                        onClick = {
                            stopSpeech?.invoke()
                            sessionController.reset()
                            question = ""
                            roundGuidance = ""
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text("开始一个新问题", color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanQuestionHome(
    question: String,
    onQuestionChange: (String) -> Unit,
    questionWithinLimit: Boolean,
    answerMode: AnswerMode,
    onAnswerModeChange: (AnswerMode) -> Unit,
    selectedServices: List<ArenaService>,
    usableCount: Int,
    onMembers: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    voiceInputActive: Boolean,
    voiceInputEnabled: Boolean,
    onVoiceInput: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground)
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("AI 圆桌", color = Ink, style = MaterialTheme.typography.titleLarge)
                Text(
                    if (usableCount == selectedServices.size) {
                        "$usableCount / ${selectedServices.size} 可用"
                    } else {
                        "已选 ${selectedServices.size} 家 · $usableCount 家可用"
                    },
                    color = if (usableCount >= ArenaService.MIN_MEMBERS) Success else Warning,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TextButton(onClick = onHistory, modifier = Modifier.height(48.dp)) {
                Text("历史", color = Accent, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onSettings, modifier = Modifier.height(48.dp)) {
                Text("设置", color = Accent, fontWeight = FontWeight.SemiBold)
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .clickable(onClick = onMembers),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                selectedServices.take(4).forEach { service ->
                    BrandIcon(service, Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        selectedServices.joinToString(" · ") { it.shortName },
                        color = Ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("AI 成员", color = Muted, fontSize = 12.sp)
                }
                Text("调整", color = Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        OutlinedTextField(
            value = question,
            onValueChange = onQuestionChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp),
            label = { Text("你的问题", fontSize = 15.sp) },
            placeholder = {
                Text(
                    "例如：帮我比较几种家庭旅行方案",
                    color = Muted,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
            },
            trailingIcon = {
                if (voiceInputEnabled) {
                    TextButton(
                        onClick = onVoiceInput,
                        enabled = !voiceInputActive,
                        modifier = Modifier
                            .height(48.dp)
                            .semantics { contentDescription = "语音输入问题" },
                    ) {
                        Text(
                            if (voiceInputActive) "听写中" else "语音",
                            color = if (voiceInputActive) Muted else Accent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            },
            textStyle = MaterialTheme.typography.bodyLarge,
            isError = !questionWithinLimit,
            supportingText = if (!questionWithinLimit) {
                { Text("问题超过 ${ArenaLimits.MAX_QUESTION_CHARS} 字，请缩短") }
            } else {
                null
            },
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            minLines = 4,
            maxLines = 7,
        )

        AnswerModeSelector(
            selected = answerMode,
            enabled = true,
            onSelected = onAnswerModeChange,
        )

        Button(
            onClick = onStart,
            enabled = usableCount >= ArenaService.MIN_MEMBERS && question.isNotBlank() && questionWithinLimit,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) {
            Text(
                when {
                    usableCount < ArenaService.MIN_MEMBERS -> "连接两家后可开始"
                    !questionWithinLimit -> "问题过长，请缩短"
                    else -> "开始讨论"
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun RoundtablePageHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .height(48.dp)
                .semantics { contentDescription = "返回圆桌主界面" },
        ) {
            Text("返回", color = Accent, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, color = Muted, fontSize = 13.sp, maxLines = 1)
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
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            RoundtablePageHeader(
                title = "最近问题",
                subtitle = if (sessions.isEmpty()) "只保存在本机" else "${sessions.size} 条 · 只保存在本机",
                onBack = onBack,
            )
        }
        if (warning != null) {
            item { Text(warning, color = Error, style = MaterialTheme.typography.bodyMedium) }
        }
        if (sessions.isEmpty()) {
            item {
                Text(
                    "完成一次提问后，会话会自动出现在这里。",
                    modifier = Modifier.padding(vertical = 20.dp),
                    color = Muted,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            items(sessions.take(8)) { session ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRestore(session.id) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                session.title.ifBlank { "未命名问题" },
                                color = Ink,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${formatRecentTime(session.updatedAtMillis)} · ${session.roundCount} 轮 · ${session.serviceCount} 家",
                                color = Muted,
                                fontSize = 13.sp,
                            )
                        }
                        Text("继续", color = Accent, fontWeight = FontWeight.Bold)
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
    largeTextEnabled: Boolean,
    onLargeTextChange: (Boolean) -> Unit,
    speechState: SpeechPlaybackState?,
    stopSpeech: (() -> Unit)?,
    onBack: () -> Unit,
    onMembers: () -> Unit,
    onConnections: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            RoundtablePageHeader(
                title = "设置",
                subtitle = "辅助使用与 AI 连接",
                onBack = onBack,
            )
        }
        item {
            AccessibilityToolbar(
                largeTextEnabled = largeTextEnabled,
                onLargeTextChange = onLargeTextChange,
                speechState = speechState,
                stopSpeech = stopSpeech,
            )
        }
        item {
            SettingsActionCard(
                title = "AI 成员",
                detail = selectedServices.joinToString(" · ") { it.shortName },
                action = "调整",
                onClick = onMembers,
            )
        }
        item {
            SettingsActionCard(
                title = "连接管理",
                detail = "$usableCount / ${selectedServices.size} 可用 · 登录一次后自动复用",
                action = "查看",
                onClick = onConnections,
            )
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AccentSoft,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    "无需注册圆桌账号；成员选择、讨论记录和网页登录信息都只保存在本机。",
                    modifier = Modifier.padding(14.dp),
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            Text(
                text = "AI 圆桌 v${BuildConfig.VERSION_NAME} · 版本代码 ${BuildConfig.VERSION_CODE}",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                color = Muted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, style = MaterialTheme.typography.titleMedium)
                Text(detail, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Text(action, color = Accent, fontWeight = FontWeight.Bold)
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
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            RoundtablePageHeader(
                title = "选择 AI 成员",
                subtitle = "选择 ${ArenaService.MIN_MEMBERS}-${ArenaService.MAX_MEMBERS} 家参与圆桌",
                onBack = onBack,
            )
        }
        item {
            MemberSelectorPanel(
                selected = selectedServices,
                expanded = true,
                enabled = true,
                onExpandedChange = { expanded -> if (!expanded) onBack() },
                onToggle = onToggle,
            )
        }
        if (loginNeededServices.isNotEmpty()) {
            item {
                LoginNeededPanel(
                    services = loginNeededServices,
                    statuses = statuses,
                    onOpenService = onOpenService,
                )
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("辅助使用", color = Ink, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (largeTextEnabled) "大字已开启，网页也会放大" else "需要时可一键放大全局文字",
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    speechState?.detail ?: "朗读：不可用",
                    color = if (speechState?.activeKey != null) Accent else Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick = { onLargeTextChange(!largeTextEnabled) },
                    modifier = Modifier
                        .height(48.dp)
                        .semantics {
                            contentDescription = if (largeTextEnabled) "关闭大字模式" else "开启大字模式"
                        },
                ) {
                    Text(
                        if (largeTextEnabled) "大字：开" else "大字：关",
                        color = if (largeTextEnabled) Success else Accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (speechState?.activeKey != null) {
                    TextButton(
                        onClick = { stopSpeech?.invoke() },
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text("停止朗读", color = Error, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun formatRecentTime(value: Long): String =
    if (value <= 0L) "时间未知" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(value))

@Composable
private fun DiscussionSummaryCard(
    summary: DiscussionSummary,
    trustSignal: DiscussionTrustSignal,
    isSpeaking: Boolean,
    onSpeechToggle: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onShare: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFCF2)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE8DDB8)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("讨论总结", color = Ink, style = MaterialTheme.typography.titleMedium)
                    Text(
                        summary.judge?.let { "由 ${it.displayName} 生成" }.orEmpty(),
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                RunStatusPill(summary.phase)
            }
            if (summary.text.isNotBlank()) {
                TrustSignalPanel(trustSignal)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (onCopy != null) {
                        TextButton(onClick = onCopy, modifier = Modifier.weight(1f).height(48.dp)) {
                            Text("复制", color = Accent, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (onShare != null) {
                        TextButton(onClick = onShare, modifier = Modifier.weight(1f).height(48.dp)) {
                            Text("分享", color = Accent, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (onSpeechToggle != null) {
                        TextButton(onClick = onSpeechToggle, modifier = Modifier.weight(1f).height(48.dp)) {
                            Text(if (isSpeaking) "停止" else "朗读", color = Accent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFFE8DDB8))
                Text(
                    summary.text,
                    color = Ink,
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                Text(summary.detail, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TrustSignalPanel(signal: DiscussionTrustSignal) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF2F7F8))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("交叉核验", color = Accent, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                "${signal.providerCount}家观点",
                if (signal.consensusReviewed) "共识已提炼" else "未标出共识",
                if (signal.differencesReviewed) "分歧已检查" else "未标出分歧",
            ).forEach { label ->
                Surface(
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    shape = RoundedCornerShape(999.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
                        color = Ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
        Text(
            "待核验提醒：${signal.verificationReminderCount} 处 · ${signal.domainCaution}",
            color = if (signal.verificationReminderCount > 0) Warning else Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MemberSelectorPanel(
    selected: List<ArenaService>,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onToggle: (ArenaService) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onExpandedChange(!expanded) },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("AI 成员", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    selected.joinToString(" · ") { it.shortName },
                    modifier = Modifier.weight(1f),
                    color = Muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (enabled) if (expanded) "完成" else "调整" else "新问题时调整",
                    color = if (enabled) Accent else Muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (expanded) {
                HorizontalDivider(color = Border)
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ArenaService.entries.chunked(2).forEach { rowServices ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowServices.forEach { service ->
                                val isSelected = service in selected
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                        .clickable { onToggle(service) }
                                        .semantics {
                                            contentDescription = "${service.displayName}，${if (isSelected) "已选择" else "未选择"}"
                                        },
                                    color = if (isSelected) AccentSoft else Color(0xFFFAFBFC),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isSelected) Accent else Border,
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        BrandIcon(service, Modifier.size(24.dp))
                                        Text(
                                            service.displayName,
                                            modifier = Modifier.weight(1f),
                                            color = if (isSelected) Accent else Ink,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        if (isSelected) Text("已选", color = Accent, fontSize = 11.sp)
                                    }
                                }
                            }
                            if (rowServices.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Text(
                        "选择 ${ArenaService.MIN_MEMBERS}-${ArenaService.MAX_MEMBERS} 家；未登录成员会在下方显示登录入口。",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    OutlinedButton(
                        onClick = { onExpandedChange(false) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Accent),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                    ) {
                        Text("完成选择", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginNeededPanel(
    services: List<ArenaService>,
    statuses: Map<ArenaService, ServiceStatus>,
    onOpenService: (ArenaService) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = WarningSoft),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE9C99F)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                "还有 ${services.size} 家需要登录",
                color = Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "登录一次后会自动复用。完成一家后点顶部“返回圆桌”，再继续下一家。",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
            services.chunked(2).forEach { rowServices ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowServices.forEach { service ->
                        val state = statuses[service]?.state ?: ConnectionState.NOT_LOADED
                        OutlinedButton(
                            onClick = { onOpenService(service) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .semantics { contentDescription = "打开 ${service.displayName} 登录页面" },
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Accent),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.White,
                                contentColor = Accent,
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            BrandIcon(service, Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${if (state == ConnectionState.ERROR) "重试" else "登录"} ${service.shortName}",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                    if (rowServices.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AnswerModeSelector(
    selected: AnswerMode,
    enabled: Boolean,
    onSelected: (AnswerMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("回答方式", color = Ink, style = MaterialTheme.typography.titleMedium)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFFEFF3F5),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AnswerMode.entries.forEach { mode ->
                    val isSelected = mode == selected
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .semantics {
                                contentDescription = "${mode.displayName}，${if (isSelected) "已选择" else "未选择"}"
                            }
                            .clickable(enabled = enabled) { onSelected(mode) },
                        color = if (isSelected) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                        shadowElevation = if (isSelected) 1.dp else 0.dp,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (mode == AnswerMode.PARALLEL) "同时回答" else "依次回答",
                                color = if (isSelected) Accent else Muted,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderStatusRow(
    service: ArenaService,
    status: ServiceStatus,
    run: ParticipantRun,
    onClick: () -> Unit,
    isSpeaking: Boolean,
    onSpeechToggle: (() -> Unit)?,
    recoveryEnabled: Boolean,
    canReextract: Boolean,
    onRetrySend: () -> Unit,
    onRetryExtraction: () -> Unit,
    onSkip: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BrandIcon(service, Modifier.size(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        service.displayName,
                        color = Ink,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (run.requestId.isNotBlank() || run.detail != "等待开始") run.detail else status.detail,
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                    )
                }
                if (run.requestId.isNotBlank()) RunStatusPill(run.phase) else StatusPill(status.state)
            }
            if (run.response.isNotBlank()) {
                HorizontalDivider(color = Border)
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "最新回答",
                            color = Accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (onSpeechToggle != null) {
                            TextButton(onClick = onSpeechToggle, modifier = Modifier.height(48.dp)) {
                                Text(
                                    if (isSpeaking) "停止" else "朗读",
                                    color = Accent,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    Text(
                        text = run.response,
                        color = Ink,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 7,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (run.phase == ParticipantPhase.ERROR && run.requestId.isNotBlank()) {
                if (run.response.isBlank()) HorizontalDivider(color = Border)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = onRetrySend,
                        enabled = recoveryEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text("重发", fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = onRetryExtraction,
                        enabled = recoveryEnabled && canReextract,
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp),
                    ) {
                        Text("重新提取", fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = onSkip,
                        enabled = recoveryEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text("跳过", color = Muted, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun RunStatusPill(phase: ParticipantPhase) {
    val (background, foreground, label) = when (phase) {
        ParticipantPhase.IDLE -> Triple(WarningSoft, Warning, "等待")
        ParticipantPhase.SENDING -> Triple(AccentSoft, Accent, "发送中")
        ParticipantPhase.WAITING -> Triple(AccentSoft, Accent, "等待回答")
        ParticipantPhase.STREAMING -> Triple(AccentSoft, Accent, "回答中")
        ParticipantPhase.COMPLETE -> Triple(SuccessSoft, Success, "完成")
        ParticipantPhase.ERROR -> Triple(ErrorSoft, Error, "失败")
    }
    Surface(color = background, shape = RoundedCornerShape(999.dp)) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            color = foreground,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ProviderHeader(
    service: ArenaService,
    status: ServiceStatus,
    onBack: () -> Unit,
    onReload: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp),
        color = Color.White,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier
                    .height(48.dp)
                    .semantics { contentDescription = "返回 AI 圆桌主界面" },
            ) {
                Text("返回圆桌", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            BrandIcon(service, Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${service.displayName} 原网页", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(status.detail, color = Muted, fontSize = 12.sp, maxLines = 1)
            }
            StatusPill(status.state)
            TextButton(onClick = onReload, modifier = Modifier.height(48.dp)) {
                Text("刷新", color = Accent, fontWeight = FontWeight.Bold)
            }
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
    NavigationBar(
        containerColor = Color(0xFFFBFCFD),
        tonalElevation = 2.dp,
    ) {
        NavigationBarItem(
            selected = selected == null,
            onClick = onRoundtable,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_roundtable),
                    contentDescription = "圆桌",
                    tint = if (selected == null) Accent else Muted,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = { Text("圆桌", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
            alwaysShowLabel = true,
            colors = navColors(),
        )
        services.forEach { service ->
            NavigationBarItem(
                selected = selected == service,
                onClick = { onService(service) },
                icon = {
                    Box {
                        BrandIcon(service, Modifier.size(23.dp))
                        if (statuses[service]?.state?.isUsable() == true) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .align(Alignment.TopEnd)
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(Success),
                            )
                        }
                    }
                },
                label = {
                    Text(
                        service.shortName,
                        fontSize = if (service == ArenaService.DEEPSEEK) 9.sp else 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                },
                alwaysShowLabel = true,
                colors = navColors(),
            )
        }
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Accent,
    selectedTextColor = Accent,
    indicatorColor = AccentSoft,
    unselectedIconColor = Muted,
    unselectedTextColor = Muted,
)

@Composable
private fun BrandIcon(service: ArenaService, modifier: Modifier = Modifier) {
    val iconRes = service.iconRes
    if (iconRes != null) {
        val painter: Painter = painterResource(iconRes)
        Icon(
            painter = painter,
            contentDescription = service.displayName,
            modifier = modifier,
            tint = Color.Unspecified,
        )
    } else {
        Surface(
            modifier = modifier.semantics { contentDescription = service.displayName },
            color = Color(service.brandColor),
            shape = RoundedCornerShape(10.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    service.brandGlyph.orEmpty(),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(state: ConnectionState) {
    val (background, foreground) = when (state) {
        ConnectionState.SIGNED_IN -> SuccessSoft to Success
        ConnectionState.LOADING -> AccentSoft to Accent
        ConnectionState.ERROR -> ErrorSoft to Error
        ConnectionState.NEEDS_LOGIN, ConnectionState.NOT_LOADED -> WarningSoft to Warning
    }
    Surface(color = background, shape = RoundedCornerShape(999.dp)) {
        Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(foreground),
            )
            Text(
                text = statusLabel(state),
                color = foreground,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

private fun statusLabel(state: ConnectionState): String = when (state) {
    ConnectionState.NOT_LOADED -> "未打开"
    ConnectionState.LOADING -> "加载中"
    ConnectionState.NEEDS_LOGIN -> "待登录"
    ConnectionState.SIGNED_IN -> "可用"
    ConnectionState.ERROR -> "需重试"
}
