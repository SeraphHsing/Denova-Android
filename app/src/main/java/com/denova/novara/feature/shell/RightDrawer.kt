package com.denova.novara.feature.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.denova.novara.R
import com.denova.novara.core.network.WorkspaceChangeGroupSummary
import com.denova.novara.core.project.ProjectFileRow
import com.denova.novara.feature.changes.ChangesUiState
import com.denova.novara.feature.project.ProjectDirectoryUiState

/**
 * 右项目 / 控制台抽屉（01 §3）：从右侧滑出，内容按当前页面提供——
 * 写作页为项目目录，游戏页为游戏控制台。数据源待接（文件树 API / 游戏状态）。
 */
@Composable
fun RightDrawer(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(onClick = onDismiss),
        )
        AnimatedVisibility(
            visible = true,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(320.dp),
                tonalElevation = 3.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    content()
                }
            }
        }
    }
}

/**
 * 项目目录（WRI-002）：`/files/resolve` 递归文件树。点击目录展开/折叠，点击文件由回调交给编辑器打开。
 */
@Composable
fun ProjectDirectoryContent(
    state: ProjectDirectoryUiState,
    onToggle: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.project_directory), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onRefresh, enabled = !state.loading) {
                Text(stringResource(R.string.refresh))
            }
        }
        val muted = MaterialTheme.colorScheme.outline
        when {
            state.projectId.isBlank() -> Text(
                stringResource(R.string.project_directory_hint),
                color = muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            state.loading && state.rows.isEmpty() -> Text(
                stringResource(R.string.loading),
                color = muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            state.error != null -> Text(
                state.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            state.rows.isEmpty() -> Text(
                stringResource(R.string.project_directory_empty),
                color = muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.rows, key = { it.node.path }) { row ->
                    ProjectFileRowItem(
                        row = row,
                        selected = row.node.path == state.selectedPath,
                        onToggle = onToggle,
                        onOpenFile = onOpenFile,
                    )
                }
            }
        }
    }
}

/**
 * 变更审阅（P3 链路）：列出 `/changes/groups`，支持接受 / 拒绝 / 撤销 / 重做。
 */
@Composable
fun ChangesContent(
    state: ChangesUiState,
    onReview: (groupId: String, decision: String) -> Unit,
    onUndo: (String) -> Unit,
    onRedo: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.changes_title), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onRefresh, enabled = !state.loading) {
                Text(stringResource(R.string.refresh))
            }
        }
        val muted = MaterialTheme.colorScheme.outline
        when {
            state.projectId.isBlank() -> Text(
                stringResource(R.string.changes_hint), color = muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            state.loading && state.groups.isEmpty() -> Text(
                stringResource(R.string.loading), color = muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            state.error != null -> Text(
                state.error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            state.groups.isEmpty() -> Text(
                stringResource(R.string.changes_empty), color = muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.groups, key = { it.id }) { group ->
                    ChangeGroupItem(
                        group = group,
                        busy = state.busyGroupId == group.id,
                        onReview = onReview,
                        onUndo = onUndo,
                        onRedo = onRedo,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangeGroupItem(
    group: WorkspaceChangeGroupSummary,
    busy: Boolean,
    onReview: (String, String) -> Unit,
    onUndo: (String) -> Unit,
    onRedo: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "${group.id.take(12)} · ${group.reviewStatus}/${group.applyState}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (group.paths.isNotEmpty()) {
            Text(
                group.paths.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
            )
        }
        Text(
            stringResource(R.string.changes_pending_edits, group.pendingEditCount, group.changeSetCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (group.reviewStatus == "pending") {
                TextButton(onClick = { onReview(group.id, "accept") }, enabled = !busy) {
                    Text(stringResource(R.string.changes_accept))
                }
                TextButton(onClick = { onReview(group.id, "reject") }, enabled = !busy) {
                    Text(stringResource(R.string.changes_reject))
                }
            }
            if (group.canUndo) {
                TextButton(onClick = { onUndo(group.id) }, enabled = !busy) {
                    Text(stringResource(R.string.changes_undo))
                }
            }
            if (group.canRedo) {
                TextButton(onClick = { onRedo(group.id) }, enabled = !busy) {
                    Text(stringResource(R.string.changes_redo))
                }
            }
        }
    }
}

@Composable
private fun ProjectFileRowItem(
    row: ProjectFileRow,
    selected: Boolean,
    onToggle: (String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val node = row.node
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable { if (node.isDir) onToggle(node.path) else onOpenFile(node.path) }
            .padding(start = (12 + row.depth * 16).dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (node.isDir) {
            Text(
                if (row.expanded) "▾" else "▸",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(node.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        } else {
            Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Text(node.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
}

/** 游戏控制台占位（游戏页）。会话 / 分支状态后续接 GameViewModel。 */
@Composable
fun GameConsoleContent() {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "游戏控制台（待接会话 / 分支状态）",
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
