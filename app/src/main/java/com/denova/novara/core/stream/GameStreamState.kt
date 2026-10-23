package com.denova.novara.core.stream

/** 一个游戏流事件（EventSource 的 id 即游标 cursor；data 为原始 JSON 负载）。 */
data class GameStreamEvent(
    val id: String?,
    val type: GameEventType,
    val data: String? = null,
)

/**
 * 游戏流状态：跟踪恢复游标与 checkpoint 回放窗口。
 *
 * - 每个携带非零 id 的事件都会推进恢复游标（对齐 Web：`id && id !== '0'`）。
 * - `task_checkpoint` 开启回放窗口（服务端重放历史事件重建投影；重放事件不携带 id）。
 * - `task_checkpoint_committed` 携带 checkpoint 游标，结束回放窗口。
 * - `task_rehydrate_required` 表示投影超预算、游标不再可信，需从 canonical 历史整体重取。
 */
class GameStreamState {

    /** 最后确认的游标；重连时作为 `after=` / `Last-Event-ID`。 */
    var cursor: String? = null
        private set

    /** 是否收到过 rehydrate_required（需整体重取）。 */
    var rehydrationRequired: Boolean = false
        private set

    /** 是否处于 checkpoint 回放窗口内。 */
    var replayingCheckpoint: Boolean = false
        private set

    /** 处理一个事件并返回之；恢复状态随游标 / checkpoint / rehydrate 事件推进。 */
    fun onEvent(id: String?, type: GameEventType, data: String? = null): GameStreamEvent {
        if (id != null && id != "0") {
            cursor = id
        }
        when (type) {
            GameEventType.TASK_CHECKPOINT -> replayingCheckpoint = true
            GameEventType.TASK_CHECKPOINT_COMMITTED -> replayingCheckpoint = false
            GameEventType.TASK_REHYDRATE_REQUIRED -> {
                rehydrationRequired = true
                replayingCheckpoint = false
            }
            else -> Unit
        }
        return GameStreamEvent(id, type, data)
    }

    /** canonical 重取后重置：清空游标与回放窗口，下次挂接不带 after。 */
    fun reset() {
        cursor = null
        rehydrationRequired = false
        replayingCheckpoint = false
    }
}
