package com.denova.novara.feature.trajectory

import com.denova.novara.core.network.AgentRunRecord
import com.denova.novara.core.network.AgentRunTrace
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 轨迹分析层（纯逻辑，无 Android 依赖）。
 *
 * 逐条对齐 Web 基线 `web/src/features/trajectory/trajectory-analysis.ts`：
 * - span 只由带 `span_id` 的非内容记录构成（`llm_input` / `llm_output` / `tool_output` 不参与）；
 * - `gapBeforeMs` 按非 run span 的时间覆盖区间累计，用于「调用前等待」；
 * - 指标口径（total / busy / idle / model / tool / TTFT 分位 / 吞吐 / 缓存命中）与基线一致；
 * - 时间线支持 `actual`（真实时间）/ `duration`（压缩空闲）/ `sequence`（等宽步骤）三种投影。
 *
 * 放在 feature 层而不是 core：它只服务统计页，且需要能单独 JVM 测试。
 */
enum class TrajectoryCategory { RUN, MODEL, TOOL, CONTEXT, VERIFICATION, EVENT }

enum class TrajectoryTimelineMode { ACTUAL, DURATION, SEQUENCE }

data class TrajectorySpan(
    val id: String,
    val parentId: String,
    val recordIndex: Int,
    val type: String,
    val category: TrajectoryCategory,
    val label: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val gapBeforeMs: Long,
    val ttftMs: Long?,
    val generationMs: Long?,
    val inputTokens: Int,
    val cachedTokens: Int,
    val outputTokens: Int,
    val reasoningTokens: Int,
    val attrs: JsonObject,
    val children: MutableList<TrajectorySpan> = mutableListOf(),
)

data class TrajectoryEventRecord(
    val id: String,
    val recordIndex: Int,
    val type: String,
    val category: TrajectoryCategory,
    val label: String,
    val status: String,
    val timestamp: Long,
    val data: JsonObject,
)

data class TrajectoryMetrics(
    val totalMs: Long = 0,
    val busyMs: Long = 0,
    val idleMs: Long = 0,
    val modelMs: Long = 0,
    val toolMs: Long = 0,
    val modelCalls: Int = 0,
    val toolCalls: Int = 0,
    val errors: Int = 0,
    val p50TtftMs: Long? = null,
    val p95TtftMs: Long? = null,
    val averageThroughput: Double? = null,
    val promptTokens: Int = 0,
    val cachedTokens: Int = 0,
    val completionTokens: Int = 0,
    val cacheHitRate: Double = 0.0,
)

data class TrajectoryAnalysis(
    val roots: List<TrajectorySpan> = emptyList(),
    val spans: List<TrajectorySpan> = emptyList(),
    val events: List<TrajectoryEventRecord> = emptyList(),
    val startMs: Long = 0,
    val endMs: Long = 0,
    val metrics: TrajectoryMetrics = TrajectoryMetrics(),
)

data class TimelineSpan(
    val id: String,
    val category: TrajectoryCategory,
    val label: String,
    val status: String,
    val start: Double,
    val end: Double,
    val durationMs: Long,
    val ttftMs: Long?,
    val ttftEnd: Double?,
)

data class TimelineProjection(
    val start: Double = 0.0,
    val end: Double = 1.0,
    val unit: String = "time",
    val spans: List<TimelineSpan> = emptyList(),
)

private val SPAN_CATEGORIES: Map<String, TrajectoryCategory> = mapOf(
    "agent_run" to TrajectoryCategory.RUN,
    "llm_call" to TrajectoryCategory.MODEL,
    "tool_call" to TrajectoryCategory.TOOL,
    "context_build" to TrajectoryCategory.CONTEXT,
    "context_compaction" to TrajectoryCategory.CONTEXT,
    "post_run_verification" to TrajectoryCategory.VERIFICATION,
)

