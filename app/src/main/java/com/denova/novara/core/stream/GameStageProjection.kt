package com.denova.novara.core.stream

/** 游戏阶段运行态。 */
enum class GamePhase { IDLE, RUNNING, SUSPENDED, DONE, ABORTED, ERROR }

/** 当前活动的语义类别（用于 UI 提示，不含具体正文）。 */
enum class GameActivity { IDLE, THINKING, TOOL, REPAIRING, RECOVERING }

/**
 * 游戏阶段投影状态：由 [GameStageReducer] 从事件流推导。
 * 纯类型驱动（不解析 data 正文）；叙事累积与工具卡片在 UI 层用事件 data 拼接。
 */
data class GameStageState(
    val phase: GamePhase = GamePhase.IDLE,
    val activity: GameActivity = GameActivity.IDLE,
    val terminalReason: String? = null,
    val receivedPersistedTurn: Boolean = false,
    val pendingAsk: Boolean = false,
    val streamFailed: Boolean = false,
)

/**
 * 把游戏流事件投影到 [GameStageState]。只按事件类型转移，正文/工具参数由上层消费 data。
 */
object GameStageReducer {

    fun reduce(state: GameStageState, event: GameStreamEvent): GameStageState = when (event.type) {
        // checkpoint / rehydrate：重建投影，重置运行态。
        GameEventType.TASK_CHECKPOINT,
        GameEventType.TASK_REHYDRATE_REQUIRED -> state.copy(
            phase = GamePhase.RUNNING,
            activity = GameActivity.RECOVERING,
            terminalReason = null,
            receivedPersistedTurn = false,
            streamFailed = false,
        )

        GameEventType.AGENT_CYCLE_STARTED -> state.copy(
            phase = GamePhase.RUNNING,
            activity = GameActivity.THINKING,
        )

        GameEventType.THINKING,
        GameEventType.INTERACTIVE_CONTENT_RECLASSIFIED -> state.copy(activity = GameActivity.THINKING)

        GameEventType.TOOL_CALL,
        GameEventType.TOOL_STARTED,
        GameEventType.TOOL_PROGRESS -> state.copy(activity = GameActivity.TOOL)

        GameEventType.MODEL_RETRY -> state.copy(activity = GameActivity.REPAIRING)

        GameEventType.SUSPENDED -> state.copy(phase = GamePhase.SUSPENDED, activity = GameActivity.IDLE)

        GameEventType.DONE -> state.copy(phase = GamePhase.DONE, activity = GameActivity.IDLE)

        GameEventType.ABORTED -> state.copy(phase = GamePhase.ABORTED, activity = GameActivity.IDLE)

        GameEventType.ERROR -> state.copy(
            phase = GamePhase.ERROR,
            activity = GameActivity.IDLE,
            streamFailed = true,
        )

        GameEventType.INTERACTIVE_TURN_PERSISTED -> state.copy(receivedPersistedTurn = true)

        GameEventType.ASK_PENDING -> state.copy(pendingAsk = true)

        GameEventType.ASK_RESOLVED -> state.copy(pendingAsk = false)

        else -> state
    }
}
