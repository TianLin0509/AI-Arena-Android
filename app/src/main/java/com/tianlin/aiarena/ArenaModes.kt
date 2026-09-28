package com.tianlin.aiarena

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 下一轮做什么（参考 AI圆桌 Lite 0.3.0 的四个按钮）：
 * 独立迭代 = 原文发给每位；工作流 = 按顺序接力；观点讨论 = 互相评议；队长总结 = 一位队长整理。
 */
enum class RoundMode(val label: String, val placeholder: String, val sendLabel: String, val preset: Boolean) {
    ITERATE("独立迭代", "新的问题或追问，原文发给每位成员…", "发送给每位成员", false),
    RELAY("工作流", "你的问题（第 1 位直接收到）…", "开始工作流", true),
    DISCUSS("观点讨论", "你的补充（可选，附在预设之后）…", "发起讨论", true),
    SUMMARY("队长总结", "你的补充（可选，附在预设之后）…", "请队长总结", true),
    ;

    companion object {
        fun fromName(value: String?): RoundMode = entries.firstOrNull { it.name == value } ?: ITERATE
    }
}

/** 胶囊式分段选择器；选中项蓝底白字。 */
@Composable
internal fun ModeSegmented(options: List<Pair<String, String>>, selected: String, enabled: Boolean,
                           onSelect: (String) -> Unit, modifier: Modifier = Modifier, testTagPrefix: String = "mode") {
    val colors = ArenaStyle.colors
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.card).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(11.dp))
                .background(if (on) colors.accent else colors.card)
                .clickable(enabled = enabled && !on) { onSelect(key) }
                .semantics { role = Role.Tab; this.selected = on; contentDescription = label }
                .testTag("$testTagPrefix-$key"),
                contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) colors.onAccent else if (enabled) colors.ink else colors.muted)
            }
        }
    }
}

