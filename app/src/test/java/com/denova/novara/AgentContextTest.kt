package com.denova.novara

import com.denova.novara.core.stream.AgentContextAnalysis
import com.denova.novara.core.stream.AgentContextLogic
import com.denova.novara.core.stream.AgentToolPart
import com.denova.novara.core.stream.CompactionState
import com.denova.novara.core.stream.ToolCallStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AGT-012 上下文与 Todo：分析结果口径 + `todo` 工具入参解析（只读展示，不建第二套可写状态）。 */
class AgentContextTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun toolPart(name: String, input: String) = AgentToolPart(
        id = "$name#0",
        callId = "call-1",
        name = name,
        state = "output-available",
        inputJson = input,
        outputText = "",
        errorText = "",
        status = ToolCallStatus.OK,
    )

    @Test
    fun `analysis parses usage and compaction`() {
        val raw = """
            {"agent_kind":"writing","mode":"native","message_count":42,
             "token_estimate":98000,"projected_token_estimate":99500,
             "reserved_completion_tokens":8000,"reserved_tool_result_tokens":4000,
             "context_window_tokens":200000,"context_usage_ratio":0.49,
             "compaction_revision":3,"compaction_active":true,"would_compact":false,
             "compaction":{"id":"c1","revision":3,"summary":"前情摘要","tokens_before":180000,
                           "tokens_after":40000,"source_message_count":30,"removable":true},
             "system_prompt":"…","system_prompt_parts":[{"title":"a","content":"b"}],
             "context_parts":[],"context_messages":[]}
        """.trimIndent()
        val analysis = json.decodeFromString(AgentContextAnalysis.serializer(), raw)
        assertEquals(98000, analysis.tokenEstimate)
        assertEquals(200000, analysis.contextWindowTokens)
        assertEquals(49, analysis.usagePercent)
        assertEquals(42, analysis.messageCount)
        assertEquals(CompactionState.ACTIVE, analysis.compactionState)
        assertEquals("前情摘要", analysis.compaction?.summary)
        assertEquals(180000, analysis.compaction?.tokensBefore)
        assertTrue(analysis.compaction?.removable == true)
    }

    @Test
    fun `analysis without compaction reports next turn or none`() {
        val nextTurn = json.decodeFromString(
            AgentContextAnalysis.serializer(),
            """{"context_usage_ratio":0.91,"would_compact":true}""",
        )
        assertEquals(CompactionState.NEXT_TURN, nextTurn.compactionState)
        assertNull(nextTurn.compaction)

        val none = json.decodeFromString(AgentContextAnalysis.serializer(), """{"context_usage_ratio":0.2}""")
        assertEquals(CompactionState.NONE, none.compactionState)
        assertEquals(20, none.usagePercent)
    }

    @Test
    fun `todos are parsed from tool input`() {
        val todos = AgentContextLogic.parseTodos(
            """{"todos":[{"step":"写大纲","status":"completed"},
                         {"content":"改第三节","status":"in_progress"},
                         {"step":"梳理时间线","status":"pending"}]}""",
        )
        assertEquals(3, todos.size)
        assertEquals("写大纲", todos[0].step)
        assertTrue(todos[0].done)
        assertTrue(todos[1].running)
        assertEquals("改第三节", todos[1].step)
        assertFalse(todos[2].done)
    }

    @Test
    fun `partial streamed arguments yield empty list instead of throwing`() {
        // 流式期间入参是半截 JSON：必须安静地返回空表，由界面保留上一次清单。
        assertTrue(AgentContextLogic.parseTodos("""{"todos":[{"step":"写""").isEmpty())
        assertTrue(AgentContextLogic.parseTodos("").isEmpty())
        assertTrue(AgentContextLogic.parseTodos("{}").isEmpty())
        assertTrue(AgentContextLogic.parseTodos("""{"todos":[]}""").isEmpty())
    }

    @Test
    fun `todo tool detection covers naming variants`() {
        assertTrue(AgentContextLogic.isTodoTool("todo"))
        assertTrue(AgentContextLogic.isTodoTool(" Todo_Write "))
        assertFalse(AgentContextLogic.isTodoTool("read_file"))
    }

    @Test
    fun `latest non-empty todo list wins`() {
        val first = toolPart("todo", """{"todos":[{"step":"a","status":"pending"}]}""")
        val empty = toolPart("todo", """{"todos":[]}""")
        val readTool = toolPart("read_file", """{"path":"x.md"}""")
        val latest = toolPart("todo", """{"todos":[{"step":"b","status":"completed"}]}""")
        val todos = AgentContextLogic.latestTodos(listOf(first, empty, readTool, latest))
        assertEquals(listOf("b"), todos.map { it.step })
    }
}
