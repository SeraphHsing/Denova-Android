package com.denova.novara

import com.denova.novara.core.network.AgentCommandRequest
import com.denova.novara.core.network.TextSelectionRef
import com.denova.novara.core.stream.AgentActiveView
import com.denova.novara.feature.agent.AgentCommands
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AGT-006 追加与转向：`active.queue` 投影、follow_up 分流与命令线格式。 */
class AgentQueueTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `active projection carries queued commands`() {
        val raw = """
            {"active":true,"phase":"running","active_operation_id":"op-42",
             "queue":[{"command_id":"cmd-1","operation_id":"op-42","delivery":"follow_up",
                       "message":"继续写第二章","message_truncated":false,"steer_requested":true}]}
        """.trimIndent()
        val view = json.decodeFromString(AgentActiveView.serializer(), raw)
        assertEquals(1, view.queue.size)
        val item = view.queue.single()
        assertEquals("cmd-1", item.commandId)
        assertEquals("op-42", item.operationId)
        assertEquals("follow_up", item.delivery)
        assertEquals("继续写第二章", item.message)
        assertTrue(item.steerRequested)
        assertFalse(item.messageTruncated)
    }

    @Test
    fun `explicit null queue falls back to empty`() {
        // 服务端在部分阶段会显式下发 null（同 recovery_actions 的历史问题），不能因此解析崩溃。
        val view = json.decodeFromString(
            AgentActiveView.serializer(),
            """{"active":false,"phase":"idle","queue":null}""",
        )
        assertTrue(view.queue.isEmpty())
    }

    @Test
    fun `follow up decision requires server operation id`() {
        assertTrue(AgentCommands.shouldFollowUp("op-42"))
        assertFalse(AgentCommands.shouldFollowUp("   "))
        assertFalse(AgentCommands.shouldFollowUp(""))
        assertFalse(AgentCommands.shouldFollowUp(null))
    }

    @Test
    fun `follow up request carries input and exact target`() {
        val selection = TextSelectionRef("chapters/01.md", 2, 4, "引文")
        val request = AgentCommands.followUp(
            commandId = "cmd-7",
            sessionId = "default",
            operationId = "op-42",
            message = "  继续写第二章  ",
            references = listOf("chapters/01.md"),
            selections = listOf(selection),
        )
        assertEquals(AgentCommands.TYPE_FOLLOW_UP, request.type)
        assertEquals("op-42", request.targetOperationId)
        assertEquals("default", request.sessionId)
        assertEquals("cmd-7", request.commandId)
        assertEquals("继续写第二章", request.input?.message)
        assertEquals(listOf("chapters/01.md"), request.input?.references)
        assertEquals(listOf(selection), request.input?.selections)
        assertNull(request.reason)
    }

    @Test
    fun `follow up body nests input under server field name`() {
        val body = Json.encodeToString(
            AgentCommandRequest.serializer(),
            AgentCommands.followUp("cmd-7", "default", "op-42", "继续"),
        )
        assertTrue(body.contains("\"type\":\"follow_up\""))
        assertTrue(body.contains("\"target_operation_id\":\"op-42\""))
        assertTrue(body.contains("\"input\":{\"message\":\"继续\"}"))
        // 未启用的可选字段必须缺省，否则服务端会把空字段当成显式值。
        assertFalse(body.contains("target_command_id"))
        assertFalse(body.contains("reason"))
    }
}
