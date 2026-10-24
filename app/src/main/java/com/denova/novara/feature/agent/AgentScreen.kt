package com.denova.novara.feature.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.denova.novara.R
import com.denova.novara.core.stream.AgentApprovalLogic
import com.denova.novara.core.stream.ApprovalMode
import com.denova.novara.core.stream.ApprovalReason
import com.denova.novara.core.stream.ApprovalRisk
import com.denova.novara.core.stream.AgentAskInteraction
import com.denova.novara.core.stream.AgentAskLogic
import com.denova.novara.core.stream.AgentAskQuestion
import com.denova.novara.core.stream.AgentPhase
import com.denova.novara.core.stream.ApprovalOption
import com.denova.novara.core.stream.ApprovalState
import com.denova.novara.feature.editor.TextSelectionQuote

/** 审批结果态文案（AGT-003）。 */
@Composable
private fun approvalStateText(state: ApprovalState): String = stringResource(
    when (state) {
        ApprovalState.WAITING -> R.string.approval_state_waiting
        ApprovalState.ALLOWED -> R.string.approval_state_allowed
        ApprovalState.ALLOWED_WORKSPACE -> R.string.approval_state_allowed_workspace
        ApprovalState.DENIED -> R.string.approval_state_denied
        ApprovalState.CANCELLED -> R.string.approval_state_cancelled
    },
)

/** 审批原因文案：未知 rule_id 一律用通用文案，不猜测具体原因。 */
@Composable
private fun approvalReasonText(reason: ApprovalReason): String = stringResource(
    when (reason) {
        ApprovalReason.WORKSPACE_UNAVAILABLE -> R.string.approval_reason_workspace_unavailable
        ApprovalReason.INVALID_WORKSPACE_BOUNDARY -> R.string.approval_reason_invalid_boundary
        ApprovalReason.BASH_PARSE_FAILED -> R.string.approval_reason_bash_parse_failed
        ApprovalReason.BASH_DYNAMIC_SYNTAX -> R.string.approval_reason_bash_dynamic
        ApprovalReason.BASH_UNLISTED_COMMAND -> R.string.approval_reason_bash_unlisted
        ApprovalReason.BASH_UNCLASSIFIED -> R.string.approval_reason_bash_unclassified
        ApprovalReason.PWSH_DYNAMIC_SYNTAX -> R.string.approval_reason_pwsh_dynamic
        ApprovalReason.PWSH_PARSE_FAILED -> R.string.approval_reason_pwsh_parse_failed
        ApprovalReason.PWSH_UNLISTED_COMMAND -> R.string.approval_reason_pwsh_unlisted
        ApprovalReason.BROWSER_ARGUMENTS_UNKNOWN -> R.string.approval_reason_browser_args
        ApprovalReason.BROWSER_REMOTE_MUTATION -> R.string.approval_reason_browser_mutation
        ApprovalReason.BROWSER_COMMAND_UNKNOWN -> R.string.approval_reason_browser_command
        ApprovalReason.BROWSER_ACTION_UNKNOWN -> R.string.approval_reason_browser_action
        ApprovalReason.BROWSER_NETWORK_ACCESS -> R.string.approval_reason_browser_network
        ApprovalReason.EXTERNAL_FILESYSTEM_READ -> R.string.approval_reason_external_fs
        ApprovalReason.GENERIC -> R.string.approval_reason_generic
    },
)

@Composable
private fun approvalRiskText(risk: ApprovalRisk): String = stringResource(
    when (risk) {
        ApprovalRisk.LOW -> R.string.approval_risk_low
        ApprovalRisk.MEDIUM -> R.string.approval_risk_medium
        ApprovalRisk.HIGH -> R.string.approval_risk_high
        ApprovalRisk.CRITICAL -> R.string.approval_risk_critical
        ApprovalRisk.UNKNOWN -> R.string.approval_risk_unknown
    },
)

@Composable
private fun approvalModeText(mode: ApprovalMode): String = stringResource(
    when (mode) {
        ApprovalMode.ASK -> R.string.approval_mode_ask
        ApprovalMode.WRITE -> R.string.approval_mode_write
        ApprovalMode.FULL_ACCESS -> R.string.approval_mode_full
        ApprovalMode.UNKNOWN -> R.string.approval_mode_unknown
    },
)

/**
 * 工具调用审批卡片（AGT-003）：展示审批内容（工具 / 模式 / 风险 / 目录 / 命令 / 原因），
 * 「始终允许」只在服务端 `can_remember` 且给出作用域时出现，作用域原文照抄服务端
 * `rule_display_pattern`，本地不构造规则。
 */
