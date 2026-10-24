package com.denova.novara.feature.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.command.CommandRecord
import com.denova.novara.core.command.CommandStatus
import com.denova.novara.core.command.CommandStore
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.SendChatRequest
import com.denova.novara.core.stream.AgentPhase
import com.denova.novara.core.stream.AgentStreamRecoveryCoordinator
import com.denova.novara.core.stream.AgentUiFrame
import com.denova.novara.core.stream.AgentUiReducer
import com.denova.novara.core.stream.AgentUiState
import com.denova.novara.core.stream.GameStreamError
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
    private val commands = CommandStore(application)
    private var client: DenovaClient? = null
    private var coordinator: AgentStreamRecoveryCoordinator? = null
    private var streamJob: Job? = null
    private var sendJob: Job? = null

    private val _state = MutableStateFlow(AgentScreenState())
    val state: StateFlow<AgentScreenState> = _state.asStateFlow()

    fun bindClient(client: DenovaClient) {
        this.client = client
        this.coordinator = AgentStreamRecoveryCoordinator(client) {
            // canonical 历史（session messages）重建待后续轮次。
            _state.value = _state.value.copy(ui = AgentUiState())
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
                c.recover(sessionId).collect { onFrame(it) }
            } catch (e: Exception) {
                DenovaLog.w("Agent", "recover failed", e)
                _state.value = _state.value.copy(error = e.message)
            }
            finishStream()
        }
    }

    /** 发送写作输入：先落本地事务（SUBMITTING）再 POST /api/chat。 */
    fun send(sessionId: String, message: String) {
        val client = client ?: return
        val text = message.trim()
        if (text.isEmpty() || sessionId.isBlank()) return
        val commandId = UUID.randomUUID().toString()
        val request = SendChatRequest(commandId = commandId, sessionId = sessionId, message = text)
        val record = CommandRecord(
            commandId = commandId,
            target = "chat:$sessionId",
            payload = json.encodeToString(SendChatRequest.serializer(), request),
            status = CommandStatus.SUBMITTING,
            createdAt = System.currentTimeMillis(),
        )
        DenovaLog.i("Agent", "send session=$sessionId cmd=$commandId")
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
                        }
                        onFrame(frame)
                    },
                )
                if (!accepted) {
                    commands.updateStatus(commandId, CommandStatus.ACCEPTED)
                    _state.value = _state.value.copy(sending = false)
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
        val ui = if (current.ui.phase == AgentPhase.RUNNING) current.ui.copy(phase = AgentPhase.ERROR) else current.ui
        _state.value = current.copy(ui = ui, connected = false, sending = false)
    }

    override fun onCleared() {
        streamJob?.cancel()
        sendJob?.cancel()
        super.onCleared()
    }
}
