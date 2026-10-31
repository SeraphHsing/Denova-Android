package com.denova.novara.feature.trajectory

import com.denova.novara.core.network.AgentRunRecord
import com.denova.novara.core.network.AgentRunTrace
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 模型可见记录重建（纯逻辑）。
 *
 * 对齐 Web `web/src/features/trajectory/trajectory-content.ts` 的「对话」视图：
 * - 每个 `llm_input` 视为一次请求，按时间排序后编号（`Request #n`）；
 * - 首条 System Prompt 记为 `Initial System Prompt`，其后内容或工具定义变化记 `System Prompt Update`；
 * - 相邻请求之间用消息指纹的公共前缀判断新增消息，前缀缩短说明上下文被重建/压缩，插入 `Input Snapshot Changed`；
 * - 输出消息取 `llm_output.content.message`；工具调用按 `tool_calls[].id` 与后续 `role=tool` 消息配对。
 *
 * 未实现（登记在 09，随 P5 逐屏细化）：`debug-input` / `debug-output` 全量条目与 inline/side 切换。
 */
enum class TrajectoryContentKind { SYSTEM, USER, CONTEXT, ASSISTANT, TOOL }

data class TrajectoryToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class TrajectoryContentEntry(
    val id: String,
    val kind: TrajectoryContentKind,
    val direction: String,
    val requestIndex: Int,
    val messageIndex: Int,
    val label: String,
    val content: String,
    val reasoning: String,
    val status: String,
    val toolName: String,
    val toolCallId: String,
    val toolCalls: List<TrajectoryToolCall> = emptyList(),
    val toolResult: String = "",
    val toolError: String = "",
    val toolTruncated: Boolean = false,
    val toolCall: TrajectoryToolCall? = null,
    val spanId: String = "",
    val spanStartedAt: Long? = null,
    val spanDurationMs: Long? = null,
    val raw: JsonElement? = null,
) {
    val hasBody: Boolean get() = content.isNotBlank() || reasoning.isNotBlank() || toolCalls.isNotEmpty() ||
        toolResult.isNotBlank() || toolError.isNotBlank()
}

/** 工具调用与结果的配对（供「工具」筛选与详情展示）。 */
data class TrajectoryToolExchange(
    val id: String,
    val call: TrajectoryToolCall,
    val callerEntryId: String?,
    val result: TrajectoryContentEntry?,
    val status: String,
)

data class TrajectoryRequest(
    val id: String,
    val index: Int,
    val entries: List<TrajectoryContentEntry>,
)

data class TrajectoryContentAnalysis(
    val available: Boolean = false,
    val requests: List<TrajectoryRequest> = emptyList(),
    val entries: List<TrajectoryContentEntry> = emptyList(),
    val toolExchanges: List<TrajectoryToolExchange> = emptyList(),
)

private data class CapturedMessage(
    val role: String,
    val content: String,
    val reasoning: String,
    val name: String,
    val toolCalls: List<TrajectoryToolCall>,
    val toolCallId: String,
    val toolName: String,
    val raw: JsonObject,
)

private data class Capture(
    val id: String,
    val spanId: String,
    val createdAt: Long,
    val inputRecord: AgentRunRecord,
    val outputRecord: AgentRunRecord?,
    val messages: List<CapturedMessage>,
    val tools: JsonElement?,
    val outputMessage: CapturedMessage?,
    val outputContent: JsonObject,
)

