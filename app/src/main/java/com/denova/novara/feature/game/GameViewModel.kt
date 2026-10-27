package com.denova.novara.feature.game

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.command.CommandRecord
import com.denova.novara.core.command.CommandStatus
import com.denova.novara.NovaraApplication
import com.denova.novara.core.command.CommandStore
import com.denova.novara.core.game.StoryLibrary
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.SendInteractiveChatRequest
import com.denova.novara.core.network.StorySummary
import com.denova.novara.core.stream.GameActivity
import com.denova.novara.core.stream.GameEventType
import com.denova.novara.core.stream.GamePhase
import com.denova.novara.core.stream.GameStageReducer
import com.denova.novara.core.stream.GameStageState
import com.denova.novara.core.stream.GameStreamError
import com.denova.novara.core.stream.GameStreamEvent
import com.denova.novara.core.stream.DrawLatencyMeter
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

/** 默认分支 id（与 Web 一致；分支切换属 P4-A 后续切片）。 */
private const val DEFAULT_BRANCH = "main"

/**
 * 渐进式恢复：首屏只取最近若干轮，让「恢复可操作」不必等全量历史；
 * 全量随后台任务补全（history 耗时随 limit 近似线性：3 轮 ≈0.22s，50 轮 ≈2.1s）。
 */
private const val FAST_BASELINE_TURNS = 3
private const val FULL_BASELINE_TURNS = 50

/** 游戏 UI 状态：投影阶段 + 累积叙事 + 连接态。 */
data class GameUiState(
    val storyId: String = "",
    val branchId: String = "",
    val stage: GameStageState = GameStageState(),
    /** canonical 历史叙事基线（挂接前/重取时加载）。 */
    val baseline: String = "",
    val connected: Boolean = false,
    val sending: Boolean = false,
    val error: UiText? = null,
)

/**
 * 故事库状态（P4-A / GAM-002）：服务端列表 + 当前选择 + 操作态。
 * 选择必须先在服务端成功（`POST .../select`），本地不自行改 `current_story_id`。
 */
data class StoryLibraryState(
    val stories: List<StorySummary> = emptyList(),
    val currentStoryId: String = "",
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: UiText? = null,
)

