package com.tianlin.aiarena

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 顶栏：左侧菜单，中间标题，右侧「时光机」「新提问」带文字小标，长辈一眼知道是什么。 */
@Composable
internal fun SimpleHeader(busy: Boolean, onNew: () -> Unit, onNavigate: (RoundtablePage) -> Unit,
                          onShareSession: (() -> Unit)? = null, onTimeMachine: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val colors = ArenaStyle.colors
    if (confirm) ConfirmDialog(
        title = "开始新会话？", text = "将停止等待本轮回答，已收到的内容会保留在历史中。",
        confirmLabel = "开始新会话", onConfirm = { confirm = false; onNew() }, onDismiss = { confirm = false },
    )
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 6.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box {
            SimpleIcon(R.drawable.ic_menu, "历史与设置", { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("历史对话") }, onClick = { menu = false; onNavigate(RoundtablePage.HISTORY) })
                DropdownMenuItem(text = { Text("设置") }, onClick = { menu = false; onNavigate(RoundtablePage.SETTINGS) })
                if (onShareSession != null) DropdownMenuItem(text = { Text("分享整场讨论") },
                    onClick = { menu = false; onShareSession() }, modifier = Modifier.testTag("share-session"))
                HorizontalDivider(color = colors.border, thickness = 0.5.dp)
                DropdownMenuItem(text = { Text("AI 圆桌 v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall) },
                    onClick = {}, enabled = false, modifier = Modifier.testTag("menu-version"))
            }
        }
        Row(Modifier.weight(1f).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ArenaIcon(R.drawable.ic_logo, tint = Color.Unspecified, size = 24.dp)
            Text("AI 圆桌", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = colors.ink)
        }
        if (onTimeMachine != null) LabeledIcon(R.drawable.ic_history, "时光机", "打开时光机，回看这场讨论的每一轮", onTimeMachine,
            Modifier.testTag("open-time-machine"))
        LabeledIcon(R.drawable.ic_add, "新提问", "新提问", { if (busy) confirm = true else onNew() }, Modifier.testTag("new-session"))
    }
}

@Composable
private fun LabeledIcon(@DrawableRes icon: Int, label: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ArenaStyle.colors
    Column(modifier.widthIn(min = 56.dp).heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = description }.padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ArenaIcon(icon, tint = colors.ink, size = 22.dp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.muted)
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
        BrandAvatar(service = service, size = 34.dp)
    }
}

@Composable
internal fun SimpleNotice(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall, color = ArenaStyle.colors.warning)
}

/** 附件列表 + 圆角输入框 + 圆形发送键；首页和回答页共用。 */
@Composable
internal fun InputBar(
    text: String, onChange: (String) -> Unit, hint: String, enabled: Boolean, busy: Boolean, onSend: () -> Unit, onStop: () -> Unit,
    attachmentDraft: AttachmentDraft?, onChooseAttachments: (() -> Unit)?, attachmentNotice: String?, sendDescription: String = "发送问题",
) {
    val colors = ArenaStyle.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (attachmentDraft != null && attachmentDraft.attachments.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
                attachmentDraft.attachments.forEach { file ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(file.name, Modifier.weight(1f).padding(start = 10.dp), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        SimpleIcon(R.drawable.ic_close, "移除附件 ${file.name}", { attachmentDraft.remove(file.id) },
                            enabled = enabled && !busy && !attachmentDraft.picking)
                    }
                }
            }
            Text(attachmentNotice ?: "文件将自动发给本轮成员；各家确认附件就绪后再提问。",
                Modifier.padding(horizontal = 10.dp).testTag("attachment-member-notice"),
                style = MaterialTheme.typography.labelSmall, color = if (attachmentNotice == null) colors.muted else colors.warning)
        }
        attachmentDraft?.error?.let { SimpleNotice(it) }
        if (attachmentDraft?.picking == true) Text("正在添加附件…", Modifier.padding(10.dp), style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.Bottom) {
            Surface(Modifier.weight(1f), color = colors.card, shape = RoundedCornerShape(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onChooseAttachments != null) SimpleIcon(R.drawable.ic_add, "添加照片或文件", onChooseAttachments,
                        Modifier.testTag("choose-attachments"), enabled = enabled && !busy && attachmentDraft?.picking != true)
                    TextField(value = text, onValueChange = onChange, placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f).testTag("simple-composer"), minLines = 1, maxLines = 4,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = TextFieldDefaults.colors(focusedContainerColor = colors.card, unfocusedContainerColor = colors.card,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                }
            }
            IconButton(onClick = if (busy) onStop else onSend, enabled = busy || (enabled && attachmentDraft?.picking != true),
                modifier = Modifier.padding(start = 8.dp).size(56.dp).testTag("simple-send")
                    .semantics { contentDescription = if (busy) "停止等待" else sendDescription }) {
                Box(Modifier.size(52.dp).background(if (busy || enabled) colors.accent else colors.surfaceAlt, CircleShape), contentAlignment = Alignment.Center) {
                    ArenaIcon(if (busy) R.drawable.ic_close else R.drawable.ic_send,
                        tint = if (busy || enabled) colors.onAccent else colors.muted, size = 22.dp)
                }
            }
        }
    }
}