/** 重建对话视图；无 `llm_input` 内容（纯元数据轨迹）时返回 `available=false`。 */
fun analyzeTrajectoryContent(trace: AgentRunTrace, analysis: TrajectoryAnalysis): TrajectoryContentAnalysis {
    val spanById = analysis.spans.associateBy { it.id }
    val toolOutputs = indexToolOutputs(trace.records, spanById)
    val outputs = HashMap<String, AgentRunRecord>()
    for (record in trace.records) {
        if (record.type != "llm_output") continue
        val data = record.data ?: continue
        outputs[requestKey(data)] = record
    }

    val captures = trace.records.mapIndexedNotNull { index, record ->
        if (record.type != "llm_input") return@mapIndexedNotNull null
        val data = record.data ?: return@mapIndexedNotNull null
        val content = data["content"] as? JsonObject ?: JsonObject(emptyMap())
        val spanId = stringAt(data, "span_id")
        val callId = stringAt(data, "call_id")
        val outputRecord = outputs[requestKey(data)]
        val span = spanById[spanId]
        Capture(
            id = callId.ifBlank { spanId.ifBlank { "request-${index + 1}" } },
            spanId = spanId,
            createdAt = span?.startedAt ?: parseTimestamp(record.createdAt) ?: 0L,
            inputRecord = record,
            outputRecord = outputRecord,
            messages = (content["messages"] as? JsonArray)
                ?.mapNotNull { messageValue(it) }
                .orEmpty(),
            tools = content["tools"],
            outputMessage = messageValue((outputRecord?.data?.get("content") as? JsonObject)?.get("message")),
            outputContent = (outputRecord?.data?.get("content") as? JsonObject) ?: JsonObject(emptyMap()),
        )
    }.sortedWith(compareBy({ it.createdAt }, { it.inputRecord.createdAt }))

    if (captures.isEmpty()) return TrajectoryContentAnalysis()

    val requests = ArrayList<TrajectoryRequest>(captures.size)
    val callsById = HashMap<String, TrajectoryContentEntry>()
    var previousMessages: List<CapturedMessage> = emptyList()
    var previousOutput: CapturedMessage? = null
    var previousSystem = ""
    var previousTools = ""

    captures.forEachIndexed { captureIndex, capture ->
        val entries = ArrayList<TrajectoryContentEntry>()
        val systemMessages = capture.messages.filter { it.role == "system" }
        val system = systemMessages.joinToString("\n\n") { it.content }
        val toolsKey = stableString(capture.tools ?: JsonObject(emptyMap()))
        if (captureIndex == 0 || system != previousSystem || toolsKey != previousTools) {
            entries += baseEntry(
                capture = capture,
                captureIndex = captureIndex,
                kind = TrajectoryContentKind.SYSTEM,
                direction = "input",
                messageIndex = 0,
                label = if (captureIndex == 0) "initial_system" else "system_update",
                content = system,
                status = "included",
                spanById = spanById,
            )
        }

        val prefixLength = sharedPrefixLength(previousMessages, capture.messages)
        if (captureIndex > 0 && prefixLength < previousMessages.size) {
            entries += baseEntry(
                capture = capture,
                captureIndex = captureIndex,
                kind = TrajectoryContentKind.CONTEXT,
                direction = "input",
                messageIndex = prefixLength,
                label = "snapshot_changed",
                content = "",
                status = "included",
                spanById = spanById,
            )
        }

        val newMessages = if (captureIndex == 0) capture.messages else capture.messages.drop(prefixLength)
        newMessages.forEachIndexed { offset, message ->
            if (message.role == "system") return@forEachIndexed
            if (message.role == "assistant" && previousOutput != null && message.raw == previousOutput?.raw) {
                return@forEachIndexed
            }
            val entry = entryFromMessage(
                capture = capture,
                captureIndex = captureIndex,
                message = message,
                messageIndex = prefixLength + offset,
                direction = "input",
                callsById = callsById,
                spanById = spanById,
                toolOutputs = toolOutputs,
            )
            entries += entry
            rememberToolCalls(callsById, entry)
        }

        capture.outputMessage?.let { outputMessage ->
            val entry = entryFromMessage(
                capture = capture,
                captureIndex = captureIndex,
                message = outputMessage,
                messageIndex = capture.messages.size,
                direction = "output",
                callsById = callsById,
                spanById = spanById,
                toolOutputs = toolOutputs,
            )
            entries += entry
            rememberToolCalls(callsById, entry)
            previousOutput = outputMessage
        }

        requests += TrajectoryRequest(id = capture.id, index = captureIndex + 1, entries = entries)
        previousMessages = capture.messages
        previousSystem = system
        previousTools = toolsKey
    }

    val entries = requests.flatMap { it.entries }
    return TrajectoryContentAnalysis(
        available = true,
        requests = requests,
        entries = entries,
        toolExchanges = buildToolExchanges(entries, callsById),
    )
}

