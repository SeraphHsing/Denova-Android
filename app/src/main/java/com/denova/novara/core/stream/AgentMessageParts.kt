package com.denova.novara.core.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 完整消息部件（AGT-001）：把 `GET /api/session/messages` 与流里的 AI SDK parts 解析成可渲染模型。
 *
 * 与服务端 `internal/api/agentui/message.go` 一一对应：`text` / `reasoning` / `dynamic-tool` /
 * `file` / `data-agent-*`；未知类型不丢弃，落 [AgentUnknownPart] 供界面按原样展开，保证
 * 「保留完整 parts 和稳定身份」。
 */

/** 部件身份：`消息 id # 序号`。历史重载后同一部件得到同一 id，便于界面稳定复用。 */
sealed interface AgentPart {
    val id: String
    val type: String
}

data class AgentTextPart(
    override val id: String,
    val text: String,
    /** 流式未完成（`state == "streaming"`）。 */
    val streaming: Boolean = false,
) : AgentPart {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "text"
    }
}

data class AgentReasoningPart(
    override val id: String,
    val text: String,
) : AgentPart {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "reasoning"
    }
}

enum class ToolCallStatus { RUNNING, OK, FAIL }

data class AgentToolPart(
    override val id: String,
    val callId: String,
    val name: String,
    /** AI SDK 原生 state：input-available / output-available / output-error / output-denied … */
    val state: String,
    val inputJson: String,
    val outputText: String,
    val errorText: String,
    val status: ToolCallStatus,
) : AgentPart {
    override val type: String get() = TYPE

    /** 一行摘要：优先工具入参里的 `description`，否则命令/路径，再否则工具名。 */
    val summary: String
        get() = AgentPartLogic.summarizeToolInput(inputJson) ?: name

    /** 工具产物里的图片路径（生成图工具不在 `file` 部件里下发，只能从结果取）。 */
    val imagePaths: List<String>
        get() = AgentPartLogic.imagePaths(outputText)

    companion object {
        const val TYPE = "dynamic-tool"
    }
}

/** 图片 / 附件部件。`url` 可能是 `data:`、绝对 http(s) 或工作区相对路径。 */
data class AgentImagePart(
    override val id: String,
    val url: String,
    val mediaType: String,
    val label: String,
) : AgentPart {
    override val type: String get() = TYPE

    val isRemote: Boolean get() = url.startsWith("http://") || url.startsWith("https://")
    val isInline: Boolean get() = url.startsWith("data:")
    val isWorkspacePath: Boolean get() = !isRemote && !isInline && url.isNotBlank()

    companion object {
        const val TYPE = "file"
    }
}

/**
 * `data-agent-*` 部件：提问、用量、执行摘要、Todo、计划、工作区变更等。
 * [summary] 是本地提炼的一行文案，[rawJson] 保留原样供展开。
 */
data class AgentDataPart(
    override val id: String,
    override val type: String,
    val summary: String,
    val rawJson: String,
) : AgentPart

data class AgentUnknownPart(
    override val id: String,
    override val type: String,
    val rawJson: String,
) : AgentPart

/** 一条消息的渲染视图：角色 + 有序部件。 */
data class AgentMessageView(
    val id: String,
    val role: String,
    val parts: List<AgentPart>,
) {
    val isUser: Boolean get() = role == "user"
}

object AgentPartLogic {

    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"

    const val STATE_STREAMING = "streaming"

    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json { prettyPrint = true }

    /** 解析一条消息的全部部件；顺序与原文一致，未知类型也保留。 */
    fun parseMessage(message: AgentMessage, identity: String = message.id): AgentMessageView = AgentMessageView(
        id = identity,
        role = message.role,
        parts = message.parts.mapIndexedNotNull { index, part -> parsePart(identity, index, part) },
    )

