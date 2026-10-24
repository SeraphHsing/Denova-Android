package com.denova.novara

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
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
import com.denova.novara.feature.agent.AgentScreen
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
import com.denova.novara.feature.project.ProjectDirectoryViewModel
import com.denova.novara.feature.settings.SettingsViewModel
import com.denova.novara.feature.shell.AppDrawer
import com.denova.novara.feature.shell.GameConsoleContent
import com.denova.novara.feature.shell.PlaceholderScreen
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
                val debugViewModel: DebugPanelViewModel = viewModel(factory = container.viewModelFactory)
                val state by connectionViewModel.state.collectAsState()
                val projectState by projectDirectoryViewModel.state.collectAsState()
                var showDebug by remember { mutableStateOf(false) }
                var showRightDrawer by remember { mutableStateOf(false) }
                // 当前编辑项目：编辑器输入框同步，供右抽屉项目目录加载。
                var currentProjectId by remember { mutableStateOf("") }
                val navController = rememberNavController()
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

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
                                        ) { destination ->
                                            scope.launch { drawerState.close() }
                                            navController.navigate(destination.route) {
                                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    }
                                },
                            ) {
                                NavHost(
                                    navController = navController,
                                    startDestination = "editor",
                                ) {
                                    composable("editor") {
                                        EditorScreen(
                                            viewModel = editorViewModel,
                                            onOpenDrawer = { scope.launch { drawerState.open() } },
                                            onOpenRightDrawer = { showRightDrawer = true },
                                            onOpenTerminal = { projectId ->
                                                navController.navigate("terminal/$projectId")
                                            },
                                            onLogout = { connectionViewModel.logout() },
                                            onProjectIdChange = { currentProjectId = it },
                                        )
                                    }
                                    composable("game") {
                                        GameScreen(
                                            viewModel = gameViewModel,
                                            onOpenDrawer = { scope.launch { drawerState.open() } },
                                            onOpenRightDrawer = { showRightDrawer = true },
                                            onBack = { navController.popBackStack() },
                                        )
                                    }
                                    composable("agent") {
                                        AgentScreen(
                                            viewModel = agentViewModel,
                                            onOpenDrawer = { scope.launch { drawerState.open() } },
                                            onBack = { navController.popBackStack() },
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
                        LaunchedEffect(showRightDrawer, currentProjectId, isGame) {
                            if (!isGame) projectDirectoryViewModel.load(currentProjectId)
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
                                GameConsoleContent()
                            } else {
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
                                )
                            }
                        }
                    }

                    // 仅 debug 构建显示调试入口与面板。
                    if (BuildConfig.DEBUG) {
                        Button(
                            onClick = { showDebug = true },
                            shape = CircleShape,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        ) {
                            Text("Debug")
                        }
                    }
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
