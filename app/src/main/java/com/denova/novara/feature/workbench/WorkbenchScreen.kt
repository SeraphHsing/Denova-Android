package com.denova.novara.feature.workbench

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.denova.novara.feature.changes.ChangesViewModel
import com.denova.novara.feature.editor.EditorScreen
import com.denova.novara.feature.shell.ChangesContent
import com.denova.novara.feature.editor.EditorViewModel
import com.denova.novara.feature.project.ProjectDirectoryViewModel
import com.denova.novara.feature.terminal.TerminalScreen
import com.denova.novara.feature.terminal.TerminalViewModel

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
    // 关页签要释放该页签独占的 PTY。终端 ViewModel 挂在 Activity 的 ViewModelStore 上（旋转后仍在），
    // holder 同样是 Activity 级 ViewModel，因此「页签 → 终端会话」的对应关系不会因旋转丢失。
    val terminalHolder: TerminalTabHolder = viewModel(key = TERMINAL_HOLDER_KEY)
    val closeTab: (String) -> Unit = { tabId ->
        terminalHolder.release(tabId)
        viewModel.closeTab(tabId)
    }
    val terminalLabel = stringResource(R.string.terminal_title)

    // 历史面板（WB-006）：Activity 级 ViewModel，关掉再开保留上次项目与查询。
    var historyOpen by remember { mutableStateOf(false) }
    val application = LocalContext.current.applicationContext as Application
    val historyFactory: ViewModelProvider.Factory = remember(application) {
        viewModelFactory { initializer { WorkbenchHistoryViewModel(application) } }
    }
    val historyViewModel: WorkbenchHistoryViewModel = viewModel(key = HISTORY_KEY, factory = historyFactory)
    if (historyOpen) {
        LaunchedEffect(state.projects, client) {
            val c = client ?: return@LaunchedEffect
            historyViewModel.bindClient(c, state.projects, state.selectedProjectId)
        }
        WorkbenchHistorySheet(
            viewModel = historyViewModel,
            onDismiss = { historyOpen = false },
            onOpenSession = { projectId, sessionId -> viewModel.openSession(projectId, sessionId) },
        )
    }

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
            // 会话历史（WB-006）：跨项目检索、分页、行内重命名 / 导出 / 删除。
            TextButton(onClick = { historyOpen = true }) { Text(stringResource(R.string.workbench_history)) }
            TextButton(onClick = onOpenRightDrawer) { Text("»") }
        }

        TabStrip(
            tabs = tabs,
            activeTabId = activeTab?.id,
            titles = titles,
            onActivate = viewModel::activateTab,
            onClose = closeTab,
            onMoveLeft = { viewModel.moveTabWithinGroup(it, -1) },
            onMoveRight = { viewModel.moveTabWithinGroup(it, 1) },
            onSendToOtherGroup = viewModel::moveTabToOtherGroup,
            onNewSession = {
                val projectId = state.selectedProjectId
                if (projectId.isNotBlank()) viewModel.createSession(projectId)
            },
            onNewTerminal = {
                val projectId = state.selectedProjectId
                if (projectId.isNotBlank()) {
                    viewModel.openTerminal(projectId = projectId, label = terminalLabel)
                }
            },
            onNewFiles = {
                val projectId = state.selectedProjectId
                if (projectId.isNotBlank()) viewModel.openFiles(projectId)
            },
            onNewReview = {
                val projectId = state.selectedProjectId
                if (projectId.isNotBlank()) viewModel.openReview(projectId)
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
            WorkbenchTabHost(
                tab = activeTab,
                client = client,
                onOpenDrawer = onOpenDrawer,
                terminalHolder = terminalHolder,
                onSelectFile = viewModel::selectFile,
                onSelectReviewGroup = viewModel::selectReviewGroup,
            )
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
    onNewTerminal: () -> Unit,
    onNewFiles: () -> Unit,
    onNewReview: () -> Unit,
    canCreateSession: Boolean,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 页签可横向滚动，但「＋」固定在右端：标题一长，加号被滚出屏幕后就再也找不到入口。
        // 激活页签自动滚进可视区：否则切到后面的页签时，条上还停在最左边，看不到自己刚打开的是哪个。
        val listState = rememberLazyListState()
        LaunchedEffect(activeTabId, tabs.size) {
            val index = tabs.indexOfFirst { it.id == activeTabId }
            if (index >= 0) listState.animateScrollToItem(index)
        }
        LazyRow(
            modifier = Modifier.weight(1f),
            state = listState,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(tabs, key = { it.id }) { tab ->
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
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workbench_terminal_new)) },
                    enabled = canCreateSession,
                    onClick = {
                        addMenu = false
                        onNewTerminal()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workbench_files_new)) },
                    enabled = canCreateSession,
                    onClick = {
                        addMenu = false
                        onNewFiles()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workbench_review_new)) },
                    enabled = canCreateSession,
                    onClick = {
                        addMenu = false
                        onNewReview()
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
private fun WorkbenchTabHost(
    tab: WorkbenchTab,
    client: DenovaClient?,
    onOpenDrawer: () -> Unit,
    terminalHolder: TerminalTabHolder,
    onSelectFile: (tabId: String, path: String) -> Unit,
    onSelectReviewGroup: (tabId: String, groupId: String) -> Unit,
) {
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

        is WorkbenchTabKind.Terminal -> {
            val application = LocalContext.current.applicationContext as Application
            val factory: ViewModelProvider.Factory = remember(application) {
                viewModelFactory { initializer { TerminalViewModel(application) } }
            }
            val terminalViewModel: TerminalViewModel =
                viewModel(key = terminalViewModelKey(tab.id), factory = factory)
            DisposableEffect(tab.id, terminalViewModel) {
                terminalHolder.register(tab.id, terminalViewModel)
                onDispose { }
            }
            LaunchedEffect(tab.id, tab.projectId, client) {
                val c = client ?: return@LaunchedEffect
                terminalViewModel.bindClient(c)
                // 每个页签一个独立 owner：服务端用 owner_tab_id 做幂等键且禁止跨项目复用，
                // 共用固定 owner 会让第二个项目的终端直接 409。
                terminalViewModel.bindOwner(tab.id)
                // 已有会话（旋转重建 / 切区回来）就不要再 connect()：那会重新解析会话，
                // 白白多一轮 POST 且可能打断正在跑的 PTY。只有全新页签才发起首次连接。
                if (terminalViewModel.state.value.projectId == null) {
                    terminalViewModel.connect(tab.projectId)
                }
            }
            TerminalScreen(
                viewModel = terminalViewModel,
                onOpenDrawer = onOpenDrawer,
                onBack = {},
                initialProjectId = tab.projectId,
                inline = true,
            )
        }

        is WorkbenchTabKind.Files -> WorkbenchFilesHost(
            tab = tab,
            selectedPath = kind.selectedPath,
            client = client,
            onSelectFile = { path -> onSelectFile(tab.id, path) },
        )
        is WorkbenchTabKind.Review -> WorkbenchReviewHost(
            tab = tab,
            reviewedGroupId = kind.groupId,
            client = client,
            onSelectGroup = { groupId -> onSelectReviewGroup(tab.id, groupId) },
        )
    }
}