    /**
     * 历史消息的 id 不保证唯一：`token_usage` 与 `execution_summary` 两条消息都以 run id 作 id
     * （真机复现：LazyColumn 重复 key 直接崩溃）。这里用「消息 id@位置」做稳定身份，
     * 既保证部件 id 唯一，又保持同一条历史在多次重载后得到同一 key。
     */
    fun parseMessages(messages: List<AgentMessage>): List<AgentMessageView> =
        messages.mapIndexedNotNull { index, message ->
            val base = message.id.ifBlank { "msg" }
            val view = parseMessage(message, identity = "$base@$index")
            if (view.parts.isEmpty()) null else view
        }

    private fun parsePart(messageId: String, index: Int, part: JsonObject): AgentPart? {
        val id = "$messageId#$index"
        val type = part.str("type") ?: return null
        return when {
            type == AgentTextPart.TYPE -> AgentTextPart(
                id = id,
                text = part.str("text").orEmpty(),
                streaming = part.str("state") == STATE_STREAMING,
            )

            type == AgentReasoningPart.TYPE -> AgentReasoningPart(
                id = id,
                text = part.str("text").orEmpty(),
            )

            type == AgentToolPart.TYPE || type.startsWith("tool-") -> toolPart(id, type, part)

            type == AgentImagePart.TYPE -> imagePart(id, part)

            type.startsWith("data-") -> AgentDataPart(
                id = id,
                type = type,
                summary = dataSummary(type, part["data"] ?: part),
                rawJson = pretty(part),
            )

            else -> AgentUnknownPart(id = id, type = type, rawJson = pretty(part))
        }
    }

    private fun toolPart(id: String, type: String, part: JsonObject): AgentToolPart {
        val name = part.str("toolName")
            ?: part.str("name")
            ?: type.removePrefix("tool-").ifBlank { "tool" }
        val output = part["output"]?.let(::rawText).orEmpty()
        val errorText = part.str("errorText").orEmpty()
        val state = part.str("state").orEmpty()
        return AgentToolPart(
            id = id,
            callId = part.str("toolCallId") ?: id,
            name = name,
            state = state,
            inputJson = part["input"]?.let(::pretty).orEmpty(),
            outputText = output,
            errorText = errorText,
            status = statusOf(state, output, errorText),
        )
    }

    private fun imagePart(id: String, part: JsonObject): AgentImagePart? {
        val url = part.str("url") ?: part.str("data") ?: part.str("image_path") ?: return null
        if (url.isBlank()) return null
        val mediaType = part.str("mediaType") ?: part.str("media_type") ?: ""
        return AgentImagePart(
            id = id,
            url = url,
            mediaType = mediaType,
            label = part.str("filename") ?: part.str("name") ?: url.takeLast(32),
        )
    }

    /** 以工具执行结果为准判状态：`output` 里的 `status` 字段比 AI SDK state 更细。 */
    fun statusOf(state: String, output: String, errorText: String): ToolCallStatus {
        if (errorText.isNotBlank()) return ToolCallStatus.FAIL
        if (state == "output-error" || state == "output-denied") return ToolCallStatus.FAIL
        val envelope = runCatching { json.parseToJsonElement(output) as? JsonObject }.getOrNull()
        val status = (envelope?.get("status") as? JsonPrimitive)?.contentOrNull
            ?: (envelope?.get("state") as? JsonPrimitive)?.contentOrNull
        return when {
            status == null -> if (state == "output-available") ToolCallStatus.OK else ToolCallStatus.RUNNING
            status.equals("success", true) || status.equals("ok", true) || status.equals("completed", true) ->
                ToolCallStatus.OK
            status.equals("running", true) || status.equals("pending", true) -> ToolCallStatus.RUNNING
            else -> ToolCallStatus.FAIL
        }
    }