/** 构建 span 树、事件流与延迟指标（对应基线 `analyzeTrajectory`）。 */
fun analyzeTrajectory(trace: AgentRunTrace): TrajectoryAnalysis {
    val spanRecords = ArrayList<TrajectorySpan>(trace.records.size)
    trace.records.forEachIndexed { recordIndex, record ->
        when (record.type) {
            "llm_input", "llm_output", "tool_output" -> return@forEachIndexed
        }
        val data = record.data ?: return@forEachIndexed
        val spanId = stringAt(data, "span_id")
        if (spanId.isBlank()) return@forEachIndexed
        val startedAt = timestampAt(data, "started_at") ?: parseTimestamp(record.createdAt) ?: return@forEachIndexed
        val durationMs = max(0L, longAt(data, "duration_ms"))
        val endedAt = timestampAt(data, "ended_at") ?: (startedAt + durationMs)
        val attrs = data["attrs"] as? JsonObject ?: JsonObject(emptyMap())
        spanRecords += TrajectorySpan(
            id = spanId,
            parentId = stringAt(data, "parent_span_id"),
            recordIndex = recordIndex,
            type = record.type,
            category = spanCategory(record.type),
            label = spanLabel(record.type, attrs),
            status = stringAt(data, "status").ifBlank { "success" },
            startedAt = startedAt,
            endedAt = max(startedAt, endedAt),
            durationMs = durationMs,
            gapBeforeMs = 0L,
            ttftMs = optionalLong(attrs["ttft_ms"]),
            generationMs = null,
            inputTokens = intAt(attrs, "prompt_tokens"),
            cachedTokens = intAt(attrs, "cached_prompt_tokens"),
            outputTokens = intAt(attrs, "completion_tokens"),
            reasoningTokens = intAt(attrs, "reasoning_tokens"),
            attrs = attrs,
        )
    }

    val ordered = spanRecords.sortedWith(::compareSpanTime)
    val operations = ordered.filter { it.category != TrajectoryCategory.RUN }
    var coveredUntil = operations.firstOrNull()?.startedAt ?: 0L
    val gapFilled = operations.map { span ->
        val gap = max(0L, span.startedAt - coveredUntil)
        coveredUntil = max(coveredUntil, span.endedAt)
        span.copy(gapBeforeMs = gap)
    }
    val gapByRecord = gapFilled.associateBy { it.recordIndex }
    val withGaps = ordered.map { span ->
        val filled = gapByRecord[span.recordIndex] ?: span
        filled.copy(generationMs = filled.ttftMs?.let { max(0L, filled.durationMs - it) })
    }

    val byId = withGaps.associateBy { it.id }
    val roots = ArrayList<TrajectorySpan>()
    for (span in withGaps) {
        val parent = byId[span.parentId]
        if (parent != null && parent !== span) parent.children.add(span) else roots.add(span)
    }
    sortSpanTree(roots)

    val events = trace.records.mapIndexedNotNull { recordIndex, record ->
        val data = record.data ?: JsonObject(emptyMap())
        if (stringAt(data, "span_id").isNotBlank()) return@mapIndexedNotNull null
        TrajectoryEventRecord(
            id = "${record.type}:${record.createdAt}:$recordIndex",
            recordIndex = recordIndex,
            type = record.type,
            category = eventCategory(record),
            label = eventLabel(record, data),
            status = eventStatus(data),
            timestamp = parseTimestamp(record.createdAt) ?: 0L,
            data = data,
        )
    }

    val timestamps = ArrayList<Long>(withGaps.size * 2 + events.size)
    withGaps.forEach { timestamps += it.startedAt; timestamps += it.endedAt }
    events.forEach { if (it.timestamp > 0) timestamps += it.timestamp }
    val fallbackStart = parseTimestamp(trace.summary.createdAt) ?: System.currentTimeMillis()
    val startMs = timestamps.minOrNull() ?: fallbackStart
    val endFromSummary = if (trace.summary.durationMs > 0) fallbackStart + trace.summary.durationMs else fallbackStart
    val endMs = maxOf(startMs, endFromSummary, timestamps.maxOrNull() ?: startMs)

    return TrajectoryAnalysis(
        roots = roots,
        spans = withGaps,
        events = events,
        startMs = startMs,
        endMs = endMs,
        metrics = deriveMetrics(withGaps, startMs, endMs),
    )
}

