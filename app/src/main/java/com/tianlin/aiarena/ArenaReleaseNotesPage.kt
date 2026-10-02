package com.tianlin.aiarena

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ArenaReleaseNotesPage(latest: ArenaUpdateInfo?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val notes = remember(latest) { ArenaReleaseNotes.entries(latest) }
    Column(Modifier.fillMaxSize().background(ArenaStyle.colors.page).navigationBarsPadding().testTag("release-notes-page")) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars), verticalAlignment = Alignment.CenterVertically) {
            SimpleIcon(R.drawable.ic_arrow_back, "返回设置", onBack)
            Text("更新日志", style = MaterialTheme.typography.titleMedium)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("release-notes-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("当前安装 v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("release-installed-version"))
                Text("按版本查看主要变化和建议体验；历史日志离线可读。旧版功能可能已在后续版本调整。", style = MaterialTheme.typography.bodySmall, color = ArenaStyle.colors.muted, modifier = Modifier.padding(top = 8.dp))
            }
            items(notes, key = { it.version }) { note ->
                var expanded by rememberSaveable(note.version) { mutableStateOf(note.version == notes.first().version) }
                val current = note.version == BuildConfig.VERSION_NAME
                val available = latest?.isNewerThan(BuildConfig.VERSION_CODE) == true && note.version == latest.versionName
                Card(colors = CardDefaults.cardColors(containerColor = ArenaStyle.colors.surface), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().testTag("release-version-${note.version}"), contentPadding = PaddingValues(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("v${note.version}" + when { current -> " · 当前安装"; available -> " · 可更新"; else -> "" }, style = MaterialTheme.typography.titleSmall, color = ArenaStyle.colors.ink)
                            Text(note.title, style = MaterialTheme.typography.bodyMedium, color = ArenaStyle.colors.ink)
                            if (note.date.isNotBlank()) Text(note.date, style = MaterialTheme.typography.bodySmall, color = ArenaStyle.colors.muted)
                        }
                        Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium, color = ArenaStyle.colors.muted, modifier = Modifier.padding(start = 12.dp))
                    }
                    if (expanded) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).testTag("release-detail-${note.version}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("主要变化", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        note.changes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                        Text("建议体验", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        Text(note.tryThis, style = MaterialTheme.typography.bodyMedium, color = ArenaStyle.colors.muted)
                    }
                }
            }
        }
    }
}
