package com.denova.novara.feature.versions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.feature.shell.VersionsContent

/**
 * 版本管理一级页面（P4-D / NAV-001）。
 *
 * 版本能力（状态 / 列表 / 创建 / diff / 两步恢复）原本只在右抽屉的「版本」段里，NAV-001 的
 * 「依次打开全部目的地」要求它也有独立入口，所以这里复用同一份 [VersionsContent] 与
 * `VersionsViewModel`，不复制第二套实现。
 */
@Composable
fun VersionsScreen(
    client: DenovaClient?,
    projectId: String,
    onOpenDrawer: () -> Unit,
    viewModel: VersionsViewModel,
) {
    LaunchedEffect(client) { client?.let { viewModel.bindClient(it) } }
    LaunchedEffect(projectId, client) {
        if (client != null && projectId.isNotBlank()) viewModel.load(projectId)
    }
    val state by viewModel.state.collectAsState()

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) { Text("☰") }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.versions_page_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    projectId.ifBlank { stringResource(R.string.versions_page_no_project) },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(
                onClick = { if (projectId.isNotBlank()) viewModel.load(projectId, force = true) },
                enabled = projectId.isNotBlank(),
            ) { Text(stringResource(R.string.refresh)) }
        }
        HorizontalDivider()
        VersionsContent(
            state = state,
            onRefresh = { if (projectId.isNotBlank()) viewModel.load(projectId, force = true) },
            onCreateTextChange = viewModel::setCreateText,
            onCreate = viewModel::createVersion,
            onOpenVersion = viewModel::openVersion,
            onCloseVersion = viewModel::closeVersion,
            onTogglePath = viewModel::togglePath,
            onToggleAll = viewModel::toggleAllPaths,
            onLoadPlan = viewModel::loadPlan,
            onConfirmRestore = viewModel::confirmRestore,
            onDismissAction = viewModel::clearActionResult,
        )
    }
}
