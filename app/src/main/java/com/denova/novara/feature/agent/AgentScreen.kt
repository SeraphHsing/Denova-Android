package com.denova.novara.feature.agent

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.denova.novara.R
import com.denova.novara.core.stream.AgentApprovalLogic
import com.denova.novara.core.stream.AgentDataPart
import com.denova.novara.core.stream.AgentImagePart
import com.denova.novara.core.stream.AgentMessageView
import com.denova.novara.core.stream.AgentPartLogic
import com.denova.novara.core.stream.AgentReasoningPart
import com.denova.novara.core.stream.AgentTextPart
import com.denova.novara.core.stream.AgentToolPart
import com.denova.novara.core.stream.AgentUnknownPart
import com.denova.novara.core.stream.ToolCallStatus
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
/** 一条消息：角色标签 + 逐部件渲染（顺序与 parts 一致）。 */
@Composable
private fun AgentMessageRow(
    message: AgentMessageView,
    loadImage: suspend (AgentImagePart) -> ByteArray?,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            stringResource(if (message.isUser) R.string.agent_role_user else R.string.agent_role_assistant),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        message.parts.forEach { part ->
            when (part) {
                is AgentTextPart -> Text(part.text, style = MaterialTheme.typography.bodyMedium)
                is AgentReasoningPart -> AgentCollapsibleBlock(
                    title = stringResource(R.string.agent_part_reasoning),
                    body = part.text,
                )

                is AgentToolPart -> AgentToolCard(part, loadImage)
                is AgentImagePart -> AgentImageCard(part, loadImage)
                is AgentDataPart -> AgentDataRow(part)
                is AgentUnknownPart -> AgentCollapsibleBlock(
                    title = stringResource(R.string.agent_part_data) + " · " + part.type,
                    body = AgentPartLogic.prettyText(part.rawJson),
                )
            }
        }
    }
}

/** 可折叠文本块：推理与原始事件共用；默认收起，只显示一行摘要。 */
@Composable
private fun AgentCollapsibleBlock(title: String, body: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(if (expanded) R.string.agent_collapse else R.string.agent_expand),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Text(
            if (expanded) AgentPartLogic.prettyText(body) else body.lineSequence().firstOrNull().orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = if (expanded) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
        )
    }
}

/**
 * 工具卡片（AGT-001 的工具详情）：名称 + 状态 + 入参摘要；展开后看到格式化后的输入与输出。
 * 输出超过 1200 字符会截断并标注原长，不做二次摘要以免误读服务端结果。
 */
@Composable
private fun AgentToolCard(part: AgentToolPart, loadImage: suspend (AgentImagePart) -> ByteArray?) {
    var expanded by remember(part.id) { mutableStateOf(false) }
    val statusText = stringResource(
        when (part.status) {
            ToolCallStatus.RUNNING -> R.string.agent_tool_running
            ToolCallStatus.OK -> R.string.agent_tool_ok
            ToolCallStatus.FAIL -> R.string.agent_tool_failed
        },
    )
    val statusColor = when (part.status) {
        ToolCallStatus.FAIL -> MaterialTheme.colorScheme.error
        ToolCallStatus.RUNNING -> MaterialTheme.colorScheme.outline
        ToolCallStatus.OK -> MaterialTheme.colorScheme.primary
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.agent_part_tool) + " · " + part.name,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
        }
        Text(part.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        // 生成图工具的产物不在 file 部件里，直接把结果里的图片路径渲染出来。
        part.imagePaths.forEach { path ->
            AgentImageCard(
                part = AgentImagePart(
                    id = part.id + "@img:" + path,
                    url = path,
                    mediaType = "",
                    label = path.substringAfterLast('/'),
                ),
                loadImage = loadImage,
            )
        }
        if (expanded) {
            if (part.inputJson.isNotBlank()) {
                Text(
                    stringResource(R.string.agent_tool_input),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    AgentPartLogic.prettyText(part.inputJson),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (part.outputText.isNotBlank()) {
                Text(
                    stringResource(R.string.agent_tool_output),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    AgentPartLogic.prettyText(part.outputText),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (part.errorText.isNotBlank()) {
                Text(
                    stringResource(R.string.agent_tool_error),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(part.errorText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 图片部件：工作区路径经鉴权客户端取字节后本地解码；取不到时明确说不可用并给出路径。 */
@Composable
private fun AgentImageCard(part: AgentImagePart, loadImage: suspend (AgentImagePart) -> ByteArray?) {
    var bitmap by remember(part.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(part.id) { mutableStateOf(false) }
    LaunchedEffect(part.id) {
        val bytes = loadImage(part)
        val decoded = bytes?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        if (decoded == null) failed = true else bitmap = decoded
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            stringResource(R.string.agent_part_image) + " · " + part.label,
            style = MaterialTheme.typography.labelMedium,
        )
        val image = bitmap
        when {
            image != null -> Image(
                bitmap = image.asImageBitmap(),
                contentDescription = part.label,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                contentScale = ContentScale.FillWidth,
            )

            failed -> Text(
                stringResource(R.string.agent_image_missing, part.url.take(80)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )

            else -> Text(
                stringResource(R.string.agent_image_loading),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** `data-agent-*` 事件：一行摘要，展开看原始 JSON（用量、执行摘要、工作区变更等都走这里）。 */
@Composable
private fun AgentDataRow(part: AgentDataPart) {
    var expanded by remember(part.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                part.type.removePrefix("data-") + " · " + part.summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(if (expanded) R.string.agent_collapse else R.string.agent_expand),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (expanded) {
            Text(
                AgentPartLogic.prettyText(part.rawJson),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

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
                viewModel.start(ensured, projectId)
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
            OutlinedButton(onClick = { viewModel.start(sessionId.trim(), projectId) }) { Text("挂接") }
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

        // 完整消息部件（AGT-001）：历史按 part 渲染（文本 / 推理 / 工具 / 图片 / 事件），
        // 末尾再显示本轮流式正文。工具详情、推理与原始事件都可展开，图片按需取字节。
        val listState = rememberLazyListState()
        // 新消息或流式增量到达时贴底，避免用户每次手动往下滚（历史很长时尤其明显）。
        LaunchedEffect(state.messages.size, state.ui.content.length) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) listState.scrollToItem(total - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            if (state.historyLoading && state.messages.isEmpty()) {
                item(key = "history-loading") {
                    Text(
                        stringResource(R.string.agent_history_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (state.messages.isEmpty() && state.ui.content.isBlank()) {
                item(key = "history-empty") {
                    Text(
                        stringResource(R.string.agent_history_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            items(state.messages, key = { it.id }) { message ->
                AgentMessageRow(
                    message = message,
                    loadImage = { part -> viewModel.loadImage(part) },
                )
            }
            if (state.ui.content.isNotBlank()) {
                item(key = "live-tail") {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            stringResource(R.string.agent_live_tail),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(state.ui.content, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

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