/** 游戏流 ViewModel：绑定 client → 恢复协调器 → 投影 + 叙事累积。 */
class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val json = Json { ignoreUnknownKeys = true }
    private val commands = CommandStore((application as NovaraApplication).container.database.commandDao())
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

    /** 本次挂接后由流追加的正文；全量基线到达时用它重建，避免覆盖流增量。 */
    private val streamTail = StringBuilder()
    private var flushJob: Job? = null

    // 「事件接收 → 投影可见」延迟采样（gfxinfo 在本机不可用，改用直接埋点）。
    private val drawLatency = DrawLatencyMeter()
    @Volatile
    private var lastEventNanos = 0L

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    /** 故事库：进入游戏页时加载；切换 / 改名 / 删除成功后按服务端结果更新。 */
    private val _library = MutableStateFlow(StoryLibraryState())
    val library: StateFlow<StoryLibraryState> = _library.asStateFlow()
    private var libraryJob: Job? = null

    /** 故事世代：切换故事即推进，迟到回包据此丢弃（GAM-002 任务隔离）。 */
    private var storyEpoch = 0L

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
        // 登录后预取故事列表；进入游戏页时页面再按需刷新（不重复挂接）。
        if (_library.value.stories.isEmpty()) loadStories()
    }

    // ---- 故事库（P4-A / GAM-002）----

    /**
     * 拉取故事列表（`GET /api/interactive/stories`）。
     *
     * 服务端 `current_story_id` 命中且舞台尚未开始时自动挂接该故事；
     * 否则只更新列表，等用户点选——不替用户改动服务端的选择。
     */
    fun loadStories(autoAttach: Boolean = false) {
        val client = client ?: return
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            _library.value = _library.value.copy(loading = true, error = null)
            val index = try {
                client.interactiveStories()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Game", "load stories failed", e)
                _library.value = _library.value.copy(loading = false, error = e.message?.let { UiText.raw(it) })
                return@launch
            }
            val stories = StoryLibrary.visible(index.stories)
            val current = StoryLibrary.current(stories, index.currentStoryId)
            _library.value = StoryLibraryState(
                stories = stories,
                currentStoryId = current?.id.orEmpty(),
                loading = false,
            )
            DenovaLog.i("Game", "stories loaded count=${stories.size} current=${current?.id}")
            if (autoAttach && current != null && _uiState.value.storyId.isBlank()) {
                start(current.id, DEFAULT_BRANCH)
            }
        }
    }

    /**
     * 切换故事：先在服务端选中，成功后才更新本地并重新挂接。
     * `preview` 故事或不存在的 id 服务端返回 400，本地保持原选择。
     */
    fun selectStory(storyId: String) {
        val client = client ?: return
        val target = storyId.trim()
        if (target.isEmpty() || target == _library.value.currentStoryId) return
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            _library.value = _library.value.copy(busy = true, error = null)
            try {
                client.selectInteractiveStory(target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Game", "select story failed id=$target", e)
                _library.value = _library.value.copy(busy = false, error = e.message?.let { UiText.raw(it) })
                return@launch
            }
            _library.value = _library.value.copy(currentStoryId = target, busy = false)
            DenovaLog.i("Game", "story selected id=$target")
            start(target, DEFAULT_BRANCH)
        }
    }

    /** 重命名：空标题本地拒绝（不发请求），成功后按服务端返回的摘要原位替换。 */
    fun renameStory(storyId: String, title: String) {
        val client = client ?: return
        val name = title.trim()
        if (name.isEmpty()) {
            _library.value = _library.value.copy(error = UiText.of(R.string.game_story_rename_required))
            return
        }
        val existing = _library.value.stories.firstOrNull { it.id == storyId }
        if (existing != null && existing.title == name) return
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            _library.value = _library.value.copy(busy = true, error = null)
            val updated = try {
                client.renameInteractiveStory(storyId, name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Game", "rename story failed id=$storyId", e)
                _library.value = _library.value.copy(busy = false, error = e.message?.let { UiText.raw(it) })
                return@launch
            }
            _library.value = _library.value.copy(
                stories = StoryLibrary.merged(_library.value.stories, updated),
                busy = false,
            )
            DenovaLog.i("Game", "story renamed id=$storyId title=${updated.title}")
        }
    }

    /** 删除故事；删掉的正是当前故事时回退到列表首个并重新挂接。 */
    fun deleteStory(storyId: String) {
        val client = client ?: return
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            _library.value = _library.value.copy(busy = true, error = null)
            try {
                client.deleteInteractiveStory(storyId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Game", "delete story failed id=$storyId", e)
                _library.value = _library.value.copy(busy = false, error = e.message?.let { UiText.raw(it) })
                return@launch
            }
            val (remaining, next) = StoryLibrary.afterDelete(
                _library.value.stories,
                storyId,
                _library.value.currentStoryId,
            )
            val deletedCurrent = _library.value.currentStoryId == storyId
            // 删的是当前故事时先清空选择，再走一次真正的服务端 select（否则会被去重逻辑短路）。
            _library.value = _library.value.copy(
                stories = remaining,
                currentStoryId = if (deletedCurrent) "" else _library.value.currentStoryId,
                busy = false,
            )
            DenovaLog.i("Game", "story deleted id=$storyId next=$next")
            if (deletedCurrent) {
                stop()
                if (next.isNotBlank()) selectStory(next) else _uiState.value = GameUiState()
            }
        }
    }

    /** 清掉列表级错误提示（对话框关闭时调用）。 */
    fun dismissLibraryError() {
        _library.value = _library.value.copy(error = null)
    }

    /** 挂接指定 story/branch 的活动游戏流。 */
    fun start(storyId: String, branchId: String) {
        val c = coordinator ?: return
        DenovaLog.i("Game", "start story=$storyId branch=$branchId")
        // 切换故事：结束在途发送，并推进世代，使迟到回包不写入新故事的舞台状态。
        sendJob?.cancel()
        sendJob = null
        storyEpoch++
        dropPendingChunks()
        narrative.reset("")
        streamTail.setLength(0)
        _blocks.value = emptyList()
        resetPerfCounters()
        _uiState.value = GameUiState(storyId = storyId, branchId = branchId)
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            // 渐进恢复：先加载最近 FAST_BASELINE_TURNS 轮让界面立刻可操作，
            // 全量历史在后台补全（不阻塞挂接与首屏）。
            loadBaseline(storyId, branchId, FAST_BASELINE_TURNS, stage = "fast")
            launch {
                loadBaseline(
                    storyId,
                    branchId,
                    FULL_BASELINE_TURNS,
                    stage = "full",
                    mergeStreamTail = true,
                )
            }
            c.recover(storyId, branchId).collect { event ->
                onEvent(event)
            }
            finishStream()
        }
    }

    /**
     * 回前台：若上一轮流因系统中断（后台被掐连接）而异常结束，自动重挂恢复。
     * 正常 done/settle 不触发（[GameStageState.streamFailed] 为 false）。
     */
    fun onForeground() {
        val state = _uiState.value
        if (state.storyId.isBlank() || state.sending) return
        if (!state.stage.streamFailed) return
        DenovaLog.i("Game", "foreground re-attach after stream failure")
        start(state.storyId, state.branchId)
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
        resetPerfCounters()
        // 发送结果只在同一故事世代内写舞台：切换故事后丢弃迟到回包（命令状态仍照常落库）。
        val epoch = storyEpoch
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
                        if (epoch != storyEpoch) return@collectWithIdleGuard
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
                    updateUiIfCurrent(epoch) { it.copy(sending = false) }
                }
                if (idleTerminated) {
                    DenovaLog.w("Game", "stream idle timeout; task finished")
                    updateUiIfCurrent(epoch) { it.copy(error = UiText.of(R.string.agent_flow_idle_timeout)) }
                }
                if (epoch == storyEpoch) finishStream()
            } catch (e: GameStreamError) {
                val rejected = e.status in 400..499 && e.code != null && e.code != "agent_runtime.stream_attached"
                commands.updateStatus(commandId, if (rejected) CommandStatus.REJECTED else CommandStatus.UNCERTAIN)
                DenovaLog.w("Game", "send failed code=${e.code} status=${e.status}")
                updateUiIfCurrent(epoch) { it.copy(error = e.message?.let { msg -> UiText.raw(msg) }) }
                if (epoch == storyEpoch) finishStream()
            } catch (e: Exception) {
                commands.updateStatus(commandId, CommandStatus.UNCERTAIN)
                DenovaLog.w("Game", "send uncertain", e)
                updateUiIfCurrent(epoch) { it.copy(error = e.message?.let { msg -> UiText.raw(msg) }) }
                if (epoch == storyEpoch) finishStream()
            }
        }
    }

    /** 发送到舞台当前故事/分支：UI 不需要知道分支身份。 */
    fun sendCurrent(message: String) {
        val state = _uiState.value
        if (state.storyId.isBlank()) return
        send(state.storyId, state.branchId.ifBlank { DEFAULT_BRANCH }, message)
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
                streamTail.setLength(0)
                narrative.reset(current.baseline)
                _blocks.value = narrative.snapshot
                _uiState.value = current.copy(stage = stage, connected = true, error = null)
            }

            else -> _uiState.value = current.copy(stage = stage, connected = true, error = null)
        }

        lastEventNanos = System.nanoTime()

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
        streamTail.append(text)
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

    /** 每次开始挂接/发送前重置性能计数，保证单轮测量独立可重复。 */
    private fun resetPerfCounters() {
        perfEventCount = 0
        perfTotalNanos = 0
        perfMaxNanos = 0
        perfFlushCount = 0
        perfCharsPerFlush = 0
        drawLatency.reset()
        lastEventNanos = 0
    }

    /** 叙事面板完成一次绘制：结算「最近事件接收 → 可见」的延迟。 */
    fun onNarrativeDrawn() {
        val last = lastEventNanos
        if (last == 0L) return
        lastEventNanos = 0L
        drawLatency.add((System.nanoTime() - last) / 1_000_000)
        if (drawLatency.size % 64 == 0) {
            DenovaLog.i("GamePerf", "latency ${drawLatency.summary()}")
        }
    }

    /** 丢弃未发布的 chunk（checkpoint/rehydrate 会整段重建，避免旧增量串进新基线）。 */
    private fun dropPendingChunks() {
        flushJob?.cancel()
        flushJob = null
        pendingChunks.setLength(0)
    }

    /** 只有当前故事世代的回包才允许写舞台状态。 */
    private inline fun updateUiIfCurrent(epoch: Long, block: (GameUiState) -> GameUiState) {
        if (epoch != storyEpoch) return
        _uiState.value = block(_uiState.value)
    }

    /** 流结束：若仍停在运行中，说明没收到 terminal 事件（流异常中断），标记断开。 */
    private fun finishStream() {
        flushPendingChunks()
        // 结束时无条件落一次延迟汇总，避免仅依赖周期性打点而丢失指标。
        DenovaLog.i("GamePerf", "latency(final) ${drawLatency.summary() ?: "n=0"}")
        val current = _uiState.value
        val stage = if (current.stage.phase == GamePhase.RUNNING) {
            current.stage.copy(phase = GamePhase.ERROR, activity = GameActivity.IDLE, streamFailed = true)
        } else {
            current.stage
        }
        _uiState.value = current.copy(stage = stage, connected = false, sending = false)
    }

    private suspend fun loadBaseline(
        storyId: String,
        branchId: String,
        limit: Int = FULL_BASELINE_TURNS,
        stage: String = "full",
        mergeStreamTail: Boolean = false,
    ) {
        val client = client ?: return
        if (storyId.isBlank()) return
        val startedAt = System.nanoTime()
        try {
            val page = client.interactiveHistory(storyId, branchId, limit = limit)
            val baseline = buildCanonicalNarrative(page.turns)
            val tail = if (mergeStreamTail) streamTail.toString() else ""
            narrative.reset(baseline)
            if (tail.isNotEmpty()) narrative.append(tail)
            _blocks.value = narrative.snapshot
            _uiState.value = _uiState.value.copy(baseline = baseline)
            DenovaLog.i(
                "Game",
                "canonical baseline loaded stage=$stage turns=${page.turns.size} len=${baseline.length}",
            )
            DenovaLog.i("Perf", "baseline-$stage=${(System.nanoTime() - startedAt) / 1_000_000}ms")
        } catch (e: Exception) {
            DenovaLog.w("Game", "load canonical baseline failed stage=$stage", e)
        }
    }

    private suspend fun reloadCanonical(request: RehydrateRequest?) {
        val client = client
        val storyId = _uiState.value.storyId
        val branchId = _uiState.value.branchId
        DenovaLog.w("Game", "rehydrate required task=${request?.taskId} cursor=${request?.cursor}; reload canonical history")
        val baseline = if (client != null && storyId.isNotBlank()) {
            try {
                val page = client.interactiveHistory(storyId, branchId, limit = FULL_BASELINE_TURNS)
                buildCanonicalNarrative(page.turns)
            } catch (e: Exception) {
                DenovaLog.w("Game", "reload canonical history failed", e)
                _uiState.value.baseline
            }
        } else {
            _uiState.value.baseline
        }
        dropPendingChunks()
        streamTail.setLength(0)
        narrative.reset(baseline)
        _blocks.value = narrative.snapshot
        _uiState.value = _uiState.value.copy(baseline = baseline, stage = GameStageState())
    }

    override fun onCleared() {
        streamJob?.cancel()
        sendJob?.cancel()
        flushJob?.cancel()
        libraryJob?.cancel()
        super.onCleared()
    }
}
