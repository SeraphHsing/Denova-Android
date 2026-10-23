package com.denova.novara.core.stream

/** 把解析后的 [SseFrame] 映射为 [GameStreamEvent]（event 名 → 枚举）。 */
object GameStreamDecoder {
    fun decode(frame: SseFrame): GameStreamEvent? {
        val eventName = frame.event ?: return null
        return GameStreamEvent(frame.id, GameEventType.fromWireName(eventName), frame.data)
    }
}
