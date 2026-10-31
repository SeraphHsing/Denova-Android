package com.denova.novara.feature.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.denova.novara.R
import com.denova.novara.ui.theme.NovaStroke
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.market.MarketLogic
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.Installation
import com.denova.novara.core.network.MarketEntry
import androidx.compose.material.icons.Icons
import com.denova.novara.ui.components.NovaTopBar
import com.denova.novara.ui.theme.NovaSize
import com.denova.novara.ui.theme.NovaSpacing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Menu
import com.denova.novara.ui.components.NovaTextField

/**
 * 资源市场与扩展页（P4-D / MKT-001…005）。
 *
 * 三个标签：目录（搜索 / 分组 / 详情 / 刷新）、已安装（详情 / 备份 / 更新策略 / 检查更新）、
 * 运行时（程序更新状态、插件页目录、运行时快照）。
 * 安装、权限、配置写入、刷新目录、检查更新、更新策略都是**本机限定**：远程调用会把服务端的
 * `403 LOCAL_HOST_ONLY` 如实呈现成「需在运行 Denova 的本机操作」，而不是假装成功。
 */
@Composable
fun MarketScreen(
    client: DenovaClient?,
    language: String,
    projectId: String,
    onOpenDrawer: () -> Unit,
    viewModel: MarketViewModel,
) {
    LaunchedEffect(client, language, projectId) {
        client?.let { viewModel.bindClient(it, language, projectId) }
        viewModel.setLanguage(language)
    }
    val state by viewModel.state.collectAsState()
    MarketContent(state = state, viewModel = viewModel, language = language, onOpenDrawer = onOpenDrawer)
}

@Composable
private fun MarketContent(
    state: MarketUiState,
    viewModel: MarketViewModel,
    language: String,
    onOpenDrawer: () -> Unit,
) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        NovaTopBar(
            title = stringResource(R.string.market_title),
            subtitle = stringResource(
                R.string.market_subtitle,
                state.entries.size,
                state.installations.size,
            ),
            navigationIcon = Icons.Filled.Menu,
            navigationContentDescription = stringResource(R.string.drawer_open),
            onNavigationClick = onOpenDrawer,
            actions = {
                if (state.loading || state.busy) {
                    CircularProgressIndicator(
                        Modifier
                            .size(NovaSize.iconAction)
                            .padding(NovaSpacing.xxs),
                        strokeWidth = NovaStroke.thick,
                    )
                }
            },
        )
        state.error?.let {
            Text(
                it.resolve(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = NovaSpacing.lg, vertical = NovaSpacing.xxs),
            )
        }
        state.notice?.let {
            Text(
                it.resolve(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = NovaSpacing.lg, vertical = NovaSpacing.xxs),
            )
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.sm), horizontalArrangement = Arrangement.spacedBy(NovaSpacing.chip)) {
            TabChip(MarketTab.CATALOG, R.string.market_tab_catalog, state, viewModel)
            TabChip(MarketTab.INSTALLED, R.string.market_tab_installed, state, viewModel)
            TabChip(MarketTab.RUNTIME, R.string.market_tab_runtime, state, viewModel)
        }
        HorizontalDivider()

        when (state.tab) {
            MarketTab.CATALOG -> if (state.selectedEntry != null) {
                CatalogDetail(state, viewModel, language)
            } else {
                CatalogList(state, viewModel, language)
            }

            MarketTab.INSTALLED -> if (state.selectedInstallation != null) {
                InstalledDetail(state, viewModel)
            } else {
                InstalledList(state, viewModel)
            }

            MarketTab.RUNTIME -> RuntimePanel(state, viewModel)
        }
    }
}

@Composable
private fun TabChip(tab: MarketTab, label: Int, state: MarketUiState, viewModel: MarketViewModel) {
    FilterChip(
        selected = state.tab == tab,
        onClick = {
            viewModel.selectTab(tab)
            if (tab == MarketTab.RUNTIME && state.updateStatus == null) viewModel.loadRuntime()
        },
        label = { Text(stringResource(label)) },
    )
}

@Composable
private fun CatalogList(state: MarketUiState, viewModel: MarketViewModel, language: String) {
    NovaTextField(
        value = state.query,
        onValueChange = viewModel::setQuery,
        label = stringResource(R.string.market_search),
        modifier = Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.lg, vertical = NovaSpacing.xs),
        singleLine = true,
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(NovaSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (state.catalog?.stale == true) {
                stringResource(R.string.market_stale)
            } else {
                state.catalog?.registryUrl.orEmpty()
            },
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { viewModel.refreshCatalog() }, enabled = !state.busy) {
            Text(stringResource(R.string.market_refresh))
        }
    }
    if (state.entries.isEmpty()) {
        Text(
            stringResource(R.string.market_empty),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(NovaSpacing.lg),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        state.groups.forEach { (kind, entries) ->
            item(key = "group-$kind") {
                Text(
                    stringResource(R.string.market_group, kind, entries.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = NovaSpacing.lg, end = NovaSpacing.lg, top = NovaSpacing.md, bottom = NovaSpacing.xs),
                )
            }
            items(entries, key = { "$kind:${it.id}" }) { entry ->
                MarketEntryRow(entry, language) { viewModel.selectEntry(entry.id) }
            }
        }
    }
}

