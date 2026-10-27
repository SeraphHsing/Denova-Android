package com.denova.novara.core.stream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** `GET /api/interactive/stories/:id/history` 的响应页。 */
@Serializable
data class StoryHistoryPage(
    @SerialName("story_id") val storyId: String = "",
    @SerialName("branch_id") val branchId: String = "",
    val turns: List<TurnEvent> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("before_cursor") val beforeCursor: String? = null,
)

/**
 * 一个已持久化的 story turn。
 *
 * A1 只用 `user` / `narrative` 重建叙事；A3 起补上行动选项（`turn_result.choices` / `hot_state.choices`）、
 * 状态增量（`state_delta`）与状态就绪位，供行动选项与状态台账使用。
 * 其余字段（`display_events` / `rule_resolution` / `versions` 等）仍由 `ignoreUnknownKeys` 忽略。
 */
@Serializable
data class TurnEvent(
    val id: String = "",
    val type: String = "",
    val user: String? = null,
    val narrative: String? = null,
    val ts: String = "",
    @SerialName("branch_id") val branchId: String = "",
    @SerialName("user_context_only") val userContextOnly: Boolean = false,
    @SerialName("hot_state") val hotState: HotState? = null,
    @SerialName("turn_result") val turnResult: TurnResult? = null,
    @SerialName("state_delta") val stateDelta: TurnStateDelta? = null,
    @SerialName("state_status") val stateStatus: String = "",
    @SerialName("state_error") val stateError: String = "",
)

/** 推荐行动：`turn_result.choices` 优先，缺失时回退 `hot_state.choices`（与 Web 一致）。 */
@Serializable
data class TurnResult(
    val choices: List<String> = emptyList(),
)

@Serializable
data class HotState(
    val choices: List<String> = emptyList(),
)

/** 该轮的规范状态增量；`ops` 是普通路径，`actor_ops` 是角色字段（`actor_id` + `field_id`）。 */
@Serializable
data class TurnStateDelta(
    @SerialName("schema_version") val schemaVersion: Int = 0,
    val ops: List<StateOp> = emptyList(),
    @SerialName("actor_ops") val actorOps: List<ActorStateOp> = emptyList(),
)

@Serializable
data class StateOp(
    val op: String = "",
    val path: String = "",
    val value: JsonElement? = null,
    val reason: String = "",
)

@Serializable
data class ActorStateOp(
    val op: String = "",
    @SerialName("actor_id") val actorId: String = "",
    @SerialName("field_id") val fieldId: String = "",
    val value: JsonElement? = null,
    val reason: String = "",
)

/** 从 canonical 历史 turn 列表重建叙事基线（玩家输入 + agent 叙事交替）。 */
fun buildCanonicalNarrative(turns: List<TurnEvent>): String =
    turns.mapNotNull { turn -> StoryTurnText.of(turn).takeIf { it.isNotBlank() } }
        .joinToString("\n\n")
