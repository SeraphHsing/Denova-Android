package com.denova.novara.core.stream

import kotlinx.serialization.SerialName
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

/**
 * `GET /api/session/messages` 的分页元信息（后端 `sessionMessagePageMeta`）。
 *
 * 只有带 `limit` 时才返回；`next_before` 是上一页的游标，`has_more=false` 表示已到历史开头。
 */
@Serializable
data class AgentMessagesMeta(
    @SerialName("next_before") val nextBefore: String = "",
    @SerialName("has_more") val hasMore: Boolean = false,
    val total: Int = 0,
)

/** `GET /api/session/messages?limit=` 的分页响应。 */
@Serializable
data class AgentMessagesPage(
    val messages: List<AgentMessage> = emptyList(),
    val page: AgentMessagesMeta? = null,
)

/**
 * 历史分页的身份合并（AGT-010）。
 *
 * 服务端按 `next_before` 游标返回更早的一页，两页在游标边界可能给出重复消息（分页期间有新消息写入），
 * 因此按 `id` 去重后**前置**：保留既有顺序，只把新出现的更早消息放到前面。
 */
fun mergeOlderMessages(
    current: List<AgentMessage>,
    older: List<AgentMessage>,
): List<AgentMessage> {
    if (older.isEmpty()) return current
    val known = current.mapTo(HashSet()) { it.id }
    val prepended = older.filter { it.id.isNotBlank() && it.id !in known }
    return if (prepended.isEmpty()) current else prepended + current
}

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
