package com.denova.novara.core.network

import com.denova.novara.core.stream.GameStreamDecoder
import com.denova.novara.core.stream.GameStreamEvent
import com.denova.novara.core.stream.SseParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.BufferedSource

/**
 * 从 [BufferedSource] 逐行读取 SSE 帧并解码为 [GameStreamEvent]。
 * 用 `readUtf8Line()` 逐行读（正确处理 UTF-8 与 `\r\n`），喂给 [SseParser] 分帧。
 * 与网络解耦：可直接用 okio.Buffer 喂入做 JVM 单测。
 */
fun sseEventsFromSource(source: BufferedSource): Flow<GameStreamEvent> = flow {
    val parser = SseParser()
    while (true) {
        val line = source.readUtf8Line() ?: break
        for (frame in parser.feed("$line\n")) {
            GameStreamDecoder.decode(frame)?.let { emit(it) }
        }
    }
    for (frame in parser.finish()) {
        GameStreamDecoder.decode(frame)?.let { emit(it) }
    }
}
