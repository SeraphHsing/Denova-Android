package com.denova.novara.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 「新建故事」表单用到的资源列表契约（P4-A / GAM-001）。
 *
 * 六个导演模块资源各自有独立顶层键（不是统一数组），字段名以固定基线后端为准：
 * - `GET /api/interactive/tellers` → `{tellers[], default_id}`
 * - `GET /api/game-planning-templates` → `{game_planning_templates[]}`
 * - `GET /api/event-packages` → `{event_packages[]}`
 * - `GET /api/rule-systems` → `{rule_systems[]}`
 * - `GET /api/actor-states` → `{actor_states[]}`
 * - `GET /api/image-presets` → `{presets[]}`（键不是 image_presets）
 *
 * 表单只展示 `id` / `name` / `description`，其余字段（slots、templates、events…）由服务端持有，
 * 客户端不回写，因此不解析。
 */
@Serializable
data class NamedResource(
    val id: String,
    val name: String = "",
    val description: String = "",
)

@Serializable
data class TellersResponse(
    val tellers: List<NamedResource> = emptyList(),
    @SerialName("default_id") val defaultId: String = "",
)

@Serializable
data class PlanningTemplatesResponse(
    @SerialName("game_planning_templates") val templates: List<NamedResource> = emptyList(),
)

@Serializable
data class EventPackagesResponse(
    @SerialName("event_packages") val packages: List<NamedResource> = emptyList(),
)

@Serializable
data class RuleSystemsResponse(
    @SerialName("rule_systems") val systems: List<NamedResource> = emptyList(),
)

@Serializable
data class ActorStatesResponse(
    @SerialName("actor_states") val states: List<NamedResource> = emptyList(),
)

@Serializable
data class ImagePresetsResponse(
    val presets: List<NamedResource> = emptyList(),
)

/**
 * 资料库条目（主角 `mode=lore` 的候选来源）：
 * `GET /api/projects/:id/book/lore/items`。Web 选择器的过滤条件是
 * `enabled && type === 'character'`（`StoryProtagonistSelector`）。
 */
@Serializable
data class LoreItemSummary(
    val id: String,
    val name: String = "",
    val type: String = "",
    val enabled: Boolean = true,
    val tags: List<String> = emptyList(),
    @SerialName("brief_description") val briefDescription: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    // ---- 资料库页面（P4-C / LOR-001…004）用的完整字段；游戏侧选择器只读上面的子集 ----
    @SerialName("type_source") val typeSource: String = "",
    val importance: String = "",
    val keywords: List<String> = emptyList(),
    @SerialName("load_mode") val loadMode: String = "",
    @SerialName("index_memberships") val indexMemberships: List<LoreIndexMembership> = emptyList(),
    val content: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val image: LoreImage? = null,
    val materials: LoreMaterials? = null,
    @SerialName("resolved_materials") val resolvedMaterials: List<LoreMaterial> = emptyList(),
    @SerialName("detail_fields") val detailFields: Int = 0,
)

@Serializable
data class LoreItemsResponse(
    @SerialName("project_id") val projectId: String = "",
    val items: List<LoreItemSummary> = emptyList(),
)

/** 资料库主角候选：只保留启用中的角色条目（与 Web 选择器同口径）。 */
fun List<LoreItemSummary>.characterLoreItems(): List<LoreItemSummary> =
    filter { it.enabled && it.type == "character" }
