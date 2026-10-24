package com.denova.novara.feature.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.command.CommandRecord
import com.denova.novara.core.command.CommandStatus
import com.denova.novara.core.command.CommandStore
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.SendInteractiveChatRequest
import com.denova.novara.core.stream.GameActivity
import com.denova.novara.core.stream.GameEventType
import com.denova.novara.core.stream.GamePhase
import com.denova.novara.core.stream.GameStageReducer
import com.denova.novara.core.stream.GameStageState
import com.denova.novara.core.stream.GameStreamError
import com.denova.novara.core.stream.GameStreamEvent
import com.denova.novara.core.stream.GameStreamRecoveryCoordinator
import com.denova.novara.core.stream.NarrativeBuilder
import com.denova.novara.core.stream.RehydrateRequest
import com.denova.novara.core.stream.buildCanonicalNarrative
import com.denova.novara.core.stream.collectWithIdleGuard
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** chunk 事件负载的最小解析（叙事累积用）。 */
@Serializable
private data class ChunkData(val content: String? = null, val subagent: Boolean? = null)

/** chunk 合批发布间隔：约 16 次/秒，兼顾流式观感与主线程负载。 */
private const val CHUNK_FLUSH_INTERVAL_MS = 60L

/** 游戏 UI 状态：投影阶段 + 累积叙事 + 连接态。 */
data class GameUiState(
    val storyId: String = "",
    val branchId: String = "",
    val stage: GameStageState = GameStageState(),
    /** canonical 历史叙事基线（挂接前/重取时加载）。 */
    val baseline: String = "",
    val connected: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
)

