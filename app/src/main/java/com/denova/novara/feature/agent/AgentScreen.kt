package com.denova.novara.feature.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.denova.novara.R
import com.denova.novara.core.stream.AgentPhase

/** 写作 Agent 舞台：输入 session/消息 → 挂接 → 展示正文/思考/活动/终态。 */
@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
    projectId: String = "",
    initialFile: String = "",
) {
    val state by viewModel.state.collectAsState()
    var sessionId by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }

    // 从编辑器进入时自动确保写作会话（无需手动输入 session ID）。
    LaunchedEffect(projectId, initialFile) {
        if (sessionId.isBlank()) {
            val ensured = viewModel.ensureSession()
            if (ensured != null) {
                sessionId = ensured
                viewModel.start(ensured)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            IconButton(onClick = onOpenDrawer) { Text("☰") }
            Text(stringResource(R.string.agent_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = sessionId,
                onValueChange = { sessionId = it },
                label = { Text("session ID") },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            OutlinedButton(onClick = { viewModel.start(sessionId.trim()) }) { Text("挂接") }
        }

        val phaseText = when (state.ui.phase) {
            AgentPhase.IDLE -> "空闲"
            AgentPhase.RUNNING -> "运行中"
            AgentPhase.DONE -> "完成"
            AgentPhase.ERROR -> "错误"
        }
        val activityText = if (state.ui.activity.isNotBlank()) " · ${state.ui.activity}" else ""
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text(
                "状态：$phaseText$activityText${if (state.connected) " · 已连接" else ""}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
        }
        if (state.ui.thinking.isNotBlank()) {
            Text(
                "思考：${state.ui.thinking}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        Text(
            text = state.ui.content.ifBlank { "输入 session ID 后挂接写作流" },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
        )

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("消息") },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            OutlinedButton(
                onClick = {
                    val refs = if (initialFile.isNotBlank()) listOf(initialFile) else emptyList()
                    viewModel.send(sessionId.trim(), input, refs)
                    input = ""
                },
                enabled = !state.sending && sessionId.isNotBlank() && input.isNotBlank(),
            ) { Text(if (state.sending) "发送中…" else "发送") }
        }
    }
}
