package com.denova.novara.feature.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.stream.GameEventType
import com.denova.novara.core.stream.GameStageReducer
import com.denova.novara.core.stream.GameStageState
import com.denova.novara.core.stream.GameStreamEvent
import com.denova.novara.core.stream.GameStreamRecoveryCoordinator
import com.denova.novara.core.stream.RehydrateRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** chunk 事件负载的最小解析（叙事累积用）。 */
@Serializable
private data class ChunkData(val content: String? = null, val subagent: Boolean? = null)

/** 游戏 UI 状态：投影阶段 + 累积叙事 + 连接态。 */
data class GameUiState(
    val storyId: String = "",
    val branchId: String = "",
    val stage: GameStageState = GameStageState(),
    val narrative: String = "",
    val connected: Boolean = false,
    val error: String? = null,
)

/** 游戏流 ViewModel：绑定 client → 恢复协调器 → 投影 + 叙事累积。 */
class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val json = Json { ignoreUnknownKeys = true }
    private var client: DenovaClient? = null
    private var coordinator: GameStreamRecoveryCoordinator? = null
    private var streamJob: Job? = null

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

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
        _uiState.value = GameUiState(storyId = storyId, branchId = branchId)
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            c.recover(storyId, branchId).collect { event ->
                onEvent(event)
            }
            _uiState.value = _uiState.value.copy(connected = false)
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        _uiState.value = _uiState.value.copy(connected = false)
    }

    private fun onEvent(event: GameStreamEvent) {
        val current = _uiState.value
        val stage = GameStageReducer.reduce(current.stage, event)

        var narrative = current.narrative
        when (event.type) {
            GameEventType.CHUNK -> {
                val data = runCatching { json.decodeFromString(ChunkData.serializer(), event.data ?: "") }.getOrNull()
                if (data != null && data.subagent != true) narrative += data.content.orEmpty()
            }
            GameEventType.TASK_CHECKPOINT,
            GameEventType.TASK_REHYDRATE_REQUIRED -> narrative = ""
            else -> Unit
        }

        _uiState.value = current.copy(stage = stage, narrative = narrative, connected = true, error = null)
    }

    private suspend fun reloadCanonical(request: RehydrateRequest?) {
        DenovaLog.w("Game", "rehydrate required task=${request?.taskId} cursor=${request?.cursor}")
        // 简化：重置叙事与投影；canonical 历史（/interactive/stories/:id/history）重建待后续轮次。
        _uiState.value = _uiState.value.copy(narrative = "", stage = GameStageState())
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}
