package com.denova.novara.core.stream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `POST /api/chat/context-analysis` 的投影（AGT-012）。
 *
 * 只解析界面真正展示的字段：用量与压缩计划。`system_prompt` / `context_parts` / `context_messages`
 * 是整段正文，体积可达数万 token，与本行验收无关，故意不进 DTO。
 */
@Serializable
data class AgentContextAnalysis(
    @SerialName("agent_kind") val agentKind: String = "",
    val mode: String = "",
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("token_estimate") val tokenEstimate: Int = 0,
    @SerialName("projected_token_estimate") val projectedTokenEstimate: Int = 0,
    @SerialName("reserved_completion_tokens") val reservedCompletionTokens: Int = 0,
    @SerialName("reserved_tool_result_tokens") val reservedToolResultTokens: Int = 0,
    @SerialName("context_window_tokens") val contextWindowTokens: Int = 0,
    @SerialName("context_usage_ratio") val contextUsageRatio: Double = 0.0,
    @SerialName("compaction_active") val compactionActive: Boolean = false,
    @SerialName("would_compact") val wouldCompact: Boolean = false,
    val compaction: AgentContextCompaction? = null,
) {
    val usagePercent: Int
        get() = (contextUsageRatio * 100).toInt().coerceIn(0, 100)

    /** 「已压缩 / 下一轮会压缩 / 未压缩」三态：服务端只给布尔，界面口径在这里固定一次。 */
    val compactionState: CompactionState
        get() = when {
            compactionActive -> CompactionState.ACTIVE
            wouldCompact -> CompactionState.NEXT_TURN
            else -> CompactionState.NONE
        }
}

@Serializable
data class AgentContextCompaction(
    val revision: Long = 0,
    val summary: String = "",
    @SerialName("tokens_before") val tokensBefore: Int = 0,
    @SerialName("tokens_after") val tokensAfter: Int = 0,
    @SerialName("source_message_count") val sourceMessageCount: Int = 0,
    val removable: Boolean = false,
)

enum class CompactionState { NONE, ACTIVE, NEXT_TURN }

/** 一条待办：来自 `todo` 工具调用入参，只读展示——客户端不另建可写运行时 Todo（矩阵约束）。 */
data class AgentTodoItem(
    val step: String,
    val status: String,
) {
    val done: Boolean get() = status == "completed"
    val running: Boolean get() = status == "in_progress"
}

/** 与 Web `CONTEXT_ANALYSIS_SIMULATED_MESSAGE` 一致：服务端要求 message 非空，用它当探针，不进入历史。 */
const val CONTEXT_ANALYSIS_PROBE = "[Denova context analysis probe]"

/** `POST /api/chat/context-analysis` 的请求体（服务的 `agentChatTurnRequest` 只用到这两个字段）。 */
@Serializable
data class AgentContextAnalysisRequest(
    @SerialName("session_id") val sessionId: String,
    val message: String,
)

/** AGT-012 的纯逻辑：`todo` 工具识别与入参解析、消息流里最新一份清单的取法。 */
object AgentContextLogic {

    /** 服务端工具名是 `todo`；兼容 `todo_write` / `todowrite` 这类写法。 */
    fun isTodoTool(name: String): Boolean {
        val normalized = name.trim().lowercase()
        return normalized == "todo" || normalized == "todo_write" || normalized == "todowrite"
    }

    /**
     * 从工具入参取待办清单。流式期间入参可能是半截 JSON：解析失败返回空表，
     * 界面据 `latestTodos` 保留上一次非空结果，不会闪成空清单。
     */
    fun parseTodos(inputJson: String): List<AgentTodoItem> {
        if (inputJson.isBlank()) return emptyList()
        val root = runCatching { Json.parseToJsonElement(inputJson) }.getOrNull() as? JsonObject ?: return emptyList()
        val array = (root["todos"] as? JsonArray) ?: (root["items"] as? JsonArray) ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val step = item.string("step") ?: item.string("content") ?: return@mapNotNull null
            AgentTodoItem(step = step, status = item.string("status").orEmpty())
        }
    }

    /** 消息流里最新一份非空待办清单（`todo` 工具可能被多次调用）。 */
    fun latestTodos(parts: List<AgentPart>): List<AgentTodoItem> =
        parts.asReversed()
            .filterIsInstance<AgentToolPart>()
            .filter { isTodoTool(it.name) }
            .map { parseTodos(it.inputJson) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull?.trim() }.getOrNull()?.ifBlank { null }
}
