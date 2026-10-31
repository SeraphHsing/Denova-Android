package com.denova.novara

import com.denova.novara.core.network.AgentRunRecord
import com.denova.novara.core.network.AgentRunSummary
import com.denova.novara.core.network.AgentRunTrace
import com.denova.novara.feature.trajectory.TrajectoryCategory
import com.denova.novara.feature.trajectory.TrajectoryTimelineMode
import com.denova.novara.feature.trajectory.analyzeTrajectory
import com.denova.novara.feature.trajectory.formatTrajectoryDuration
import com.denova.novara.feature.trajectory.projectTimeline
import com.denova.novara.feature.trajectory.timelineRangeSpanIds
import com.denova.novara.feature.trajectory.visibleTreeSpanIds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨迹分析层（TRJ-002）：口径逐条对齐 Web `trajectory-analysis.ts`。
 *
 * 覆盖：span 构建与树、`gapBeforeMs` 覆盖区间、指标聚合（TTFT 分位 / 缓存命中 / 空闲）、
 * 事件分类、时间线三尺度投影、区间选中、祖先保留与时长格式化。
 */
class TrajectoryAnalysisTest {

    private val base = "2026-11-01T10:00:00Z"

    @Test
    fun `builds span tree with gaps and aggregates metrics`() {
        val trace = traceOf(
            record("agent_run", 0, span("root", startedSec = 0, durationMs = 1_000)),
            record(
                "llm_call",
                1,
                span(
                    "model-1",
                    startedSec = 1,
                    durationMs = 400,
                    parent = "root",
                    status = "success",
                    attrs = buildJsonObject {
                        put("model", "gpt-test")
                        put("ttft_ms", 100)
                        put("prompt_tokens", 1_000)
                        put("cached_prompt_tokens", 400)
                        put("completion_tokens", 50)
                    },
                ),
            ),
            record("tool_call", 2, span("tool-1", startedSec = 6, durationMs = 100, parent = "root")),
            record("agent_cycle", 3, buildJsonObject { put("count", 2) }),
        )

        val analysis = analyzeTrajectory(trace)

        assertEquals(3, analysis.spans.size)
        assertEquals(1, analysis.roots.size)
        assertEquals("root", analysis.roots.single().id)
        assertEquals(2, analysis.roots.single().children.size)

        // gap：以「上一段覆盖结束」为基准，模型段 1.0s→1.4s，工具段 6.0s 起，间隔 4.6s。
        assertEquals(0L, analysis.spans.first { it.id == "model-1" }.gapBeforeMs)
        assertEquals(4_600L, analysis.spans.first { it.id == "tool-1" }.gapBeforeMs)
        // generationMs = duration − ttft。
        assertEquals(300L, analysis.spans.first { it.id == "model-1" }.generationMs)

        val metrics = analysis.metrics
        assertEquals(1, metrics.modelCalls)
        assertEquals(1, metrics.toolCalls)
        assertEquals(400L, metrics.modelMs)
        assertEquals(100L, metrics.toolMs)
        assertEquals(1_000, metrics.promptTokens)
        assertEquals(400, metrics.cachedTokens)
        assertEquals(0.4, metrics.cacheHitRate, 0.0001)
        assertEquals(100L, metrics.p50TtftMs)
        assertEquals(100L, metrics.p95TtftMs)
        // 50 tokens / 300ms → ≈166.7 tok/s。
        assertEquals(166.67, metrics.averageThroughput ?: 0.0, 0.01)

        // 无 span_id 的记录进事件流并按类型分类。
        assertEquals(1, analysis.events.size)
        assertEquals("Agent cycle 2", analysis.events.single().label)
        assertEquals(TrajectoryCategory.RUN, analysis.events.single().category)
    }

    @Test
    fun `idle time is total minus union of operation intervals`() {
        val trace = traceOf(
            record("agent_run", 0, span("root", startedSec = 0, durationMs = 10_000)),
            record("llm_call", 1, span("m1", startedSec = 0, durationMs = 2_000, parent = "root")),
            record("llm_call", 2, span("m2", startedSec = 1_000, durationMs = 2_000, parent = "root")),
            record("tool_call", 3, span("t1", startedSec = 5_000, durationMs = 1_000, parent = "root")),
        )

        val metrics = analyzeTrajectory(trace).metrics

        // 并集：0–3000 与 5000–6000 → 4000ms 忙；总 10s。
        assertEquals(4_000L, metrics.busyMs)
        assertEquals(6_000L, metrics.idleMs)
    }