/** 时间线投影（对应基线 `projectTimeline`）。 */
fun projectTimeline(analysis: TrajectoryAnalysis, mode: TrajectoryTimelineMode): TimelineProjection {
    val operations = analysis.spans.filter { it.category != TrajectoryCategory.RUN }
    if (mode == TrajectoryTimelineMode.SEQUENCE) {
        return TimelineProjection(
            start = 0.0,
            end = max(1, operations.size).toDouble(),
            unit = "step",
            spans = operations.mapIndexed { index, span ->
                val from = index.toDouble()
                val to = index + 1.0
                TimelineSpan(
                    id = span.id,
                    category = span.category,
                    label = span.label,
                    status = span.status,
                    start = from,
                    end = to,
                    durationMs = span.durationMs,
                    ttftMs = span.ttftMs,
                    ttftEnd = span.ttftMs?.let { min(to, from + min(1.0, it.toDouble() / max(1L, span.durationMs))) },
                )
            },
        )
    }

    var removedIdle = 0L
    var covered: Long? = null
    val removedBefore = HashMap<String, Long>(operations.size)
    for (span in operations) {
        if (mode == TrajectoryTimelineMode.DURATION && covered != null && span.startedAt > covered) {
            removedIdle += span.startedAt - covered
        }
        removedBefore[span.id] = removedIdle
        covered = covered?.let { max(it, span.endedAt) } ?: span.endedAt
    }
    val spans = operations.map { span ->
        val offset = removedBefore[span.id] ?: 0L
        val from = (span.startedAt - offset).toDouble()
        val to = max(from + 1.0, (span.endedAt - offset).toDouble())
        TimelineSpan(
            id = span.id,
            category = span.category,
            label = span.label,
            status = span.status,
            start = from,
            end = to,
            durationMs = span.durationMs,
            ttftMs = span.ttftMs,
            ttftEnd = span.ttftMs?.let { min(to, from + it) },
        )
    }
    if (spans.isEmpty()) return TimelineProjection(0.0, 1.0, "time", emptyList())
    return TimelineProjection(
        start = spans.minOf { it.start },
        end = spans.maxOf { it.end },
        unit = "time",
        spans = spans,
    )
}

/** 区间涉及的 span（对应基线 `timelineRangeSpanIDs`）。 */
fun timelineRangeSpanIds(projection: TimelineProjection, start: Double, end: Double): Set<String> {
    val low = min(start, end)
    val high = max(start, end)
    return projection.spans.filter { it.start <= high && it.end >= low }.mapTo(LinkedHashSet()) { it.id }
}

/**
 * 过滤后仍保留祖先的可见集合（对应基线 `visibleTreeSpanIDs`）。
 * 必须遍历每个子节点：命中一个分支就短路会丢掉后面的兄弟调用链。
 */
fun visibleTreeSpanIds(roots: List<TrajectorySpan>, matches: Set<String>): Set<String> {
    val visible = LinkedHashSet<String>()
    fun visit(span: TrajectorySpan): Boolean {
        var childMatches = false
        for (child in span.children) {
            if (visit(child)) childMatches = true
        }
        if (span.id in matches || childMatches) {
            visible += span.id
            return true
        }
        return false
    }
    roots.forEach(::visit)
    return visible
}

/** 时长格式（逐字对齐基线：`—` / `N ms` / `X.XX s` / `X.Y s` / `Xm Ys`）。 */
fun formatTrajectoryDuration(milliseconds: Long?): String {
    if (milliseconds == null) return "—"
    val value = milliseconds.toDouble()
    if (value < 1_000) return "${Math.round(value)} ms"
    if (value < 60_000) {
        val seconds = if (value < 10_000) {
            String.format(Locale.ROOT, "%.2f", value / 1_000)
        } else {
            String.format(Locale.ROOT, "%.1f", value / 1_000)
        }
        return "$seconds s"
    }
    val minutes = floor(value / 60_000).toLong()
    val seconds = Math.round((value % 60_000) / 1_000)
    return "${minutes}m ${seconds}s"
}