/**
 * 回答页输入区：四段模式 → 该模式的选项 → 预设卡片 → 输入框。
 * 「独立迭代」没有预设，原文发给每位成员。
 */
@Composable
internal fun ModeComposer(
    mode: RoundMode, onMode: (RoundMode) -> Unit, text: String, onText: (String) -> Unit, ready: Boolean, busy: Boolean,
    attachmentDraft: AttachmentDraft?, onChooseAttachments: (() -> Unit)?, attachmentNotice: String?, scope: String,
    options: @Composable () -> Unit, preset: (@Composable () -> Unit)?, onStop: () -> Unit, onSend: () -> Unit,
    collapsed: Boolean = false, onCollapsedChange: ((Boolean) -> Unit)? = null,
) {
    val colors = ArenaStyle.colors
    Surface(color = colors.page, shadowElevation = 6.dp, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        if (collapsed && onCollapsedChange != null) {
            // 收起：只留一条细栏，屏幕留给回答；点一下展开。进行中仍可直接停止。
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onCollapsedChange(false) }
                .semantics { contentDescription = "展开提问区" }.testTag("composer-expand")
                .padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (busy) "${mode.label} · 本轮进行中" else "${mode.label} · 点这里继续提问", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = colors.muted)
                if (busy) TextButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) { Text("停止", color = colors.error) }
                ArenaIcon(R.drawable.ic_chevron_right, Modifier.padding(12.dp).rotate(-90f), tint = colors.muted, size = 20.dp)
            }
            return@Surface
        }
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = if (onCollapsedChange != null) 0.dp else 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onCollapsedChange != null) Box(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable { onCollapsedChange(true) }
                .semantics { contentDescription = "收起提问区" }.testTag("composer-collapse"), contentAlignment = Alignment.Center) {
                // 一条把手 + 向下的箭头：一看就知道能往下收。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(colors.border))
                }
                ArenaIcon(R.drawable.ic_chevron_right, Modifier.align(Alignment.CenterEnd).padding(end = 4.dp).rotate(90f), tint = colors.muted, size = 18.dp)
            }
            ModeSegmented(RoundMode.entries.map { it.name to it.label }, mode.name, !busy, { onMode(RoundMode.fromName(it)) })
            options()
            preset?.invoke()
            Text(scope, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall, color = colors.muted)
            InputBar(text, onText, mode.placeholder, ready, busy, onSend, onStop, attachmentDraft, onChooseAttachments,
                attachmentNotice, sendDescription = mode.sendLabel)
        }
    }
}

