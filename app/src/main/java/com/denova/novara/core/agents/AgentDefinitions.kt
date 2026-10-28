package com.denova.novara.core.agents

import com.denova.novara.R
import com.denova.novara.core.network.CustomAgentDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 内置 Agent 的分组（对齐基线 `agents.group.*`）。 */
enum class AgentGroup { GENERAL, WRITING, INTERACTIVE, UTILITY, VERSION }

/**
 * 一个内置 Agent 视图定义（基线 `agent-registry.ts` 的 `AGENTS`）。
 *
 * [customizable] 对应后端 `CustomizableAgentKinds()`：只有 general / ide /
 * interactive_story / image 四类能被「克隆」成自定义定义；其余（version_summary /
 * tool_agent）是模型单点用途，只读展示。
 */
data class BuiltinAgent(
    val key: String,
    val titleRes: Int,
    val subtitleRes: Int,
    val group: AgentGroup,
    val customizable: Boolean,
)

/**
 * Agents 定义与引擎状态的纯逻辑（P4-C / DEF-001·002）。
 *
 * 契约事实来源：`web/src/features/agents/agent-contracts.ts`（kind ↔ contract 映射）与
 * `config/custom_agents.go`（`CustomAgentConfig` 字段、ID 规范化）。
 * 自定义定义的写回只落在 `user` 层（跨项目），`workspace` 层编辑属设置页 P4-D 的范围。
 */
object AgentDefinitions {

    /** `AGENT_CONTRACT_BY_KIND`：自定义定义的 `contract` 是稳定的运行边界，不是继承链接。 */
    val contractByKind: Map<String, String> = linkedMapOf(
        "general" to "project.general.v1",
        "ide" to "writing.primary.v1",
        "interactive_story" to "game.narrator.v1",
        "image" to "image.creator.v1",
    )

    /** 可定制的运行类型（与服务端一致）。 */
    val customizableKinds: List<String> = contractByKind.keys.toList()

    /** 内置 Agent 视图（顺序照基线 `AGENTS`）。 */
    val builtinAgents: List<BuiltinAgent> = listOf(
        BuiltinAgent("general", R.string.agents_general_title, R.string.agents_general_subtitle, AgentGroup.GENERAL, true),
        BuiltinAgent("ide", R.string.agents_ide_title, R.string.agents_ide_subtitle, AgentGroup.WRITING, true),
        BuiltinAgent(
            "interactive_story",
            R.string.agents_interactive_title,
            R.string.agents_interactive_subtitle,
            AgentGroup.INTERACTIVE,
            true,
        ),
        BuiltinAgent("image", R.string.agents_image_title, R.string.agents_image_subtitle, AgentGroup.UTILITY, true),
        BuiltinAgent(
            "version_summary",
            R.string.agents_version_title,
            R.string.agents_version_subtitle,
            AgentGroup.VERSION,
            false,
        ),
        BuiltinAgent("tool_agent", R.string.agents_tool_title, R.string.agents_tool_subtitle, AgentGroup.UTILITY, false),
    )

    fun contractFor(kind: String): String = contractByKind[kind] ?: "project.general.v1"

    /** 由契约反查运行类型；未知契约（含空）返回 null。 */
    fun kindFor(contract: String): String? =
        contractByKind.entries.firstOrNull { it.value == contract }?.key

