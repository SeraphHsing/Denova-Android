package com.denova.novara.core.network

import com.denova.novara.core.stream.TurnEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 游戏快照契约（P4-A / GAM-003、GAM-006）：`GET /api/interactive/stories/:id/snapshot?branch=`。
 *
 * `turns` 只是**规范日志的尾部窗口**（`turn_start` 之前的轮次被省略，`has_earlier_turns` 表示可继续翻页）；
 * 更早历史走 `/history`。`state` 是折叠后的规范状态，是状态台账的唯一数据源；
 * `current_turn` 是分支头轮次，行动选项也从它取。
 *
 * 只解析客户端要用的字段；`pending_display_events` / `token_usage_events` 等 Agent 运行时投影忽略。
 */
@Serializable
data class InteractiveSnapshot(
    @SerialName("story_id") val storyId: String = "",
    @SerialName("branch_id") val branchId: String = "",
    val turns: List<TurnEvent> = emptyList(),
    @SerialName("current_turn") val currentTurn: TurnEvent? = null,
    val state: JsonObject = JsonObject(emptyMap()),
    @SerialName("actor_state_schema") val actorStateSchema: ActorStateSchema? = null,
    @SerialName("branch_plan") val branchPlan: BranchPlan? = null,
    val graph: StoryGraph = StoryGraph(),
    @SerialName("turn_count") val turnCount: Int = 0,
    @SerialName("turn_start") val turnStart: Int = 0,
    @SerialName("history_before_cursor") val historyBeforeCursor: String? = null,
    @SerialName("has_earlier_turns") val hasEarlierTurns: Boolean = false,
    @SerialName("context_revision") val contextRevision: Long = 0,
)

/** `actor_state_schema.system.templates[].fields[]` 提供角色字段的显示名与顺序。 */
@Serializable
data class ActorStateSchema(
    val version: Int = 0,
    val revision: Int = 0,
    val system: ActorStateSystem = ActorStateSystem(),
)

@Serializable
data class ActorStateSystem(
    val templates: List<ActorStateTemplate> = emptyList(),
)

@Serializable
data class ActorStateTemplate(
    val id: String = "",
    val name: String = "",
    val fields: List<ActorStateField> = emptyList(),
)

@Serializable
data class ActorStateField(
    val id: String = "",
    val path: String = "",
    val name: String = "",
    val type: String = "",
    val group: String = "",
)

@Serializable
data class BranchPlan(
    val markdown: String = "",
    val revision: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("updated_turn_id") val updatedTurnId: String = "",
)

@Serializable
data class StoryGraph(
    val nodes: List<PlotNode> = emptyList(),
    val branches: List<BranchSummary> = emptyList(),
)

@Serializable
data class PlotNode(
    val id: String = "",
    @SerialName("parent_id") val parentId: String = "",
    @SerialName("branch_id") val branchId: String = "",
    val title: String = "",
    val current: Boolean = false,
    val head: Boolean = false,
    val terminal: Boolean = false,
)

@Serializable
data class BranchSummary(
    val id: String = "",
    val head: String = "",
    val title: String = "",
    val current: Boolean = false,
    @SerialName("from_event") val fromEvent: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)

// ---- 分支与分支方案（P4-A / GAM-008、GAM-009）----

/** `GET /api/interactive/stories/:id/branches`。 */
@Serializable
data class BranchesResponse(
    val branches: List<BranchSummary> = emptyList(),
)

/** `POST .../branches`：从某一轮分叉（`parent_event_id` 就是该轮 turn id）。 */
@Serializable
data class CreateBranchRequest(
    @SerialName("parent_event_id") val parentEventId: String,
    val title: String = "",
)

/** `POST .../switch-branch`。 */
@Serializable
data class SwitchBranchRequest(
    @SerialName("branch_id") val branchId: String,
)

/** `PUT .../branches/:branchId/plan`：方案是带 revision 的文档，冲突返回 409。 */
@Serializable
data class UpdateBranchPlanRequest(
    val markdown: String,
    @SerialName("base_revision") val baseRevision: String,
)

@Serializable
data class UpdateBranchPlanResult(
    @SerialName("branch_plan") val branchPlan: BranchPlan = BranchPlan(),
    @SerialName("context_revision") val contextRevision: Long = 0,
)
