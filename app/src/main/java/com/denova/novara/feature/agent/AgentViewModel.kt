package com.denova.novara.feature.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.command.CommandRecord
import com.denova.novara.core.command.CommandStatus
import com.denova.novara.NovaraApplication
import com.denova.novara.core.command.CommandStore
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.AgentCommandReceipt
import com.denova.novara.core.network.AgentCommandRequest
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.persistence.PreferencesStore
import com.denova.novara.core.network.SendChatRequest
import com.denova.novara.core.network.TextSelectionRef
import com.denova.novara.core.stream.AgentActiveView
import com.denova.novara.core.stream.AgentApprovalLogic
import com.denova.novara.core.stream.AgentImagePart
import com.denova.novara.core.stream.AgentMessagesPage
import com.denova.novara.core.stream.AgentMessageView
import com.denova.novara.core.stream.AgentPartLogic
import com.denova.novara.core.stream.AgentToolPart
import com.denova.novara.core.stream.AgentAskInteraction
import com.denova.novara.core.stream.AgentAskLogic
import com.denova.novara.core.stream.AgentAskQuestion
import com.denova.novara.core.stream.AgentAskResolution
import com.denova.novara.core.stream.AgentUiChunk
import com.denova.novara.core.stream.ApprovalOption
import com.denova.novara.core.stream.AgentPhase
import com.denova.novara.core.stream.AgentStreamRecoveryCoordinator
import com.denova.novara.core.stream.AgentUiFrame
import com.denova.novara.core.stream.AgentUiReducer
import com.denova.novara.core.stream.AgentUiState
import com.denova.novara.core.stream.GameStreamError
import com.denova.novara.core.stream.buildAgentHistoryNarrative
import com.denova.novara.core.stream.collectWithIdleGuard
import com.denova.novara.core.stream.completesTurn
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class AgentScreenState(
    val sessionId: String = "",
    val ui: AgentUiState = AgentUiState(),
    val sending: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
    /** 暂停 / 停止命令提交中（按钮禁用，避免重复下发）。 */
    val commandSubmitting: Boolean = false,
    /** 服务端 active 投影里的当前操作身份；空表示没有可控操作（AGT-005 门控依据）。 */
    val activeOperationId: String = "",
    /** 服务端 runtime phase（`running` / `suspended` / `idle` …）。 */
    val runtimePhase: String = "",
    /** 已请求停止，等待服务端给出最终状态。 */
    val abortPending: Boolean = false,
    /** 待回答的提问（AGT-004）：来自流 `data-ask` 或 active 投影 `pending_asks`。 */
    val pendingAsks: List<AgentAskInteraction> = emptyList(),
    /** 已解决的提问，用于展示「已回答 / 已取消」与答案文案。 */
    val resolvedAsks: List<AgentAskInteraction> = emptyList(),
    /** 答案 / 取消提交中。 */
    val askSubmitting: Boolean = false,
    val askError: String? = null,
    /** 完整消息部件（AGT-001）：canonical 历史按 part 保留，界面据此渲染文本/推理/工具/图片。 */
    val messages: List<AgentMessageView> = emptyList(),
    val historyLoading: Boolean = false,
    val historyError: String? = null,
    /** 当前项目作用域：图片部件走 `/files/asset` 需要它。 */
    val projectId: String = "",
    /** 发送结果不明（AGT-008）：命令 id、原文与发送前的最后一条用户消息 id，用于向服务端核对。 */
    val uncertainCommandId: String = "",
    val uncertainMessage: String = "",
    val uncertainBaselineId: String = "",
    val reconciling: Boolean = false,
    /** 核对结论文案（已确认收到 / 服务端未收到）。 */
    val reconcileNotice: String = "",
)