/** 工作流顺序：① DeepSeek → ② 豆包 ↑ …，点 ↑ 把这一位提前一位。 */
@Composable
internal fun RelayOrderRow(order: List<ArenaService>, enabled: Boolean, onMoveUp: (Int) -> Unit) {
    val colors = ArenaStyle.colors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("relay-order"),
        verticalAlignment = Alignment.CenterVertically) {
        Text("顺序", style = MaterialTheme.typography.labelMedium, color = colors.muted)
        order.forEachIndexed { index, service ->
            if (index > 0) Text("→", Modifier.padding(horizontal = 4.dp), color = colors.muted, style = MaterialTheme.typography.labelMedium)
            Surface(Modifier.padding(start = if (index == 0) 8.dp else 0.dp), shape = RoundedCornerShape(10.dp),
                color = colors.page, border = BorderStroke(1.dp, colors.border)) {
                Row(Modifier.height(40.dp).padding(start = 8.dp, end = if (index == 0) 10.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = colors.accent, fontWeight = FontWeight.Bold)
                    BrandAvatar(service, Modifier.padding(start = 6.dp), size = 18.dp)
                    Text(service.shortName, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium, color = colors.ink)
                    if (index > 0) Box(Modifier.padding(start = 2.dp).size(40.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled) { onMoveUp(index) }
                        .semantics { contentDescription = "把 ${service.shortName} 提前一位" },
                        contentAlignment = Alignment.Center) {
                        Text("↑", color = if (enabled) colors.accent else colors.muted, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

/** 小号分段：讨论方式、总结深度这类二三选一的选项。 */
@Composable
internal fun ChipChoice(label: String, options: List<Pair<String, String>>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val colors = ArenaStyle.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.muted)
        options.forEach { (key, name) ->
            val on = key == selected
            Surface(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(9.dp)).clickable(enabled = enabled && !on) { onSelect(key) }
                .semantics { this.selected = on; contentDescription = "$label：$name" }.testTag("choice-$key"),
                shape = RoundedCornerShape(9.dp), color = if (on) colors.accentSoft else colors.page,
                border = BorderStroke(1.dp, if (on) colors.accent else colors.border)) {
                Text(name, Modifier.padding(horizontal = 10.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium,
                    color = if (on) colors.accent else colors.ink, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** 队长总结的两个选项：谁来整理、写多深。 */
@Composable
internal fun SummaryOptionsRow(members: List<ArenaService>, captain: ArenaService?, depth: SummaryDepth, enabled: Boolean,
                               onCaptain: (ArenaService) -> Unit, onDepth: (SummaryDepth) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            ChipChoice("队长", members.map { it.name to it.shortName }, captain?.name.orEmpty(), enabled) { name ->
                ArenaService.fromName(name)?.let(onCaptain)
            }
        }
        ChipChoice("深度", SummaryDepth.entries.map { it.name to it.displayName }, depth.name, enabled) { onDepth(SummaryDepth.fromName(it)) }
    }
}

/**
 * 预设提示词卡片：灰底，占位用虚线胶囊（「队友1的回答」），默认收起成两行，点开看全文；
 * 「编辑」修改后对之后所有圆桌生效。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PresetCard(key: PresetKey, parts: List<PresetPart>, custom: Boolean, enabled: Boolean, onEdit: () -> Unit) {
    val colors = ArenaStyle.colors
    var open by remember(key) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().testTag("preset-card"), shape = RoundedCornerShape(12.dp), color = colors.card) {
        Column(Modifier.clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("预设 · ${key.displayName}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = colors.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (custom) Text("已自定义", Modifier.padding(end = 6.dp), style = MaterialTheme.typography.labelSmall, color = colors.accent)
                Text(if (open) "收起" else "展开", Modifier.padding(end = 4.dp), style = MaterialTheme.typography.labelSmall, color = colors.muted)
                TextButton(onClick = onEdit, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.heightIn(min = 36.dp).testTag("edit-preset")) {
                    Text("编辑", style = MaterialTheme.typography.labelMedium, color = colors.accent)
                }
            }
            if (open) {
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    groupParts(parts).forEach { group ->
                        if (group.first().slot) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            group.forEach { SlotChip(it.text) }
                        } else Text(group.joinToString("") { it.text }.trim(), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    }
                }
            } else {
                val text = parts.filterNot { it.slot }.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim()
                Text(text, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 4) {
                    parts.filter { it.slot }.distinctBy { it.text }.take(4).forEach { SlotChip(it.text) }
                }
            }
        }
    }
}

private fun groupParts(parts: List<PresetPart>): List<List<PresetPart>> {
    val groups = mutableListOf<MutableList<PresetPart>>()
    parts.forEach { part ->
        val last = groups.lastOrNull()
        if (last != null && last.first().slot == part.slot) last += part else groups += mutableListOf(part)
    }
    return groups
}

@Composable
private fun SlotChip(label: String) {
    val colors = ArenaStyle.colors
    val border = colors.accent
    Text(label, Modifier.padding(vertical = 1.dp).drawBehind {
        drawRoundRect(color = border.copy(alpha = 0.55f), cornerRadius = CornerRadius(size.height / 2),
            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
    }.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = colors.accent)
}

/** 编辑预设：必须保留占位；「恢复默认」删掉自定义；保存后之后所有圆桌都用这一版。 */
@Composable
internal fun PresetEditorDialog(key: PresetKey, store: ArenaPresetStore, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(key) { mutableStateOf(store.template(key)) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    val colors = ArenaStyle.colors
    AlertDialog(onDismissRequest = onDismiss, containerColor = colors.page,
        title = { Text("编辑预设 · ${key.displayName}", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("花括号里的是占位，发送时换成真实材料，必须保留：${key.required.joinToString("、")}。你在输入框写的补充会附在最后。",
                    style = MaterialTheme.typography.bodySmall, color = colors.muted)
                OutlinedTextField(text, { text = it; error = null }, Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp).testTag("preset-editor"),
                    textStyle = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = store.save(key, text)
                if (problem == null) onDone("预设已保存，之后所有圆桌都用这一版") else error = problem
            }, modifier = Modifier.testTag("preset-save")) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { store.save(key, null); onDone("已恢复默认预设") }, modifier = Modifier.testTag("preset-reset")) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        })
}

/** 预设相关的上下文：给卡片用的占位数量与轮次。 */
internal data class PresetContext(val peers: Int, val answers: Int, val members: Int, val debateIndex: Int)

/** 家庭用户友好的空状态提示卡。 */
@Composable
internal fun HintCard(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val colors = ArenaStyle.colors
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = colors.card) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.muted, textAlign = TextAlign.Start)
            if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) { Text(action, color = colors.accent) }
        }
    }
}

internal fun Modifier.hairline(color: androidx.compose.ui.graphics.Color, radius: Int = 12): Modifier =
    border(1.dp, color, RoundedCornerShape(radius.dp))