/** 首页：新 Logo、成员卡、示例问题，底部「一起回答 / 接力回答」+ 输入框。 */
@Composable
internal fun SimpleAskHome(
    question: String, onQuestionChange: (String) -> Unit, selectedServices: List<ArenaService>, usableCount: Int,
    onMembers: () -> Unit, onConnections: () -> Unit, onOpenService: (ArenaService) -> Unit,
    onNavigate: (RoundtablePage) -> Unit, onStart: () -> Unit, onNeedQuestion: () -> Unit, onTooLong: () -> Unit,
    lengthAdvisory: String?, offline: Boolean, crashNotice: ArenaCrashReport?, onCrashDismiss: () -> Unit,
    pendingConnectionCount: Int = 0,
    attachmentDraft: AttachmentDraft? = null, onChooseAttachments: (() -> Unit)? = null,
    onStartRelay: ((List<ArenaService>) -> Unit)? = null,
    /** 网页体检发现的问题（只读检查）；没有问题时为 null，首页不多显示任何东西。 */
    healthNotice: String? = null,
) {
    val colors = ArenaStyle.colors
    var relay by rememberSaveable { mutableStateOf(false) }
    var orderNames by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val order = orderNames.mapNotNull(ArenaService::fromName).filter { it in selectedServices }
        .let { saved -> saved + selectedServices.filterNot { it in saved } }
    Column(Modifier.fillMaxSize().background(colors.page).navigationBarsPadding().imePadding()) {
        SimpleHeader(false, { onQuestionChange(""); attachmentDraft?.clear() }, onNavigate)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
            if (offline) SimpleNotice("网络未连接，请检查 Wi-Fi 或手机流量。")
            else healthNotice?.let { SimpleNotice(it) }
            if (crashNotice != null) {
                TextButton(onClick = onCrashDismiss) { Text("上次异常退出，历史已保留 · 知道了", style = MaterialTheme.typography.bodySmall) }
            }
            ArenaIcon(R.drawable.ic_logo, tint = Color.Unspecified, size = 96.dp, modifier = Modifier.testTag("home-logo"))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("想听听不同的答案？", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = colors.ink)
                Text("一个问题，一起问。", Modifier.padding(top = 6.dp), color = colors.muted, style = MaterialTheme.typography.bodyMedium)
            }
            Surface(Modifier.fillMaxWidth(), color = colors.card, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    selectedServices.forEach { service ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SimpleAvatar(service) { onOpenService(service) }
                            Text(service.shortName, style = MaterialTheme.typography.labelSmall, color = colors.ink)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onMembers) { Text("更换", style = MaterialTheme.typography.titleSmall, color = colors.accent) }
                }
            }
            if (usableCount + pendingConnectionCount < ArenaService.MIN_MEMBERS) TextButton(onClick = onConnections) { Text("先登录至少两家 AI") }
            if (question.isBlank()) Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .clickable { onQuestionChange("每天只有 30 分钟，怎么把英语口语练起来？") }, color = colors.accentSoft, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("示例问题：", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                    Text("每天只有 30 分钟，怎么把英语口语练起来？", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
                        color = colors.ink, textAlign = TextAlign.Start)
                }
            }
        }
        if (lengthAdvisory != null) SimpleNotice(lengthAdvisory)
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onStartRelay != null) {
                ModeSegmented(listOf("together" to "一起回答", "relay" to "接力回答"), if (relay) "relay" else "together", true,
                    { relay = it == "relay" }, testTagPrefix = "home-mode")
                if (relay) RelayOrderRow(order, true) { index ->
                    val list = order.toMutableList(); list[index - 1] = list[index].also { list[index] = list[index - 1] }
                    orderNames = list.map { it.name }
                }
                Text(if (relay) "按顺序接力：第 1 位直接回答，之后每位参考前面各位的回答再补充。" else "原文同时发给每位成员，各自独立回答。",
                    Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall, color = colors.muted)
            }
            InputBar(question, onQuestionChange, "问一个问题…", true, false,
                onSend = {
                    when {
                        question.isBlank() && attachmentDraft?.attachments.isNullOrEmpty() -> onNeedQuestion()
                        question.length > ArenaLimits.MAX_QUESTION_CHARS -> onTooLong()
                        usableCount + pendingConnectionCount < ArenaService.MIN_MEMBERS -> onConnections()
                        relay && onStartRelay != null -> onStartRelay(order)
                        else -> onStart()
                    }
                }, onStop = {}, attachmentDraft = attachmentDraft, onChooseAttachments = onChooseAttachments,
                attachmentNotice = ArenaAttachmentSupport.notice(selectedServices, attachmentDraft?.attachments.orEmpty()))
        }
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
    /** 把各家网页状态和每轮结果（不含问答正文）分享给开发者。 */
    onExportDiagnostics: (() -> Unit)? = null,
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
                    Text("输入框旁的加号可添加照片或文件，自动发给本轮支持附件的 AI；不支持时会明确提示。网页里手动上传的文件只供那一家使用。", style = MaterialTheme.typography.bodySmall, color = ArenaStyle.colors.muted)
                    SimpleSettingRow("重新加载 AI 网页", "", onReloadPages)
                    SimpleSettingRow("清除卡住的讨论", "", { confirm = "清除卡住的讨论" })
                    if (onRestartApp != null) SimpleSettingRow("重启应用", "", { confirm = "重启应用" })
                    SimpleSettingRow("使用说明", "", onShowOnboarding)
                    if (onExportDiagnostics != null) SimpleSettingRow("导出诊断信息", "", onExportDiagnostics)
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
