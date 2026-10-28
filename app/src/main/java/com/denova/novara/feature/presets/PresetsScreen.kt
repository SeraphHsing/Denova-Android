package com.denova.novara.feature.presets

import android.app.Application
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.denova.novara.core.network.PresetKind
import com.denova.novara.core.network.PresetResource
import kotlinx.coroutines.delay

/**
 * 创作方案（P4-C / PLN-001·002），对齐基线 `SettingPanel mode="teller"`（六类预设资源）：
 * 叙事风格 / 游戏规划 / 事件包 / TRPG 检定 / 状态系统 / 图像方案。
 *
 * 目录选类型 → 列表选条目 → 详情查看 / 编辑 / 删除；结构字段以 JSON 文本编辑，
 * 保存时与服务端整份定义合并（不丢客户端不认识的字段）。
 */
@Composable
fun PresetsScreen(
    client: DenovaClient?,
    onOpenDrawer: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as Application
    val factory: ViewModelProvider.Factory = remember(application) {
        viewModelFactory { initializer { PresetsViewModel(application) } }
    }
    val viewModel: PresetsViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()

    LaunchedEffect(client) { client?.let { viewModel.bindClient(it) } }

    var deleteTarget by remember { mutableStateOf<PresetResource?>(null) }
    val entries = state.list()
    // 详情 / 表单在列表末尾：选中或开始编辑后自动滚到底，否则用户点了「新建」看不到表单
    // （2026-10-28 真机确认）。
    val listState = rememberLazyListState()
    LaunchedEffect(state.editing, state.selectedId) {
        if (!state.editing && state.selectedId.isBlank()) return@LaunchedEffect
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
                Text(stringResource(R.string.dest_teller), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.presets_count, entries.size, kindLabel(state.kind)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (state.loading) {
                Text(stringResource(R.string.presets_loading), style = MaterialTheme.typography.labelSmall)
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
            item(key = "kinds") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PresetKind.entries.forEach { kind ->
                        FilterChip(
                            selected = state.kind == kind,
                            onClick = { viewModel.selectKind(kind) },
                            label = { Text(kindLabel(kind)) },
                        )
                    }
                }
            }

            item(key = "search") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.presets_search)) },
                    )
                    TextButton(onClick = { viewModel.beginCreate() }) {
                        Text(stringResource(R.string.presets_new))
                    }
                }
            }

            if (entries.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (state.loading) stringResource(R.string.presets_loading)
                        else stringResource(R.string.presets_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            items(entries, key = { "preset-${it.id}" }) { resource ->
                PresetRow(
                    kind = state.kind,
                    resource = resource,
                    selected = state.selectedId == resource.id,
                    onSelect = { viewModel.select(resource.id) },
                )
            }

            if (state.editing) {
                item(key = "editor") {
                    PresetEditorCard(
                        state = state,
                        kindLabel = kindLabel(state.kind),
                        onId = viewModel::editId,
                        onName = viewModel::editName,
                        onDescription = viewModel::editDescription,
                        onDetail = viewModel::editDetail,
                        onSave = { viewModel.save() },
                        onCancel = { viewModel.cancelEdit() },
                    )
                }
            } else {
                state.selected?.let { selected ->
                    item(key = "detail") {
                        PresetDetailCard(
                            kind = state.kind,
                            resource = selected,
                            busy = state.busy,
                            onEdit = { viewModel.beginEdit() },
                            onDelete = { deleteTarget = selected },
                        )
                    }
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

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.presets_delete)) },
            text = { Text(stringResource(R.string.presets_delete_confirm, target.name.ifBlank { target.id })) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    viewModel.delete(target.id)
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
private fun PresetRow(
    kind: PresetKind,
    resource: PresetResource,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val customLabel = stringResource(R.string.presets_custom)
    val invalidLabel = stringResource(R.string.presets_invalid)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                resource.name.ifBlank { resource.id },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(resource.id)
                    append(" · ")
                    append(resource.summary(kind))
                    if (resource.custom) append(" · ").append(customLabel)
                    if (resource.invalid) append(" · ").append(invalidLabel)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (resource.invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (resource.description.isNotBlank()) {
                Text(
                    resource.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onSelect) { Text(stringResource(R.string.presets_open)) }
    }
}

@Composable
private fun PresetDetailCard(
    kind: PresetKind,
    resource: PresetResource,
    busy: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Text(
            resource.name.ifBlank { resource.id } + " · " + kindLabel(kind),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            resource.id + " · " + stringResource(
                R.string.presets_detail_meta,
                resource.revision.take(8).ifBlank { "-" },
                resource.summary(kind),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (resource.path.isNotBlank()) {
            Text(
                resource.path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (resource.description.isNotBlank()) {
            Text(resource.description, style = MaterialTheme.typography.bodySmall)
        }
        if (resource.invalid) {
            Text(
                stringResource(R.string.presets_invalid_reason, resource.error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.presets_edit)) }
            TextButton(onClick = onDelete, enabled = !busy) { Text(stringResource(R.string.presets_delete)) }
        }
    }
}

@Composable
private fun PresetEditorCard(
    state: PresetsUiState,
    kindLabel: String,
    onId: (String) -> Unit,
    onName: (String) -> Unit,
    onDescription: (String) -> Unit,
    onDetail: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(
            if (state.creating) {
                stringResource(R.string.presets_create_title, kindLabel)
            } else {
                stringResource(R.string.presets_edit_title, kindLabel)
            },
            style = MaterialTheme.typography.titleSmall,
        )
        if (state.creating) {
            OutlinedTextField(
                value = state.editId,
                onValueChange = onId,
                label = { Text(stringResource(R.string.presets_id_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        OutlinedTextField(
            value = state.editName,
            onValueChange = onName,
            label = { Text(stringResource(R.string.presets_name)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.editDescription,
            onValueChange = onDescription,
            label = { Text(stringResource(R.string.presets_description)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.editDetail,
            onValueChange = onDetail,
            label = { Text(stringResource(R.string.presets_detail_label)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.presets_detail_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (state.conflict) {
            Text(
                stringResource(R.string.presets_conflict_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onSave, enabled = !state.busy) {
                Text(stringResource(R.string.presets_save))
            }
            TextButton(onClick = onCancel, enabled = !state.busy) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

@Composable
private fun kindLabel(kind: PresetKind): String = when (kind) {
    PresetKind.TELLER -> stringResource(R.string.presets_kind_teller)
    PresetKind.PLANNING -> stringResource(R.string.presets_kind_planning)
    PresetKind.EVENT -> stringResource(R.string.presets_kind_event)
    PresetKind.RULE -> stringResource(R.string.presets_kind_rule)
    PresetKind.ACTOR -> stringResource(R.string.presets_kind_actor)
    PresetKind.IMAGE -> stringResource(R.string.presets_kind_image)
}