@Composable
private fun AgentApprovalCard(
    ask: AgentAskInteraction,
    submitting: Boolean,
    onDecide: (ApprovalOption) -> Unit,
    onCancel: () -> Unit,
) {
    val approval = ask.approval ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.approval_title),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                approvalStateText(AgentApprovalLogic.state(ask.status, null)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.approval_label_tool) + "：" + approval.toolName,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.approval_label_mode) + "：" + approvalModeText(AgentApprovalLogic.mode(approval.mode)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                approvalRiskText(AgentApprovalLogic.risk(approval.risk)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        approval.cwd?.takeIf { it.isNotBlank() }?.let {
            Text(
                stringResource(R.string.approval_label_cwd) + "：$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        AgentApprovalLogic.body(approval)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                    .padding(6.dp),
            )
        }
        Text(
            stringResource(R.string.approval_reason_label) + "：" + approvalReasonText(AgentApprovalLogic.reason(approval.ruleId)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (AgentApprovalLogic.canAlwaysAllow(approval)) {
            Text(
                stringResource(R.string.approval_scope, approval.ruleDisplayPattern),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onDecide(ApprovalOption.DENY) }, enabled = !submitting) {
                Text(stringResource(R.string.approval_deny))
            }
            if (AgentApprovalLogic.canAlwaysAllow(approval)) {
                OutlinedButton(
                    onClick = { onDecide(ApprovalOption.ALLOW_WORKSPACE) },
                    enabled = !submitting,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(stringResource(R.string.approval_allow_workspace)) }
            }
            OutlinedButton(
                onClick = { onDecide(ApprovalOption.ALLOW_ONCE) },
                enabled = !submitting,
                modifier = Modifier.padding(start = 8.dp),
            ) { Text(stringResource(R.string.approval_allow_once)) }
            TextButton(onClick = onCancel, enabled = !submitting) {
                Text(stringResource(R.string.ask_cancel))
            }
        }
    }
}

/**
 * 提问卡片（AGT-004）：逐题列出服务端选项，选中才能提交；取消按固定原因上报。
 * 选项文案、推荐项与新选项全部来自服务端问句，本地不构造问题。
 */
@Composable
private fun AgentAskCard(
    ask: AgentAskInteraction,
    selected: Map<String, List<String>>,
    submitting: Boolean,
    onToggle: (AgentAskQuestion, String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        ask.questions.forEach { question ->
            Text(question.question, style = MaterialTheme.typography.bodyMedium)
            val picked = selected[question.id].orEmpty()
            question.options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !submitting) { onToggle(question, option.id) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (option.id in picked) "●" else "○", modifier = Modifier.padding(end = 8.dp))
                    Column {
                        Text(
                            option.label + if (option.id == question.recommendedOptionId) "（推荐）" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        option.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
        Row(modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(onClick = onSubmit, enabled = !submitting) {
                Text(stringResource(if (submitting) R.string.ask_submitting else R.string.ask_submit))
            }
            OutlinedButton(
                onClick = onCancel,
                enabled = !submitting,
                modifier = Modifier.padding(start = 8.dp),
            ) { Text(stringResource(R.string.ask_cancel)) }
        }
    }
}

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
    val askSelections by viewModel.askSelections.collectAsState()
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

        // 提问交互（AGT-004）：待答卡片 + 已解决状态。选项与答案来自服务端问句，本地只记录选择。
        state.pendingAsks.forEach { ask ->
            if (ask.isToolApproval) {
                AgentApprovalCard(
                    ask = ask,
                    submitting = state.askSubmitting,
                    onDecide = { option -> viewModel.approveAsk(ask.id, option) },
                    onCancel = { viewModel.cancelAsk(ask.id) },
                )
            } else {
                AgentAskCard(
                    ask = ask,
                    selected = askSelections[ask.id].orEmpty(),
                    submitting = state.askSubmitting,
                    onToggle = { question, optionId -> viewModel.toggleAskOption(ask.id, question, optionId) },
                    onSubmit = { viewModel.answerAsk(ask.id) },
                    onCancel = { viewModel.cancelAsk(ask.id) },
                )
            }
        }
        state.askError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        state.resolvedAsks.forEach { ask ->
            val text = if (ask.isToolApproval) {
                approvalStateText(
                    AgentApprovalLogic.state(
                        ask.status,
                        ask.answers.firstOrNull()?.selectedOptions?.firstOrNull()?.id,
                    ),
                )
            } else {
                AgentAskLogic.statusText(ask)
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

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
