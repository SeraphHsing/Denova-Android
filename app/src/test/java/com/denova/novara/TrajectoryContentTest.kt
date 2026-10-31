package com.denova.novara

import com.denova.novara.core.network.AgentRunRecord
import com.denova.novara.core.network.AgentRunSummary
import com.denova.novara.core.network.AgentRunTrace
import com.denova.novara.feature.trajectory.TrajectoryContentKind
import com.denova.novara.feature.trajectory.analyzeTrajectory
import com.denova.novara.feature.trajectory.analyzeTrajectoryContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型可见记录重建（TRJ-002）。
 *
 * 覆盖：请求分组、初始/更新 System Prompt、消息前缀变化插入「快照已变化」、输出消息、
 * 工具调用与结果配对，以及纯元数据轨迹的 `available=false`。
 */
class TrajectoryContentTest {

    @Test
    fun `groups requests and marks initial and updated system prompts`() {
        val trace = traceOf(
            llmInput(
                index = 0,
                callId = "c1",
                system = "S1",
                messages = listOf(
                    message("system", "S1"),
                    message("user", "hello"),
                ),
            ),
            llmOutput(index = 1, callId = "c1", text = "first answer"),
            // 第二条：system 变了，且前缀被重写（user 换成别的）→ 应出现 system_update + snapshot_changed。
            llmInput(
                index = 2,
                callId = "c2",
                system = "S2",
                messages = listOf(
                    message("system", "S2"),
                    message("user", "rewritten"),
                ),
            ),
            llmOutput(index = 3, callId = "c2", text = "second answer"),
        )

        val content = analyzeTrajectoryContent(trace, analyzeTrajectory(trace))

        assertTrue(content.available)
        assertEquals(2, content.requests.size)
        assertEquals(1, content.requests[0].index)
        assertEquals(2, content.requests[1].index)

        val first = content.requests[0].entries
        assertTrue(first.any { it.kind == TrajectoryContentKind.SYSTEM && it.label == "initial_system" })
        assertEquals("S1", first.first { it.kind == TrajectoryContentKind.SYSTEM }.content)
        assertTrue(first.any { it.kind == TrajectoryContentKind.USER && it.content == "hello" })
        assertTrue(first.any { it.kind == TrajectoryContentKind.ASSISTANT && it.content == "first answer" })

        val second = content.requests[1].entries
        assertTrue(second.any { it.label == "system_update" })
        assertTrue(second.any { it.label == "snapshot_changed" })
    }

    @Test
    fun `pairs tool calls with their results`() {
        val toolCall = buildJsonObject {
            put("id", "call-1")
            putJsonObjectOf("function") {
                put("name", "read_file")
                put("arguments", """{"path":"a.md"}""")
            }
        }
        val trace = traceOf(
            llmInput(
                index = 0,
                callId = "c1",
                system = "S",
                messages = listOf(
                    message("system", "S"),
                    messageWithToolCalls("assistant", listOf(toolCall)),
                ),
                tools = buildJsonArray {
                    add(
                        buildJsonObject {
                            put("name", "read_file")
                            put("description", "read a file")
                        },
                    )
                },
            ),
            llmOutput(index = 1, callId = "c1", text = ""),
            llmInput(
                index = 2,
                callId = "c2",
                system = "S",
                messages = listOf(
                    message("system", "S"),
                    messageWithToolCalls("assistant", listOf(toolCall)),
                    message("tool", "file body", toolCallId = "call-1", toolName = "read_file"),
                ),
            ),
            llmOutput(index = 3, callId = "c2", text = "done"),
        )

        val content = analyzeTrajectoryContent(trace, analyzeTrajectory(trace))

        val exchange = content.toolExchanges.first { it.call.id == "call-1" && it.result != null }
        assertEquals("read_file", exchange.call.name)
        assertEquals("""{"path":"a.md"}""", exchange.call.arguments)
        assertEquals("file body", exchange.result?.content)
        assertEquals(TrajectoryContentKind.TOOL, exchange.result?.kind)
    }

    @Test
    fun `metadata only traces have no content available`() {
        val trace = traceOf(
            AgentRunRecord(
                type = "agent_run",
                runId = "run-1",
                createdAt = "2026-11-01T10:00:00Z",
                data = buildJsonObject {
                    put("span_id", "root")
                    put("duration_ms", 10)
                },
            ),
        )

        val content = analyzeTrajectoryContent(trace, analyzeTrajectory(trace))

        assertFalse(content.available)
        assertTrue(content.requests.isEmpty())
        assertTrue(content.entries.isEmpty())
    }

    private fun traceOf(vararg records: AgentRunRecord): AgentRunTrace = AgentRunTrace(
        summary = AgentRunSummary(id = "run-1", projectId = "p1", status = "success"),
        records = records.toList(),
    )

    private fun llmInput(
        index: Int,
        callId: String,
        system: String,
        messages: List<JsonObject>,
        tools: kotlinx.serialization.json.JsonArray = buildJsonArray { },
    ): AgentRunRecord {
        val content = buildJsonObject {
            put("messages", buildJsonArray { messages.forEach { add(it) } })
            put("tools", tools)
            put("message_count", messages.size)
            put("tool_count", tools.size)
        }
        return AgentRunRecord(
            type = "llm_input",
            runId = "run-1",
            createdAt = "2026-11-01T10:00:0${index}Z",
            data = buildJsonObject {
                put("call_id", callId)
                put("span_id", "span-$callId")
                put("content", content)
            },
        )
    }

    private fun llmOutput(index: Int, callId: String, text: String): AgentRunRecord = AgentRunRecord(
        type = "llm_output",
        runId = "run-1",
        createdAt = "2026-11-01T10:00:0${index}Z",
        data = buildJsonObject {
            put("call_id", callId)
            put("span_id", "span-$callId")
            put(
                "content",
                buildJsonObject {
                    put("status", "success")
                    put("message", message("assistant", text))
                },
            )
        },
    )

    private fun message(role: String, content: String, toolCallId: String = "", toolName: String = ""): JsonObject =
        buildJsonObject {
            put("role", role)
            put("content", content)
            if (toolCallId.isNotEmpty()) put("tool_call_id", toolCallId)
            if (toolName.isNotEmpty()) put("tool_name", toolName)
        }

    private fun messageWithToolCalls(
        role: String,
        calls: List<JsonObject>,
    ): JsonObject = buildJsonObject {
        put("role", role)
        put("content", "")
        put("tool_calls", buildJsonArray { calls.forEach { add(it) } })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObjectOf(
        key: String,
        block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) {
        put(key, buildJsonObject(block))
    }
}