@Composable
private fun MarketEntryRow(entry: MarketEntry, language: String, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.lg, vertical = NovaSpacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    MarketLogic.localized(entry.name, language).ifBlank { entry.id },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    listOf(entry.format, entry.kinds.joinToString("/"), entry.author)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = onOpen) { Text(stringResource(R.string.market_open)) }
        }
        HorizontalDivider(Modifier.padding(top = NovaSpacing.sm))
    }
}

@Composable
private fun CatalogDetail(state: MarketUiState, viewModel: MarketViewModel, language: String) {
    val entry = state.selectedEntry ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NovaSpacing.lg)) {
        Text(
            MarketLogic.localized(entry.name, language).ifBlank { entry.id },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            listOf(entry.id, entry.format, MarketLogic.localized(entry.compatibility, language))
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
        )
        if (entry.tags.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(NovaSpacing.xs), modifier = Modifier.padding(vertical = NovaSpacing.chip)) {
                items(entry.tags) { tag ->
                    Text("#$tag", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            MarketLogic.localized(entry.description, language),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = NovaSpacing.sm),
        )
        val usage = MarketLogic.localized(entry.usage, language)
        if (usage.isNotBlank()) {
            Text(
                stringResource(R.string.market_usage) + ": " + usage,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = NovaSpacing.sm),
            )
        }
        if (entry.author.isNotBlank()) {
            Text(
                stringResource(R.string.market_author) + ": " + entry.author,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            stringResource(R.string.market_local_only),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = NovaSpacing.md),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NovaSpacing.chip)) {
            // 安装 / 预览属本机限定动作：远程客户端不提供入口（MKT-003 的「禁用」表现）。
            TextButton(onClick = {}, enabled = false) { Text(stringResource(R.string.market_install)) }
            TextButton(onClick = { viewModel.clearEntry() }) { Text(stringResource(R.string.back)) }
        }
    }
}

@Composable
private fun InstalledList(state: MarketUiState, viewModel: MarketViewModel) {
    if (state.installations.isEmpty()) {
        Text(
            stringResource(R.string.market_installed_empty),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(NovaSpacing.lg),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        MarketLogic.groupByProject(state.installations).forEach { (project, list) ->
            item(key = "project-$project") {
                Text(
                    stringResource(R.string.market_project, project.takeLast(8), list.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = NovaSpacing.lg, end = NovaSpacing.lg, top = NovaSpacing.md, bottom = NovaSpacing.xs),
                )
            }
            items(list, key = { it.installationId }) { installation ->
                InstallationRow(installation, viewModel)
            }
        }
    }
}

@Composable
private fun InstallationRow(installation: Installation, viewModel: MarketViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.lg, vertical = NovaSpacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(installation.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOf(
                        MarketLogic.updateModeLabel(installation.updateMode).resolve(),
                        MarketLogic.localStateLabel(installation.localState).resolve(),
                        MarketLogic.trackingLabel(installation).resolve(),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = { viewModel.selectInstallation(installation.installationId) }) {
                Text(stringResource(R.string.market_open))
            }
        }
        HorizontalDivider(Modifier.padding(top = NovaSpacing.sm))
    }
}

