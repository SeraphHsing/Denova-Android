package com.denova.novara.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.denova.novara.R
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.settings.SettingsEditor
import com.denova.novara.core.settings.SettingsFieldKind
import com.denova.novara.core.settings.SettingsSchema
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/**
 * 设置页（P4-D / SET-001…014）。
 *
 * 结构对齐基线 `SettingsView`：顶部 section 选择 + 当前 section 的字段表。
 * 每个字段显示「显式值」（存在 `user` 层）与「继承值」（`effective`）两种情况，
 * 清空输入框即回到继承；保存时只提交改动字段（merge patch），409 保留草稿。
 */
@Composable
fun SettingsScreen(
    client: DenovaClient?,
    onOpenDrawer: () -> Unit,
) {
    val context = LocalContext.current
    val factory = remember(context) {
        viewModelFactory {
            initializer { SettingsPageViewModel(context.applicationContext as android.app.Application) }
        }
    }
    val viewModel: SettingsPageViewModel = viewModel(factory = factory)
    LaunchedEffect(client) { client?.let { viewModel.bindClient(it) } }
    val state by viewModel.state.collectAsState()
    SettingsContent(state = state, viewModel = viewModel, onOpenDrawer = onOpenDrawer)
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    viewModel: SettingsPageViewModel,
    onOpenDrawer: () -> Unit,
) {
    val section = state.section()
    val listState = rememberLazyListState()
    // 切换 section 后回到顶部，避免停在上一段的长 JSON 中间。
    LaunchedEffect(section.id) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) { Text("☰") }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.settings_subtitle, SettingsSchema.SECTIONS.size),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (state.loading || state.saving) {
                CircularProgressIndicator(Modifier.padding(8.dp))
            }
            TextButton(onClick = { viewModel.save() }, enabled = state.dirty && !state.saving) {
                Text(stringResource(R.string.save))
            }
            TextButton(onClick = { viewModel.reload() }, enabled = !state.saving) {
                Text(stringResource(R.string.settings_reload))
            }
        }
        state.error?.let {
            Text(
                it.resolve(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        state.notice?.let {
            Text(
                it.resolve(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (state.conflict) {
            Text(
                stringResource(R.string.settings_conflict_hint),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(SettingsSchema.SECTIONS, key = { it.id }) { item ->
                FilterChip(
                    selected = item.id == section.id,
                    onClick = { viewModel.selectSection(item.id) },
                    label = { Text(stringResource(item.titleRes)) },
                )
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))

        if (state.snapshot == null && state.loading) {
            Text(
                stringResource(R.string.settings_loading),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }

        // 单列 LazyColumn 承载全部字段：任意高度（横屏 / 分屏）都能滚到每一项。
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item(key = "section-${section.id}-header") {
                Text(
                    stringResource(R.string.settings_section_hint),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            items(section.fields, key = { "${section.id}:${it.path}" }) { field ->
                SettingsFieldRow(
                    spec = field,
                    state = state,
                    onEdit = { viewModel.edit(field.path, it) },
                    onRevert = { viewModel.revertField(field.path) },
                )
            }
            item(key = "section-${section.id}-footer") {
                Text(
                    stringResource(R.string.settings_layering_hint),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsFieldRow(
    spec: com.denova.novara.core.settings.SettingsFieldSpec,
    state: SettingsUiState,
    onEdit: (String) -> Unit,
    onRevert: () -> Unit,
) {
    val error = state.fieldErrors[spec.path]
    val explicit = state.isExplicit(spec.path)
    val inherited = state.inheritedText(spec.path)
    val draft = state.fieldText(spec.path)

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(spec.labelRes), style = MaterialTheme.typography.bodyMedium)
                Text(
                    spec.path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (spec.writable && state.edits.containsKey(spec.path)) {
                TextButton(onClick = onRevert) { Text(stringResource(R.string.settings_revert)) }
            }
        }
        when (spec.kind) {
            SettingsFieldKind.BOOL -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = draft.isEmpty(),
                    onClick = { onEdit("") },
                    label = { Text(stringResource(R.string.settings_inherit)) },
                )
                FilterChip(
                    selected = draft == "true",
                    onClick = { onEdit("true") },
                    label = { Text(stringResource(R.string.settings_on)) },
                )
                FilterChip(
                    selected = draft == "false",
                    onClick = { onEdit("false") },
                    label = { Text(stringResource(R.string.settings_off)) },
                )
            }

            SettingsFieldKind.ENUM -> LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(
                        selected = draft.isEmpty(),
                        onClick = { onEdit("") },
                        label = { Text(stringResource(R.string.settings_inherit)) },
                    )
                }
                items(spec.options) { option ->
                    FilterChip(
                        selected = draft == option,
                        onClick = { onEdit(option) },
                        label = { Text(option) },
                    )
                }
            }

            SettingsFieldKind.JSON -> OutlinedTextField(
                value = draft,
                onValueChange = onEdit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text(inherited.ifBlank { stringResource(R.string.settings_empty) }) },
                isError = error != null,
                minLines = 4,
                maxLines = 14,
            )

            SettingsFieldKind.READ_ONLY -> Text(
                state.readOnlyText(spec.path).ifBlank { stringResource(R.string.settings_empty) },
                style = MaterialTheme.typography.bodySmall,
            )

            else -> OutlinedTextField(
                value = draft,
                onValueChange = onEdit,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = error != null,
                visualTransformation = if (spec.kind == SettingsFieldKind.SECRET) {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                keyboardOptions = if (spec.kind == SettingsFieldKind.INTEGER) {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                } else {
                    KeyboardOptions.Default
                },
                placeholder = { Text(inherited.ifBlank { stringResource(R.string.settings_empty) }) },
                supportingText = {
                    if (error != null) {
                        Text(error.resolve())
                    } else if (spec.kind == SettingsFieldKind.SECRET && explicit) {
                        Text(stringResource(R.string.settings_secret_hint))
                    }
                },
            )
        }
        if (!spec.writable) {
            Text(
                stringResource(R.string.settings_read_only),
                style = MaterialTheme.typography.labelSmall,
            )
        } else if (spec.kind != SettingsFieldKind.READ_ONLY) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        R.string.settings_inherited_value,
                        inherited.ifBlank { stringResource(R.string.settings_empty) },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (error != null && spec.kind == SettingsFieldKind.JSON) {
            Text(
                error.resolve(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