/**
 * 变更审阅页签宿主（P4-B / WB-005）。
 *
 * 直接复用右抽屉里的 [ChangesContent] 与 [ChangesViewModel]：列表 / diff / 评论 / 审阅 / 撤销 / 重做
 * 与写作页共用同一套实现与同一批端点（`/api/projects/:id/changes/…`），没有第二份逻辑。
 * 注意：KDoc 里不能出现「斜杠 + 星号」，Kotlin 的块注释是可嵌套的，会吞掉后面的代码。
 * 页签记住当前分组，切走再回来仍在同一组上，撤销 / 重做不会打到别的 change group。
 */
@Composable
private fun WorkbenchReviewHost(
    tab: WorkbenchTab,
    reviewedGroupId: String,
    client: DenovaClient?,
    onSelectGroup: (String) -> Unit,
) {
    val application = LocalContext.current.applicationContext as Application
    val factory: ViewModelProvider.Factory = remember(application) {
        viewModelFactory { initializer { ChangesViewModel(application) } }
    }
    // 每项目一个审阅页签，因此 key 用页签 id 即可；旋转后仍是同一个 ViewModel。
    val changesViewModel: ChangesViewModel = viewModel(key = "workbench-review-${tab.id}", factory = factory)
    val changesState by changesViewModel.state.collectAsState()

    LaunchedEffect(tab.id, tab.projectId, client) {
        val c = client ?: return@LaunchedEffect
        changesViewModel.bindClient(c)
        changesViewModel.load(tab.projectId)
    }
    // 页签记得的分组在别处被关掉 / 撤销到不存在时，回到列表，避免详情卡在空数据上。
    LaunchedEffect(tab.id, reviewedGroupId, changesState.detail?.id) {
        if (reviewedGroupId.isNotBlank() && changesState.detail?.id != reviewedGroupId) {
            changesViewModel.openGroup(reviewedGroupId)
        }
    }

    ChangesContent(
        state = changesState,
        onReview = { groupId, decision, changeSetId ->
            changesViewModel.review(groupId, decision, changeSetId)
        },
        onUndo = { groupId -> changesViewModel.undo(groupId) },
        onRedo = { groupId -> changesViewModel.redo(groupId) },
        onRefresh = { changesViewModel.load(tab.projectId, force = true) },
        onOpen = { groupId ->
            onSelectGroup(groupId)
            changesViewModel.openGroup(groupId)
        },
        onCloseDetail = {
            onSelectGroup("")
            changesViewModel.closeDetail()
        },
        onSelectLine = changesViewModel::selectDiffLine,
        onGroupComment = changesViewModel::startGroupComment,
        onDraftChange = changesViewModel::onCommentDraftChange,
        onSubmitComment = changesViewModel::submitComment,
        onCancelComment = changesViewModel::cancelComment,
        onEditComment = changesViewModel::startEditComment,
        onDeleteComment = changesViewModel::deleteComment,
    )
}

