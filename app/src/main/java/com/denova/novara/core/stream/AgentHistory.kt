package com.denova.novara.core.stream

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** AI SDK UIMessage（`GET /api/session/messages` 的元素）。 */
@Serializable
data class AgentMessage(
    val id: String = "",
    val role: String = "",
    val parts: List<JsonObject> = emptyList(),
)

/** `GET /api/session/messages?limit=` 的分页响应。 */
@Serializable
data class AgentMessagesPage(
    val messages: List<AgentMessage> = emptyList(),
)

/**
 * 从 canonical 消息历史重建文本基线：玩家消息以 `> ` 前缀，助手正文直接拼接。
 * 只取 `type=="text"` 的 part；reasoning/tool part 不进基线。
 */
fun buildAgentHistoryNarrative(messages: List<AgentMessage>): String =
    messages.mapNotNull { message ->
        val text = message.parts
            .filter { part -> (part["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            .mapNotNull { part -> (part["text"] as? JsonPrimitive)?.contentOrNull }
            .joinToString("")
            .trim()
        if (text.isEmpty()) {
            null
        } else if (message.role == "user") {
            "> $text"
        } else {
            text
        }
    }.joinToString("\n\n")
