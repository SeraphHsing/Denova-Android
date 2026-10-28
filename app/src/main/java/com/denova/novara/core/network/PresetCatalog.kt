package com.denova.novara.core.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 创作方案（预设）资源契约（P4-C / PLN-001·002），来源基线 `web/src/features/interactive/api.ts`
 * 与后端 `internal/api/handlers/handler_interactive.go`、`internal/interactive/` 各模块库。
 *
 * 六类资源各自一套 CRUD（列表 / 单项 / 创建 / 更新 / 删除），顶层列表键不同：
 *
 * | 类型 | 列表端点 | 顶层键 |
 * |---|---|---|
 * | 叙事导演（teller） | `GET/POST /api/interactive/tellers` | `tellers` |
 * | 策划模板 | `GET/POST /api/game-planning-templates` | `game_planning_templates` |
 * | 事件包 | `GET/POST /api/event-packages` | `event_packages` |
 * | TRPG 检定 | `GET/POST /api/rule-systems` | `rule_systems` |
 * | 状态系统 | `GET/POST /api/actor-states` | `actor_states` |
 * | 图像方案 | `GET/POST /api/image-presets` | `presets` |
 *
 * 更新为 `PATCH {list}/{id}`，body 是「完整定义 + `base_revision`」，冲突返回 409
 * `api.resource.revisionConflict`；删除为 `DELETE {list}/{id}`。
 *
 * 客户端不复制六套 DTO：各类型的嵌套结构由服务端持有且随版本演进，用 [PresetResource.raw]
 * 原样保留整份 JSON，只把可编辑的结构字段抽成一段 JSON 文本，避免丢字段或误删未知键。
 */
enum class PresetKind(
    /** 列表响应里的顶层键。 */
    val listKey: String,
    /** `api/` 之后的路径段（teller 在 `interactive/` 下）。 */
    val apiPath: String,
    /** 该类型可编辑的结构字段（详情 JSON 区域按此顺序输出）。 */
    val detailFields: List<String>,
    /** 只读元数据：回写时必须剥掉，避免把服务端派生的输出字段当成输入。 */
    val metaFields: List<String>,
) {
    TELLER(
        listKey = "tellers",
        apiPath = "interactive/tellers",
        detailFields = listOf("style_refs", "context_policy", "style_rules", "slots"),
        metaFields = emptyList(),
    ),
    PLANNING(
        listKey = "game_planning_templates",
        apiPath = "game-planning-templates",
        detailFields = listOf("sections"),
        metaFields = emptyList(),
    ),
    EVENT(
        listKey = "event_packages",
        apiPath = "event-packages",
        detailFields = listOf("events"),
        metaFields = emptyList(),
    ),
    RULE(
        listKey = "rule_systems",
        apiPath = "rule-systems",
        detailFields = listOf("actor_state_id", "trpg_system"),
        metaFields = emptyList(),
    ),
    ACTOR(
        listKey = "actor_states",
        apiPath = "actor-states",
        detailFields = listOf("actor_state"),
        metaFields = emptyList(),
    ),
    IMAGE(
        listKey = "presets",
        apiPath = "image-presets",
        detailFields = listOf("prompt", "slots"),
        metaFields = emptyList(),
    ),
    ;

    /** ID 允许的字符（teller 允许大写与下划线，其余是小写字母/数字/连字符）。 */
    fun isValidId(id: String): Boolean = when (this) {
        TELLER -> id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        else -> id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }

    companion object {
        /** 服务端列表里的只读输出字段（任何类型都不该回写）。 */
        val READ_ONLY_FIELDS: Set<String> = setOf(
            "path", "revision", "custom", "builtin_overridden", "invalid", "error",
            "created_at", "updated_at",
        )
    }
}

/** 一条预设资源：保留服务端原始 JSON，只读属性按需提取。 */
data class PresetResource(val raw: JsonObject) {

    private fun text(key: String): String = (raw[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun flag(key: String): Boolean = (raw[key] as? JsonPrimitive)?.booleanOrNull ?: false

    val id: String get() = text("id")
    val name: String get() = text("name")
    val description: String get() = text("description")
    val revision: String get() = text("revision")
    val updatedAt: String get() = text("updated_at")
    val path: String get() = text("path")
    val custom: Boolean get() = flag("custom")
    val invalid: Boolean get() = flag("invalid")
    val error: String get() = text("error")

    /** 结构字段的原始 JSON（缺省为空对象）。 */
    fun detailOf(kind: PresetKind): JsonObject = JsonObject(
        kind.detailFields.mapNotNull { field -> raw[field]?.let { field to it } }.toMap(),
    )

    /** 该类型的结构摘要（列表展示用）：事件数 / 模板数 / 规则数… */
    fun summary(kind: PresetKind): String = when (kind) {
        PresetKind.TELLER -> {
            val slots = (raw["slots"] as? JsonArray)?.size ?: 0
            "$slots slots"
        }
        PresetKind.PLANNING -> "${(raw["sections"] as? JsonArray)?.size ?: 0} sections"
        PresetKind.EVENT -> "${(raw["events"] as? JsonArray)?.size ?: 0} events"
        PresetKind.RULE -> {
            val rules = ((raw["trpg_system"] as? JsonObject)?.get("rule_templates") as? JsonArray)?.size ?: 0
            "${rules} checks"
        }
        PresetKind.ACTOR -> {
            val templates = ((raw["actor_state"] as? JsonObject)?.get("templates") as? JsonArray)?.size ?: 0
            "${templates} templates"
        }
        PresetKind.IMAGE -> {
            val slots = (raw["slots"] as? JsonArray)?.size ?: 0
            if (slots > 0) "$slots slots" else if (text("prompt").isNotBlank()) "prompt" else "—"
        }
    }
}