/** 写作 Agent ViewModel：绑定 client → 恢复协调器 → 投影；发送先落命令事务再 POST。 */
class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val json = Json { ignoreUnknownKeys = true }
    private val commands = CommandStore((application as NovaraApplication).container.database.commandDao())
    private val prefs = PreferencesStore(application)
    private var client: DenovaClient? = null
    private var coordinator: AgentStreamRecoveryCoordinator? = null
    private var streamJob: Job? = null
    private var sendJob: Job? = null

    private val _state = MutableStateFlow(AgentScreenState())
    val state: StateFlow<AgentScreenState> = _state.asStateFlow()

    /** 待发送的正文选区引用（WRI-009）：编辑器「引用到 AI」写入，随下一条消息提交。 */
    private val _selections = MutableStateFlow<List<TextSelectionRef>>(emptyList())
    val selections: StateFlow<List<TextSelectionRef>> = _selections.asStateFlow()

    /** 提问交互的选项选择：askId → (questionId → 选中项)。提问被回答/取消后清理。 */
    private val _askSelections = MutableStateFlow<Map<String, Map<String, List<String>>>>(emptyMap())
    val askSelections: StateFlow<Map<String, Map<String, List<String>>>> = _askSelections.asStateFlow()

    fun bindClient(client: DenovaClient) {
        this.client = client
        this.coordinator = AgentStreamRecoveryCoordinator(client, reloadCanonical = ::reloadCanonical)
    }

    /**
     * 确保有可用的写作会话：优先当前 active 会话，否则首个会话，都没有则新建。
     * 返回 session id；失败返回 null。
     */
    suspend fun ensureSession(): String? {
        val c = client ?: return null
        return try {
            val sessions = c.sessionList()
            val chosen = sessions.firstOrNull { it.active } ?: sessions.firstOrNull()
            if (chosen != null) {
                DenovaLog.i("Agent", "ensureSession picked=${chosen.id} active=${chosen.active}")
                chosen.id
            } else {
                val created = c.createSession()
                DenovaLog.i("Agent", "ensureSession created=${created.id}")
                created.id
            }
        } catch (e: Exception) {
            DenovaLog.w("Agent", "ensureSession failed", e)
            _state.value = _state.value.copy(error = e.message)
            null
        }
    }

    /** 上次挂接的会话 id：进程被杀后据此自动回到同一会话（服务端 active 仍是任务事实源）。 */
    suspend fun savedSessionId(): String = prefs.lastAgentSession()

    /** 挂接 session 的活动 Agent 流。 */
    fun start(sessionId: String, projectId: String = "") {
        if (coordinator == null) return
        if (sessionId.isBlank()) return
        DenovaLog.i("Agent", "start session=$sessionId project=${projectId.take(12)}")
        viewModelScope.launch { prefs.setLastAgentSession(sessionId) }
        // 项目作用域随会话一起带入：图片部件要用它拼 `/files/asset`。
        _state.value = AgentScreenState(sessionId = sessionId, projectId = projectId)
        streamJob?.cancel()
        sendJob?.cancel()
        streamJob = viewModelScope.launch {
            try {
                // 先加载 canonical 历史作为基线，再挂接流补充增量。
                reloadCanonical()
                attachLoop(sessionId)
            } catch (e: Exception) {
                DenovaLog.w("Agent", "recover failed", e)
                _state.value = _state.value.copy(error = e.message)
            }
            finishStream()
        }
    }

    /**
     * 挂接流：先同步一次 active（暂停 / 停止按钮只对「当前确实存在的操作」可用，AGT-005），
     * 再交给恢复协调器（active → 挂接 → 断线重挂）。
     * [start] 与「发送被截断后核对已落地」共用，避免后者为同一目标再走一次状态重置。
     */
    private suspend fun attachLoop(sessionId: String) {
        val c = coordinator ?: return
        refreshActive()
        c.recover(sessionId).collect { onFrame(it) }
    }

    /** 追加一段正文引用；同一文件同一区间同一内容只保留一条。 */
    fun addSelection(selection: TextSelectionRef) {
        val current = _selections.value
        if (current.any { it == selection }) return
        _selections.value = current + selection
        DenovaLog.i(
            "Agent",
            "selection added file=${selection.fileName} lines=${selection.startLine}-${selection.endLine} chars=${selection.content.length}",
        )
    }

    fun removeSelection(index: Int) {
        val current = _selections.value
        if (index !in current.indices) return
        _selections.value = current.filterIndexed { i, _ -> i != index }
        DenovaLog.i("Agent", "selection removed index=$index left=${_selections.value.size}")
    }

    /** 发送写作输入：先落本地事务（SUBMITTING）再 POST /api/chat（可带引用文件与正文选区）。 */
    fun send(
        sessionId: String,
        message: String,
        references: List<String> = emptyList(),
        selections: List<TextSelectionRef> = emptyList(),
    ) {
        val client = client ?: return
        val text = message.trim()
        if (text.isEmpty() || sessionId.isBlank()) return
        val commandId = UUID.randomUUID().toString()
        val request = SendChatRequest(
            commandId = commandId,
            sessionId = sessionId,
            message = text,
            references = references,
            selections = selections,
        )
        val record = CommandRecord(
            commandId = commandId,
            target = "chat:$sessionId",
            payload = json.encodeToString(SendChatRequest.serializer(), request),
            status = CommandStatus.SUBMITTING,
            createdAt = System.currentTimeMillis(),
        )
        DenovaLog.i(
            "Agent",
            "send session=$sessionId cmd=$commandId refs=${references.size} selections=${selections.size}",
        )
        streamJob?.cancel()
        streamJob = null
        sendJob?.cancel()
        // 记下发送前的最后一条用户消息：回包丢失时用它判断「服务端到底有没有收到这条命令」。
        val baseline = _state.value.messages.lastOrNull { it.isUser }?.id.orEmpty()
        _state.value = _state.value.copy(
            sessionId = sessionId,
            sending = true,
            error = null,
            // 上一轮的错误原因不能挂在新的这一轮上。
            ui = _state.value.ui.copy(errorText = null),
            reconcileNotice = "",
            uncertainCommandId = "",
            uncertainMessage = "",
        )
        sendJob = viewModelScope.launch {
            commands.save(record)
            try {
                var accepted = false
                var completed = false
                val idleTerminated = client.postChat(request).collectWithIdleGuard(
                    isTaskFinished = {
                        try {
                            !client.agentActive(sessionId).active
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            true
                        }
                    },
                    onEvent = { frame ->
                        if (frame.completesTurn()) completed = true
                        if (!accepted) {
                            accepted = true
                            commands.updateStatus(commandId, CommandStatus.ACCEPTED)
                            _state.value = _state.value.copy(sending = false, abortPending = false)
                            // 服务端已接受：引用的使命结束；失败时不解释放，便于原样重发。
                            _selections.value = emptyList()
                            // 新操作开始后才有可暂停 / 停止的目标，异步刷新一次按钮门控。
                            viewModelScope.launch { refreshActive() }
                        }
                        onFrame(frame)
                    },
                )
                // 读流干净结束但没有完成标记（AGT-009）：FIN 也可能是被切断，此时既不能判成功
                // 也不能重发——按「结果不明」核对，服务端收到就挂接补结果。
                if (!completed && !idleTerminated && !_state.value.abortPending) {
                    commands.updateStatus(commandId, CommandStatus.UNCERTAIN)
                    DenovaLog.w("Agent", "stream ended without completion marker; reconcile instead of resend")
                    setUncertain(commandId, text, baseline)
                    // 在本 job 内核对并接管：核对已落地就直接挂接补结果。
                    // 核对用的 canonical 页同时用于重建正文，因此不再二次全量拉取、也不重置页面状态。
                    if (reconcile(client, sessionId, commandId, text, baseline) is ReconcileOutcome.Landed) {
                        attachLoop(sessionId)
                    }
                } else {
                    if (!accepted) {
                        commands.updateStatus(commandId, CommandStatus.ACCEPTED)
                        _state.value = _state.value.copy(sending = false)
                        _selections.value = emptyList()
                    }
                    if (idleTerminated) {
                        DenovaLog.w("Agent", "stream idle timeout; task finished")
                        _state.value = _state.value.copy(error = "流空闲超时，任务可能已结束")
                    }
                }
            } catch (e: GameStreamError) {
                val rejected = e.status in 400..499 && e.code != null && e.code != "agent_runtime.stream_attached"
                commands.updateStatus(commandId, if (rejected) CommandStatus.REJECTED else CommandStatus.UNCERTAIN)
                DenovaLog.w("Agent", "send failed code=${e.code} status=${e.status}")
                _state.value = _state.value.copy(error = e.message)
                if (!rejected) markUncertain(commandId, text, baseline)
            } catch (e: Exception) {
                commands.updateStatus(commandId, CommandStatus.UNCERTAIN)
                DenovaLog.w("Agent", "send uncertain", e)
                _state.value = _state.value.copy(error = e.message)
                markUncertain(commandId, text, baseline)
            }
            finishStream()
        }
    }

    /**
     * 回包丢失（AGT-008）：**不重发**，标记不确定并立刻向服务端核对。
     * 新命令会带来重复回答，因此核对只用历史 + active 两个只读接口。
     */
    private fun markUncertain(commandId: String, message: String, baselineId: String) {
        setUncertain(commandId, message, baselineId)
        reconcileSend()
    }

    /** 只标记「结果不明」，由调用方决定随后的核对方式（就地等待或后台发起）。 */
    private fun setUncertain(commandId: String, message: String, baselineId: String) {
        _state.value = _state.value.copy(
            sending = false,
            uncertainCommandId = commandId,
            uncertainMessage = message,
            uncertainBaselineId = baselineId,
            error = null,
            reconcileNotice = "发送结果不明，正在核对…",
        )
    }

    /** 核对结论：已落地（带 canonical 页面）/ 未落地 / 核对失败。 */
    private sealed interface ReconcileOutcome {
        data class Landed(val page: AgentMessagesPage) : ReconcileOutcome
        data object NotLanded : ReconcileOutcome
        data class Failed(val message: String) : ReconcileOutcome
    }

    /** 用户点「重新核对」：后台核对，落地则挂接补结果。 */
    fun reconcileSend() {
        val c = client ?: return
        val s = _state.value
        if (s.uncertainCommandId.isBlank() || s.reconciling) return
        viewModelScope.launch {
            val outcome = reconcile(c, s.sessionId, s.uncertainCommandId, s.uncertainMessage, s.uncertainBaselineId)
            if (outcome is ReconcileOutcome.Landed) {
                attachLoop(s.sessionId)
                finishStream()
            }
        }
    }

    /**
     * 核对「这条命令到底有没有落到服务端」：以服务端 canonical 历史为准。
     * 找到发送前最后一条用户消息之后、正文与本次相同的用户消息，即视为已收到。
     *
     * 核对按 canonical 页大小拉一次历史，判定落地与重建正文**共用这一次响应**：
     * 落地后直接挂接，不再为了挂接二次全量拉取（此前是 limit=20 判定 + limit=50 重建）。
     */
    private suspend fun reconcile(
        client: DenovaClient,
        sessionId: String,
        commandId: String,
        message: String,
        baselineId: String,
    ): ReconcileOutcome {
        _state.value = _state.value.copy(reconciling = true, reconcileNotice = "正在向服务端核对…")
        return try {
            val page = client.sessionMessages(sessionId, limit = CANONICAL_LIMIT)
            if (AgentPartLogic.hasLandedMessage(page.messages, baselineId, message.trim())) {
                commands.updateStatus(commandId, CommandStatus.ACCEPTED)
                DenovaLog.i("Agent", "reconcile landed cmd=$commandId; resume without refetch")
                // 用这一次响应重建正文（清空流式尾块避免与历史重复），挂接交给调用方。
                applyCanonical(page)
                _state.value = _state.value.copy(
                    sending = false,
                    reconciling = false,
                    uncertainCommandId = "",
                    uncertainMessage = "",
                    uncertainBaselineId = "",
                    reconcileNotice = "",
                )
                ReconcileOutcome.Landed(page)
            } else {
                DenovaLog.w("Agent", "reconcile not landed cmd=$commandId")
                _state.value = _state.value.copy(
                    reconciling = false,
                    reconcileNotice = "服务端未收到该命令；为避免重复回答不会自动重发，请手动重发",
                )
                ReconcileOutcome.NotLanded
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DenovaLog.w("Agent", "reconcile failed cmd=$commandId", e)
            _state.value = _state.value.copy(
                reconciling = false,
                reconcileNotice = "核对失败：${e.message ?: "网络错误"}",
            )
            ReconcileOutcome.Failed(e.message ?: "网络错误")
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        sendJob?.cancel()
        sendJob = null
        _state.value = _state.value.copy(connected = false)
    }

    /**
     * 刷新 active 投影：暂停 / 停止按钮的门控与命令目标身份都以它为准。
     * 失败只记日志，不清空已有身份（网络抖动不该让按钮消失）。
     */
    suspend fun refreshActive(): AgentActiveView? {
        val client = client ?: return null
        val sessionId = _state.value.sessionId.trim()
        if (sessionId.isBlank()) return null
        return try {
            val view = client.agentActive(sessionId)
            val operationId = AgentCommands.activeOperationId(view)
            val state = _state.value
            _state.value = state.copy(
                activeOperationId = operationId,
                runtimePhase = view.phase.orEmpty(),
                // 服务端不再报告当前操作即说明停止已落地：清掉「正在停止…」，否则按钮文案会一直挂着。
                abortPending = state.abortPending && operationId.isNotEmpty(),
                // 页面恢复（切页返回 / 进程重建）：以服务端 pending_asks 为准重新露出待答卡片；
                // 投影暂时为空时保留本地卡片，避免刚由流投递、投影尚未落库的提问被抹掉。
                pendingAsks = if (view.pendingAsks.isNotEmpty()) {
                    view.pendingAsks
                } else {
                    state.pendingAsks
                },
            )
            view
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DenovaLog.w("Agent", "refresh active failed", e)
            null
        }
    }

    /** 暂停当前操作（`suspend`）。 */
    fun pause() = submitControl(AgentCommands.TYPE_SUSPEND)

    /** 停止当前操作（`abort` + `reason=user_requested`）。 */
    fun abort() = submitControl(AgentCommands.TYPE_ABORT)

    /**
     * 下发暂停 / 停止命令：先读 active 拿到当前操作身份，再落本地事务（SUBMITTING）后 POST。
     * 目标身份取不到时不发命令（服务端同样会拒绝空目标）；失败按错误码区分提示，且不自动重发。
     */
    private fun submitControl(type: String) {
        val client = client ?: return
        val sessionId = _state.value.sessionId.trim()
        if (sessionId.isBlank() || _state.value.commandSubmitting) return
        _state.value = _state.value.copy(commandSubmitting = true, error = null)
        var commandId: String? = null
        viewModelScope.launch {
            try {
                val view = refreshActive()
                val operationId = AgentCommands.activeOperationId(view)
                if (operationId.isEmpty()) {
                    DenovaLog.w("Agent", "control skipped type=$type reason=no active operation")
                    _state.value = _state.value.copy(
                        commandSubmitting = false,
                        error = AgentCommands.errorMessage("agent_runtime.invalid_phase"),
                    )
                    return@launch
                }
                val id = UUID.randomUUID().toString()
                commandId = id
                val request = if (type == AgentCommands.TYPE_ABORT) {
                    AgentCommands.abort(id, sessionId, operationId)
                } else {
                    AgentCommands.suspend(id, sessionId, operationId)
                }
                commands.save(
                    CommandRecord(
                        commandId = id,
                        target = "chat:$sessionId",
                        payload = json.encodeToString(AgentCommandRequest.serializer(), request),
                        status = CommandStatus.SUBMITTING,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                DenovaLog.i("Agent", "control type=$type session=$sessionId cmd=$id target=$operationId")
                val receipt: AgentCommandReceipt = client.submitAgentCommand(request)
                commands.updateStatus(id, CommandStatus.ACCEPTED)
                if (type == AgentCommands.TYPE_ABORT) {
                    _state.value = _state.value.copy(abortPending = true)
                }
                DenovaLog.i(
                    "Agent",
                    "control accepted type=$type op=${receipt.operationId} cursor=${receipt.cursor}",
                )
                refreshActive()
            } catch (e: DenovaApiException) {
                val rejected = e.status in 400..499
                commandId?.let { commands.updateStatus(it, if (rejected) CommandStatus.REJECTED else CommandStatus.UNCERTAIN) }
                DenovaLog.w("Agent", "control failed type=$type code=${e.code} status=${e.status}")
                _state.value = _state.value.copy(error = AgentCommands.errorMessage(e.code, e.message))
                // 目标已消失 / 已换代：立刻按服务端投影纠正按钮状态，不重发。
                if (AgentCommands.isTargetGone(e.code)) refreshActive()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                commandId?.let { commands.updateStatus(it, CommandStatus.UNCERTAIN) }
                DenovaLog.w("Agent", "control uncertain type=$type", e)
                _state.value = _state.value.copy(error = e.message ?: "操作失败")
            } finally {
                _state.value = _state.value.copy(commandSubmitting = false)
            }
        }
    }

    /** 切换某个问题的选项：单选覆盖、多选切换（纯逻辑在 [AgentAskLogic.toggle]）。 */
    fun toggleAskOption(askId: String, question: AgentAskQuestion, optionId: String) {
        val current = _askSelections.value[askId].orEmpty()
        val updated = current.toMutableMap().apply {
            this[question.id] = AgentAskLogic.toggle(question, current[question.id].orEmpty(), optionId)
        }
        _askSelections.value = _askSelections.value + (askId to updated)
    }

    /**
     * 审批工具调用（AGT-003）：`allow-once` / `allow-workspace` / `deny` 走同一个 answer 端点，
     * 答案体固定 `question_id=tool-approval`。选择「始终允许」时服务端会落一条工作区规则，
     * 客户端不自行记忆、不改写规则内容。
     */
    fun approveAsk(askId: String, option: ApprovalOption) {
        val client = client ?: return
        val sessionId = _state.value.sessionId.trim()
        val ask = _state.value.pendingAsks.firstOrNull { it.id == askId } ?: return
        if (!ask.isToolApproval) {
            DenovaLog.w("Agent", "approve ignored: not a tool approval id=$askId kind=${ask.kind}")
            return
        }
        if (sessionId.isBlank() || _state.value.askSubmitting) return
        if (option == ApprovalOption.ALLOW_WORKSPACE && !AgentApprovalLogic.canAlwaysAllow(ask.approval)) {
            // 服务端没有允许记忆时不发这个选项，避免请求被拒后状态不明。
            DenovaLog.w("Agent", "approve ignored: workspace scope not allowed id=$askId")
            _state.value = _state.value.copy(askError = "服务端未允许记住该规则")
            return
        }
        _state.value = _state.value.copy(askSubmitting = true, askError = null)
        viewModelScope.launch {
            try {
                val answers = listOf(AgentApprovalLogic.answer(option))
                val resolution = client.answerSessionAsk(sessionId, askId, answers)
                DenovaLog.i(
                    "Agent",
                    "approval resolved id=$askId option=${option.wireId} status=${resolution.status}",
                )
                applyAskResolution(ask, resolution)
            } catch (e: DenovaApiException) {
                DenovaLog.w("Agent", "approval failed id=$askId code=${e.code} status=${e.status}")
                _state.value = _state.value.copy(askError = AgentAskLogic.askError(e.code, e.message))
                if (AgentAskLogic.needsRefresh(e.code)) refreshActive()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Agent", "approval uncertain id=$askId", e)
                _state.value = _state.value.copy(askError = e.message ?: "审批提交失败")
            } finally {
                _state.value = _state.value.copy(askSubmitting = false)
            }
        }
    }

    /** 回答提问：答案体只包含已选问题，成功后用服务端返回的 resolution 落状态并刷新投影。 */
    fun answerAsk(askId: String) {
        val client = client ?: return
        val sessionId = _state.value.sessionId.trim()
        val ask = _state.value.pendingAsks.firstOrNull { it.id == askId } ?: return
        if (sessionId.isBlank() || _state.value.askSubmitting) return
        val selected = _askSelections.value[askId].orEmpty()
        if (!AgentAskLogic.canSubmit(ask, selected)) {
            _state.value = _state.value.copy(askError = "每个问题都要选择一个选项")
            return
        }
        val answers = AgentAskLogic.answers(ask, selected)
        _state.value = _state.value.copy(askSubmitting = true, askError = null)
        viewModelScope.launch {
            try {
                val resolution: AgentAskResolution = client.answerSessionAsk(sessionId, askId, answers)
                DenovaLog.i("Agent", "ask answered id=$askId status=${resolution.status} answers=${answers.size}")
                applyAskResolution(ask, resolution)
            } catch (e: DenovaApiException) {
                DenovaLog.w("Agent", "ask answer failed id=$askId code=${e.code} status=${e.status}")
                _state.value = _state.value.copy(askError = AgentAskLogic.askError(e.code, e.message))
                // 已过期 / 不存在：本地卡片不再可信，用投影纠正而不是重发同一答案。
                if (AgentAskLogic.needsRefresh(e.code)) refreshActive()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Agent", "ask answer uncertain id=$askId", e)
                _state.value = _state.value.copy(askError = e.message ?: "回答失败")
            } finally {
                _state.value = _state.value.copy(askSubmitting = false)
            }
        }
    }

    /** 取消提问：原因固定 `user_cancelled`（与 Web 默认值一致）。 */
    fun cancelAsk(askId: String) {
        val client = client ?: return
        val sessionId = _state.value.sessionId.trim()
        val ask = _state.value.pendingAsks.firstOrNull { it.id == askId } ?: return
        if (sessionId.isBlank() || _state.value.askSubmitting) return
        _state.value = _state.value.copy(askSubmitting = true, askError = null)
        viewModelScope.launch {
            try {
                val resolution = client.cancelSessionAsk(sessionId, askId)
                DenovaLog.i("Agent", "ask cancelled id=$askId status=${resolution.status} reason=${resolution.cancelReason}")
                applyAskResolution(ask, resolution)
            } catch (e: DenovaApiException) {
                DenovaLog.w("Agent", "ask cancel failed id=$askId code=${e.code} status=${e.status}")
                _state.value = _state.value.copy(askError = AgentAskLogic.askError(e.code, e.message))
                if (AgentAskLogic.needsRefresh(e.code)) refreshActive()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Agent", "ask cancel uncertain id=$askId", e)
                _state.value = _state.value.copy(askError = e.message ?: "取消失败")
            } finally {
                _state.value = _state.value.copy(askSubmitting = false)
            }
        }
    }

    /** 落定一次回答/取消：从待答列表移除、记入已解决，并以 active 投影校正。 */
    private fun applyAskResolution(ask: AgentAskInteraction, resolution: AgentAskResolution) {
        val resolved = AgentAskLogic.applyResolution(ask, resolution)
        val state = _state.value
        _state.value = state.copy(
            pendingAsks = state.pendingAsks.filterNot { it.id == ask.id },
            resolvedAsks = (state.resolvedAsks.filterNot { it.id == ask.id } + resolved).takeLast(5),
        )
        _askSelections.value = _askSelections.value - ask.id
        viewModelScope.launch { refreshActive() }
    }

    /** 流里的 `data-ask` 部件：待答则入列，已解决则移出并记状态。 */
    private fun handleAskChunk(chunk: AgentUiChunk) {
        if (chunk.type != ASK_CHUNK_TYPE) return
        val data = chunk.data ?: return
        val ask = runCatching {
            json.decodeFromString(AgentAskInteraction.serializer(), data.toString())
        }.getOrNull()
        if (ask == null || ask.id.isBlank()) {
            DenovaLog.w("Agent", "ask chunk ignored: unparsable")
            return
        }
        val state = _state.value
        val previous = state.pendingAsks.firstOrNull { it.id == ask.id }
            ?: state.resolvedAsks.firstOrNull { it.id == ask.id }
        _state.value = if (ask.isPending) {
            DenovaLog.i("Agent", "ask pending id=${ask.id} questions=${ask.questions.size}")
            state.copy(
                pendingAsks = state.pendingAsks.filterNot { it.id == ask.id } + ask,
                resolvedAsks = state.resolvedAsks.filterNot { it.id == ask.id },
            )
        } else {
            DenovaLog.i("Agent", "ask resolved id=${ask.id} status=${ask.status}")
            // 流里的 resolved 部件可能缺答案/原因，与本地已有详情合并，避免界面丢掉已答内容。
            val merged = AgentAskLogic.merge(previous, ask)
            state.copy(
                pendingAsks = state.pendingAsks.filterNot { it.id == ask.id },
                resolvedAsks = (state.resolvedAsks.filterNot { it.id == ask.id } + merged).takeLast(5),
            )
        }
    }

    /**
     * 从 canonical 消息历史重建部件视图（AGT-001）：保留每条消息的完整 parts，
     * 不再压平成一段文本——文本/推理/工具/图片各自可访问，部件身份由「消息 id#序号」稳定给出。
     * 流式正文清空，避免与历史里的同一段内容重复显示。
     */
    private suspend fun reloadCanonical() {
        val client = client ?: return
        val sessionId = _state.value.sessionId
        if (sessionId.isBlank()) return
        try {
            _state.value = _state.value.copy(historyLoading = true, historyError = null)
            applyCanonical(client.sessionMessages(sessionId, limit = CANONICAL_LIMIT))
        } catch (e: Exception) {
            DenovaLog.w("Agent", "load canonical history failed", e)
            _state.value = _state.value.copy(
                historyLoading = false,
                historyError = e.message ?: "加载消息失败",
            )
        }
    }

    /**
     * 用一页 canonical 历史重建消息部件，并清空流式正文（避免与历史里的同一段内容重复显示）。
     * 挂接前的常规重建与「核对已落地」的重建共用，保证两条路径的显示口径一致。
     */
    private fun applyCanonical(page: AgentMessagesPage) {
        val views = AgentPartLogic.parseMessages(page.messages)
        val tools = views.sumOf { view -> view.parts.count { it is AgentToolPart } }
        _state.value = _state.value.copy(
            messages = views,
            historyLoading = false,
            historyError = null,
            ui = _state.value.ui.copy(content = "", thinking = ""),
        )
        DenovaLog.i(
            "Agent",
            "canonical parts loaded messages=${views.size} tools=$tools session=${_state.value.sessionId}",
        )
    }

    /**
     * 图片部件取字节，三种来源都覆盖：
     * 工作区相对路径 → `/files/asset`；绝对 http(s) → 同鉴权客户端直取；`data:` → 本地 base64 解码。
     */
    suspend fun loadImage(part: AgentImagePart): ByteArray? {
        val client = client ?: return null
        val projectId = _state.value.projectId
        return try {
            when {
                part.isInline -> decodeDataUrl(part.url)
                part.isRemote -> client.fetchBytes(part.url)
                part.isWorkspacePath && projectId.isNotBlank() -> client.projectAssetBytes(projectId, part.url)
                else -> null
            }
        } catch (e: Exception) {
            DenovaLog.w("Agent", "image load failed path=${part.url.take(48)}", e)
            null
        }
    }

    private fun decodeDataUrl(url: String): ByteArray? {
        val comma = url.indexOf(',')
        if (comma <= 0) return null
        return runCatching {
            android.util.Base64.decode(url.substring(comma + 1), android.util.Base64.DEFAULT)
        }.getOrNull()
    }

    private fun onFrame(frame: AgentUiFrame) {
        val current = _state.value
        when (frame) {
            is AgentUiFrame.Chunk -> {
                // 提问交互（AGT-004）走独立的 data 部件，先于正文投影处理。
                handleAskChunk(frame.chunk)
                _state.value = _state.value.copy(
                    ui = AgentUiReducer.reduce(_state.value.ui, frame.chunk),
                    connected = true,
                )
            }

            AgentUiFrame.Done -> _state.value = _state.value.copy(connected = false)
        }
    }

    private fun finishStream() {
        val current = _state.value
        val ui = when {
            // 用户主动停止导致的流结束不是错误：如实记为完成，避免把 abort 显示成失败。
            current.ui.phase == AgentPhase.RUNNING && current.abortPending ->
                current.ui.copy(phase = AgentPhase.DONE, activity = "")
            current.ui.phase == AgentPhase.RUNNING -> current.ui.copy(phase = AgentPhase.ERROR, activity = "")
            else -> current.ui
        }
        _state.value = current.copy(ui = ui, connected = false, sending = false)
        // 终态后以服务端投影为准刷新暂停 / 停止门控（abort 或 suspend 的最终结果由它给出）。
        viewModelScope.launch { refreshActive() }
    }

    override fun onCleared() {
        streamJob?.cancel()
        sendJob?.cancel()
        super.onCleared()
    }

    private companion object {
        /** 提问交互的 AI SDK data 部件类型（后端 `agentui.DataTypeAsk`）。 */
        const val ASK_CHUNK_TYPE = "data-agent-ask"

        /** canonical 历史一页的消息数：常规重建与「核对+重建」共用同一口径。 */
        const val CANONICAL_LIMIT = 50
    }
}