fun formatTrajectoryThroughput(value: Double?): String =
    if (value == null) "—" else String.format(Locale.ROOT, "%.1f tok/s", value)

private fun deriveMetrics(spans: List<TrajectorySpan>, startMs: Long, endMs: Long): TrajectoryMetrics {
    val operations = spans.filter { it.category != TrajectoryCategory.RUN && it.durationMs > 0 }
    val totalMs = max(0L, endMs - startMs)
    val busyMs = intervalUnionDuration(operations.map { it.startedAt to it.endedAt })
    val models = spans.filter { it.category == TrajectoryCategory.MODEL }
    val tools = spans.filter { it.category == TrajectoryCategory.TOOL }
    val ttfts = models.mapNotNull { it.ttftMs }
    val generatedTokens = models.sumOf { it.outputTokens }
    val generationMs = models.sumOf { max(0L, it.generationMs ?: 0L) }
    val promptTokens = models.sumOf { it.inputTokens }
    val cachedTokens = models.sumOf { it.cachedTokens }
    return TrajectoryMetrics(
        totalMs = totalMs,
        busyMs = busyMs,
        idleMs = max(0L, totalMs - busyMs),
        modelMs = models.sumOf { it.durationMs },
        toolMs = tools.sumOf { it.durationMs },
        modelCalls = models.size,
        toolCalls = tools.size,
        errors = spans.count { isErrorStatus(it.status) },
        p50TtftMs = percentile(ttfts, 0.5),
        p95TtftMs = percentile(ttfts, 0.95),
        averageThroughput = if (generatedTokens > 0 && generationMs > 0) {
            generatedTokens.toDouble() / (generationMs.toDouble() / 1_000)
        } else {
            null
        },
        promptTokens = promptTokens,
        cachedTokens = cachedTokens,
        completionTokens = generatedTokens,
        cacheHitRate = if (promptTokens == 0) 0.0 else cachedTokens.toDouble() / promptTokens,
    )
}

private fun percentile(values: List<Long>, ratio: Double): Long? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val index = min((sorted.size - 1).toDouble(), max(0.0, (sorted.size - 1) * ratio))
    val lower = floor(index).toInt()
    val upper = ceil(index).toInt()
    val value = if (lower == upper) {
        sorted[lower].toDouble()
    } else {
        sorted[lower] + (sorted[upper] - sorted[lower]) * (index - lower)
    }
    return Math.round(value)
}

private fun intervalUnionDuration(intervals: List<Pair<Long, Long>>): Long {
    if (intervals.isEmpty()) return 0
    val ordered = intervals.sortedBy { it.first }
    var start = ordered.first().first
    var end = ordered.first().second
    var total = 0L
    for ((nextStart, nextEnd) in ordered.drop(1)) {
        if (nextStart <= end) {
            end = max(end, nextEnd)
            continue
        }
        total += max(0L, end - start)
        start = nextStart
        end = nextEnd
    }
    return total + max(0L, end - start)
}

private fun sortSpanTree(spans: MutableList<TrajectorySpan>) {
    spans.sortWith(::compareSpanTime)
    spans.forEach { sortSpanTree(it.children) }
}

private fun compareSpanTime(left: TrajectorySpan, right: TrajectorySpan): Int {
    val byStart = left.startedAt.compareTo(right.startedAt)
    if (byStart != 0) return byStart
    val byEnd = left.endedAt.compareTo(right.endedAt)
    return if (byEnd != 0) byEnd else left.recordIndex.compareTo(right.recordIndex)
}

private fun spanCategory(type: String): TrajectoryCategory =
    SPAN_CATEGORIES[type] ?: when {
        type.contains("tool") -> TrajectoryCategory.TOOL
        type.contains("llm") || type.contains("model") -> TrajectoryCategory.MODEL
        else -> TrajectoryCategory.EVENT
    }