private fun baseEntry(
    capture: Capture,
    captureIndex: Int,
    kind: TrajectoryContentKind,
    direction: String,
    messageIndex: Int,
    label: String,
    content: String,
    status: String,
    spanById: Map<String, TrajectorySpan>,
    toolName: String = "",
    toolCallId: String = "",
    toolCalls: List<TrajectoryToolCall> = emptyList(),
    toolCall: TrajectoryToolCall? = null,
    reasoning: String = "",
    raw: JsonElement? = null,
): TrajectoryContentEntry {
    val span = spanById[capture.spanId]
    return TrajectoryContentEntry(
        id = "${capture.id}:$direction:$kind:$messageIndex",
        kind = kind,
        direction = direction,
        requestIndex = captureIndex + 1,
        messageIndex = messageIndex,
        label = label,
        content = content,
        reasoning = reasoning,
        status = status,
        toolName = toolName,
        toolCallId = toolCallId,
        toolCalls = toolCalls,
        toolCall = toolCall,
        spanId = span?.id.orEmpty(),
        spanStartedAt = span?.startedAt,
        spanDurationMs = span?.durationMs,
        raw = raw,
    )
}

private fun entryFromMessage(
    capture: Capture,
    captureIndex: Int,
    message: CapturedMessage,
    messageIndex: Int,
    direction: String,
    callsById: Map<String, TrajectoryContentEntry>,
    spanById: Map<String, TrajectorySpan>,
    toolOutputs: Map<String, ToolOutput>,
): TrajectoryContentEntry {
    val kind = messageKind(message, direction)
    val output = toolOutputs[message.toolCallId]
    val call = if (message.toolCallId.isNotBlank() && kind == TrajectoryContentKind.TOOL) {
        callsById[message.toolCallId]?.toolCalls?.firstOrNull()?.takeIf { it.id == message.toolCallId }
            ?: message.toolCalls.firstOrNull()
    } else {
        message.toolCalls.firstOrNull()
    }
    return baseEntry(
        capture = capture,
        captureIndex = captureIndex,
        kind = kind,
        direction = direction,
        messageIndex = messageIndex,
        label = messageLabel(kind, captureIndex + 1, message.toolName.ifBlank { call?.name.orEmpty() }, direction),
        content = message.content,
        status = if (direction == "output") {
            stringAt(capture.outputContent, "status").ifBlank { spanById[capture.spanId]?.status ?: "success" }
        } else {
            "included"
        },
        spanById = spanById,
        toolName = message.toolName.ifBlank { message.name }.ifBlank { call?.name.orEmpty() },
        toolCallId = message.toolCallId,
        toolCalls = message.toolCalls,
        toolCall = call,
        reasoning = message.reasoning,
        raw = message.raw,
    ).copy(
        toolResult = output?.content.orEmpty(),
        toolError = output?.error.orEmpty(),
        toolTruncated = output?.truncated == true,
    )
}

private fun messageLabel(
    kind: TrajectoryContentKind,
    requestIndex: Int,
    toolName: String,
    direction: String,
): String = when (kind) {
    TrajectoryContentKind.ASSISTANT ->
        if (direction == "output") "request_output" else "assistant_history"
    TrajectoryContentKind.TOOL -> if (toolName.isBlank()) "tool_result" else toolName
    TrajectoryContentKind.CONTEXT -> "model_context"
    TrajectoryContentKind.USER -> "user_message"
    TrajectoryContentKind.SYSTEM -> "system_prompt"
}.let { label ->
    if (kind == TrajectoryContentKind.ASSISTANT && direction == "output") "$label#$requestIndex" else label
}

private fun messageKind(message: CapturedMessage, direction: String): TrajectoryContentKind = when {
    direction == "output" || message.role == "assistant" -> TrajectoryContentKind.ASSISTANT
    message.role == "tool" -> TrajectoryContentKind.TOOL
    message.role == "system" -> TrajectoryContentKind.SYSTEM
    message.role == "user" && isContextMessage(message) -> TrajectoryContentKind.CONTEXT
    else -> TrajectoryContentKind.USER
}

private fun isContextMessage(message: CapturedMessage): Boolean {
    val extra = message.raw["extra"] as? JsonObject ?: return false
    return extra.keys.any { it.startsWith("agent.context") || it.contains("context_placement") }
}

private data class ToolOutput(
    val content: String,
    val error: String,
    val truncated: Boolean,
    val spanId: String,
)

