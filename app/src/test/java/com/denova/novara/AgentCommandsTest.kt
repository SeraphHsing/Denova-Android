package com.denova.novara

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText

import com.denova.novara.core.stream.AgentActiveView
import com.denova.novara.feature.agent.AgentCommands
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AGT-005 暂停 / 停止：目标操作身份门控、命令线格式、错误码文案。 */
class AgentCommandsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `active projection exposes operation id`() {
        val raw = """
            {"active":true,"phase":"running","active_operation_id":"op-42","cursor":17,
             "last_operation":{"operation_id":"op-41","command_id":"cmd-1","status":"succeeded"}}
        """.trimIndent()
        val view = json.decodeFromString(AgentActiveView.serializer(), raw)
        assertEquals("op-42", AgentCommands.activeOperationId(view))
        assertTrue(AgentCommands.canControl(view))
        assertEquals("op-41", view.lastOperation?.operationId)
        assertEquals(17L, view.cursor)
    }

    @Test
    fun `projection without operation id cannot control`() {
        val view = json.decodeFromString(
            AgentActiveView.serializer(),
            """{"active":false,"phase":"idle"}""",
        )
        assertEquals("", AgentCommands.activeOperationId(view))
        assertFalse(AgentCommands.canControl(view))
        assertFalse(AgentCommands.canControl(null))
    }

    @Test
    fun `blank operation id is trimmed away`() {
        val view = json.decodeFromString(
            AgentActiveView.serializer(),
            """{"active":true,"active_operation_id":"  "}""",
        )
        assertFalse(AgentCommands.canControl(view))
    }

    @Test
    fun `suspend request carries exact target operation without reason`() {
        val request = AgentCommands.suspend("cmd-9", "default", "op-42")
        assertEquals("suspend", request.type)
        assertEquals("op-42", request.targetOperationId)
        assertEquals("default", request.sessionId)
        assertEquals("cmd-9", request.commandId)
        assertNull(request.reason)
    }

    @Test
    fun `abort request carries user requested reason`() {
        val request = AgentCommands.abort("cmd-10", "default", "op-42")
        assertEquals("abort", request.type)
        assertEquals("user_requested", request.reason)
        assertEquals("op-42", request.targetOperationId)
    }

    @Test
    fun `request serialises to server field names`() {
        val body = Json.encodeToString(
            com.denova.novara.core.network.AgentCommandRequest.serializer(),
            AgentCommands.abort("cmd-10", "default", "op-42"),
        )
        assertTrue(body.contains("\"session_id\":\"default\""))
        assertTrue(body.contains("\"type\":\"abort\""))
        assertTrue(body.contains("\"command_id\":\"cmd-10\""))
        assertTrue(body.contains("\"target_operation_id\":\"op-42\""))
        assertTrue(body.contains("\"reason\":\"user_requested\""))
    }

    @Test
    fun `error messages distinguish phase mismatch and commit`() {
        assertEquals(UiText.of(R.string.cmd_err_invalid_phase), AgentCommands.errorMessage("agent_runtime.invalid_phase"))
        assertEquals(
            UiText.of(R.string.cmd_err_target_mismatch),
            AgentCommands.errorMessage("agent_runtime.target_operation_mismatch"),
        )
        assertEquals(
            UiText.of(R.string.cmd_err_commit_won),
            AgentCommands.errorMessage("agent_runtime.commit_won"),
        )
        assertEquals(UiText.raw("自定义失败"), AgentCommands.errorMessage("agent_runtime.unknown", "自定义失败"))
        assertEquals(UiText.of(R.string.cmd_err_action_failed), AgentCommands.errorMessage(null, " "))
    }

    @Test
    fun `target gone covers phase and mismatch only`() {
        assertTrue(AgentCommands.isTargetGone("agent_runtime.invalid_phase"))
        assertTrue(AgentCommands.isTargetGone("agent_runtime.target_operation_mismatch"))
        assertFalse(AgentCommands.isTargetGone("agent_runtime.commit_won"))
        assertFalse(AgentCommands.isTargetGone(null))
    }
}
