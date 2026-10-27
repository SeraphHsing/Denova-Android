package com.denova.novara.feature.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.denova.novara.R
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.network.AgentChatProject
import com.denova.novara.core.network.AgentChatSession

/**
 * 工作台项目 / 会话树（P4-B / WB-001 / WB-002）。
 *
 * 结构对齐基线 `AgentChatActivitySidebar` + `AgentChatSidebarProject`：
 * 项目行（折叠箭头 / 名称 / 当前标记 / 缺失提示 / 操作菜单 / 新建会话），
 * 展开后是该项目的会话行（标题 / 运行状态 / 重命名 / 删除）。
 * 「最近会话」按基线先显示 5 条，可继续展开。
 */
@Composable
fun WorkbenchTreeContent(
    state: WorkbenchUiState,
    onRefresh: () -> Unit,
    onSelectProject: (String) -> Unit,
    onToggleProject: (String) -> Unit,
    onOpenSession: (String, String) -> Unit,
    onCreateSession: (String) -> Unit,
    onRenameSession: (String, String, String) -> Unit,
    onDeleteSession: (AgentChatProject, AgentChatSession) -> Unit,
    onRenameProject: (String, String) -> Unit,
    onArchiveProject: (AgentChatProject) -> Unit,
    onMoveProject: (String, Int) -> Unit,
    onAddFromHost: () -> Unit,
    onCreateDirectory: (String, String) -> Unit,
    onDismissNotice: () -> Unit,
    onDismissConfirm: () -> Unit,
    onConfirmDeleteSession: (String, String) -> Unit,
    onConfirmArchiveProject: (String) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var showCreateDirectory by remember { mutableStateOf(false) }
    var renamingProject by remember { mutableStateOf<AgentChatProject?>(null) }
    var renamingSession by remember { mutableStateOf<Pair<AgentChatProject, AgentChatSession>?>(null) }
    // 每个项目已展开的会话条数（默认 5，与基线一致）。
    var expandedCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.workbench_projects),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, enabled = !state.loading) {
                Text(stringResource(R.string.refresh))
            }
            Box {
                TextButton(onClick = { showAdd = true }, enabled = !state.busy) {
                    Text("＋ " + stringResource(R.string.workbench_project_add))
                }
                DropdownMenu(expanded = showAdd, onDismissRequest = { showAdd = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.workbench_project_add_host)) },
                        enabled = !state.hostDialogBusy,
                        onClick = {
                            showAdd = false
                            onAddFromHost()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.workbench_project_add_directory)) },
                        onClick = { showAdd = false; showCreateDirectory = true },
                    )
                }
            }
        }
        HorizontalDivider()

        state.notice?.let { notice ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    notice.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissNotice) { Text(stringResource(R.string.common_close)) }
            }
        }
        state.error?.let { error ->
            Text(
                error.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        if (state.projects.isEmpty() && !state.loading) {
            Text(
                stringResource(R.string.workbench_empty_projects),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(12.dp),
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            items(state.projects, key = { it.id }) { project ->
                val expanded = project.id !in state.collapsedProjects
                val limit = expandedCounts[project.id] ?: RECENT_SESSION_LIMIT
                val visible = project.sessions.take(limit)
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp)) {
                    ProjectRow(
                        project = project,
                        selected = project.id == state.selectedProjectId,
                        expanded = expanded,
                        onToggle = { onToggleProject(project.id) },
                        onNewSession = { onCreateSession(project.id) },
                        onSelect = { onSelectProject(project.id) },
                        onRename = { renamingProject = project },
                        onMoveUp = { onMoveProject(project.id, -1) },
                        onMoveDown = { onMoveProject(project.id, 1) },
                        onArchive = { onArchiveProject(project) },
                    )
                    if (expanded) {
                        Column(Modifier.padding(start = 16.dp)) {
                            project.error?.let { message ->
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            visible.forEach { session ->
                                SessionRow(
                                    session = session,
                                    running = "${project.id}/${session.id}" in state.runningSessions,
                                    deletable = project.sessions.size > 1,
                                    onOpen = { onOpenSession(project.id, session.id) },
                                    onRename = { renamingSession = project to session },
                                    onDelete = { onDeleteSession(project, session) },
                                )
                            }
                            if (limit < project.sessions.size) {
                                TextButton(
                                    onClick = {
                                        expandedCounts = expandedCounts +
                                            (project.id to (limit + RECENT_SESSION_LIMIT))
                                    },
                                ) {
                                    Text(
                                        stringResource(R.string.workbench_show_more_sessions) +
                                            " (" + stringResource(
                                                R.string.workbench_session_count,
                                                project.sessions.size,
                                            ) + ")",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- 对话框：新建目录 / 重命名项目 / 重命名会话 / 破坏性确认 ----
    if (showCreateDirectory) {
        InputDialog(
            title = stringResource(R.string.workbench_project_add_directory),
            initial = "",
            label = stringResource(R.string.workbench_project_directory_name),
            confirmLabel = stringResource(R.string.workbench_project_create),
            onDismiss = { showCreateDirectory = false },
            onConfirm = { name ->
                showCreateDirectory = false
                onCreateDirectory(name, "")
            },
        )
    }
    renamingProject?.let { project ->
        InputDialog(
            title = stringResource(R.string.workbench_project_rename),
            initial = project.name.ifBlank { project.path },
            label = null,
            confirmLabel = stringResource(R.string.workbench_rename),
            onDismiss = { renamingProject = null },
            onConfirm = { name ->
                renamingProject = null
                onRenameProject(project.id, name)
            },
        )
    }
    renamingSession?.let { (project, session) ->
        InputDialog(
            title = stringResource(R.string.workbench_session_rename),
            initial = session.title,
            label = null,
            confirmLabel = stringResource(R.string.workbench_rename),
            onDismiss = { renamingSession = null },
            onConfirm = { title ->
                renamingSession = null
                onRenameSession(project.id, session.id, title)
            },
        )
    }

    when (val confirm = state.confirm) {
        is WorkbenchConfirm.DeleteSession -> AlertDialog(
            onDismissRequest = onDismissConfirm,
            title = { Text(stringResource(R.string.workbench_session_delete)) },
            text = {
                Text(
                    stringResource(
                        R.string.workbench_session_delete_confirm,
                        confirm.title.ifBlank { stringResource(R.string.workbench_session_untitled) },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { onConfirmDeleteSession(confirm.projectId, confirm.sessionId) }) {
                    Text(stringResource(R.string.workbench_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissConfirm) { Text(stringResource(R.string.workbench_cancel)) }
            },
        )

        is WorkbenchConfirm.ArchiveProject -> AlertDialog(
            onDismissRequest = onDismissConfirm,
            title = { Text(stringResource(R.string.workbench_project_archive)) },
            text = { Text(stringResource(R.string.workbench_project_archive_confirm, confirm.name)) },
            confirmButton = {
                TextButton(onClick = { onConfirmArchiveProject(confirm.projectId) }) {
                    Text(stringResource(R.string.workbench_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissConfirm) { Text(stringResource(R.string.workbench_cancel)) }
            },
        )

        null -> Unit
    }
}

private const val RECENT_SESSION_LIMIT = 5

@Composable
private fun ProjectRow(
    project: AgentChatProject,
    selected: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    onNewSession: () -> Unit,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onArchive: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(6.dp),
            )
            .clickable {
                onSelect()
                onToggle()
            }
            .padding(start = 4.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Text(
            project.name.ifBlank { project.path },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 6.dp),
        )
        if (project.current) {
            Text(
                stringResource(R.string.workbench_project_current),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        if (project.status == "missing") {
            Text("⚠", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = onNewSession) { Text("＋", style = MaterialTheme.typography.bodyMedium) }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.workbench_project_rename)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.workbench_project_move_up)) }, onClick = { menu = false; onMoveUp() })
                DropdownMenuItem(text = { Text(stringResource(R.string.workbench_project_move_down)) }, onClick = { menu = false; onMoveDown() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.workbench_project_archive)) }, onClick = { menu = false; onArchive() })
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: AgentChatSession,
    running: Boolean,
    deletable: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 5.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            session.title.ifBlank { stringResource(R.string.workbench_session_untitled) },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (running) {
            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Text(
                stringResource(R.string.workbench_session_running),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.bodySmall) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.workbench_session_rename)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workbench_session_delete)) },
                    enabled = deletable && !running,
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
internal fun InputDialog(
    title: String,
    initial: String,
    label: String?,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.imePadding()) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = label?.let { { Text(it) } },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.trim().isNotEmpty()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.workbench_cancel)) } },
    )
}