/** 游戏流 ViewModel：绑定 client → 恢复协调器 → 投影 + 叙事累积。 */
class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val json = Json { ignoreUnknownKeys = true }
    private val commands = CommandStore(application)
    private var client: DenovaClient? = null
    private var coordinator: GameStreamRecoveryCoordinator? = null
    private var streamJob: Job? = null
    private var sendJob: Job? = null

    // D03 性能埋点：事件投影耗时 + 合批频次累计统计（不影响功能）。
    private var perfEventCount = 0
    private var perfTotalNanos = 0L
    private var perfMaxNanos = 0L
    private var perfFlushCount = 0
    private var perfCharsPerFlush = 0

    // 叙事增量分块 + chunk 合批（60ms 发布一次，避免每个 chunk 触发重组）。
    private val narrative = NarrativeBuilder()
    private val pendingChunks = StringBuilder()
    private var flushJob: Job? = null

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    /**
     * 叙事分块单独成流：高频（~16 次/秒）更新只驱动叙事面板重组，
     * 不会连带重组状态行与输入框（后者含 IME 布局，代价高）。
     */
    private val _blocks = MutableStateFlow<List<String>>(emptyList())
    val blocks: StateFlow<List<String>> = _blocks.asStateFlow()

    /** 登录后绑定当前连接的 client，建立恢复协调器。 */
    fun bindClient(client: DenovaClient) {
        this.client = client
        this.coordinator = GameStreamRecoveryCoordinator(
            transport = client,
            reloadCanonical = ::reloadCanonical,
        )
    }

    /** 挂接指定 story/branch 的活动游戏流。 */
    fun start(storyId: String, branchId: String) {
        val c = coordinator ?: return
        DenovaLog.i("Game", "start story=$storyId branch=$branchId")
        dropPendingChunks()
        narrative.reset("")
        _blocks.value = emptyList()
        _uiState.value = GameUiState(storyId = storyId, branchId = branchId)
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            // 先加载 canonical 历史作为叙事基线，再挂接流补充增量，
            // 否则 checkpoint 回放只能重建当前轮，历史叙事会缺失。
            loadBaseline(storyId, branchId)
            c.recover(storyId, branchId).collect { event ->
                onEvent(event)
            }
            finishStream()
        }
    }

    /** 发送玩家输入：先落本地事务（SUBMITTING）再 POST，流正常结束即 ACCEPTED。 */
    fun send(storyId: String, branchId: String, message: String) {
        val client = client ?: return
        val text = message.trim()
        if (text.isEmpty() || storyId.isBlank()) return
        val commandId = UUID.randomUUID().toString()
        val request = SendInteractiveChatRequest(
            commandId = commandId,
            storyId = storyId,
            branch = branchId.ifBlank { null },
            message = text,
        )
        val record = CommandRecord(
            commandId = commandId,
            target = "game:$storyId:$branchId",
            payload = json.encodeToString(SendInteractiveChatRequest.serializer(), request),
            status = CommandStatus.SUBMITTING,
            createdAt = System.currentTimeMillis(),
        )
        DenovaLog.i("Game", "send story=$storyId cmd=$commandId")
        // 结束当前观察流，改用 POST 实时流。
        streamJob?.cancel()
        streamJob = null
        sendJob?.cancel()
        _uiState.value = _uiState.value.copy(storyId = storyId, branchId = branchId, sending = true, error = null)
        val sendStartNanos = System.nanoTime()
        perfEventCount = 0
        perfTotalNanos = 0
        perfMaxNanos = 0
        perfFlushCount = 0
        perfCharsPerFlush = 0
        sendJob = viewModelScope.launch {
            commands.save(record) // 本地事务先落盘
            try {
                var accepted = false
                val idleTerminated = client.postInteractiveChat(request).collectWithIdleGuard(
                    isTaskFinished = {
                        try {
                            !client.active(storyId, branchId).active
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            true
                        }
                    },
                    onEvent = { event ->
                        if (!accepted) {
                            accepted = true
                            DenovaLog.i("GamePerf", "first-frame=${(System.nanoTime() - sendStartNanos) / 1_000_000.0}ms")
                            commands.updateStatus(commandId, CommandStatus.ACCEPTED)
                            _uiState.value = _uiState.value.copy(sending = false)
                        }
                        onEvent(event)
                    },
                )
                if (!accepted) {
                    commands.updateStatus(commandId, CommandStatus.ACCEPTED)
                    _uiState.value = _uiState.value.copy(sending = false)
                }
                if (idleTerminated) {
                    DenovaLog.w("Game", "stream idle timeout; task finished")
                    _uiState.value = _uiState.value.copy(error = "流空闲超时，任务可能已结束")
                }
                finishStream()
            } catch (e: GameStreamError) {
                val rejected = e.status in 400..499 && e.code != null && e.code != "agent_runtime.stream_attached"
                commands.updateStatus(commandId, if (rejected) CommandStatus.REJECTED else CommandStatus.UNCERTAIN)
                DenovaLog.w("Game", "send failed code=${e.code} status=${e.status}")
                _uiState.value = _uiState.value.copy(error = e.message)
                finishStream()
            } catch (e: Exception) {
                commands.updateStatus(commandId, CommandStatus.UNCERTAIN)
                DenovaLog.w("Game", "send uncertain", e)
                _uiState.value = _uiState.value.copy(error = e.message)
                finishStream()
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        sendJob?.cancel()
        sendJob = null
        _uiState.value = _uiState.value.copy(connected = false)
    }

    private fun onEvent(event: GameStreamEvent) {
        val startNanos = System.nanoTime()
        val current = _uiState.value
        val stage = GameStageReducer.reduce(current.stage, event)

        when (event.type) {
            GameEventType.CHUNK -> {
                val data = runCatching { json.decodeFromString(ChunkData.serializer(), event.data ?: "") }.getOrNull()
                val content = data?.content.orEmpty()
                if (data != null && data.subagent != true && content.isNotEmpty()) {
                    pendingChunks.append(content)
                    scheduleFlush()
                }
                // 正文走合批；阶段变化（例如首帧进入运行中）仍需立即反映。
                if (stage != current.stage) {
                    _uiState.value = current.copy(stage = stage, connected = true, error = null)
                }
            }

            GameEventType.TASK_CHECKPOINT,
            GameEventType.TASK_REHYDRATE_REQUIRED -> {
                dropPendingChunks()
                narrative.reset(current.baseline)
                _blocks.value = narrative.snapshot
                _uiState.value = current.copy(stage = stage, connected = true, error = null)
            }

            else -> _uiState.value = current.copy(stage = stage, connected = true, error = null)
        }

        val costNanos = System.nanoTime() - startNanos
        perfEventCount++
        perfTotalNanos += costNanos
        if (costNanos > perfMaxNanos) perfMaxNanos = costNanos
        if (perfEventCount % 100 == 0) {
            DenovaLog.i(
                "GamePerf",
                "events=$perfEventCount avgMs=${perfTotalNanos / 1_000_000.0 / perfEventCount} maxMs=${perfMaxNanos / 1_000_000.0}",
            )
        }
    }

    /**
     * chunk 合批：把高频 chunk 攒进缓冲，按 [CHUNK_FLUSH_INTERVAL_MS] 合并发布一次。
     * 重组频率从「每 chunk」降到约 16 次/秒，避免 UI 线程被逐帧更新淹没。
     */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = viewModelScope.launch {
            delay(CHUNK_FLUSH_INTERVAL_MS)
            flushPendingChunks()
        }
    }

    private fun flushPendingChunks() {
        if (pendingChunks.isEmpty()) return
        val text = pendingChunks.toString()
        pendingChunks.setLength(0)
        narrative.append(text)
        _blocks.value = narrative.snapshot

        perfFlushCount++
        perfCharsPerFlush += text.length
        if (perfFlushCount % 50 == 0) {
            DenovaLog.i(
                "GamePerf",
                "flushes=$perfFlushCount avgChars=${perfCharsPerFlush / perfFlushCount} blocks=${narrative.snapshot.size}",
            )
        }
    }

    /** 丢弃未发布的 chunk（checkpoint/rehydrate 会整段重建，避免旧增量串进新基线）。 */
    private fun dropPendingChunks() {
        flushJob?.cancel()
        flushJob = null
        pendingChunks.setLength(0)
    }

    /** 流结束：若仍停在运行中，说明没收到 terminal 事件（流异常中断），标记断开。 */
    private fun finishStream() {
        flushPendingChunks()
        val current = _uiState.value
        val stage = if (current.stage.phase == GamePhase.RUNNING) {
            current.stage.copy(phase = GamePhase.ERROR, activity = GameActivity.IDLE, streamFailed = true)
        } else {
            current.stage
        }
        _uiState.value = current.copy(stage = stage, connected = false, sending = false)
    }

    private suspend fun loadBaseline(storyId: String, branchId: String) {
        val client = client ?: return
        if (storyId.isBlank()) return
        try {
            val page = client.interactiveHistory(storyId, branchId, limit = 50)
            val baseline = buildCanonicalNarrative(page.turns)
            narrative.reset(baseline)
            _blocks.value = narrative.snapshot
            _uiState.value = _uiState.value.copy(baseline = baseline)
            DenovaLog.i("Game", "canonical baseline loaded len=${baseline.length}")
        } catch (e: Exception) {
            DenovaLog.w("Game", "load canonical baseline failed", e)
        }
    }

    private suspend fun reloadCanonical(request: RehydrateRequest?) {
        val client = client
        val storyId = _uiState.value.storyId
        val branchId = _uiState.value.branchId
        DenovaLog.w("Game", "rehydrate required task=${request?.taskId} cursor=${request?.cursor}; reload canonical history")
        val baseline = if (client != null && storyId.isNotBlank()) {
            try {
                val page = client.interactiveHistory(storyId, branchId, limit = 50)
                buildCanonicalNarrative(page.turns)
            } catch (e: Exception) {
                DenovaLog.w("Game", "reload canonical history failed", e)
                _uiState.value.baseline
            }
        } else {
            _uiState.value.baseline
        }
        dropPendingChunks()
        narrative.reset(baseline)
        _blocks.value = narrative.snapshot
        _uiState.value = _uiState.value.copy(baseline = baseline, stage = GameStageState())
    }

    override fun onCleared() {
        streamJob?.cancel()
        sendJob?.cancel()
        flushJob?.cancel()
        super.onCleared()
    }
}
