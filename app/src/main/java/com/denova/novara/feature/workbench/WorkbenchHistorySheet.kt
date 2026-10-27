package com.denova.novara.feature.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.denova.novara.R
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.network.AgentChatHistoryItem
import com.denova.novara.core.network.AgentChatProject
import com.denova.novara.core.workbench.HistoryList

/**
 * 工作台会话历史（P4-B / WB-006）。
 *
 * 对齐基线 `AgentChatSessionHistoryDialog`：项目切换条 + 搜索框 + 历史行（状态点 / 标题 / 运行中 /
 * 时间 / 消息数 / 摘要高亮）+ 行内动作（重命名 / 导出 Markdown / 删除）+「加载更多」+ 底部计数。
 * 手机上一屏放不下「项目侧栏 + 列表」两栏，项目选择改成顶部横向 chip 条。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchHistorySheet(
    viewModel: WorkbenchHistoryViewModel,
    onDismiss: () -> Unit,
    onOpenSession: (projectId: String, sessionId: String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var menuFor by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.workbench_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.refresh() }) { Text(stringResource(R.string.refresh)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            }

            if (state.projects.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.projects, key = { it.id }) { project ->
                        ProjectChip(
                            project = project,
                            selected = project.id == state.selectedProjectId,
                            onClick = { viewModel.selectProject(project.id) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text(stringResource(R.string.workbench_history_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )

            state.error?.let { error ->
                Text(
                    error.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            state.message?.let { message ->
                Text(
                    message.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            HorizontalDivider()
            when {
                state.loading && state.page.items.isEmpty() -> Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall) }
                state.page.items.isEmpty() -> Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.workbench_history_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxWidth().sizeIn(maxHeight = 460.dp)) {
                    items(state.page.items, key = { HistoryList.key(it) }) { item ->
                        HistoryRow(
                            item = item,
                            query = state.query,
                            exporting = state.exportingKey == HistoryList.key(item),
                            menuOpen = menuFor == HistoryList.key(item),
                            onOpenMenu = { menuFor = HistoryList.key(item) },
                            onDismissMenu = { menuFor = null },
                            onOpen = {
                                onOpenSession(item.projectId, item.session.id)
                                onDismiss()
                            },
                            onRename = {
                                menuFor = null
                                viewModel.startRename(item)
                            },
                            onExport = {
                                menuFor = null
                                viewModel.export(item)
                            },
                            onDelete = {
                                menuFor = null
                                viewModel.requestDelete(item)
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                    if (state.page.hasMore) {
                        item("load-more") {
                            Row(
                                Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                TextButton(
                                    onClick = { viewModel.loadMore() },
                                    enabled = !state.loadingMore,
                                ) {
                                    Text(
                                        stringResource(
                                            if (state.loadingMore) R.string.loading else R.string.workbench_history_load_more,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    item("count") {
                        Text(
                            stringResource(
                                R.string.workbench_history_count,
                                state.page.items.size,
                                state.page.total,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }

    state.renaming?.let { item ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRename,
            title = { Text(stringResource(R.string.workbench_rename)) },
            text = {
                OutlinedTextField(
                    value = state.renameDraft,
                    onValueChange = viewModel::onRenameDraftChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.workbench_history_rename_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.submitRename() },
                    enabled = state.renameDraft.isNotBlank(),
                ) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRename) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    state.pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.workbench_history_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.workbench_history_delete_body,
                        item.session.title.ifBlank { stringResource(R.string.workbench_session_untitled) },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) {
                    Text(stringResource(R.string.workbench_history_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

}

@Composable
private fun ProjectChip(project: AgentChatProject, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            project.name.ifBlank { project.path.ifBlank { project.id } },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (project.current) {
            Text(
                stringResource(R.string.workbench_project_current),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun HistoryRow(
    item: AgentChatHistoryItem,
    query: String,
    exporting: Boolean,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(
                    if (item.session.running) MaterialTheme.colorScheme.primary else Color.Transparent,
                    CircleShape,
                ),
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.session.title.ifBlank { stringResource(R.string.workbench_session_untitled) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.session.running) {
                    Text(
                        stringResource(R.string.workbench_session_running),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(
                    compactTime(item.session.updatedAt.ifBlank { item.session.createdAt }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Text(
                    stringResource(R.string.workbench_history_message_count, item.session.messageCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (item.excerpt.isNotBlank()) {
                Text(
                    highlighted(item.excerpt, query),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Box {
            TextButton(onClick = onOpenMenu, enabled = !exporting) { Text("⋮") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workbench_history_open)) },
                    onClick = onOpen,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workbench_rename)) },
                    onClick = onRename,
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (exporting) R.string.workbench_history_exporting else R.string.workbench_history_export,
                            ),
                        )
                    },
                    enabled = !exporting,
                    onClick = onExport,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workbench_history_delete), color = MaterialTheme.colorScheme.error) },
                    enabled = !item.session.running,
                    onClick = onDelete,
                )
            }
        }
    }
}

/** 摘要高亮：命中查询词的部分用主色加粗（对应基线 `HighlightedText`）。 */
@Composable
private fun highlighted(text: String, query: String): AnnotatedString {
    val ranges = HistoryList.highlightRanges(text, query)
    val highlight = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        ranges.forEach { range ->
            if (range.first > cursor) append(text.substring(cursor, range.first))
            withStyle(highlight) { append(text.substring(range.first, range.last + 1)) }
            cursor = range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

/** 只显示「月-日 时:分」，历史行空间有限；服务端时间是 RFC3339。 */
private fun compactTime(raw: String): String {
    if (raw.length < 16) return raw
    val date = raw.substring(5, 10)
    val time = raw.substring(11, 16)
    return "$date $time"
}
