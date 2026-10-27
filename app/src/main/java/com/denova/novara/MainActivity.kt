package com.denova.novara

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.denova.novara.R
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.logging.LogcatSink
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.onboarding.OnboardingLogic
import com.denova.novara.core.persistence.PreferencesStore
import com.denova.novara.feature.shell.ActivityNav
import com.denova.novara.core.onboarding.OnboardingStep
import com.denova.novara.feature.agent.AgentScreen
import com.denova.novara.feature.onboarding.CreateBookDialog
import com.denova.novara.feature.agent.AgentViewModel
import com.denova.novara.feature.connection.ConnectionScreen
import com.denova.novara.feature.connection.ConnectionUiState
import com.denova.novara.feature.connection.ConnectionViewModel
import com.denova.novara.feature.debug.DebugPanelScreen
import com.denova.novara.feature.debug.DebugPanelViewModel
import com.denova.novara.feature.editor.EditorScreen
import com.denova.novara.feature.editor.EditorViewModel
import com.denova.novara.feature.game.GameScreen
import com.denova.novara.feature.game.GameViewModel
import com.denova.novara.feature.bookshelf.BookShelfViewModel
import com.denova.novara.feature.changes.ChangesViewModel
import com.denova.novara.feature.chapters.ChaptersViewModel
import com.denova.novara.feature.versions.VersionsViewModel
import com.denova.novara.feature.project.ProjectDirectoryViewModel
import com.denova.novara.feature.settings.SettingsViewModel
import com.denova.novara.feature.shell.AppDrawer
import com.denova.novara.feature.shell.GameConsoleContent
import com.denova.novara.feature.shell.PlaceholderScreen
import com.denova.novara.feature.shell.ChaptersContent
import com.denova.novara.feature.shell.ChangesContent
import com.denova.novara.feature.shell.VersionsContent
import com.denova.novara.feature.shell.ProjectDirectoryContent
import com.denova.novara.feature.shell.RightDrawer
import com.denova.novara.feature.shell.appDestinations
import com.denova.novara.feature.terminal.TerminalScreen
import com.denova.novara.feature.terminal.TerminalViewModel
import com.denova.novara.ui.theme.NovaraTheme
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!logcatInstalled) {
            logcatInstalled = true
            DenovaLog.addSink(LogcatSink())
        }
        // D03 恢复耗时埋点：进程冷启起点（与 login ok / attach / baseline / narrative-visible 对齐）
        DenovaLog.i("Perf", "process-start")
        setContent {
            val container = (application as NovaraApplication).container
            val settingsViewModel: SettingsViewModel = viewModel(factory = container.viewModelFactory)
            val darkTheme by settingsViewModel.darkTheme.collectAsState()
            val language by settingsViewModel.language.collectAsState()
            NovaraTheme(darkTheme = darkTheme) {
                val connectionViewModel: ConnectionViewModel = viewModel(factory = container.viewModelFactory)
                val editorViewModel: EditorViewModel = viewModel(factory = container.viewModelFactory)
                val gameViewModel: GameViewModel = viewModel(factory = container.viewModelFactory)
                val agentViewModel: AgentViewModel = viewModel(factory = container.viewModelFactory)
                val terminalViewModel: TerminalViewModel = viewModel(factory = container.viewModelFactory)
                val projectDirectoryViewModel: ProjectDirectoryViewModel = viewModel(factory = container.viewModelFactory)
                val bookShelfViewModel: BookShelfViewModel = viewModel(factory = container.viewModelFactory)
                val changesViewModel: ChangesViewModel = viewModel(factory = container.viewModelFactory)
                val versionsViewModel: VersionsViewModel = viewModel(factory = container.viewModelFactory)
    val chaptersViewModel: ChaptersViewModel = viewModel(factory = container.viewModelFactory)
                val debugViewModel: DebugPanelViewModel = viewModel(factory = container.viewModelFactory)
                val state by connectionViewModel.state.collectAsState()
                val projectState by projectDirectoryViewModel.state.collectAsState()
                val bookState by bookShelfViewModel.state.collectAsState()
                val changesState by changesViewModel.state.collectAsState()
                val versionsState by versionsViewModel.state.collectAsState()
    val chaptersState by chaptersViewModel.state.collectAsState()
                var showDebug by remember { mutableStateOf(false) }
                var showRightDrawer by remember { mutableStateOf(false) }
                var rightTab by remember { mutableStateOf(0) }
                // 当前编辑项目：书籍切换或编辑器输入框同步，供右抽屉项目目录加载。
                var currentProjectId by remember { mutableStateOf("") }
    // WRI-001 引导：建书对话框开关（「刚建的书」记在书架 ViewModel 里，跨重组保留）。
    var showCreateBook by remember { mutableStateOf(false) }
    // NAV-004：一级导航顺序（保存顺序优先；缺项按默认位插入，见 ActivityNav）。
    val navPrefs = remember { PreferencesStore(applicationContext) }
    var activityOrder by remember { mutableStateOf(ActivityNav.DEFAULT_ORDER) }
    LaunchedEffect(Unit) {
        val saved = navPrefs.activityOrder()
        activityOrder = if (saved.isEmpty()) ActivityNav.DEFAULT_ORDER else ActivityNav.insertMissing(saved)
    }
                val navController = rememberNavController()
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                // NAV-003：同一时刻只允许一个侧面板可见——左抽屉打开就收起右抽屉，反之亦然。
                LaunchedEffect(drawerState.isOpen) {
                    if (drawerState.isOpen) showRightDrawer = false
                }
                // NAV-009：系统返回逐层收起最上层（右抽屉 → 左抽屉 → 页面本身）。
                BackHandler(enabled = showRightDrawer) { showRightDrawer = false }
                BackHandler(enabled = !showRightDrawer && drawerState.isOpen) {
                    scope.launch { drawerState.close() }
                }
                // 去 query 归一化，保证带参数的 route 也能在抽屉里高亮。
                val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
                    ?.substringBefore('?')

                Box(modifier = Modifier.fillMaxSize()) {
                    when (state) {
                        is ConnectionUiState.Authenticated -> {
                            val client = connectionViewModel.client
                            LaunchedEffect(client) {
                                if (client != null) {
                                    editorViewModel.bindClient(client)
                                    gameViewModel.bindClient(client)
                                    agentViewModel.bindClient(client)
                                    terminalViewModel.bindClient(client)
                                    projectDirectoryViewModel.bindClient(client)
                                    bookShelfViewModel.bindClient(client)
                                    bookShelfViewModel.load()
                                    changesViewModel.bindClient(client)
                versionsViewModel.bindClient(client)
        chaptersViewModel.bindClient(client)
                                }
                            }
                            ModalNavigationDrawer(
                                drawerState = drawerState,
                                drawerContent = {
                                    ModalDrawerSheet {
                                        AppDrawer(
                                            currentRoute = currentRoute,
                                            darkTheme = darkTheme,
                                            onToggleDarkTheme = { settingsViewModel.toggleDarkTheme() },
                                            language = language,
                                            onSelectLanguage = { settingsViewModel.setLanguage(it) },
                                            books = bookState.books,
                                            currentProjectId = currentProjectId.ifBlank { null },
                                            onSelectBook = { book ->
                                                scope.launch {
                                                    // 对齐 Web onBeforeWorkspaceSwitch：先 flush 编辑器草稿
                                                    if (!editorViewModel.flush()) {
                                                        DenovaLog.w("BookShelf", "flush failed, abort switch")
                                                        return@launch
                                                    }
                                                    // 服务端切换成功后才更新客户端当前项目
                                                    if (bookShelfViewModel.switch(book)) {
                                                        currentProjectId = book.projectId
                                                        // 每个项目各自一份文件标签桶（WRI-003）。
                                                        editorViewModel.setProject(book.projectId)
                                                        drawerState.close()
                                                    }
                                                }
                                            },
                                            onOpenDebug = if (BuildConfig.DEBUG) {
                                                // 抽屉入口先关闭抽屉再打开面板，避免面板被抽屉遮住。
                                                {
                                                    scope.launch { drawerState.close() }
                                                    showDebug = true
                                                }
                                            } else {
                                                null
                                            },
                                            // WRI-001：没有书先引导建书；刚建完进入「填第一章请求」步。
                                            onboardingStep = when {
                                                bookState.books.isEmpty() -> OnboardingStep.BOOK
                                                bookState.justCreatedName.isNotEmpty() -> OnboardingStep.AGENT
                                                else -> null
                                            },
                                            onSkipOnboarding = { bookShelfViewModel.clearJustCreated() },
                                            onCreateBook = {
                                                scope.launch { drawerState.close() }
                                                showCreateBook = true
                                            },
                                            onFillFirstPrompt = {
                                                val book = bookState.justCreatedName
                                                bookShelfViewModel.clearJustCreated()
                                                scope.launch { drawerState.close() }
                                                val encoded = android.net.Uri.encode(OnboardingLogic.firstChapterPrompt(book).resolve(applicationContext))
                                                navController.navigate("agent?projectId=$currentProjectId&filePath=&prompt=$encoded")
                                            },
                                            onNavigate = { destination ->
                                                scope.launch { drawerState.close() }
                                                navController.navigate(destination.route) {
                                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            },
                                            activityItems = ActivityNav.sort(order = activityOrder),
                                            onMoveActivity = { id, delta ->
                                                activityOrder = ActivityNav.move(activityOrder, id, delta)
                                                scope.launch { navPrefs.setActivityOrder(activityOrder) }
                                            },
                                            onResetActivityOrder = {
                                                activityOrder = ActivityNav.DEFAULT_ORDER
                                                scope.launch { navPrefs.setActivityOrder(ActivityNav.DEFAULT_ORDER) }
                                            },
                                        )
                                    }
                                },
                            ) {
                                // WRI-001 建书：「从想法开始」= 建书后进入 Agent 并预填启动提示词（不自动发送）。
                                if (showCreateBook) {
                                    CreateBookDialog(
                                        creating = bookState.creating,
                                        error = bookState.error,
                                        onDismiss = { showCreateBook = false },
                                        onCreate = { title, author, description, fromIdea ->
                                            scope.launch {
                                                val record = bookShelfViewModel.createBook(title, author, description)
                                                if (record == null) return@launch
                                                showCreateBook = false
                                                // 「刚建的书」已由 createBook 写入书架状态，引导 AGENT 步据此出现。
                                                // 建书接口已在服务端切到新 workspace：客户端同步项目作用域。
                                                currentProjectId = record.projectId
                                                editorViewModel.setProject(record.projectId)
                                                if (fromIdea) {
                                                    val encoded = android.net.Uri.encode(OnboardingLogic.INIT_PROMPT.resolve(applicationContext))
                                                    navController.navigate(
                                                        "agent?projectId=${record.projectId}&filePath=&prompt=$encoded",
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }

                                NavHost(
                                    navController = navController,
                                    startDestination = "editor",
                                ) {
                                    composable("editor") {
                                        EditorScreen(
                                            viewModel = editorViewModel,
                                            onOpenDrawer = { scope.launch { drawerState.open() } },
                                            onOpenRightDrawer = {
                                                // 打开右抽屉先收起左抽屉，避免两个面板同屏（NAV-003）。
                                                scope.launch { drawerState.close() }
                                                showRightDrawer = true
                                            },
                                            onOpenTerminal = { projectId ->
                                                navController.navigate("terminal/$projectId")
                                            },
                                            onLogout = { connectionViewModel.logout() },
                                            currentProjectId = currentProjectId,
                                            onProjectIdChange = { currentProjectId = it },
                                            onOpenAgent = { pid, path ->
                                                val encodedPath = android.net.Uri.encode(path)
                                                navController.navigate("agent?projectId=$pid&filePath=$encodedPath")
                                            },
                                            // 选区引用（WRI-009）：引用进 Agent 输入区，随后随消息提交。
                                            onQuoteSelection = { pid, selection ->
                                                agentViewModel.addSelection(selection)
                                                val encodedPath = android.net.Uri.encode(selection.fileName)
                                                navController.navigate("agent?projectId=$pid&filePath=$encodedPath")
                                            },
                                            // 预览图片走项目资源接口（需要会话 Cookie），交给 OkHttp 代取。
                                            loaderFactory = { pid, path ->
                                                val c = connectionViewModel.client
                                                if (c == null) null
                                                else com.denova.novara.feature.editor.PreviewAssetLoader(c, pid, path)
                                            },
                                            // 从项目 assets/ 里挑图片插入（基线没有文档图片上传接口）。
                                            assetLister = { pid ->
                                                val c = connectionViewModel.client
                                                if (c == null) {
                                                    emptyList()
                                                } else {
                                                    runCatching {
                                                        c.resolveProjectFiles(pid, listOf("assets"), recursive = true)
                                                            .results.flatMap { it.directories }
                                                            .flatMap { it.entries }
                                                            .filter { !it.isDir && it.name.substringAfterLast('.', "").lowercase() in
                                                                setOf("png", "jpg", "jpeg", "gif", "webp") }
                                                            .map { it.path }
                                                    }.getOrDefault(emptyList())
                                                }
                                            },
                                        )
                                    }
                                    composable("game") {
                                        GameScreen(
                                            viewModel = gameViewModel,
                                            projectId = currentProjectId,
                                            onOpenDrawer = { scope.launch { drawerState.open() } },
                                            onOpenRightDrawer = {
                                                // 打开右抽屉先收起左抽屉，避免两个面板同屏（NAV-003）。
                                                scope.launch { drawerState.close() }
                                                showRightDrawer = true
                                            },
                                            onBack = { navController.popBackStack() },
                                        )
                                    }
                                    composable(
                                        route = "agent?projectId={projectId}&filePath={filePath}&prompt={prompt}",
                                        arguments = listOf(
                                            navArgument("projectId") { type = NavType.StringType; defaultValue = "" },
                                            navArgument("filePath") { type = NavType.StringType; defaultValue = "" },
                                            navArgument("prompt") { type = NavType.StringType; defaultValue = "" },
                                        ),
                                    ) { entry ->
                                        AgentScreen(
                                            viewModel = agentViewModel,
                                            onOpenDrawer = { scope.launch { drawerState.open() } },
                                            onBack = { navController.popBackStack() },
                                            projectId = entry.arguments?.getString("projectId").orEmpty(),
                                            initialFile = entry.arguments?.getString("filePath").orEmpty(),
                                            initialPrompt = entry.arguments?.getString("prompt").orEmpty(),
                                        )
                                    }
                                    composable(
                                        route = "terminal/{projectId}",
                                        arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
                                    ) { entry ->
                                        val projectId = entry.arguments?.getString("projectId").orEmpty()
                                        TerminalScreen(
                                            viewModel = terminalViewModel,
                                            onOpenDrawer = { scope.launch { drawerState.open() } },
                                            initialProjectId = projectId,
                                            onBack = { navController.popBackStack() },
                                        )
                                    }
                                    appDestinations.filter { !it.implemented }.forEach { destination ->
                                        composable(destination.route) {
                                            PlaceholderScreen(
                                                labelRes = destination.labelRes,
                                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        else -> ConnectionScreen(connectionViewModel)
                    }

                    // 右项目 / 控制台抽屉（按当前页面）。
                    if (showRightDrawer) {
                        val isGame = currentRoute == "game"
                        LaunchedEffect(showRightDrawer, currentProjectId, isGame, rightTab) {
                            if (!isGame) {
                                when (rightTab) {
                                    0 -> projectDirectoryViewModel.load(currentProjectId)
                                    1 -> changesViewModel.load(currentProjectId)
                                    2 -> versionsViewModel.load(currentProjectId)
                                    else -> chaptersViewModel.load(currentProjectId)
                                }
                            }
                        }
                        RightDrawer(
                            title = if (isGame) {
                                stringResource(R.string.right_drawer_console)
                            } else {
                                stringResource(R.string.right_drawer_project)
                            },
                            onDismiss = { showRightDrawer = false },
                        ) {
                            if (isGame) {
                                val gameLedger by gameViewModel.ledger.collectAsState()
                                val gameUi by gameViewModel.uiState.collectAsState()
                                GameConsoleContent(
                                    ledger = gameLedger,
                                    turnCount = gameUi.turnCount,
                                    turnStart = gameUi.turnStart,
                                    branchCount = gameUi.branchCount,
                                    onRefresh = { gameViewModel.refreshSnapshot() },
                                )
                            } else {
                                // 写作页右抽屉：项目文件 / 变更审阅。
                                Column(Modifier.fillMaxSize()) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        TextButton(onClick = { rightTab = 0 }) {
                                            Text(stringResource(R.string.right_drawer_project))
                                        }
                                        TextButton(onClick = { rightTab = 1 }) {
                                            Text(stringResource(R.string.changes_title))
                                        }
                                        TextButton(onClick = { rightTab = 2 }) {
                                            Text(stringResource(R.string.versions_title))
                                        }
                                        TextButton(onClick = { rightTab = 3 }) {
                                            Text(stringResource(R.string.chapters_title))
                                        }
                                    }
                                    if (rightTab == 0) {
                                        ProjectDirectoryContent(
                                            state = projectState,
                                            onToggle = projectDirectoryViewModel::toggle,
                                            onOpenFile = { path ->
                                                projectDirectoryViewModel.setSelected(path)
                                                editorViewModel.openFile(currentProjectId, path)
                                                showRightDrawer = false
                                                if (currentRoute != "editor") navController.navigate("editor")
                                            },
                                            onRefresh = projectDirectoryViewModel::refresh,
                                            onLoadMore = projectDirectoryViewModel::loadMore,
                                            onSetDebugBudget = projectDirectoryViewModel::setDebugEntryBudget,
                                        )
                                    } else if (rightTab == 1) {
                                        ChangesContent(
                                            state = changesState,
                                            onReview = changesViewModel::review,
                                            onUndo = changesViewModel::undo,
                                            onRedo = changesViewModel::redo,
                                            onRefresh = {
                                                if (currentProjectId.isNotBlank()) {
                                                    changesViewModel.load(currentProjectId, force = true)
                                                }
                                            },
                                            onOpen = changesViewModel::openGroup,
                                            onCloseDetail = changesViewModel::closeDetail,
                                            onSelectLine = changesViewModel::selectDiffLine,
                                            onGroupComment = changesViewModel::startGroupComment,
                                            onDraftChange = changesViewModel::onCommentDraftChange,
                                            onSubmitComment = changesViewModel::submitComment,
                                            onCancelComment = changesViewModel::cancelComment,
                                            onEditComment = changesViewModel::startEditComment,
                                            onDeleteComment = changesViewModel::deleteComment,
                                        )
                                    } else if (rightTab == 2) {
                                        VersionsContent(
                                            state = versionsState,
                                            onRefresh = {
                                                if (currentProjectId.isNotBlank()) {
                                                    versionsViewModel.load(currentProjectId, force = true)
                                                }
                                            },
                                            onCreateTextChange = versionsViewModel::setCreateText,
                                            onCreate = versionsViewModel::createVersion,
                                            onOpenVersion = versionsViewModel::openVersion,
                                            onCloseVersion = versionsViewModel::closeVersion,
                                            onTogglePath = versionsViewModel::togglePath,
                                            onToggleAll = versionsViewModel::toggleAllPaths,
                                            onLoadPlan = versionsViewModel::loadPlan,
                                            onConfirmRestore = versionsViewModel::confirmRestore,
                                            onDismissAction = versionsViewModel::clearActionResult,
                                        )
                                    } else {
                                        ChaptersContent(
                                            state = chaptersState,
                                            onRefresh = {
                                                if (currentProjectId.isNotBlank()) {
                                                    chaptersViewModel.load(currentProjectId, force = true)
                                                }
                                            },
                                            onOpenChapter = { path ->
                                                editorViewModel.openFile(currentProjectId, path)
                                                showRightDrawer = false
                                                if (currentRoute != "editor") navController.navigate("editor")
                                            },
                                            onToggleConfirmed = chaptersViewModel::toggleConfirmed,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 调试面板仍覆盖在最上层，入口在左抽屉（见 AppDrawer 的 onOpenDebug）。
                    if (showDebug) {
                        DebugPanelScreen(
                            viewModel = debugViewModel,
                            client = connectionViewModel.client,
                            onClose = { showDebug = false },
                        )
                    }
                }
            }
        }
    }

    companion object {
        @Volatile
        private var logcatInstalled = false
    }
}
