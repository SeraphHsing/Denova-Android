package com.denova.novara.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Agents 定义与引擎状态契约（P4-C / DEF-001·002）。
 *
 * - `GET /api/settings` → 分层设置快照：`{user, workspace, effective, revisions{user,workspace}, agent_configuration}`
 * - `PATCH /api/settings` body `{layer, base_revision, changes}` → 同一快照
 * - `GET /api/agent-runtimes` → `{items:[{id,name_key,status,reason_key,capabilities,configuration_sections}]}`
 * - `POST /api/agent-runtimes/:id/check` → 该引擎的最新状态描述
 * - `GET /api/agent-runtimes/:id/models` → `{items[],default_id}`
 *
 * 自定义 Agent 定义（`custom_agents[]`）字段很多且随版本演进（model / runtime / tools /
 * skill_policy / runtime_context / context_bindings / delegation / image_api_profile_id …），
 * 客户端用 [CustomAgentDefinition.raw] 原样保留整份定义，只把名称、说明、契约、指令与启用开关
 * 抽成可编辑字段，写回时合回原对象，避免把未展示的配置清空。
 */
@Serializable
data class SettingsRevisions(
    val user: String = "",
    val workspace: String = "",
)

/** 分层设置快照：只解析 Agents 页需要的部分，其余字段原样留在 [raw]。 */
data class SettingsSnapshot(val raw: JsonObject) {

    private fun layerObject(layer: String): JsonObject =
        (raw[layer] as? JsonObject) ?: JsonObject(emptyMap())

    private fun stringField(obj: JsonObject?, key: String): String =
        (obj?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    val revisions: SettingsRevisions
        get() {
            val node = raw["revisions"] as? JsonObject
            return SettingsRevisions(
                user = stringField(node, "user"),
                workspace = stringField(node, "workspace"),
            )
        }

    /** 某一层的自定义 Agent 定义（`user` / `workspace` / `effective`）。 */
    fun customAgents(layer: String): List<CustomAgentDefinition> {
        val array = layerObject(layer)["custom_agents"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element -> element as? JsonObject }.map(::CustomAgentDefinition)
    }

    /** 该层里可写的 `custom_agents` 原始数组（用于拼接 PATCH 的 changes）。 */
    fun customAgentsArray(layer: String): JsonArray =
        layerObject(layer)["custom_agents"] as? JsonArray ?: JsonArray(emptyList())
}

/** 一条自定义 Agent 定义：保留原始 JSON，编辑字段按需读写。 */
data class CustomAgentDefinition(val raw: JsonObject) {

    private fun text(key: String): String = (raw[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun flagOrNull(key: String): Boolean? = (raw[key] as? JsonPrimitive)?.booleanOrNull

    val id: String get() = text("id")
    val name: String get() = text("name")
    val description: String get() = text("description")
    val contract: String get() = text("contract")
    val instructions: String get() = text("instructions")

    /** `enabled` 缺省视为启用（与后端 `CustomAgentEnabled` 一致）。 */
    val enabled: Boolean get() = flagOrNull("enabled") ?: true

    val skillPolicy: JsonObject? get() = raw["skill_policy"] as? JsonObject
    val delegation: JsonObject? get() = raw["delegation"] as? JsonObject

    /** 覆盖可编辑字段后返回新的原始对象（其余配置原样保留）。 */
    fun withEdits(
        name: String = this.name,
        description: String = this.description,
        instructions: String = this.instructions,
        enabled: Boolean = this.enabled,
    ): JsonObject {
        val out = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(raw)
        out["name"] = JsonPrimitive(name.trim())
        out["description"] = JsonPrimitive(description.trim())
        out["instructions"] = JsonPrimitive(instructions)
        out["enabled"] = JsonPrimitive(enabled)
        return JsonObject(out)
    }
}

/** 内置 Agent 引擎描述（`GET /api/agent-runtimes` 的一项）。 */
@Serializable
data class AgentEngineDescriptor(
    val id: String = "",
    @SerialName("name_key") val nameKey: String = "",
    val status: String = "",
    @SerialName("reason_key") val reasonKey: String = "",
)

@Serializable
data class AgentRuntimesResponse(val items: List<AgentEngineDescriptor> = emptyList())

/** 引擎可用模型（`GET /api/agent-runtimes/:id/models`）。 */
@Serializable
data class AgentEngineModel(
    val id: String = "",
    @SerialName("display_name") val displayName: String = "",
    val efforts: List<String> = emptyList(),
    @SerialName("default_effort") val defaultEffort: String = "",
)

@Serializable
data class AgentEngineModels(
    val items: List<AgentEngineModel> = emptyList(),
    @SerialName("default_id") val defaultId: String = "",
)

/** `PATCH /api/settings` 请求体：只提交本层 `custom_agents`。 */
@Serializable
data class SettingsPatchRequest(
    val layer: String,
    @SerialName("base_revision") val baseRevision: String = "",
    val changes: JsonObject,
)

/** `POST /api/sessions`：新建写作会话时可绑定一个自定义 Agent。 */
@Serializable
data class CreateSessionWithAgentRequest(
    val title: String = "",
    @SerialName("custom_agent_id") val customAgentId: String = "",
)
