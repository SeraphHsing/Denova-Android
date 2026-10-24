package com.denova.novara

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.denova.novara.feature.terminal.TerminalScreen
import com.denova.novara.feature.terminal.TerminalViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!logcatInstalled) {
            logcatInstalled = true
            DenovaLog.addSink(LogcatSink())
        }
        // D03 恢复耗时埋点：进程冷启起点（与 login ok / attach / baseline / narrative-visible 对齐）
        DenovaLog.i("Perf", "process-start")
        setContent {
            MaterialTheme {
                val connectionViewModel: ConnectionViewModel = viewModel {
                    ConnectionViewModel(
                        checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]),
                    )
                }
                val editorViewModel: EditorViewModel = viewModel {
                    EditorViewModel(
                        checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]),
                    )
                }
                val gameViewModel: GameViewModel = viewModel {
                    GameViewModel(
                        checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]),
                    )
                }
                val agentViewModel: AgentViewModel = viewModel {
                    AgentViewModel(
                        checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]),
                    )
                }
                val terminalViewModel: TerminalViewModel = viewModel {
                    TerminalViewModel(
                        checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]),
                    )
                }
                val debugViewModel: DebugPanelViewModel = viewModel {
                    DebugPanelViewModel(
                        checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]),
                    )
                }
                val state by connectionViewModel.state.collectAsState()
                var showDebug by remember { mutableStateOf(false) }
                var showGame by remember { mutableStateOf(false) }
                var showAgent by remember { mutableStateOf(false) }
                var showTerminal by remember { mutableStateOf(false) }
                var terminalProjectId by remember { mutableStateOf("") }

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
                                }
                            }
                            when {
                                showAgent -> AgentScreen(viewModel = agentViewModel, onBack = { showAgent = false })
                                showGame -> GameScreen(viewModel = gameViewModel, onBack = { showGame = false })
                                showTerminal -> TerminalScreen(
                                    viewModel = terminalViewModel,
                                    initialProjectId = terminalProjectId,
                                    onBack = { showTerminal = false },
                                )
                                else -> EditorScreen(
                                    viewModel = editorViewModel,
                                    onOpenGame = { showGame = true },
                                    onOpenAgent = { showAgent = true },
                                    onOpenTerminal = { projectId ->
                                        terminalProjectId = projectId
                                        showTerminal = true
                                    },
                                    onLogout = { connectionViewModel.logout() },
                                )
                            }
                        }
                        else -> ConnectionScreen(connectionViewModel)
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
