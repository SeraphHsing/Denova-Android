package com.denova.novara.core.stream

/** 一个游戏流事件（EventSource 的 id 即游标 cursor）。 */
data class GameStreamEvent(
    val id: String?,
    val type: GameEventType,
)

/**
 * 游戏流状态：跟踪 committed 游标与 checkpoint 回放窗口。
 *
 * - `task_checkpoint` 开启回放窗口（服务端重放历史事件以重建投影）。
 * - `task_checkpoint_committed` 携带游标，结束回放窗口，客户端据此记录恢复点。
 * - `task_rehydrate_required` 表示游标已失效，客户端需整体重取（rehydrate）。
 */
class GameStreamState {

    /** 最后 committed 的游标；重连时作为 `after=` / `Last-Event-ID`。 */
    var cursor: String? = null
        private set

    /** 是否收到过 rehydrate_required（游标失效，需整体重取）。 */
    var rehydrationRequired: Boolean = false
        private set

    /** 是否处于 checkpoint 回放窗口内。 */
    var replayingCheckpoint: Boolean = false
        private set

    /** 处理一个事件，返回该事件是否应转发给 UI（回放中的事件由上层决定是否重建而非追加）。 */
    fun onEvent(id: String?, type: GameEventType): GameStreamEvent {
        when (type) {
            GameEventType.TASK_CHECKPOINT -> replayingCheckpoint = true
            GameEventType.TASK_CHECKPOINT_COMMITTED -> {
                replayingCheckpoint = false
                if (id != null) cursor = id
            }
            GameEventType.TASK_REHYDRATE_REQUIRED -> rehydrationRequired = true
            else -> Unit
        }
        return GameStreamEvent(id, type)
    }
}