/**
 * 文件页签宿主（P4-B / WB-004 工作台宿主形态）。
 *
 * 对齐 Web `AgentChatFilesTab`：一个项目一个文件工作区，页签记住上次打开的文件。
 * 手机上一屏放不下「树 + 编辑器」两栏，所以树做成可折叠的文件选择器，选中后让出空间给编辑器；
 * 编辑器本体复用 [EditorScreen] 的 inline 模式，保存 / 冲突 / 撤销重做与写作页完全同一套逻辑。
 */
@Composable
private fun WorkbenchFilesHost(
    tab: WorkbenchTab,
    selectedPath: String,
    client: DenovaClient?,
    onSelectFile: (String) -> Unit,
) {
    val application = LocalContext.current.applicationContext as Application
    val editorFactory: ViewModelProvider.Factory = remember(application) {
        viewModelFactory { initializer { EditorViewModel(application) } }
    }
    val directoryFactory: ViewModelProvider.Factory = remember(application) {
        viewModelFactory { initializer { ProjectDirectoryViewModel(application) } }
    }
    val editorViewModel: EditorViewModel = viewModel(key = "workbench-files-editor-${tab.id}", factory = editorFactory)
    val directoryViewModel: ProjectDirectoryViewModel =
        viewModel(key = "workbench-files-tree-${tab.id}", factory = directoryFactory)
    val directoryState by directoryViewModel.state.collectAsState()
    var pickerOpen by remember(tab.id) { mutableStateOf(selectedPath.isBlank()) }

    LaunchedEffect(tab.id, tab.projectId, client) {
        val c = client ?: return@LaunchedEffect
        // 不恢复「上次项目」的标签桶：页签有自己的项目作用域，恢复会把别的项目的正文带进屏幕
        // （保存还会写回那个项目）。
        editorViewModel.bindClient(c, restoreTabs = false)
        directoryViewModel.bindClient(c)
        directoryViewModel.load(tab.projectId)
    }

    LaunchedEffect(tab.id, tab.projectId, client, selectedPath) {
        if (client == null || selectedPath.isBlank()) return@LaunchedEffect
        // 已经打开同一个文件就不再重读，避免覆盖未保存的正文。
        if (editorViewModel.uiState.value.filePath != selectedPath) {
            editorViewModel.openFile(tab.projectId, selectedPath)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedPath.ifBlank { stringResource(R.string.workbench_files_unselected) },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { pickerOpen = !pickerOpen }) {
                Text(
                    stringResource(
                        if (pickerOpen) R.string.common_close else R.string.workbench_files_pick,
                    ),
                )
            }
        }
        if (pickerOpen) {
            HorizontalDivider()
            val rows = directoryState.rows
            when {
                directoryState.loading && rows.isEmpty() -> Text(
                    stringResource(R.string.loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                rows.isEmpty() -> Text(
                    stringResource(R.string.workbench_files_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(rows, key = { it.node.path.ifBlank { "$" } + ":" + it.depth }) { row ->
                        val indent = (row.depth * 12).dp
                        TextButton(
                            onClick = {
                                if (row.node.isDir) {
                                    directoryViewModel.toggle(row.node.path)
                                } else {
                                    onSelectFile(row.node.path)
                                    pickerOpen = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(start = indent),
                        ) {
                            Text(
                                text = (if (row.node.isDir) (if (row.expanded) "▾ " else "▸ ") else "") + row.node.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = if (row.node.path == selectedPath) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
        }
        EditorScreen(
            viewModel = editorViewModel,
            onOpenDrawer = {},
            onOpenRightDrawer = {},
            onOpenTerminal = {},
            onLogout = {},
            currentProjectId = tab.projectId,
            inline = true,
        )
    }
}

internal fun terminalViewModelKey(tabId: String): String = "workbench-terminal-$tabId"

/**
 * 终端页签 → 终端 ViewModel 的登记表（P4-B / WB-008）。
 *
 * 挂在 Activity 的 ViewModelStore 上：旋转重建 Composable 后对应关系仍在，
 * 关页签时才能准确释放该页签独占的 PTY（而不是释放不了或误伤别的页签）。
 */
class TerminalTabHolder : androidx.lifecycle.ViewModel() {
    private val terminals = mutableMapOf<String, TerminalViewModel>()

    fun register(tabId: String, viewModel: TerminalViewModel) {
        terminals[tabId] = viewModel
    }

    fun release(tabId: String) {
        terminals.remove(tabId)?.closeAndRelease()
    }
}

const val TERMINAL_HOLDER_KEY: String = "workbench-terminal-holder"

/** 会话历史面板的 ViewModel key（Activity 级，关掉再开保留查询与项目）。 */
const val HISTORY_KEY: String = "workbench-history"
