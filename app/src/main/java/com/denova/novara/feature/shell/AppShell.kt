package com.denova.novara.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 一级目的地（对齐 01 §3 导航顺序；写作/游戏并排入口，设置位于末尾）。 */
data class AppDestination(
    val route: String,
    val label: String,
    val implemented: Boolean,
)

val appDestinations: List<AppDestination> = listOf(
    AppDestination("workbench", "工作台", implemented = false),
    AppDestination("editor", "写作", implemented = true),
    AppDestination("game", "游戏", implemented = true),
    AppDestination("agent", "写作 Agent", implemented = true),
    AppDestination("lore", "资料库", implemented = false),
    AppDestination("teller", "创作方案", implemented = false),
    AppDestination("versions", "版本管理", implemented = false),
    AppDestination("books", "书籍管理", implemented = false),
    AppDestination("skills", "Skills", implemented = false),
    AppDestination("agents", "Agents", implemented = false),
    AppDestination("automations", "自动化", implemented = false),
    AppDestination("market", "资源中心", implemented = false),
    AppDestination("settings", "设置", implemented = false),
)

/** 左导航抽屉内容。 */
@Composable
fun AppDrawer(currentRoute: String?, onNavigate: (AppDestination) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Novara",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        HorizontalDivider()
        appDestinations.forEach { destination ->
            val selected = destination.route == currentRoute
            TextButton(
                onClick = { onNavigate(destination) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        destination.label,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (!destination.implemented) {
                        Text(
                            "·未实现",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

/** 未实现目的地的占位页。 */
@Composable
fun PlaceholderScreen(label: String, onOpenDrawer: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            IconButton(onClick = onOpenDrawer) { Text("☰") }
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("$label 尚未实现", color = MaterialTheme.colorScheme.outline)
        }
    }
}