    /** `data-agent-*` 的一行摘要；提炼不出来时退回 `content` 或类型名。 */
    fun dataSummary(type: String, data: JsonElement?): String {
        val obj = data as? JsonObject ?: return type
        val strings = listOfNotNull(
            obj.str("content"),
            obj.str("message"),
            obj.str("summary"),
            obj.str("text"),
        ).firstOrNull { it.isNotBlank() }
        val detail = when (type) {
            "data-agent-token-usage" -> listOfNotNull(
                obj.str("total_tokens")?.let { "total_tokens=$it" },
                obj.str("model_calls")?.let { "model_calls=$it" },
            ).joinToString(" ")

            "data-agent-execution-summary" -> listOfNotNull(
                obj.str("run_status"),
                obj.str("duration_ms")?.let { "duration_ms=$it" },
            ).joinToString(" ")

            "data-agent-ask" -> listOfNotNull(obj.str("kind"), obj.str("status")).joinToString(" / ")

            "data-agent-workspace-change" -> listOfNotNull(
                obj.str("apply_state"),
                (obj["paths"] as? JsonArray)?.size?.let { "paths=$it" },
            ).joinToString(" ")

            "data-agent-todo" -> (obj["todos"] as? JsonArray)?.size?.let { "todos=$it" }.orEmpty()

            "data-agent-proposed-plan" -> obj.str("title").orEmpty()

            else -> ""
        }
        // `content` 已经是服务端提炼好的整句，再拼细节会重复（真机里出现「… model_calls=2 · model_calls=2」）。
        if (!strings.isNullOrBlank()) return strings
        return detail.ifBlank { type }
    }

    /**
     * 从工具输出里提取图片路径：`generated_image.v1` 的 `images[].path`，或直接的
     * `image_path` / `target_path`。服务端不把生成图作为 `file` 部件下发（真机核对），
     * 因此图片来源必须从工具结果里取，才能做到「图片来源可访问」。
     */
    fun imagePaths(outputText: String): List<String> {
        if (outputText.isBlank()) return emptyList()
        val obj = runCatching { json.parseToJsonElement(outputText) as? JsonObject }.getOrNull()
            ?: return emptyList()
        val fromArray = (obj["images"] as? JsonArray)
            ?.mapNotNull { item -> (item as? JsonObject)?.let { it.str("path") ?: it.str("image_path") } }
            .orEmpty()
        val single = listOfNotNull(obj.str("image_path"), obj.str("target_path"))
        return (fromArray + single).map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    /** 工具入参摘要：`description` > `command` > `path` > `query` > `url`。 */
    fun summarizeToolInput(inputJson: String): String? {
        val obj = runCatching { json.parseToJsonElement(inputJson) as? JsonObject }.getOrNull() ?: return null
        val keys = listOf("description", "command", "path", "query", "url", "prompt")
        val value = keys.firstNotNullOfOrNull { key -> obj.str(key)?.takeIf { it.isNotBlank() } }
        return value?.replace("\n", " ")?.let { if (it.length > 120) it.take(117) + "…" else it }
    }

    /** 一条消息里的纯文本（只取 `text` 部件，按原顺序拼接）——用于核对「这条命令有没有落到服务端」。 */
    fun messageText(message: AgentMessage): String = message.parts
        .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == AgentTextPart.TYPE }
        .mapNotNull { (it["text"] as? JsonPrimitive)?.contentOrNull }
        .joinToString("")
        .trim()

    /** 格式化 JSON（能解析则两空格缩进），超过 [maxChars] 截断并标注原长。 */
    fun prettyText(raw: String, maxChars: Int = 1200): String {
        if (raw.isBlank()) return ""
        val formatted = runCatching {
            val element = json.parseToJsonElement(raw)
            prettyJson.encodeToString(JsonElement.serializer(), element)
        }.getOrDefault(raw)
        return if (formatted.length <= maxChars) {
            formatted
        } else {
            formatted.take(maxChars) + "\n…（共 ${formatted.length} 字符，已截断）"
        }
    }

    private fun pretty(part: JsonObject): String =
        runCatching { json.encodeToString(JsonObject.serializer(), part) }.getOrDefault(part.toString())

    private fun pretty(element: JsonElement): String = when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        else -> runCatching { json.encodeToString(JsonElement.serializer(), element) }.getOrDefault(element.toString())
    }

    private fun rawText(element: JsonElement): String = when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        else -> runCatching { json.encodeToString(JsonElement.serializer(), element) }.getOrDefault(element.toString())
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
