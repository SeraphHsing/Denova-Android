package com.denova.novara.core.presets

import com.denova.novara.core.network.PresetKind
import com.denova.novara.core.network.PresetResource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 方案编辑的校验结果；`null` 表示可以提交。 */
enum class PresetEditError {
    NAME_REQUIRED,
    INVALID_ID,
    INVALID_JSON,
    NOT_OBJECT,
    SLOTS_REQUIRED,
    INVALID_SLOT_TARGET,
    SECTIONS_REQUIRED,
    SECTION_FIELDS_REQUIRED,
    TEMPLATES_REQUIRED,
}

/**
 * 创作方案的纯逻辑（P4-C / PLN-001·002）。
 *
 * 六类资源的结构差异只体现在 [PresetKind.detailFields]：编辑器把这几段结构原样抽成 JSON 文本，
 * 保存时再合回整份定义（保留 `version` 等未展示字段），因此新增字段不会因客户端不认识而丢失。
 *
 * 校验只做「提交前能明确判断」的部分：名称、ID 字符集、JSON 语法与对象形状，
 * 以及各类必填结构（导演 slots、策划 sections、状态系统 templates）。服务端仍会二次校验，
 * 其错误原文由界面直接呈现（例如 TRPG 检定必须绑定状态系统的提示）。
 */
object PresetEditor {

    private val pretty = Json { prettyPrint = true }
    private val parser = Json { ignoreUnknownKeys = true; isLenient = false }

    /** 解析结构 JSON 文本；返回错误或对象。 */
    fun parseDetail(text: String): Pair<PresetEditError?, JsonObject?> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null to JsonObject(emptyMap())
        val element = runCatching { parser.parseToJsonElement(trimmed) }.getOrNull()
            ?: return PresetEditError.INVALID_JSON to null
        val obj = element as? JsonObject ?: return PresetEditError.NOT_OBJECT to null
        return null to obj
    }

    /** 结构 JSON 的编辑文本（缺省字段按 [PresetKind.detailFields] 顺序输出）。 */
    fun detailText(kind: PresetKind, resource: PresetResource?): String {
        val detail = resource?.detailOf(kind) ?: JsonObject(emptyMap())
        return if (detail.isEmpty()) defaultDetailText(kind) else pretty.encodeToString(JsonObject.serializer(), detail)
    }

    /** 新建时的结构骨架（只放该类型的最小合法结构，服务端字段仍以服务端校验为准）。 */
    fun defaultDetailText(kind: PresetKind): String = when (kind) {
        PresetKind.TELLER -> """
            {
              "context_policy": { "creator": "", "lore": "", "runtime_state": "" },
              "slots": [
                { "id": "main", "name": "主要提示", "target": "system", "enabled": true, "content": "" }
              ]
            }
        """.trimIndent()
        PresetKind.PLANNING -> """
            {
              "sections": [
                { "id": "overview", "title": "概览", "description": "" }
              ]
            }
        """.trimIndent()
        PresetKind.EVENT -> """
            {
              "events": []
            }
        """.trimIndent()
        PresetKind.RULE -> """
            {
              "actor_state_id": "",
              "trpg_system": { "rule_templates": [] }
            }
        """.trimIndent()
        PresetKind.ACTOR -> """
            {
              "actor_state": { "templates": [], "initial_actors": [], "trait_pools": [] }
            }
        """.trimIndent()
        PresetKind.IMAGE -> """
            {
              "prompt": "",
              "slots": []
            }
        """.trimIndent()
    }

    /** 提交前校验；返回 `null` 表示通过。 */
    fun validate(kind: PresetKind, id: String, name: String, detail: JsonObject): PresetEditError? {
        if (name.isBlank()) return PresetEditError.NAME_REQUIRED
        val trimmedId = id.trim()
        if (trimmedId.isNotEmpty() && !kind.isValidId(trimmedId)) return PresetEditError.INVALID_ID

        return when (kind) {
            PresetKind.TELLER -> validateTeller(detail)
            PresetKind.PLANNING -> validatePlanning(detail)
            PresetKind.ACTOR -> validateActorState(detail)
            else -> null
        }
    }

    private fun validateTeller(detail: JsonObject): PresetEditError? {
        val slots = detail["slots"] as? JsonArray ?: return PresetEditError.SLOTS_REQUIRED
        if (slots.isEmpty()) return PresetEditError.SLOTS_REQUIRED
        for (slot in slots) {
            val target = ((slot as? JsonObject)?.get("target") as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (target != "system" && target != "turn_context") return PresetEditError.INVALID_SLOT_TARGET
        }
        return null
    }

    private fun validatePlanning(detail: JsonObject): PresetEditError? {
        val sections = detail["sections"] as? JsonArray ?: return PresetEditError.SECTIONS_REQUIRED
        if (sections.isEmpty()) return PresetEditError.SECTIONS_REQUIRED
        for (section in sections) {
            val obj = section as? JsonObject ?: return PresetEditError.SECTION_FIELDS_REQUIRED
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            if (id.isEmpty() || title.isEmpty()) return PresetEditError.SECTION_FIELDS_REQUIRED
        }
        return null
    }

    private fun validateActorState(detail: JsonObject): PresetEditError? {
        val state = detail["actor_state"] as? JsonObject ?: return PresetEditError.TEMPLATES_REQUIRED
        val templates = state["templates"] as? JsonArray ?: return PresetEditError.TEMPLATES_REQUIRED
        if (templates.isEmpty()) return PresetEditError.TEMPLATES_REQUIRED
        return null
    }

    /**
     * 组装保存体：
     * - 保留 `base` 里客户端不展示的字段（`version`、未来新增键），保证不丢数据；
     * - 剥掉所有只读输出字段与 `base_revision`（由调用方另行追加）；
     * - 结构字段整体以 [detail] 为准（缺少即删除该键）。
     */
    fun buildSaveBody(
        kind: PresetKind,
        base: JsonObject?,
        id: String,
        name: String,
        description: String,
        detail: JsonObject,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        base?.forEach { (key, value) ->
            if (key in PresetKind.READ_ONLY_FIELDS) return@forEach
            if (key in kind.detailFields) return@forEach
            if (key == "id" || key == "name" || key == "description") return@forEach
            if (key == "base_revision") return@forEach
            out[key] = value
        }
        val trimmedId = id.trim()
        if (trimmedId.isNotEmpty()) out["id"] = JsonPrimitive(trimmedId)
        out["name"] = JsonPrimitive(name.trim())
        out["description"] = JsonPrimitive(description.trim())
        kind.detailFields.forEach { field -> detail[field]?.let { out[field] = it } }
        return JsonObject(out)
    }

    /** 列表排序：内置在前、自定义在后，同组按 ID 升序（与后端 `List` 的排序一致）。 */
    fun sort(resources: List<PresetResource>): List<PresetResource> =
        resources.sortedWith(compareBy({ it.custom }, { it.id }))

    /** 按名称/说明/ID 过滤（列表搜索）。 */
    fun filter(resources: List<PresetResource>, query: String): List<PresetResource> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return resources
        return resources.filter { resource ->
            val haystack = "${resource.id}\n${resource.name}\n${resource.description}".lowercase()
            words.all { haystack.contains(it) }
        }
    }
}
