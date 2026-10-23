package com.denova.novara.core.stream

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `task_rehydrate_required` 的负载（对齐后端 taskRehydrateRequiredData）。 */
@Serializable
data class RehydrateData(
    val code: String? = null,
    val task_id: String? = null,
    val cursor: Long? = null,
    val settled: Boolean = false,
    val status: String? = null,
    val persistence_required: Boolean? = null,
)

/** 需要整体重取时的重连参数：reload canonical 后用 `after=cursor` 重挂同一 task。 */
data class RehydrateRequest(
    val taskId: String?,
    val cursor: Long?,
    val settled: Boolean,
)

/**
 * 游戏流会话：把原始 SSE 文本解析成 [GameStreamEvent] 并维护恢复状态。
 * 纯逻辑、无 Android / 网络依赖；SSE 网络层（OkHttp）在会话之上喂入文本块。
 */
class GameStreamSession(
    private val state: GameStreamState = GameStreamState(),
    private val parser: SseParser = SseParser(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** 最近确认的游标；重连时作为 `after=` / `Last-Event-ID`。 */
    val cursor: String? get() = state.cursor

    val rehydrationRequired: Boolean get() = state.rehydrationRequired

    val replayingCheckpoint: Boolean get() = state.replayingCheckpoint

    /** 最近一次 `task_rehydrate_required` 的重连参数；null 表示无需重取。 */
    var rehydrateRequest: RehydrateRequest? = null
        private set

    /** 喂入一段 SSE 文本，返回本段解析出的全部游戏事件（已推进恢复状态）。 */
    fun onChunk(text: String): List<GameStreamEvent> {
        val events = mutableListOf<GameStreamEvent>()
        for (frame in parser.feed(text)) {
            applyFrame(frame)?.let { events.add(it) }
        }
        return events
    }

    /** 流结束：flush 解析器剩余缓冲。 */
    fun onEnd(): List<GameStreamEvent> {
        val events = mutableListOf<GameStreamEvent>()
        for (frame in parser.finish()) {
            applyFrame(frame)?.let { events.add(it) }
        }
        return events
    }

    /** canonical 重取后重置：清空游标、回放窗口与 rehydrate 请求，下次挂接不带 after。 */
    fun resetForRehydrate() {
        state.reset()
        parser.reset()
        rehydrateRequest = null
    }

    private fun applyFrame(frame: SseFrame): GameStreamEvent? {
        val event = GameStreamDecoder.decode(frame) ?: return null
        state.onEvent(event.id, event.type, event.data)
        if (event.type == GameEventType.TASK_REHYDRATE_REQUIRED) {
            rehydrateRequest = parseRehydrate(event.data)
        }
        return event
    }

    private fun parseRehydrate(data: String?): RehydrateRequest? {
        if (data.isNullOrBlank()) return null
        val parsed = runCatching { json.decodeFromString(RehydrateData.serializer(), data) }.getOrNull()
            ?: return null
        return RehydrateRequest(parsed.task_id, parsed.cursor, parsed.settled)
    }
}
