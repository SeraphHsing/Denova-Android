package com.denova.novara

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.denova.novara.feature.agents.AgentsScreen
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
import com.denova.novara.feature.settings.SettingsScreen
import com.denova.novara.feature.automations.AutomationsScreen
import com.denova.novara.feature.messages.MessageCenterScreen
import com.denova.novara.feature.market.MarketScreen
import com.denova.novara.feature.versions.VersionsScreen
import com.denova.novara.feature.shell.AppDrawer
import com.denova.novara.feature.shell.GameConsoleContent
import com.denova.novara.feature.shell.PlaceholderScreen
import com.denova.novara.feature.shell.ChaptersContent
import com.denova.novara.feature.shell.ChangesContent
import com.denova.novara.feature.shell.VersionsContent
import com.denova.novara.feature.shell.ProjectDirectoryContent
import com.denova.novara.feature.shell.DrawerSide
import com.denova.novara.feature.shell.EdgeSwipeGesture
import com.denova.novara.feature.shell.RightDrawer
import com.denova.novara.feature.shell.drawerEdgeSwipe
import com.denova.novara.feature.books.BooksScreen
import com.denova.novara.feature.lore.LoreScreen
import com.denova.novara.feature.presets.PresetsScreen
import com.denova.novara.feature.skills.SkillsScreen
import com.denova.novara.feature.shell.appDestinations
import com.denova.novara.feature.terminal.TerminalScreen
import com.denova.novara.feature.terminal.TerminalViewModel
import com.denova.novara.feature.workbench.WorkbenchScreen
import com.denova.novara.feature.workbench.WorkbenchTreeContent
import com.denova.novara.feature.workbench.WorkbenchViewModel
import com.denova.novara.ui.theme.NovaraTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    /** 资料库草稿要跨页面存活（LOR-004），所以放在 Activity 作用域。 */
    private val loreViewModel: com.denova.novara.feature.lore.LoreViewModel by viewModels()

    /** 自动化草稿要跨页面存活（AUT-004），同样放在 Activity 作用域。 */
    private val automationsViewModel: com.denova.novara.feature.automations.AutomationsViewModel by viewModels()

    /** 消息中心：抽屉角标与页面共用，放在 Activity 作用域（MSG-001…003）。 */
    private val messageCenterViewModel: com.denova.novara.feature.messages.MessageCenterViewModel by viewModels()

    /** 资源市场：需要跟随语言切换重算本地化文本，放 Activity 作用域（MKT-001…005）。 */
    private val marketViewModel: com.denova.novara.feature.market.MarketViewModel by viewModels()
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
                val workbenchViewModel: WorkbenchViewModel = viewModel(factory = container.viewModelFactory)
                val debugViewModel: DebugPanelViewModel = viewModel(factory = container.viewModelFactory)
                val messageState by messageCenterViewModel.state.collectAsState()
                val messageUnread = messageState.unread
                val state by connectionViewModel.state.collectAsState()
                val projectState by projectDirectoryViewModel.state.collectAsState()
                val bookState by bookShelfViewModel.state.collectAsState()
                val changesState by changesViewModel.state.collectAsState()
                val versionsState by versionsViewModel.state.collectAsState()
    val chaptersState by chaptersViewModel.state.collectAsState()
                var showDebug by remember { mutableStateOf(false) }
                var showRightDrawer by remember { mutableStateOf(false) }
                // 左抽屉可见性由本地状态驱动（NAV-008 起手区判定需要稳定的可观察值）。
                var leftDrawerOpen by remember { mutableStateOf(false) }
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
                // 登记位置必须在 NavHost **之后**（见下方 Box 末尾）：Compose 的返回分发按登记顺序
                // 「后登记者优先」，写在 NavHost 之前会被导航图的回调抢先，真机上表现为
                // 「抽屉开着按返回却退到了上一页」（P4-B 工作台实测）。
                // NAV-003：左抽屉入口统一走这里，先收起右抽屉再打开左抽屉，避免两侧同时可见。
                fun openLeftDrawer() {
                    showRightDrawer = false
                    leftDrawerOpen = true
                    scope.launch { drawerState.open() }
                }
                fun closeLeftDrawer() {
                    leftDrawerOpen = false
                    scope.launch { drawerState.close() }
                }
                // M3 的 DrawerState 读值不一定触发重组，边缘手势的起手区判定改用本地状态，
                // 并轮询一次真实状态做兜底同步（scrim 点击、返回键、导航后自动收起都会走到这里）。
                LaunchedEffect(drawerState) {
                    while (true) {
                        val actualOpen = drawerState.currentValue == DrawerValue.Open
                        if (actualOpen != leftDrawerOpen) {
                            leftDrawerOpen = actualOpen
                            if (actualOpen) showRightDrawer = false
                        }
                        delay(120)
                    }
                }
                fun closeTopLayer(): Boolean = when {
                    showRightDrawer -> {
                        showRightDrawer = false
                        true
                    }
                    leftDrawerOpen -> {
                        closeLeftDrawer()
                        true
                    }
                    else -> false
                }
                // 去 query 归一化，保证带参数的 route 也能在抽屉里高亮。
                val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
                    ?.substringBefore('?')
                // 换页时收起右抽屉：右抽屉内容按当前页面切换，留着会把上一页的「项目目录」盖在新页面上。
                LaunchedEffect(currentRoute) { showRightDrawer = false }

                // NAV-008：边缘手势由 [drawerEdgeSwipe] 统一判定（基线 use-mobile-drawer-swipe）。
                // 起手区：都关闭时左右各 24dp；某侧打开时只有抽屉标题栏（56dp + 状态栏）可关闭。
                // 挂在最外层 Box 上：Initial pass 从根往叶分发，父节点先于页面滚动容器看到指针。
                val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val openSide = when {
                    showRightDrawer -> DrawerSide.RIGHT
                    leftDrawerOpen -> DrawerSide.LEFT
                    else -> null
                }
                // 只有已认证的抽屉壳才响应手势：连接页没有抽屉，响应会把状态带到登录后的首屏。
                val swipeModifier = if (state is ConnectionUiState.Authenticated) {
                    Modifier.drawerEdgeSwipe(
                        openSide = openSide,
                        headerHeight = 56.dp + statusBarTop,
                        onOpen = { side ->
                            when (side) {
                                DrawerSide.LEFT -> openLeftDrawer()
                                DrawerSide.RIGHT -> {
                                    closeLeftDrawer()
                                    showRightDrawer = true
                                }
                            }
                        },
                        onClose = {
                            if (showRightDrawer) showRightDrawer = false else closeLeftDrawer()
                        },
                    )
                } else {
                    Modifier
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(swipeModifier),
                ) {
                    // NAV-008 平台差异：Android 左右边缘是系统返回手势区，先到先得。
                    // 在屏幕中部各声明一条 200dp 高的排除带（系统对该类区域的硬上限），
                    // 让抽屉边缘手势在这段生效；顶部与底部边缘仍归系统返回。
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .width(EdgeSwipeGesture.EDGE_BAND_DP.dp)
                            .height(SYSTEM_GESTURE_EXCLUSION_HEIGHT)
                            .systemGestureExclusion(),
                    )
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .width(EdgeSwipeGesture.EDGE_BAND_DP.dp)
                            .height(SYSTEM_GESTURE_EXCLUSION_HEIGHT)
                            .systemGestureExclusion(),
                    )
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
                                    workbenchViewModel.bindClient(client)
                                    // 工作台不切换前台书籍：只在进入页面/抽屉时读取项目树。
                                    workbenchViewModel.refresh()
                                    // 消息中心：抽屉角标与页面共用一份状态（MSG-001…003）。
                                    messageCenterViewModel.bindClient(client)
                                }
                            }
                            ModalNavigationDrawer(
                                drawerState = drawerState,
                                // 关掉 M3 默认手势：它整屏可拖、没有方向比例与排除区判定，
                                // 会抢走页面横向控件并让垂直阅读误开抽屉（NAV-008 按基线改由 drawerEdgeSwipe 判定）。
                                gesturesEnabled = false,
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
                                                        leftDrawerOpen = false
                                                        drawerState.close()
                                                    }
                                                }
                                            },
                                            onOpenDebug = if (BuildConfig.DEBUG) {
                                                // 抽屉入口先关闭抽屉再打开面板，避免面板被抽屉遮住。
                                                {
                                                    closeLeftDrawer()
                                                    showDebug = true
                                                }
                                            } else {
                                                null
                                            },
                                            onOpenMessages = {
                                                closeLeftDrawer()
                                                navController.navigate("messages") {
                                                    launchSingleTop = true
                                                }
                                            },
                                            unreadMessages = messageUnread,
                                            // WRI-001：没有书先引导建书；刚建完进入「填第一章请求」步。
                                            onboardingStep = when {
                                                bookState.books.isEmpty() -> OnboardingStep.BOOK
                                                bookState.justCreatedName.isNotEmpty() -> OnboardingStep.AGENT
                                                else -> null
                                            },
                                            onSkipOnboarding = { bookShelfViewModel.clearJustCreated() },
                                            onCreateBook = {
                                                closeLeftDrawer()
                                                showCreateBook = true
                                            },
                                            onFillFirstPrompt = {
                                                val book = bookState.justCreatedName
                                                bookShelfViewModel.clearJustCreated()
                                                closeLeftDrawer()
                                                val encoded = android.net.Uri.encode(OnboardingLogic.firstChapterPrompt(book).resolve(applicationContext))
                                                navController.navigate("agent?projectId=$currentProjectId&filePath=&prompt=$encoded")
                                            },
                                            onNavigate = { destination ->
                                                closeLeftDrawer()
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
                                            onOpenDrawer = { openLeftDrawer() },
                                            onOpenRightDrawer = {
                                                // 打开右抽屉先收起左抽屉，避免两个面板同屏（NAV-003）。
                                                closeLeftDrawer()
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
                                            client = connectionViewModel.client,
                                            onOpenDrawer = { openLeftDrawer() },
                                            onOpenRightDrawer = {
                                                // 打开右抽屉先收起左抽屉，避免两个面板同屏（NAV-003）。
                                                closeLeftDrawer()
                                                showRightDrawer = true
                                            },
                                            onBack = { navController.popBackStack() },
                                        )
                                    }
                                    composable(
                                        route = "agent?projectId={projectId}&filePath={filePath}&prompt={prompt}&sessionId={sessionId}",
                                        arguments = listOf(
                                            navArgument("projectId") { type = NavType.StringType; defaultValue = "" },
                                            navArgument("filePath") { type = NavType.StringType; defaultValue = "" },
                                            navArgument("prompt") { type = NavType.StringType; defaultValue = "" },
                                            navArgument("sessionId") { type = NavType.StringType; defaultValue = "" },
                                        ),
                                    ) { entry ->
                                        AgentScreen(
                                            viewModel = agentViewModel,
                                            onOpenDrawer = { openLeftDrawer() },
                                            onBack = { navController.popBackStack() },
                                            projectId = entry.arguments?.getString("projectId").orEmpty(),
                                            initialFile = entry.arguments?.getString("filePath").orEmpty(),
                                            initialPrompt = entry.arguments?.getString("prompt").orEmpty(),
                                            initialSessionId = entry.arguments?.getString("sessionId").orEmpty(),
                                        )
                                    }
                                    composable("agents") {
                                        AgentsScreen(
                                            client = connectionViewModel.client,
                                            onOpenDrawer = { openLeftDrawer() },
                                            onOpenSession = { sessionId ->
                                                navController.navigate(
                                                    "agent?projectId=$currentProjectId&filePath=&sessionId=$sessionId",
                                                )
                                            },
                                        )
                                    }
                                    composable("workbench") {
                                        WorkbenchScreen(
                                            viewModel = workbenchViewModel,
                                            client = connectionViewModel.client,
                                            onOpenDrawer = { openLeftDrawer() },
                                            onOpenRightDrawer = {
                                                closeLeftDrawer()
                                                showRightDrawer = true
                                            },
                                        )
                                    }
                                    composable(
                                        route = "terminal/{projectId}",
                                        arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
                                    ) { entry ->
                                        val projectId = entry.arguments?.getString("projectId").orEmpty()
                                        TerminalScreen(
                                            viewModel = terminalViewModel,
                                            onOpenDrawer = { openLeftDrawer() },
                                            initialProjectId = projectId,
                                            onBack = { navController.popBackStack() },
                                        )
                                    }
                                    composable("books") {
                                        BooksScreen(
                                            client = connectionViewModel.client,
                                            currentProjectId = currentProjectId,
                                            onOpenDrawer = { openLeftDrawer() },
                                            onCurrentBookChanged = { book ->
                                                // 书籍页里切换 / 新建 / 导入 / 移除后，同步外壳的项目上下文，
                                                // 否则抽屉、编辑器与项目目录还停在上一个项目上。
                                                if (book != null && book.projectId != currentProjectId) {
                                                    currentProjectId = book.projectId
                                                    editorViewModel.setProject(book.projectId)
                                                    bookShelfViewModel.refresh()
                                                }
                                            },
                                        )
                                    }
                                    composable("lore") {
                                        LoreScreen(
                                            client = connectionViewModel.client,
                                            projectId = currentProjectId,
                                            onOpenDrawer = { openLeftDrawer() },
                                            viewModel = loreViewModel,
                                        )
                                    }
                                    composable("teller") {
                                        PresetsScreen(
                                            client = connectionViewModel.client,
                                            onOpenDrawer = { openLeftDrawer() },
                                        )
                                    }
                                    composable("skills") {
                                        SkillsScreen(
                                            client = connectionViewModel.client,
                                            projectId = currentProjectId,
                                            onOpenDrawer = { openLeftDrawer() },
                                        )
                                    }
                                    composable("settings") {
                                        SettingsScreen(
                                            client = connectionViewModel.client,
                                            onOpenDrawer = { openLeftDrawer() },
                                        )
                                    }
                                    composable("automations") {
                                        AutomationsScreen(
                                            client = connectionViewModel.client,
                                            projectId = currentProjectId,
                                            onOpenDrawer = { openLeftDrawer() },
                                            viewModel = automationsViewModel,
                                        )
                                    }
                                    composable("versions") {
                                        VersionsScreen(
                                            client = connectionViewModel.client,
                                            projectId = currentProjectId,
                                            onOpenDrawer = { openLeftDrawer() },
                                            viewModel = versionsViewModel,
                                        )
                                    }
                                    composable("market") {
                                        MarketScreen(
                                            client = connectionViewModel.client,
                                            language = language,
                                            projectId = currentProjectId,
                                            onOpenDrawer = { openLeftDrawer() },
                                            viewModel = marketViewModel,
                                        )
                                    }
                                    composable("messages") {
                                        MessageCenterScreen(
                                            client = connectionViewModel.client,
                                            onBack = { navController.popBackStack() },
                                            viewModel = messageCenterViewModel,
                                            onOpenAutomation = { projectId ->
                                                // 消息 → 自动化：带项目作用域跳到自动化页（MSG-002 跳转目标）。
                                                if (projectId.isNotBlank()) {
                                                    automationsViewModel.setProject(projectId)
                                                }
                                                navController.navigate("automations") {
                                                    launchSingleTop = true
                                                }
                                            },
                                        )
                                    }
                                    appDestinations.filter { !it.implemented }.forEach { destination ->
                                        composable(destination.route) {
                                            PlaceholderScreen(
                                                labelRes = destination.labelRes,
                                                onOpenDrawer = { openLeftDrawer() },
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
                        val isWorkbench = currentRoute == "workbench"
                        LaunchedEffect(showRightDrawer, currentProjectId, isGame, isWorkbench, rightTab) {
                            if (isGame) {
                                // 打开控制台时刷新分支列表（切换 / 删除后与服务端对齐）。
                                gameViewModel.loadBranches()
                            } else if (isWorkbench) {
                                // 工作台：抽屉打开即重读项目树（会话运行状态也来自服务端）。
                                workbenchViewModel.refresh()
                            } else {
                                when (rightTab) {
                                    0 -> projectDirectoryViewModel.load(currentProjectId)
                                    1 -> changesViewModel.load(currentProjectId)
                                    2 -> versionsViewModel.load(currentProjectId)
                                    else -> chaptersViewModel.load(currentProjectId)
                                }
                            }
                        }
                        RightDrawer(
                            title = when {
                                isGame -> stringResource(R.string.right_drawer_console)
                                isWorkbench -> stringResource(R.string.workbench_projects)
                                else -> stringResource(R.string.right_drawer_project)
                            },
                            onDismiss = { showRightDrawer = false },
                        ) {
                            if (isGame) {
                                val gameLedger by gameViewModel.ledger.collectAsState()
                                val gameUi by gameViewModel.uiState.collectAsState()
                                val gameMedia by gameViewModel.media.collectAsState()
                                GameConsoleContent(
                                    ledger = gameLedger,
                                    turnAnchors = gameUi.turnAnchors,
                                    branchId = gameUi.branchId,
                                    branches = gameUi.branches,
                                    branchPlan = gameUi.branchPlan,
                                    branchBusy = gameUi.branchBusy,
                                    branchError = gameUi.branchError,
                                    turnCount = gameUi.turnCount,
                                    turnStart = gameUi.turnStart,
                                    branchCount = gameUi.branchCount,
                                    historyHasMore = gameUi.historyHasMore,
                                    loadingEarlier = gameUi.loadingEarlier,
                                    onRefresh = { gameViewModel.refreshSnapshot() },
                                    onLoadEarlier = { gameViewModel.loadEarlierHistory() },
                                    onSelectTurn = { gameViewModel.scrollToTurn(it) },
                                    onForkTurn = { gameViewModel.createBranchFromTurn(it, "") },
                                    onSwitchVersion = { turnId, direction ->
                                        gameViewModel.switchTurnVersion(turnId, direction)
                                    },
                                    onRegenerate = { gameViewModel.regenerateTurn(it) },
                                    onEditTurn = { gameViewModel.beginEditTurn(it) },
                                    onGenerateImage = { gameViewModel.generateImage(it) },
                                    imageTurnIds = gameMedia.images.keys,
                                    imageBusyTurnId = gameMedia.generatingTurnId,
                                    editingTurnId = gameUi.editingTurnId,
                                    editingNarrative = gameUi.editingTurnId?.let { gameViewModel.turnNarrative(it) },
                                    editingBusy = gameUi.editingBusy,
                                    editingError = gameUi.editingError,
                                    onSaveEdit = { turnId, text -> gameViewModel.saveTurnNarrative(turnId, text) },
                                    onCancelEdit = { gameViewModel.cancelEditTurn() },
                                    onSwitchBranch = { gameViewModel.switchBranch(it) },
                                    onDeleteBranch = { gameViewModel.deleteBranch(it) },
                                    onSavePlan = { gameViewModel.saveBranchPlan(it) },
                                )
                            } else if (isWorkbench) {
                                // 工作台项目 / 会话树（WB-001 / WB-002）：选中会话即开页签并收起抽屉。
                                val workbenchState by workbenchViewModel.state.collectAsState()
                                WorkbenchTreeContent(
                                    state = workbenchState,
                                    onRefresh = { workbenchViewModel.refresh() },
                                    onSelectProject = workbenchViewModel::selectProject,
                                    onToggleProject = workbenchViewModel::toggleProject,
                                    onOpenSession = { projectId, sessionId ->
                                        workbenchViewModel.openSession(projectId, sessionId)
                                        showRightDrawer = false
                                    },
                                    onCreateSession = { projectId ->
                                        workbenchViewModel.createSession(projectId)
                                        showRightDrawer = false
                                    },
                                    onRenameSession = workbenchViewModel::renameSession,
                                    onDeleteSession = { project, session ->
                                        workbenchViewModel.confirmDeleteSession(project.id, session)
                                    },
                                    onRenameProject = workbenchViewModel::renameProject,
                                    onArchiveProject = workbenchViewModel::confirmArchiveProject,
                                    onMoveProject = workbenchViewModel::moveProject,
                                    onAddFromHost = workbenchViewModel::addProjectFromHost,
                                    onCreateDirectory = { name, parent ->
                                        workbenchViewModel.createDirectoryProject(name, parent)
                                    },
                                    onDismissNotice = workbenchViewModel::clearNotice,
                                    onDismissConfirm = workbenchViewModel::dismissConfirm,
                                    onConfirmDeleteSession = workbenchViewModel::deleteSession,
                                    onConfirmArchiveProject = workbenchViewModel::archiveProject,
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

                    // NAV-009：抽屉开着时返回键只收起抽屉，不退出当前页面。
                    //
                    // 用 `OnBackPressedDispatcher.addCallback`（**不带** LifecycleOwner）在每次抽屉打开时
                    // 追加一个回调：分发器按「后注册优先」处理，这样才排在 NavHost 自己的返回回调之前。
                    // 早前用 `BackHandler` 写在 NavHost 之前/之后都会被导航图抢先，真机表现为
                    // 「抽屉开着按返回却退到了上一页，抽屉还留在上一页上」。
                    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                    DisposableEffect(backDispatcher, showRightDrawer, drawerState.isOpen) {
                        if (backDispatcher == null || (!showRightDrawer && !drawerState.isOpen)) {
                            onDispose { }
                        } else {
                            val callback = object : OnBackPressedCallback(true) {
                                override fun handleOnBackPressed() {
                                    closeTopLayer()
                                }
                            }
                            backDispatcher.addCallback(callback)
                            onDispose { callback.remove() }
                        }
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

/**
 * 系统手势排除带高度（NAV-008）：Android 对左右边缘排除区的单边上限是 200dp，
 * 超出部分会被系统截断，所以按上限声明一条中部的带。
 */
private val SYSTEM_GESTURE_EXCLUSION_HEIGHT = 200.dp
