package com.denova.novara.core.stream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/interactive/stories/:id/history` 的响应页。 */
@Serializable
data class StoryHistoryPage(
    @SerialName("story_id") val storyId: String = "",
    @SerialName("branch_id") val branchId: String = "",
    val turns: List<TurnEvent> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** 一个已持久化的 story turn（只解析 canonical 叙事重建所需的字段）。 */
@Serializable
data class TurnEvent(
    val id: String = "",
    val type: String = "",
    val user: String? = null,
    val narrative: String? = null,
)

/** 从 canonical 历史 turn 列表重建叙事基线（玩家输入 + agent 叙事交替）。 */
fun buildCanonicalNarrative(turns: List<TurnEvent>): String =
    turns.mapNotNull { turn ->
        buildString {
            // 跳过内部提示（如 `[Source: story opening configuration...]`），不展示给用户。
            val user = turn.user?.takeIf { it.isNotBlank() && !it.trimStart().startsWith("[Source:") }
            if (user != null) append("> ").append(user.trim())
            if (!turn.narrative.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(turn.narrative.trim())
            }
        }.takeIf { it.isNotBlank() }
    }.joinToString("\n\n")
