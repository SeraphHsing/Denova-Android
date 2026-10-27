package com.denova.novara.feature.workbench

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.denova.novara.core.network.ProjectAgentGateway
import com.denova.novara.core.workbench.WorkbenchGroup
import com.denova.novara.core.workbench.WorkbenchTab
import com.denova.novara.core.workbench.WorkbenchTabKind
import com.denova.novara.core.workbench.WorkbenchTabs
import com.denova.novara.feature.agent.AgentScreen
import com.denova.novara.feature.agent.AgentViewModel

/**
 * 工作台页面（P4-B）：页签条 + 主副会话 + 会话内容。
 *
 * 项目 / 会话树在右侧「项目」抽屉里（与基线手机布局一致：导航抽屉 + 项目抽屉），
 * 页面本身只承载当前区的页签与内容，因此手机上一屏只有一个会话可见。
 */
@Composable
fun WorkbenchScreen(
    viewModel: WorkbenchViewModel,
    client: DenovaClient?,
    onOpenDrawer: () -> Unit,
    onOpenRightDrawer: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val activeTabId = when (state.activeGroup) {
        WorkbenchGroup.PRIMARY -> state.activeTabPrimary
        WorkbenchGroup.SECONDARY -> state.activeTabSecondary
    }
    val tabs = WorkbenchTabs.ofGroup(state.tabs, state.activeGroup)
    val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: tabs.firstOrNull()
    val titles = remember(state.projects) { viewModel.sessionTitles() }
    val primaryCount = WorkbenchTabs.ofGroup(state.tabs, WorkbenchGroup.PRIMARY).size
    val secondaryCount = WorkbenchTabs.ofGroup(state.tabs, WorkbenchGroup.SECONDARY).size

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpenDrawer) { Text("☰") }
            Text(
                stringResource(R.string.workbench_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            // 主区 / 副区全屏切换（WB-003）：两区各自保留页签与对话状态。
            GroupChip(
                label = stringResource(R.string.workbench_group_primary) + count(primaryCount),
                selected = state.activeGroup == WorkbenchGroup.PRIMARY,
                onClick = { viewModel.switchGroup(WorkbenchGroup.PRIMARY) },
            )
            GroupChip(
                label = stringResource(R.string.workbench_group_secondary) + count(secondaryCount),
                selected = state.activeGroup == WorkbenchGroup.SECONDARY,
                onClick = { viewModel.switchGroup(WorkbenchGroup.SECONDARY) },
            )
            Box(Modifier.weight(1f))
            TextButton(onClick = onOpenRightDrawer) { Text("»") }
        }

        TabStrip(
            tabs = tabs,
            activeTabId = activeTab?.id,
            titles = titles,
            onActivate = viewModel::activateTab,
            onClose = viewModel::closeTab,
            onMoveLeft = { viewModel.moveTabWithinGroup(it, -1) },
            onMoveRight = { viewModel.moveTabWithinGroup(it, 1) },
            onSendToOtherGroup = viewModel::moveTabToOtherGroup,
            onNewSession = {
                val projectId = state.selectedProjectId
                if (projectId.isNotBlank()) viewModel.createSession(projectId)
            },
            canCreateSession = state.selectedProjectId.isNotBlank() && !state.busy,
        )
        HorizontalDivider()

        state.notice?.let { notice ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    notice.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::clearNotice) { Text(stringResource(R.string.common_close)) }
            }
        }

        if (activeTab == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.workbench_empty_tab),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            WorkbenchTabHost(tab = activeTab, client = client, onOpenDrawer = onOpenDrawer)
        }
    }
}

private fun count(value: Int): String = if (value > 0) " ($value)" else ""