private fun eventCategory(record: AgentRunRecord): TrajectoryCategory {
    val type = record.type
    return when {
        type.contains("tool") -> TrajectoryCategory.TOOL
        type.contains("llm") || type == "token_usage" -> TrajectoryCategory.MODEL
        type.contains("context") -> TrajectoryCategory.CONTEXT
        type.contains("verification") || type == "mutations" -> TrajectoryCategory.VERIFICATION
        type.contains("run") || type == "agent_cycle" -> TrajectoryCategory.RUN
        else -> TrajectoryCategory.EVENT
    }
}

private fun spanLabel(type: String, attrs: JsonObject): String = when (type) {
    "agent_run" -> stringAt(attrs, "agent_kind").ifBlank { "Agent run" }
    "llm_call" -> stringAt(attrs, "model").ifBlank { "Model call" }
    "tool_call" -> stringAt(attrs, "tool_name").ifBlank { "Tool call" }
    "context_compaction" -> "Context compaction"
    "context_build" -> "Context build"
    else -> stringAt(attrs, "name").ifBlank { type }
}

private fun eventLabel(record: AgentRunRecord, data: JsonObject): String = when (record.type) {
    "agent_cycle" -> "Agent cycle ${intAt(data, "count")}".trim()
    "tool_decision" -> stringAt(data["decision"] as? JsonObject ?: JsonObject(emptyMap()), "tool_name")
        .ifBlank { "Tool decision" }
    "tool_execution" -> stringAt(data["result"] as? JsonObject ?: JsonObject(emptyMap()), "tool_name")
        .ifBlank { "Tool execution" }
    "event" -> stringAt(data, "event_type").ifBlank { "Event" }
    else -> record.type.replace('_', ' ')
}

private fun eventStatus(data: JsonObject): String =
    stringAt(data, "status").ifBlank {
        stringAt(data["result"] as? JsonObject ?: JsonObject(emptyMap()), "status").ifBlank {
            stringAt(data["decision"] as? JsonObject ?: JsonObject(emptyMap()), "action").ifBlank { "recorded" }
        }
    }

private fun isErrorStatus(status: String): Boolean = when (status.lowercase(Locale.ROOT)) {
    "error", "failed", "blocked", "aborted" -> true
    else -> false
}

internal fun stringAt(obj: JsonObject?, key: String): String {
    val value = obj?.get(key) as? JsonPrimitive ?: return ""
    if (value is JsonNull) return ""
    return if (value.isString) value.content.trim() else value.content
}

internal fun intAt(obj: JsonObject?, key: String): Int =
    numberAt(obj?.get(key))?.toInt() ?: 0

internal fun longAt(obj: JsonObject?, key: String): Long =
    numberAt(obj?.get(key))?.toLong() ?: 0L

internal fun doubleAt(obj: JsonObject?, key: String): Double =
    numberAt(obj?.get(key)) ?: 0.0

internal fun optionalLong(element: JsonElement?): Long? {
    if (element == null || element is JsonNull) return null
    val value = numberAt(element) ?: return null
    return max(0L, Math.round(value))
}

internal fun numberAt(element: JsonElement?): Double? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    val parsed = primitive.content.toDoubleOrNull() ?: return null
    return if (parsed.isFinite()) parsed else null
}

/** 按字段取时间戳：数字按 epoch 毫秒，字符串按 RFC3339；缺失或不可解析返回 null。 */
internal fun timestampAt(obj: JsonObject?, key: String): Long? {
    val element = obj?.get(key) as? JsonPrimitive ?: return null
    if (!element.isString) return element.content.toLongOrNull()
    return parseTimestamp(element.content)
}

/** RFC3339（含纳秒/偏移）→ epoch millis；解析失败返回 null，不抛异常打断整页。 */
internal fun parseTimestamp(raw: String): Long? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}

internal fun JsonArray.strings(): List<String> = mapNotNull { (it as? JsonPrimitive)?.content }
