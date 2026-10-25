package com.denova.novara

import com.denova.novara.core.network.AgentCommandRequest
import com.denova.novara.core.network.TextSelectionRef
import com.denova.novara.core.stream.AgentActiveView
import com.denova.novara.core.stream.AgentQueuedCommand
import com.denova.novara.feature.agent.AgentCommands
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AGT-006 追加与转向、AGT-007 队列项控制：`active.queue` 投影、门控与命令线格式。 */
class AgentQueueTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun viewWithQueue(raw: String): AgentActiveView =
        json.decodeFromString(AgentActiveView.serializer(), raw)

    private val runningWithQueue = """
        {"active":true,"phase":"running","active_operation_id":"op-42",
         "queue":[{"command_id":"cmd-1","operation_id":"op-42","delivery":"follow_up","message":"一"},
                  {"command_id":"cmd-2","operation_id":"op-42","delivery":"follow_up","message":"二"}]}
    """.trimIndent()

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

    @Test
    fun `queue item lookup is by command id`() {
        val view = viewWithQueue(runningWithQueue)
        assertEquals("二", AgentCommands.queuedItem(view, "cmd-2")?.message)
        assertNull(AgentCommands.queuedItem(view, "cmd-9"))
        assertNull(AgentCommands.queuedItem(view, "  "))
        assertNull(AgentCommands.queuedItem(null, "cmd-1"))
    }

    @Test
    fun `steer requires item bound to the current operation`() {
        val view = viewWithQueue(runningWithQueue)
        assertTrue(AgentCommands.canSteerQueued(view, AgentCommands.queuedItem(view, "cmd-1")))
        // 操作已换代：同一个 command_id 不再是当前操作的队列项，不能转向。
        val next = viewWithQueue(
            runningWithQueue.replace("\"active_operation_id\":\"op-42\"", "\"active_operation_id\":\"op-43\""),
        )
        assertFalse(AgentCommands.canSteerQueued(next, AgentCommands.queuedItem(next, "cmd-1")))
        // 没有当前操作（已结束）：转向无从谈起。
        val idle = viewWithQueue("""{"active":false,"phase":"idle"}""")
        assertFalse(AgentCommands.canSteerQueued(idle, AgentCommands.queuedItem(view, "cmd-1")))
        assertFalse(AgentCommands.canSteerQueued(view, null))
    }

    @Test
    fun `cancel only needs the item to stay queued`() {
        val view = viewWithQueue(runningWithQueue)
        assertTrue(AgentCommands.canCancelQueued(view, AgentCommands.queuedItem(view, "cmd-2")))
        // 队列已清空（项已执行或已被别处删除）时不得再发删除。
        val idle = viewWithQueue("""{"active":false,"phase":"idle"}""")
        assertFalse(AgentCommands.canCancelQueued(idle, AgentCommands.queuedItem(view, "cmd-2")))
        assertFalse(AgentCommands.canCancelQueued(view, null))
        assertFalse(AgentCommands.canCancelQueued(view, AgentQueuedCommand(commandId = "cmd-3")))
    }

    @Test
    fun `steer request targets one queued item without input`() {
        val request = AgentCommands.steerQueued("cmd-9", "default", "op-42", "cmd-1")
        assertEquals("steer_queued", request.type)
        assertEquals("cmd-1", request.targetCommandId)
        assertEquals("op-42", request.targetOperationId)
        assertNull(request.input)
        assertNull(request.reason)
    }

    @Test
    fun `cancel request carries reason and target item`() {
        val deleted = AgentCommands.cancelQueued("cmd-10", "default", "op-42", "cmd-1")
        assertEquals("cancel_queued", deleted.type)
        assertEquals("cmd-1", deleted.targetCommandId)
        assertEquals(AgentCommands.CANCEL_REASON_DELETED, deleted.reason)
        val edited = AgentCommands.cancelQueued(
            "cmd-11",
            "default",
            "op-42",
            "cmd-1",
            AgentCommands.CANCEL_REASON_EDITOR,
        )
        assertEquals("returned_to_editor", edited.reason)
    }

    @Test
    fun `queue control body carries target command id`() {
        val body = Json.encodeToString(
            AgentCommandRequest.serializer(),
            AgentCommands.cancelQueued("cmd-10", "default", "op-42", "cmd-1"),
        )
        assertTrue(body.contains("\"type\":\"cancel_queued\""))
        assertTrue(body.contains("\"target_command_id\":\"cmd-1\""))
        assertTrue(body.contains("\"reason\":\"user_deleted\""))
        assertFalse(body.contains("input"))
    }
}
