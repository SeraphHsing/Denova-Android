package com.denova.novara.feature.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.denova.novara.feature.editor.TextSelectionQuote

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
    val selections by viewModel.selections.collectAsState()
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

        // 暂停 / 停止（AGT-005）：只有服务端投影给出当前操作身份时才可点；
        // 命令提交中禁用避免重复下发，停止请求后显示「正在停止…」等待最终状态。
        val canControl = state.activeOperationId.isNotBlank()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { viewModel.pause() },
                enabled = canControl && !state.commandSubmitting,
                modifier = Modifier.padding(end = 8.dp),
            ) { Text(stringResource(R.string.agent_pause)) }
            OutlinedButton(
                onClick = { viewModel.abort() },
                enabled = canControl && !state.commandSubmitting,
            ) {
                Text(stringResource(if (state.abortPending) R.string.agent_stopping else R.string.agent_stop))
            }
            val phaseLabel = when (state.runtimePhase) {
                "suspended" -> stringResource(R.string.agent_suspended)
                "" -> ""
                else -> state.runtimePhase
            }
            if (phaseLabel.isNotBlank()) {
                Text(
                    phaseLabel,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
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

        // 选区引用卡（WRI-009）：`file:Lx-Ly 内容前 30 字…`，可逐条移除。
        if (selections.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            ) {
                selections.forEachIndexed { index, selection ->
                    Row(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                            )
                            .clickable { viewModel.removeSelection(index) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "${TextSelectionQuote.label(selection)} ${TextSelectionQuote.excerpt(selection)} ×",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

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
                    viewModel.send(sessionId.trim(), input, refs, selections)
                    input = ""
                },
                enabled = !state.sending && sessionId.isNotBlank() && input.isNotBlank(),
            ) { Text(if (state.sending) "发送中…" else "发送") }
        }
    }
}
