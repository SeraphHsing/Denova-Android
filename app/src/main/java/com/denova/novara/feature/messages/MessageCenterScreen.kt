package com.denova.novara.feature.messages

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
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.messages.MessageCenterLogic
import com.denova.novara.core.messages.MessageFilter
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.ProductMessage

/**
 * 消息中心（P4-D / MSG-001…003）。
 *
 * 窄屏按基线做「列表 / 详情互斥」：打开列表不置已读，进入详情且未读才写 `read`；
 * 返回列表保留选中 id（焦点与滚动位置可恢复）。有 `task_id` 的消息提供「打开自动化」。
 */
@Composable
fun MessageCenterScreen(
    client: DenovaClient?,
    onBack: () -> Unit,
    viewModel: MessageCenterViewModel,
    onOpenAutomation: (String) -> Unit,
) {
    LaunchedEffect(client) { client?.let { viewModel.bindClient(it) } }
    val state by viewModel.state.collectAsState()
    MessageCenterContent(
        state = state,
        viewModel = viewModel,
        onBack = onBack,
        onOpenAutomation = onOpenAutomation,
    )
}

@Composable
private fun MessageCenterContent(
    state: MessageCenterUiState,
    viewModel: MessageCenterViewModel,
    onBack: () -> Unit,
    onOpenAutomation: (String) -> Unit,
) {
    val active = state.active
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { if (active != null) viewModel.closeDetail() else onBack() },
            ) { Text("←") }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.messages_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.messages_subtitle, state.visible.size, state.unread),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (state.loading || state.markingAll) CircularProgressIndicator(Modifier.padding(6.dp))
            if (active == null) {
                TextButton(
                    onClick = { viewModel.markAllRead() },
                    enabled = state.unread > 0 && !state.markingAll,
                ) { Text(stringResource(R.string.messages_mark_all_read)) }
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

        if (active != null) {
            MessageDetail(active, onOpenAutomation)
            return@Column
        }

        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(MessageFilter.entries.toList()) { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    label = { Text(filterLabel(filter)) },
                )
            }
        }
        HorizontalDivider()

        if (state.visible.isEmpty() && !state.loading) {
            Text(
                if (state.items.isEmpty()) {
                    stringResource(R.string.messages_empty)
                } else {
                    stringResource(R.string.messages_empty_filter)
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(state.visible, key = { it.id }) { message ->
                MessageRow(message) { viewModel.open(message.id) }
            }
        }
    }
}

@Composable
private fun filterLabel(filter: MessageFilter): String = stringResource(
    when (filter) {
        MessageFilter.ALL -> R.string.messages_filter_all
        MessageFilter.ACTION -> R.string.messages_filter_action
        MessageFilter.AUTOMATION -> R.string.messages_filter_automation
        MessageFilter.PRODUCT -> R.string.messages_filter_product
    },
)

@Composable
private fun MessageRow(message: ProductMessage, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                MessageCenterLogic.typeLabel(message).resolve(),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                message.publishedAt.replace('T', ' ').take(16),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (message.unread) {
                Text("●", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                message.title.ifBlank { message.id },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = if (message.unread) 4.dp else 0.dp).weight(1f),
            )
            TextButton(onClick = onOpen) { Text(stringResource(R.string.messages_open)) }
        }
        if (message.summary.isNotBlank()) {
            Text(message.summary, style = MaterialTheme.typography.labelSmall)
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun MessageDetail(message: ProductMessage, onOpenAutomation: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(message.title.ifBlank { message.id }, style = MaterialTheme.typography.titleMedium)
        Text(
            MessageCenterLogic.typeLabel(message).resolve() + " · " +
                message.publishedAt.replace('T', ' ').take(16) +
                if (message.unread) "" else " · " + stringResource(R.string.messages_read_state),
            style = MaterialTheme.typography.labelSmall,
        )
        if (message.summary.isNotBlank()) {
            Text(message.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        Text(
            MessageCenterLogic.bodyText(message),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (message.canOpenAutomation) {
            TextButton(
                onClick = { onOpenAutomation(message.projectId) },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(
                    stringResource(
                        if (message.actionRequired) {
                            R.string.messages_open_automation_action
                        } else {
                            R.string.messages_open_automation
                        },
                    ),
                )
            }
        }
        if (message.projectId.isNotBlank() || message.runId.isNotBlank() || message.inboxId.isNotBlank()) {
            Text(
                buildString {
                    if (message.projectId.isNotBlank()) append("project ").append(message.projectId)
                    if (message.runId.isNotBlank()) append("  run ").append(message.runId)
                    if (message.inboxId.isNotBlank()) append("  inbox ").append(message.inboxId)
                }.trim(),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
