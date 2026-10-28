package com.denova.novara.feature.agents

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.denova.novara.R
import com.denova.novara.core.agents.AgentDefinitions
import com.denova.novara.core.agents.BuiltinAgent
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.network.AgentEngineDescriptor
import com.denova.novara.core.network.CustomAgentDefinition
import com.denova.novara.core.network.DenovaClient
import kotlinx.coroutines.launch

/**
 * Agents（P4-C / DEF-001·002），对齐基线 `features/agents/AgentsView.tsx` 的定义管理形态：
 * 六个内置 Agent（只读）+ 自定义定义（用户层，可按类型分组新建 / 编辑 / 启停 / 删除），
 * 顶部展示运行引擎状态并可重新检查；选中 Agent 后可「进入会话」（自定义定义会绑定
 * `custom_agent_id` 新建会话）。
 */
@Composable
fun AgentsScreen(
    client: DenovaClient?,
    onOpenDrawer: () -> Unit,
    /** 会话创建成功后进入写作 Agent 页（带会话身份）。 */
    onOpenSession: (sessionId: String) -> Unit,
) {
    val application = LocalContext.current.applicationContext as Application
    val factory: ViewModelProvider.Factory = remember(application) {
        viewModelFactory { initializer { AgentsViewModel(application) } }
    }
    val viewModel: AgentsViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(client) { client?.let { viewModel.bindClient(it) } }

    var deleteTarget by remember { mutableStateOf<CustomAgentDefinition?>(null) }
    val grouped = state.grouped

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) { Text("☰") }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dest_agents), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.agents_layer_user) + " · " + state.customAgents.size +
                        " · rev " + state.revision.take(8).ifBlank { "-" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.loading) {
                Text("…", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { viewModel.load() }) { Text("⟳") }
        }
        HorizontalDivider()

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "engines") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.agents_engine), style = MaterialTheme.typography.labelLarge)
                    if (state.engines.isEmpty() && !state.enginesLoading) {
                        Text(
                            stringResource(R.string.agents_engine_unchecked),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    state.engines.forEach { engine ->
                        EngineRow(engine = engine, onCheck = { viewModel.checkEngine(engine.id) })
                    }
                }
            }

            item(key = "create") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.beginCreate("ide") }, enabled = !state.busy) {
                        Text(stringResource(R.string.agents_new))
                    }
                    Text(
                        stringResource(R.string.agents_create_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            grouped.forEach { (builtin, customs) ->
                item(key = "group-${builtin.key}") {
                    Text(
                        groupLabel(builtin) + " · " + titleOf(builtin),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item(key = "builtin-${builtin.key}") {
                    BuiltinRow(
                        builtin = builtin,
                        selected = state.selectedKey == "builtin:${builtin.key}",
                        onSelect = { viewModel.select("builtin:${builtin.key}") },
                    )
                }
                items(customs) { agent ->
                    CustomRow(
                        agent = agent,
                        selected = state.selectedKey == "custom:${agent.id}",
                        onSelect = { viewModel.select("custom:${agent.id}") },
                        onToggle = { enabled -> viewModel.toggleEnabled(agent, enabled) },
                    )
                }
            }

            if (state.editing) {
                item(key = "editor") {
                    AgentEditorCard(
                        state = state,
                        onKind = viewModel::editKind,
                        onName = viewModel::editName,
                        onDescription = viewModel::editDescription,
                        onInstructions = viewModel::editInstructions,
                        onEnabled = viewModel::editEnabled,
                        onSave = { viewModel.save() },
                        onCancel = { viewModel.cancelEdit() },
                    )
                }
            } else {
                state.selectedAgent?.let { agent ->
                    item(key = "custom-detail") {
                        CustomDetailCard(
                            agent = agent,
                            busy = state.busy,
                            onEnter = {
                                scope.launch { viewModel.openSession()?.let(onOpenSession) }
                            },
                            onEdit = { viewModel.beginEdit() },
                            onDelete = { deleteTarget = agent },
                        )
                    }
                }
                state.selectedBuiltinKey?.let { key ->
                    val builtin = AgentDefinitions.builtinAgents.firstOrNull { it.key == key }
                    if (builtin != null) {
                        item(key = "builtin-detail") {
                            BuiltinDetailCard(
                                builtin = builtin,
                                busy = state.busy,
                                onEnter = {
                                    scope.launch { viewModel.openSession()?.let(onOpenSession) }
                                },
                                onCreateCustom = { viewModel.beginCreate(builtin.key) },
                            )
                        }
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

    deleteTarget?.let { agent ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.agents_delete)) },
            text = { Text(stringResource(R.string.agents_delete_confirm, agent.name.ifBlank { agent.id })) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    viewModel.delete(agent.id)
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
private fun EngineRow(engine: AgentEngineDescriptor, onCheck: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(engine.id, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(AgentDefinitions.engineStatusRes(engine.status)) +
                    if (engine.reasonKey.isNotBlank()) " · ${engine.reasonKey}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (engine.status == "ready") {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onCheck) { Text(stringResource(R.string.agents_check)) }
    }
}

@Composable
private fun BuiltinRow(builtin: BuiltinAgent, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                titleOf(builtin),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else null,
            )
            Text(
                stringResource(builtin.subtitleRes) + " · " + AgentDefinitions.contractFor(builtin.key),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onSelect) { Text(stringResource(R.string.presets_open)) }
    }
}

@Composable
private fun CustomRow(
    agent: CustomAgentDefinition,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                agent.name.ifBlank { agent.id },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                agent.id + " · " + agent.contract,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onSelect) { Text(stringResource(R.string.presets_open)) }
        Switch(checked = agent.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun BuiltinDetailCard(
    builtin: BuiltinAgent,
    busy: Boolean,
    onEnter: () -> Unit,
    onCreateCustom: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Text(titleOf(builtin), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(builtin.subtitleRes),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            AgentDefinitions.contractFor(builtin.key) + " · " + stringResource(R.string.agents_locked),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onEnter, enabled = !busy) { Text(stringResource(R.string.agents_enter)) }
            if (builtin.customizable) {
                TextButton(onClick = onCreateCustom, enabled = !busy) {
                    Text(stringResource(R.string.agents_new))
                }
            }
        }
    }
}

@Composable
private fun CustomDetailCard(
    agent: CustomAgentDefinition,
    busy: Boolean,
    onEnter: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Text(agent.name.ifBlank { agent.id }, style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(
                R.string.agents_detail_meta,
                agent.contract,
                AgentDefinitions.runtimeKindOf(agent),
                "—",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(agent.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        if (agent.description.isNotBlank()) {
            Text(agent.description, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            stringResource(R.string.agents_enabled) + ": " + if (agent.enabled) "✓" else "—",
            style = MaterialTheme.typography.labelSmall,
        )
        if (agent.instructions.isNotBlank()) {
            Text(
                agent.instructions,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onEnter, enabled = !busy) { Text(stringResource(R.string.agents_enter)) }
            TextButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.agents_edit)) }
            TextButton(onClick = onDelete, enabled = !busy) { Text(stringResource(R.string.agents_delete)) }
        }
    }
}

@Composable
private fun AgentEditorCard(
    state: AgentsUiState,
    onKind: (String) -> Unit,
    onName: (String) -> Unit,
    onDescription: (String) -> Unit,
    onInstructions: (String) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(
            if (state.creating) stringResource(R.string.agents_create_title)
            else stringResource(R.string.agents_edit_title),
            style = MaterialTheme.typography.titleSmall,
        )
        if (state.creating) {
            Text(stringResource(R.string.agents_contract), style = MaterialTheme.typography.labelMedium)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AgentDefinitions.customizableKinds.forEach { kind ->
                    FilterChip(
                        selected = state.editKind == kind,
                        onClick = { onKind(kind) },
                        label = { Text(kind) },
                    )
                }
            }
        } else {
            Text(
                stringResource(R.string.agents_contract) + ": " + state.editBase?.contract.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        OutlinedTextField(
            value = state.editName,
            onValueChange = onName,
            label = { Text(stringResource(R.string.agents_name)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.editDescription,
            onValueChange = onDescription,
            label = { Text(stringResource(R.string.agents_description)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.editInstructions,
            onValueChange = onInstructions,
            label = { Text(stringResource(R.string.agents_instructions)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 300.dp),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.editEnabled, onCheckedChange = onEnabled, enabled = !state.creating)
            Text(stringResource(R.string.agents_enabled), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onSave, enabled = !state.busy) { Text(stringResource(R.string.presets_save)) }
            TextButton(onClick = onCancel, enabled = !state.busy) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

@Composable
private fun titleOf(builtin: BuiltinAgent): String = stringResource(builtin.titleRes)

@Composable
private fun groupLabel(builtin: BuiltinAgent): String = when (builtin.group) {
    com.denova.novara.core.agents.AgentGroup.GENERAL -> stringResource(R.string.agents_group_general)
    com.denova.novara.core.agents.AgentGroup.WRITING -> stringResource(R.string.agents_group_writing)
    com.denova.novara.core.agents.AgentGroup.INTERACTIVE -> stringResource(R.string.agents_group_interactive)
    com.denova.novara.core.agents.AgentGroup.UTILITY -> stringResource(R.string.agents_group_utility)
    com.denova.novara.core.agents.AgentGroup.VERSION -> stringResource(R.string.agents_group_version)
}
