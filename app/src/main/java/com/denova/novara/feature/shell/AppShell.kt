package com.denova.novara.feature.shell

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.denova.novara.R
import com.denova.novara.core.network.BookRecord

/** 一级目的地（对齐 01 §3 导航顺序；写作/游戏并排入口，设置位于末尾）。 */
data class AppDestination(
    val route: String,
    val labelRes: Int,
    val implemented: Boolean,
)

val appDestinations: List<AppDestination> = listOf(
    AppDestination("workbench", R.string.dest_workbench, implemented = false),
    AppDestination("editor", R.string.dest_editor, implemented = true),
    AppDestination("game", R.string.dest_game, implemented = true),
    AppDestination("agent", R.string.dest_agent, implemented = true),
    AppDestination("lore", R.string.dest_lore, implemented = false),
    AppDestination("teller", R.string.dest_teller, implemented = false),
    AppDestination("versions", R.string.dest_versions, implemented = false),
    AppDestination("books", R.string.dest_books, implemented = false),
    AppDestination("skills", R.string.dest_skills, implemented = false),
    AppDestination("agents", R.string.dest_agents, implemented = false),
    AppDestination("automations", R.string.dest_automations, implemented = false),
    AppDestination("market", R.string.dest_market, implemented = false),
    AppDestination("settings", R.string.dest_settings, implemented = false),
)

/** 左导航抽屉内容。 */
@Composable
fun AppDrawer(
    currentRoute: String?,
    darkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    language: String,
    onSelectLanguage: (String) -> Unit,
    books: List<BookRecord>,
    currentProjectId: String?,
    onSelectBook: (BookRecord) -> Unit,
    /** debug 构建的日志入口；为 null 时不显示。放在抽屉里而不是悬浮按钮，避免遮挡底部操作。 */
    onOpenDebug: (() -> Unit)? = null,
    onNavigate: (AppDestination) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            stringResource(R.string.drawer_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        HorizontalDivider()
        // 当前书籍切换（P3 链路起点）：选择后作为编辑器 / 项目目录的工作区。
        Text(
            stringResource(R.string.current_book),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (books.isEmpty()) {
            Text(
                stringResource(R.string.book_empty),
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else {
            books.forEach { book ->
                val selected = book.projectId == currentProjectId
                TextButton(onClick = { onSelectBook(book) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            book.name,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        HorizontalDivider()
        appDestinations.forEach { destination ->
            val selected = destination.route == currentRoute
            TextButton(
                onClick = { onNavigate(destination) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(destination.labelRes),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (!destination.implemented) {
                        Text(
                            stringResource(R.string.not_implemented_suffix),
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        TextButton(onClick = onToggleDarkTheme, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dark_mode), modifier = Modifier.weight(1f))
            Switch(checked = darkTheme, onCheckedChange = null)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.language), modifier = Modifier.weight(1f))
            LanguageOption("中文", "zh", language, onSelectLanguage)
            LanguageOption("English", "en", language, onSelectLanguage)
        }
        if (onOpenDebug != null) {
            HorizontalDivider()
            TextButton(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.drawer_debug))
            }
        }
    }
}

@Composable
private fun LanguageOption(
    label: String,
    tag: String,
    current: String,
    onSelect: (String) -> Unit,
) {
    val selected = current == tag || (current.isBlank() && tag == "en")
    TextButton(onClick = { onSelect(tag) }) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 未实现目的地的占位页。 */
@Composable
fun PlaceholderScreen(labelRes: Int, onOpenDrawer: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            IconButton(onClick = onOpenDrawer) { Text("☰") }
            Text(stringResource(labelRes), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.not_implemented, stringResource(labelRes)),
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