    @Test
    fun `timeline supports sequence duration and actual projections`() {
        val trace = traceOf(
            record("agent_run", 0, span("root", startedSec = 0, durationMs = 10_000)),
            record("llm_call", 1, span("m1", startedSec = 0, durationMs = 1_000, parent = "root")),
            record("llm_call", 2, span("m2", startedSec = 5, durationMs = 1_000, parent = "root")),
        )
        val analysis = analyzeTrajectory(trace)

        val actual = projectTimeline(analysis, TrajectoryTimelineMode.ACTUAL)
        assertEquals(2, actual.spans.size)
        assertEquals("time", actual.unit)
        // 投影坐标沿用绝对时间戳（与基线一致），跨度应为 6s。
        assertEquals(6_000.0, actual.end - actual.start, 0.0001)

        val duration = projectTimeline(analysis, TrajectoryTimelineMode.DURATION)
        // 压缩空闲后第二段紧接第一段，长度仍是 1s。
        val first = duration.spans.first { it.id == "m1" }
        val second = duration.spans.first { it.id == "m2" }
        assertEquals(first.end, second.start, 0.0001)
        assertEquals(1_000.0, second.end - second.start, 0.0001)
        assertTrue(second.start < actual.spans.first { it.id == "m2" }.start)

        val sequence = projectTimeline(analysis, TrajectoryTimelineMode.SEQUENCE)
        assertEquals("step", sequence.unit)
        assertEquals(0.0, sequence.spans[0].start, 0.0001)
        assertEquals(1.0, sequence.spans[1].start, 0.0001)
        assertEquals(2.0, sequence.end, 0.0001)
    }

    @Test
    fun `range selection returns overlapping spans`() {
        val trace = traceOf(
            record("agent_run", 0, span("root", startedSec = 0, durationMs = 10_000)),
            record("llm_call", 1, span("m1", startedSec = 0, durationMs = 1_000, parent = "root")),
            record("llm_call", 2, span("m2", startedSec = 5, durationMs = 1_000, parent = "root")),
        )
        val projection = projectTimeline(analyzeTrajectory(trace), TrajectoryTimelineMode.ACTUAL)
        val start = projection.start

        assertEquals(setOf("m1"), timelineRangeSpanIds(projection, start, start + 500))
        assertEquals(setOf("m2"), timelineRangeSpanIds(projection, start + 4_500, start + 6_000))
    }

    @Test
    fun `filtered tree keeps ancestors of matching descendants`() {
        val trace = traceOf(
            record("agent_run", 0, span("root", startedSec = 0, durationMs = 10_000)),
            record("llm_call", 1, span("m1", startedSec = 0, durationMs = 1_000, parent = "root")),
            record("tool_call", 2, span("t1", startedSec = 2_000, durationMs = 500, parent = "root")),
            record("llm_call", 3, span("m2", startedSec = 3_000, durationMs = 500, parent = "root")),
        )
        val analysis = analyzeTrajectory(trace)

        // 命中两个不同分支时，两个兄弟链都要保留（不能因为先命中就短路）。
        val visible = visibleTreeSpanIds(analysis.roots, setOf("m1", "m2"))
        assertEquals(setOf("root", "m1", "m2"), visible)
    }

    @Test
    fun `duration formatting matches the web baseline`() {
        assertEquals("—", formatTrajectoryDuration(null))
        assertEquals("0 ms", formatTrajectoryDuration(0))
        assertEquals("999 ms", formatTrajectoryDuration(999))
        assertEquals("1.00 s", formatTrajectoryDuration(1_000))
        assertEquals("9.99 s", formatTrajectoryDuration(9_994))
        assertEquals("12.3 s", formatTrajectoryDuration(12_345))
        assertEquals("1m 5s", formatTrajectoryDuration(65_000))
    }

    @Test
    fun `records without usable timing are ignored instead of throwing`() {
        val trace = traceOf(
            // span 有 id 但既没有 started_at 也没有 created_at：不能解析就不建 span，也不能抛异常。
            AgentRunRecord(
                type = "llm_call",
                runId = "run-1",
                createdAt = "",
                data = buildJsonObject { put("span_id", "no-time") },
            ),
            record("unknown_without_data", 1, null),
        )

        val analysis = analyzeTrajectory(trace)

        assertTrue(analysis.spans.isEmpty())
        // 第二条没有 span_id，进事件流；第一条被当作不可用 span 跳过（与基线一致）。
        assertEquals(1, analysis.events.size)
        assertNull(analysis.metrics.p50TtftMs)
    }

    private fun traceOf(vararg records: AgentRunRecord): AgentRunTrace = AgentRunTrace(
        summary = AgentRunSummary(
            id = "run-1",
            createdAt = base,
            status = "success",
            durationMs = 0,
        ),
        records = records.toList(),
    )

    private fun record(type: String, index: Int, data: JsonObject?): AgentRunRecord = AgentRunRecord(
        type = type,
        runId = "run-1",
        createdAt = "2026-11-01T10:00:0${index}Z",
        data = data,
    )

    private fun span(
        id: String,
        startedSec: Int,
        durationMs: Long,
        parent: String = "",
        status: String = "success",
        attrs: JsonObject = JsonObject(emptyMap()),
    ): JsonObject = buildJsonObject {
        put("span_id", id)
        if (parent.isNotEmpty()) put("parent_span_id", parent)
        put("started_at", "2026-11-01T10:00:0${startedSec}Z")
        put("duration_ms", durationMs)
        put("status", status)
        putJsonObject("attrs") { attrs.forEach { (key, value) -> put(key, value) } }
    }
}
