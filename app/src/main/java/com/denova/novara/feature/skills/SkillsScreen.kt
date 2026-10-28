package com.denova.novara.feature.skills

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.denova.novara.R
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.SkillDocument
import com.denova.novara.core.network.SkillScopes
import com.denova.novara.core.network.SkillSummary
import com.denova.novara.core.skills.SkillLibrary
import com.denova.novara.core.skills.SkillStatusFilter
import com.denova.novara.core.skills.key
import kotlinx.coroutines.delay

/**
 * 技能库（P4-C / SKL-001·002），对齐基线 `features/skills/SkillsView.tsx`：
 * 全局 / 项目目标切换、搜索与分类状态筛选、启用偏好、SKILL.md 与附属文件的读取与保存、
 * 新建与删除、内置 Skill 覆盖。
 *
 * 布局按 NAV 修复后的口径：标题固定，其余全部内容放进**单个 LazyColumn**，
 * 横屏 / 分屏任意高度都能滚到每一项（不再用「固定底栏 + weight 滚动列」）。
 */
@Composable
fun SkillsScreen(
    client: DenovaClient?,
    projectId: String,
    onOpenDrawer: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as Application
    val factory: ViewModelProvider.Factory = remember(application) {
        viewModelFactory { initializer { SkillsViewModel(application) } }
    }
    val viewModel: SkillsViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()

    LaunchedEffect(client) { client?.let { viewModel.bindClient(it) } }
    LaunchedEffect(projectId) { viewModel.setProject(projectId) }

    var createOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SkillSummary?>(null) }

    val visible = SkillLibrary.filter(state.snapshot.skills, state.query, state.category, state.status)
    val groups = SkillLibrary.groupByScope(visible)
    val categories = SkillLibrary.categories(state.snapshot.skills)
    val writable = state.writableScopes
    // 打开的 SKILL.md / 附属文件详情在列表末尾：选中后自动滚到底，否则点「打开」看起来没反应
    // （2026-10-28 真机确认）。
    val listState = rememberLazyListState()
    LaunchedEffect(state.selected, state.filePath) {
        if (state.selected == null) return@LaunchedEffect
        delay(120)
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) { Text("☰") }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dest_skills), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.skills_count, state.snapshot.skills.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (state.loading) {
                Text(stringResource(R.string.skills_loading), style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { viewModel.load(force = true) }) { Text("⟳") }
        }
        HorizontalDivider()

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "targets") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = state.target == SkillTargets.GLOBAL,
                        onClick = { viewModel.setTarget(SkillTargets.GLOBAL) },
                        label = { Text(stringResource(R.string.skills_target_global)) },
                    )
                    FilterChip(
                        selected = state.target == SkillTargets.PROJECT,
                        onClick = { viewModel.setTarget(SkillTargets.PROJECT) },
                        label = { Text(stringResource(R.string.skills_target_project)) },
                    )
                    Text(
                        if (state.target == SkillTargets.PROJECT && state.projectId.isBlank()) {
                            stringResource(R.string.skills_project_required)
                        } else {
                            stringResource(R.string.skills_scope_target, state.target)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item(key = "preferences") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.skills_shared_toggle),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.skills_shared_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Switch(
                        checked = state.snapshot.sharedEnabled,
                        onCheckedChange = { viewModel.setSharedEnabled(it) },
                    )
                }
            }

            item(key = "filters") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.skills_search)) },
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = state.category == "all",
                            onClick = { viewModel.setCategory("all") },
                            label = { Text(stringResource(R.string.skills_category_all)) },
                        )
                        val shown = categories.take(3)
                        shown.forEach { category ->
                            FilterChip(
                                selected = state.category == category,
                                onClick = { viewModel.setCategory(category) },
                                label = { Text(categoryLabel(category)) },
                            )
                        }
                        if (categories.size > 3) {
                            AssistChip(
                                onClick = { viewModel.setCategory(categories.drop(3).first()) },
                                label = { Text(stringResource(R.string.skills_category_more, categories.size - 3)) },
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = state.status == SkillStatusFilter.ALL,
                            onClick = { viewModel.setStatusFilter(SkillStatusFilter.ALL) },
                            label = { Text(stringResource(R.string.skills_status_all)) },
                        )
                        FilterChip(
                            selected = state.status == SkillStatusFilter.ENABLED,
                            onClick = { viewModel.setStatusFilter(SkillStatusFilter.ENABLED) },
                            label = { Text(stringResource(R.string.skills_status_enabled)) },
                        )
                        FilterChip(
                            selected = state.status == SkillStatusFilter.DISABLED,
                            onClick = { viewModel.setStatusFilter(SkillStatusFilter.DISABLED) },
                            label = { Text(stringResource(R.string.skills_status_disabled)) },
                        )
                    }
                }
            }

            item(key = "create") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { createOpen = true },
                        enabled = writable.isNotEmpty(),
                    ) { Text(stringResource(R.string.skills_new)) }
                    Text(
                        if (writable.isEmpty()) {
                            stringResource(R.string.skills_no_writable)
                        } else {
                            stringResource(R.string.skills_writable_scopes, writable.joinToString(", "))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (visible.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (state.loading) {
                            stringResource(R.string.skills_loading)
                        } else {
                            stringResource(R.string.skills_empty)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            groups.forEach { (scope, items) ->
                item(key = "scope-$scope") {
                    Text(
                        scopeLabel(scope) + " · " + items.size,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(items, key = { "skill-${it.scope}/${it.name}" }) { skill ->
                    SkillRow(
                        skill = skill,
                        selected = state.selected == skill.key(),
                        onSelect = { viewModel.select(skill.key()) },
                        onToggle = { enabled -> viewModel.setEnabled(skill, enabled) },
                    )
                }
            }

            state.document?.let { document ->
                item(key = "document") {
                    DocumentCard(
                        document = document,
                        content = state.content,
                        dirty = state.dirty,
                        busy = state.busy,
                        conflict = state.conflict,
                        writableScopes = writable,
                        onEdit = viewModel::editContent,
                        onSave = { viewModel.saveDocument() },
                        onOverride = { scope -> viewModel.saveAsOverride(scope) },
                        onDelete = {
                            state.snapshot.skills.firstOrNull { it.key() == state.selected }?.let {
                                deleteTarget = it
                            }
                        },
                        files = SkillLibrary.editableFiles(document.files),
                        activeFile = state.filePath,
                        fileContent = state.fileContent,
                        fileDirty = state.fileDirty,
                        fileLoading = state.fileLoading,
                        onOpenFile = viewModel::openFile,
                        onCloseFile = { viewModel.closeFile() },
                        onEditFile = viewModel::editFileContent,
                        onSaveFile = { viewModel.saveFile() },
                    )
                }
            }

            state.error?.let { error ->
                item(key = "error") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            error.resolve(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.consumeError() }) {
                            Text(stringResource(R.string.common_close))
                        }
                    }
                }
            }
            state.notice?.let { notice ->
                item(key = "notice") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            notice.resolve(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.consumeNotice() }) {
                            Text(stringResource(R.string.common_close))
                        }
                    }
                }
            }
        }
    }

    if (createOpen) {
        CreateSkillDialog(
            scopes = writable,
            projectScoped = state.target == SkillTargets.PROJECT,
            onDismiss = { createOpen = false },
            onCreate = { scope, name, description, category, capability, agents ->
                createOpen = false
                viewModel.create(
                    scope = scope,
                    name = name,
                    description = description,
                    category = category,
                    capabilities = if (capability) listOf("writing-workflow") else emptyList(),
                    agents = agents,
                )
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.skills_delete)) },
            text = { Text(stringResource(R.string.skills_delete_confirm, target.scope, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    viewModel.deleteSelected()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun SkillRow(
    skill: SkillSummary,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).padding(end = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "/" + skill.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!skill.active) {
                    Text(
                        "  " + stringResource(R.string.skills_shadowed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (!skill.enabled) {
                    Text(
                        "  " + stringResource(R.string.skills_status_disabled),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                skill.description.ifBlank { categoryLabel(SkillLibrary.categoryOf(skill)) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onSelect) { Text(stringResource(R.string.skills_open)) }
        Switch(checked = skill.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun DocumentCard(
    document: SkillDocument,
    content: String,
    dirty: Boolean,
    busy: Boolean,
    conflict: Boolean,
    writableScopes: List<String>,
    onEdit: (String) -> Unit,
    onSave: () -> Unit,
    onOverride: (String) -> Unit,
    onDelete: () -> Unit,
    files: List<com.denova.novara.core.network.SkillFileEntry>,
    activeFile: String,
    fileContent: String,
    fileDirty: Boolean,
    fileLoading: Boolean,
    onOpenFile: (String) -> Unit,
    onCloseFile: () -> Unit,
    onEditFile: (String) -> Unit,
    onSaveFile: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(
            "${document.name} · ${scopeLabel(document.scope)}",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            document.path,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            stringResource(
                R.string.skills_document_meta,
                document.revision.take(8).ifBlank { "-" },
                document.files.size,
                if (document.editable) stringResource(R.string.skills_scope_editable)
                else stringResource(R.string.skills_scope_readonly),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (!document.description.isBlank()) {
            Text(document.description, style = MaterialTheme.typography.bodySmall)
        }

        OutlinedTextField(
            value = content,
            onValueChange = onEdit,
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
            label = { Text(stringResource(R.string.skills_document_content)) },
            textStyle = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (document.editable) {
                TextButton(onClick = onSave, enabled = !busy) {
                    Text(
                        if (dirty) stringResource(R.string.skills_save_dirty)
                        else stringResource(R.string.skills_save),
                    )
                }
            } else {
                writableScopes.firstOrNull()?.let { scope ->
                    TextButton(onClick = { onOverride(scope) }, enabled = !busy) {
                        Text(stringResource(R.string.skills_override, scope))
                    }
                }
            }
            TextButton(onClick = onDelete, enabled = !busy) {
                Text(stringResource(R.string.skills_delete))
            }
            if (conflict) {
                Text(
                    stringResource(R.string.skills_conflict_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (files.isNotEmpty() || activeFile.isNotBlank()) {
            Text(stringResource(R.string.skills_files), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = onCloseFile,
                    label = { Text("SKILL.md") },
                )
                files.take(4).filter { it.path != "SKILL.md" }.forEach { file ->
                    AssistChip(
                        onClick = { onOpenFile(file.path) },
                        label = { Text(file.path.substringAfterLast('/'), maxLines = 1) },
                    )
                }
                if (activeFile.isNotBlank()) {
                    Text(
                        stringResource(R.string.skills_file_size, activeFile, fileContent.length),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (activeFile.isNotBlank()) {
            OutlinedTextField(
                value = fileContent,
                onValueChange = onEditFile,
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
                label = { Text(activeFile) },
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onSaveFile, enabled = !busy && !fileLoading) {
                    Text(
                        if (fileDirty) stringResource(R.string.skills_save_dirty)
                        else stringResource(R.string.skills_save),
                    )
                }
                TextButton(onClick = onCloseFile) { Text(stringResource(R.string.common_close)) }
            }
        }
    }
}

@Composable
private fun CreateSkillDialog(
    scopes: List<String>,
    projectScoped: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String, Boolean, List<String>) -> Unit,
) {
    var scope by remember { mutableStateOf(scopes.firstOrNull() ?: SkillScopes.USER) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("general") }
    var writingCapability by remember { mutableStateOf(false) }
    var agents by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_create_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.skills_create_scope), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    scopes.forEach { candidate ->
                        FilterChip(
                            selected = scope == candidate,
                            onClick = { scope = candidate },
                            label = { Text(scopeLabel(candidate)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.skills_create_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.skills_create_description)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.skills_create_category), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkillLibrary.standardCategories.forEach { candidate ->
                        FilterChip(
                            selected = category == candidate,
                            onClick = { category = candidate },
                            label = { Text(categoryLabel(candidate)) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = writingCapability, onCheckedChange = { writingCapability = it })
                    Text(
                        stringResource(R.string.skills_capability_writing),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = agents,
                    onValueChange = { agents = it },
                    label = { Text(stringResource(R.string.skills_create_agents)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (projectScoped) stringResource(R.string.skills_create_project_hint)
                    else stringResource(R.string.skills_create_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onCreate(
                        scope,
                        name,
                        description,
                        category,
                        writingCapability,
                        agents.split(',', ' ', '，').map { it.trim() }.filter { it.isNotEmpty() },
                    )
                },
            ) { Text(stringResource(R.string.skills_create_submit)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun scopeLabel(scope: String): String = when (scope) {
    SkillScopes.SHARED -> stringResource(R.string.skills_scope_shared)
    SkillScopes.BUILTIN -> stringResource(R.string.skills_scope_builtin)
    SkillScopes.USER -> stringResource(R.string.skills_scope_user)
    SkillScopes.WORKSPACE -> stringResource(R.string.skills_scope_workspace)
    SkillTargets.GLOBAL -> stringResource(R.string.skills_target_global)
    SkillTargets.PROJECT -> stringResource(R.string.skills_target_project)
    else -> scope
}

@Composable
private fun categoryLabel(category: String): String = when (category) {
    "general" -> stringResource(R.string.skills_category_general)
    "writing" -> stringResource(R.string.skills_category_writing)
    "game" -> stringResource(R.string.skills_category_game)
    "image" -> stringResource(R.string.skills_category_image)
    "research" -> stringResource(R.string.skills_category_research)
    "configuration" -> stringResource(R.string.skills_category_configuration)
    else -> category
}

