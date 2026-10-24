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
import com.denova.novara.core.network.SendChatRequest
import com.denova.novara.core.network.TextSelectionRef
import com.denova.novara.core.stream.AgentActiveView
import com.denova.novara.core.stream.AgentPhase
import com.denova.novara.core.stream.AgentStreamRecoveryCoordinator
import com.denova.novara.core.stream.AgentUiFrame
import com.denova.novara.core.stream.AgentUiReducer
import com.denova.novara.core.stream.AgentUiState
import com.denova.novara.core.stream.GameStreamError
import com.denova.novara.core.stream.buildAgentHistoryNarrative
import com.denova.novara.core.stream.collectWithIdleGuard
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
)

/** 写作 Agent ViewModel：绑定 client → 恢复协调器 → 投影；发送先落命令事务再 POST。 */
class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val json = Json { ignoreUnknownKeys = true }
    private val commands = CommandStore((application as NovaraApplication).container.database.commandDao())
    private var client: DenovaClient? = null
    private var coordinator: AgentStreamRecoveryCoordinator? = null
    private var streamJob: Job? = null
    private var sendJob: Job? = null

    private val _state = MutableStateFlow(AgentScreenState())
    val state: StateFlow<AgentScreenState> = _state.asStateFlow()

    /** 待发送的正文选区引用（WRI-009）：编辑器「引用到 AI」写入，随下一条消息提交。 */
    private val _selections = MutableStateFlow<List<TextSelectionRef>>(emptyList())
    val selections: StateFlow<List<TextSelectionRef>> = _selections.asStateFlow()

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

    /** 挂接 session 的活动 Agent 流。 */
    fun start(sessionId: String) {
        val c = coordinator ?: return
        if (sessionId.isBlank()) return
        DenovaLog.i("Agent", "start session=$sessionId")
        _state.value = AgentScreenState(sessionId = sessionId)
        streamJob?.cancel()
        sendJob?.cancel()
        streamJob = viewModelScope.launch {
            try {
                // 先加载 canonical 历史作为基线，再挂接流补充增量。
                reloadCanonical()
                // 挂接前同步一次 active：暂停 / 停止按钮只对「当前确实存在的操作」可用（AGT-005）。
                refreshActive()
                c.recover(sessionId).collect { onFrame(it) }
            } catch (e: Exception) {
                DenovaLog.w("Agent", "recover failed", e)
                _state.value = _state.value.copy(error = e.message)
            }
            finishStream()
        }
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
        _state.value = _state.value.copy(sessionId = sessionId, sending = true, error = null)
        sendJob = viewModelScope.launch {
            commands.save(record)
            try {
                var accepted = false
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
                if (!accepted) {
                    commands.updateStatus(commandId, CommandStatus.ACCEPTED)
                    _state.value = _state.value.copy(sending = false)
                    _selections.value = emptyList()
                }
                if (idleTerminated) {
                    DenovaLog.w("Agent", "stream idle timeout; task finished")
                    _state.value = _state.value.copy(error = "流空闲超时，任务可能已结束")
                }
            } catch (e: GameStreamError) {
                val rejected = e.status in 400..499 && e.code != null && e.code != "agent_runtime.stream_attached"
                commands.updateStatus(commandId, if (rejected) CommandStatus.REJECTED else CommandStatus.UNCERTAIN)
                DenovaLog.w("Agent", "send failed code=${e.code} status=${e.status}")
                _state.value = _state.value.copy(error = e.message)
            } catch (e: Exception) {
                commands.updateStatus(commandId, CommandStatus.UNCERTAIN)
                DenovaLog.w("Agent", "send uncertain", e)
                _state.value = _state.value.copy(error = e.message)
            }
            finishStream()
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
            _state.value = _state.value.copy(
                activeOperationId = operationId,
                runtimePhase = view.phase.orEmpty(),
                // 服务端不再报告当前操作即说明停止已落地：清掉「正在停止…」，否则按钮文案会一直挂着。
                abortPending = _state.value.abortPending && operationId.isNotEmpty(),
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

    /** 从 canonical 消息历史重建正文基线（rehydrate 后不丢已持久化内容）。 */
    private suspend fun reloadCanonical() {
        val client = client ?: return
        val sessionId = _state.value.sessionId
        if (sessionId.isBlank()) return
        try {
            val page = client.sessionMessages(sessionId, limit = 50)
            val history = buildAgentHistoryNarrative(page.messages)
            _state.value = _state.value.copy(ui = AgentUiState(content = history))
            DenovaLog.i("Agent", "canonical history loaded len=${history.length}")
        } catch (e: Exception) {
            DenovaLog.w("Agent", "load canonical history failed", e)
            _state.value = _state.value.copy(ui = AgentUiState())
        }
    }

    private fun onFrame(frame: AgentUiFrame) {
        val current = _state.value
        when (frame) {
            is AgentUiFrame.Chunk -> _state.value = current.copy(
                ui = AgentUiReducer.reduce(current.ui, frame.chunk),
                connected = true,
            )

            AgentUiFrame.Done -> _state.value = current.copy(connected = false)
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
}
