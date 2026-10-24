package com.denova.novara.core.stream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Agent UI message 流的一帧（AI SDK UI message stream 协议，`data: <json>`）。
 * `type` 区分帧种类：start / text-start|delta|end / reasoning-* / tool-input-* /
 * tool-output-available|error / data-agent-* / error / abort / finish。
 */
@Serializable
data class AgentUiChunk(
    val type: String,
    @SerialName("messageId") val messageId: String? = null,
    val id: String? = null,
    val delta: String? = null,
    @SerialName("toolCallId") val toolCallId: String? = null,
    @SerialName("toolName") val toolName: String? = null,
    @SerialName("errorText") val errorText: String? = null,
    @SerialName("finishReason") val finishReason: String? = null,
    val reason: String? = null,
    val data: JsonElement? = null,
)

/** 解析结果：一个 data chunk，或结束标记 `data: [DONE]`。 */
sealed interface AgentUiFrame {
    data class Chunk(val chunk: AgentUiChunk) : AgentUiFrame

    data object Done : AgentUiFrame
}

/**
 * Agent UI message 流解析器：`data: <json>` 帧，`data: [DONE]` 结束。
 * 复用 [SseParser] 的分帧（该流没有 id/event 行）；未知 type 也原样返回，由上层决定忽略。
 */
class AgentUiStreamParser(
    private val parser: SseParser = SseParser(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    fun feed(text: String): List<AgentUiFrame> = parser.feed(text).mapNotNull(::decodeFrame)

    fun finish(): List<AgentUiFrame> = parser.finish().mapNotNull(::decodeFrame)

    fun reset() = parser.reset()

    private fun decodeFrame(frame: SseFrame): AgentUiFrame? {
        val payload = frame.data ?: return null
        if (payload == DONE_MARKER) return AgentUiFrame.Done
        val chunk = runCatching { json.decodeFromString(AgentUiChunk.serializer(), payload) }.getOrNull()
            ?: return null
        return AgentUiFrame.Chunk(chunk)
    }

    private companion object {
        const val DONE_MARKER = "[DONE]"
    }
}