    /**
     * ID 规范化：与后端 `NormalizeSubAgentID` 同口径——小写、只保留 `a-z0-9-_`、
     * 其它字符折叠成单个 `-`、去掉首尾分隔符。
     */
    fun normalizeId(raw: String): String {
        val lowered = raw.trim().lowercase()
        val out = StringBuilder()
        var lastSeparator = false
        for (ch in lowered) {
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> {
                    out.append(ch)
                    lastSeparator = false
                }
                ch == '-' || ch == '_' -> {
                    if (!lastSeparator && out.isNotEmpty()) {
                        out.append(ch)
                        lastSeparator = true
                    }
                }
                else -> {
                    if (!lastSeparator && out.isNotEmpty()) {
                        out.append('-')
                        lastSeparator = true
                    }
                }
            }
        }
        return out.toString().trim('-', '_')
    }

    /** 新建时的 ID（服务端不会为自定义 Agent 生成 ID，必须客户端给出稳定身份）。 */
    fun newId(nowMillis: Long, random: String = "0"): String =
        normalizeId("custom-agent-$nowMillis-$random").ifEmpty { "custom-agent-$nowMillis" }

    /** 生成完整定义（克隆内置契约时只带最小可编辑字段，其余交给服务端默认）。 */
    fun newDefinition(
        id: String,
        name: String,
        description: String,
        contract: String,
        instructions: String = "",
    ): JsonObject = buildJsonObject {
        // 规范化后为空（例如全中文 ID）时退回时间戳式 ID，避免服务端按空 ID 丢弃整条定义。
        put("id", normalizeId(id).ifEmpty { newId(System.currentTimeMillis()) })
        put("name", name.trim())
        put("description", description.trim())
        put("contract", contract)
        put("enabled", true)
        put("instructions", instructions)
    }

    /** 校验可编辑字段；返回 null 表示通过。 */
    fun validate(name: String, contract: String): AgentEditError? {
        if (name.isBlank()) return AgentEditError.NAME_REQUIRED
        if (kindFor(contract) == null) return AgentEditError.INVALID_CONTRACT
        return null
    }

    /** 按 ID 合并：存在则替换、不存在则追加（对齐后端 `MergeCustomAgents` 的整份覆盖语义）。 */
    fun upsert(agents: List<CustomAgentDefinition>, updated: JsonObject): List<CustomAgentDefinition> {
        val id = (updated["id"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (id.isEmpty()) return agents
        val replaced = agents.map { existing ->
            if (existing.id == id) CustomAgentDefinition(updated) else existing
        }
        return if (agents.any { it.id == id }) replaced else replaced + CustomAgentDefinition(updated)
    }

    /** 按 ID 删除；不存在时原样返回。 */
    fun remove(agents: List<CustomAgentDefinition>, id: String): List<CustomAgentDefinition> =
        agents.filterNot { it.id == id }

    /** `PATCH changes` 的 `custom_agents` 数组（完整数组，服务端按 ID 合并）。 */
    fun changesOf(agents: List<CustomAgentDefinition>): JsonObject =
        buildJsonObject { put("custom_agents", JsonArray(agents.map { it.raw })) }

    /** 可见定义：必须有 ID、名称与可识别的契约（对齐基线 `isVisibleCustomAgent`）。 */
    fun visible(agents: List<CustomAgentDefinition>): List<CustomAgentDefinition> =
        agents.filter {
            it.id.isNotBlank() && it.name.isNotBlank() && kindFor(it.contract) != null && it.enabled
        }

    /** 自定义定义的运行类型；未知契约归为 `general`（仅用于分组展示）。 */
    fun runtimeKindOf(agent: CustomAgentDefinition): String = kindFor(agent.contract) ?: "general"

    /** 按内置类型分组：内置条目在前，自定义条目挂到对应类型的组里。 */
    fun grouped(
        agents: List<CustomAgentDefinition>,
    ): List<Pair<BuiltinAgent, List<CustomAgentDefinition>>> =
        builtinAgents.map { builtin ->
            builtin to visible(agents).filter { runtimeKindOf(it) == builtin.key }
        }

    /** 引擎状态的展示文案（基线 `agentRuntime.status.*`）。 */
    fun engineStatusRes(status: String): Int = when (status) {
        "ready" -> R.string.agents_engine_ready
        "unchecked" -> R.string.agents_engine_unchecked
        "not_installed" -> R.string.agents_engine_not_installed
        "auth_required" -> R.string.agents_engine_auth_required
        "incompatible" -> R.string.agents_engine_incompatible
        else -> R.string.agents_engine_unavailable
    }
}

/** 自定义 Agent 表单校验结果。 */
enum class AgentEditError {
    NAME_REQUIRED,
    INVALID_CONTRACT,
}