@Composable
private fun InstalledDetail(state: MarketUiState, viewModel: MarketViewModel) {
    val installation = state.selectedInstallation ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NovaSpacing.lg)) {
        Text(installation.label, style = MaterialTheme.typography.titleMedium)
        Text(
            listOf(installation.`package`.id, installation.installationId.take(8))
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            stringResource(R.string.market_installation_source) + ": " +
                listOf(installation.source.kind, installation.source.filename, installation.source.url)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = NovaSpacing.chip),
        )
        Text(
            stringResource(
                R.string.market_installation_state,
                MarketLogic.updateModeLabel(installation.updateMode).resolve(),
                MarketLogic.localStateLabel(installation.localState).resolve(),
                installation.remoteState.ifBlank { "-" },
            ),
            style = MaterialTheme.typography.labelSmall,
        )
        if (installation.reviewedSource.isNotBlank()) {
            Text(
                stringResource(R.string.market_reviewed_source, installation.reviewedSource.take(19)),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Text(
            stringResource(R.string.market_update_mode),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = NovaSpacing.md),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(NovaSpacing.chip)) {
            items(MarketLogic.UPDATE_MODES) { mode ->
                FilterChip(
                    selected = installation.updateMode == mode,
                    onClick = { viewModel.setPolicy(installation.installationId, mode) },
                    enabled = !state.busy,
                    label = { Text(MarketLogic.updateModeLabel(mode).resolve()) },
                )
            }
        }

        Text(
            stringResource(R.string.market_backups, state.backups.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = NovaSpacing.md),
        )
        if (state.backups.isEmpty()) {
            Text(stringResource(R.string.market_backups_empty), style = MaterialTheme.typography.labelSmall)
        } else {
            state.backups.forEach { backup ->
                Text(
                    "${backup.createdAt.take(19)} · ${backup.state} · ${backup.files}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(NovaSpacing.chip), modifier = Modifier.padding(top = NovaSpacing.md)) {
            TextButton(
                onClick = { viewModel.checkInstallation(installation.installationId) },
                enabled = !state.busy,
            ) { Text(stringResource(R.string.market_check_update)) }
            TextButton(onClick = { viewModel.clearInstallation() }) { Text(stringResource(R.string.back)) }
        }
        state.hostOnly?.let {
            Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RuntimePanel(state: MarketUiState, viewModel: MarketViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NovaSpacing.lg)) {
        Text(stringResource(R.string.market_program_update), style = MaterialTheme.typography.titleSmall)
        val status = state.updateStatus
        val check = state.updateCheck
        Text(
            stringResource(
                R.string.market_program_versions,
                status?.currentVersion.orEmpty().ifBlank { "-" },
                check?.latestVersion.orEmpty().ifBlank { "-" },
            ),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            if (check?.updateAvailable == true) {
                stringResource(R.string.market_program_available)
            } else {
                stringResource(R.string.market_program_current)
            },
            style = MaterialTheme.typography.labelSmall,
        )
        if (check?.releaseUrl?.isNotBlank() == true) {
            Text(check.releaseUrl, style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = { viewModel.loadRuntime() }, enabled = !state.loading) {
            Text(stringResource(R.string.market_reload))
        }

        Text(
            stringResource(R.string.market_plugin_pages, state.pluginPages.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = NovaSpacing.md),
        )
        if (state.pluginPages.isEmpty()) {
            Text(stringResource(R.string.market_plugin_pages_empty), style = MaterialTheme.typography.labelSmall)
        } else {
            state.pluginPages.forEach { page ->
                Text(
                    "${page.pluginId}/${page.id} · agent=${page.agent}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Text(
            stringResource(R.string.market_runtimes, state.runtimes.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = NovaSpacing.md),
        )
        if (state.runtimes.isEmpty()) {
            Text(stringResource(R.string.market_runtimes_empty), style = MaterialTheme.typography.labelSmall)
        } else {
            state.runtimes.forEach { runtime ->
                Text(viewModel.runtimeLabel(runtime), style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            stringResource(R.string.market_plugin_page_note),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = NovaSpacing.md),
        )

        // 项目级扩展配置：基线里少数**远程可写**的扩展设置（禁用插件 + 模型映射）。
        Text(
            stringResource(R.string.market_project_extensions, state.projectId.takeLast(8).ifBlank { "-" }),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = NovaSpacing.lg),
        )
        val config = state.extensionConfig
        if (config == null) {
            Text(stringResource(R.string.market_extensions_empty), style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                NovaTextField(
                    value = state.projectInput,
                    onValueChange = viewModel::setProjectInput,
                    label = stringResource(R.string.market_project_id),
                    modifier = Modifier.weight(1f).padding(vertical = NovaSpacing.xs),
                    singleLine = true,
                )
                TextButton(onClick = { viewModel.useProjectInput() }) {
                    Text(stringResource(R.string.market_load))
                }
            }
        } else {
            Text(
                stringResource(R.string.market_extension_revision, config.revision.take(12)),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(stringResource(R.string.market_disabled_plugins), style = MaterialTheme.typography.labelSmall)
            if (state.disabledDraft.isEmpty()) {
                Text(stringResource(R.string.market_none), style = MaterialTheme.typography.labelSmall)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(NovaSpacing.chip)) {
                    items(state.disabledDraft) { id ->
                        FilterChip(
                            selected = true,
                            onClick = { viewModel.removeDisabled(id) },
                            label = { Text("$id ✕") },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                NovaTextField(
                    value = state.newDisabled,
                    onValueChange = viewModel::setNewDisabled,
                    label = stringResource(R.string.market_add_disabled),
                    modifier = Modifier.weight(1f).padding(vertical = NovaSpacing.xs),
                    singleLine = true,
                )
                TextButton(onClick = { viewModel.addDisabled() }) { Text(stringResource(R.string.market_add)) }
            }
            NovaTextField(
                value = state.modelsText,
                onValueChange = viewModel::setModelsText,
                label = stringResource(R.string.market_models_json),
                modifier = Modifier.fillMaxWidth().padding(vertical = NovaSpacing.xs),
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 2,
                maxLines = 6,
            )
            TextButton(onClick = { viewModel.saveProjectExtensions() }, enabled = !state.busy) {
                Text(stringResource(R.string.save))
            }
        }
    }
}
