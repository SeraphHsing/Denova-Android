package com.denova.novara.feature.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.command.CommandRecord
import com.denova.novara.core.command.CommandStatus
import com.denova.novara.NovaraApplication
import com.denova.novara.core.command.CommandStore
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.SendChatRequest
import com.denova.novara.core.network.TextSelectionRef
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
                            _state.value = _state.value.copy(sending = false)
                            // 服务端已接受：引用的使命结束；失败时不解释放，便于原样重发。
                            _selections.value = emptyList()
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
        val ui = if (current.ui.phase == AgentPhase.RUNNING) {
            current.ui.copy(phase = AgentPhase.ERROR, activity = "")
        } else {
            current.ui
        }
        _state.value = current.copy(ui = ui, connected = false, sending = false)
    }

    override fun onCleared() {
        streamJob?.cancel()
        sendJob?.cancel()
        super.onCleared()
    }
}