@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.padding(end = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun TabStrip(
    tabs: List<WorkbenchTab>,
    activeTabId: String?,
    titles: Map<String, String>,
    onActivate: (String) -> Unit,
    onClose: (String) -> Unit,
    onMoveLeft: (String) -> Unit,
    onMoveRight: (String) -> Unit,
    onSendToOtherGroup: (String) -> Unit,
    onNewSession: () -> Unit,
    canCreateSession: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            TabItem(
                tab = tab,
                title = WorkbenchTabs.titleOf(tab, titles).ifBlank {
                    stringResource(R.string.workbench_session_untitled)
                },
                active = tab.id == activeTabId,
                onActivate = { onActivate(tab.id) },
                onClose = { onClose(tab.id) },
                onMoveLeft = { onMoveLeft(tab.id) },
                onMoveRight = { onMoveRight(tab.id) },
                onSendToOtherGroup = { onSendToOtherGroup(tab.id) },
            )
        }
        var addMenu by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { addMenu = true }) { Text("＋") }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workbench_session_new)) },
                    enabled = canCreateSession,
                    onClick = {
                        addMenu = false
                        onNewSession()
                    },
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: WorkbenchTab,
    title: String,
    active: Boolean,
    onActivate: () -> Unit,
    onClose: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onSendToOtherGroup: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .background(
                if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onActivate)
            .padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 4.dp),
        )
        TextButton(onClick = { menu = true }, modifier = Modifier.padding(0.dp)) { Text("⋮") }
        TextButton(onClick = onClose, modifier = Modifier.padding(0.dp)) { Text("×") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val other = if (tab.group == WorkbenchGroup.PRIMARY) {
                stringResource(R.string.workbench_tab_to_secondary)
            } else {
                stringResource(R.string.workbench_tab_to_primary)
            }
            DropdownMenuItem(text = { Text(other) }, onClick = { menu = false; onSendToOtherGroup() })
            DropdownMenuItem(text = { Text(stringResource(R.string.workbench_tab_move_left)) }, onClick = { menu = false; onMoveLeft() })
            DropdownMenuItem(text = { Text(stringResource(R.string.workbench_tab_move_right)) }, onClick = { menu = false; onMoveRight() })
            DropdownMenuItem(text = { Text(stringResource(R.string.workbench_tab_close)) }, onClick = { menu = false; onClose() })
        }
    }
}

/**
 * 一个页签的内容宿主。
 *
 * Agent 页签按「页签身份」创建独立 [AgentViewModel]：不同会话互不覆盖状态（WB-003 的
 * 「不串会话流或队列」）。通道绑定在渲染前完成，再交给 [AgentScreen] 内嵌渲染。
 */
@Composable
private fun WorkbenchTabHost(tab: WorkbenchTab, client: DenovaClient?, onOpenDrawer: () -> Unit) {
    when (val kind = tab.kind) {
        is WorkbenchTabKind.Agent -> {
            val application = LocalContext.current.applicationContext as Application
            val factory: ViewModelProvider.Factory = remember(application) {
                viewModelFactory { initializer { AgentViewModel(application) } }
            }
            val agentViewModel: AgentViewModel = viewModel(key = "workbench-agent-${tab.id}", factory = factory)
            LaunchedEffect(tab.id, tab.projectId, kind.sessionId, client) {
                if (client == null || kind.sessionId.isBlank()) return@LaunchedEffect
                // 切走主区再切回来会重新进入组合：已经挂接同一会话时**不能**再 start()，
                // 否则会重置页面状态（正文、输入区草稿、滚动位置），与「各自草稿全屏状态保留」相反。
                val current = agentViewModel.state.value
                if (current.sessionId == kind.sessionId && current.projectId == tab.projectId) {
                    return@LaunchedEffect
                }
                agentViewModel.bindGateway(ProjectAgentGateway(client, tab.projectId))
                agentViewModel.start(kind.sessionId, tab.projectId, remember = false)
            }
            AgentScreen(
                viewModel = agentViewModel,
                onOpenDrawer = onOpenDrawer,
                onBack = {},
                projectId = tab.projectId,
                inline = true,
                fixedSessionId = kind.sessionId,
                autoStart = false,
            )
        }

        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.workbench_empty_tab),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