private fun indexToolOutputs(
    records: List<AgentRunRecord>,
    spanById: Map<String, TrajectorySpan>,
): Map<String, ToolOutput> {
    val result = HashMap<String, ToolOutput>()
    for (record in records) {
        if (record.type != "tool_output") continue
        val data = record.data ?: continue
        val content = data["content"] as? JsonObject ?: JsonObject(emptyMap())
        val callId = stringAt(content, "provider_call_id").ifBlank { stringAt(data, "call_id") }
        val executionId = stringAt(content, "execution_id")
        val spanId = stringAt(data, "span_id")
        val output = ToolOutput(
            content = stringAt(content, "result"),
            error = stringAt(content, "error"),
            truncated = (content["truncated"] as? JsonPrimitive)?.content == "true",
            spanId = if (spanId in spanById) spanId else "",
        )
        for (key in listOf(callId, executionId)) {
            if (key.isNotBlank()) result[key] = output
        }
    }
    return result
}

private fun buildToolExchanges(
    entries: List<TrajectoryContentEntry>,
    callsById: Map<String, TrajectoryContentEntry>,
): List<TrajectoryToolExchange> {
    val exchanges = ArrayList<TrajectoryToolExchange>()
    for (entry in entries) {
        for (call in entry.toolCalls) {
            exchanges += TrajectoryToolExchange(
                id = "${entry.id}:tool:${call.id}",
                call = call,
                callerEntryId = entry.id,
                result = entries.firstOrNull { it.kind == TrajectoryContentKind.TOOL && it.toolCallId == call.id },
                status = entries.firstOrNull { it.kind == TrajectoryContentKind.TOOL && it.toolCallId == call.id }
                    ?.status ?: "pending",
            )
        }
    }
    // 只有结果没有调用（例如历史被裁剪）时也保留，避免结果在 UI 里凭空消失。
    for (entry in entries) {
        if (entry.kind != TrajectoryContentKind.TOOL) continue
        if (exchanges.any { it.call.id.isNotBlank() && it.call.id == entry.toolCallId }) continue
        exchanges += TrajectoryToolExchange(
            id = "${entry.id}:tool-result",
            call = entry.toolCall ?: TrajectoryToolCall(entry.toolCallId, entry.toolName, ""),
            callerEntryId = callsById[entry.toolCallId]?.id,
            result = entry,
            status = entry.status,
        )
    }
    return exchanges
}

private fun rememberToolCalls(callsById: MutableMap<String, TrajectoryContentEntry>, entry: TrajectoryContentEntry) {
    for (call in entry.toolCalls) {
        if (call.id.isNotBlank()) callsById[call.id] = entry
    }
}

private fun messageValue(value: JsonElement?): CapturedMessage? {
    val message = value as? JsonObject ?: return null
    val role = stringAt(message, "role")
    if (role.isBlank()) return null
    val function = ((message["tool_calls"] as? JsonArray)?.firstOrNull() as? JsonObject)
        ?.get("function") as? JsonObject
    return CapturedMessage(
        role = role,
        content = stringAt(message, "content"),
        reasoning = stringAt(message, "reasoning_content"),
        name = stringAt(message, "name"),
        toolCalls = (message["tool_calls"] as? JsonArray)
            ?.mapNotNull { toolCallValue(it) }
            .orEmpty(),
        toolCallId = stringAt(message, "tool_call_id"),
        toolName = stringAt(message, "tool_name").ifBlank { stringAt(function, "name") },
        raw = message,
    )
}

private fun toolCallValue(value: JsonElement): TrajectoryToolCall? {
    val call = value as? JsonObject ?: return null
    val fn = call["function"] as? JsonObject ?: JsonObject(emptyMap())
    val id = stringAt(call, "id")
    val name = stringAt(fn, "name")
    if (id.isBlank() && name.isBlank()) return null
    return TrajectoryToolCall(id = id, name = name, arguments = stringAt(fn, "arguments"))
}

private fun sharedPrefixLength(left: List<CapturedMessage>, right: List<CapturedMessage>): Int {
    val limit = minOf(left.size, right.size)
    var index = 0
    while (index < limit && stableString(left[index].raw) == stableString(right[index].raw)) index++
    return index
}

/** 稳定序列化：键排序后输出，用作消息指纹（对齐基线 `stableJSON`）。 */
internal fun stableString(element: JsonElement): String = when (element) {
    is JsonObject -> element.entries.sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (key, value) -> "\"$key\":${stableString(value)}" }
    is JsonArray -> element.joinToString(prefix = "[", postfix = "]") { stableString(it) }
    else -> element.toString()
}

private fun requestKey(data: JsonObject): String =
    stringAt(data, "call_id").ifBlank { stringAt(data, "span_id") }
