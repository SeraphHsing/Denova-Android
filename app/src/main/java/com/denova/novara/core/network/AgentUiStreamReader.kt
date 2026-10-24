package com.denova.novara.core.network

import com.denova.novara.core.stream.AgentUiFrame
import com.denova.novara.core.stream.AgentUiStreamParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.BufferedSource

/**
 * 从 [BufferedSource] 逐行读 AI SDK UI message 流（`data: <json>` / `data: [DONE]`）
 * 并解析为 [AgentUiFrame]。与网络解耦，可用 okio.Buffer 做 JVM 单测。
 */
fun agentFramesFromSource(source: BufferedSource): Flow<AgentUiFrame> = flow {
    val parser = AgentUiStreamParser()
    while (true) {
        val line = source.readUtf8Line() ?: break
        for (frame in parser.feed("$line\n")) emit(frame)
    }
    for (frame in parser.finish()) emit(frame)
}
