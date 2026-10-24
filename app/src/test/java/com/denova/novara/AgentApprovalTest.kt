package com.denova.novara

import com.denova.novara.core.network.SessionAskAnswerRequest
import com.denova.novara.core.stream.AgentApprovalLogic
import com.denova.novara.core.stream.ApprovalMode
import com.denova.novara.core.stream.ApprovalReason
import com.denova.novara.core.stream.ApprovalRisk
import com.denova.novara.core.stream.AgentAskInteraction
import com.denova.novara.core.stream.AgentAskLogic
import com.denova.novara.core.stream.AgentToolApproval
import com.denova.novara.core.stream.ApprovalOption
import com.denova.novara.core.stream.ApprovalState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AGT-003 审批与记忆规则：权限作用域门控、答案体与状态映射。 */
class AgentApprovalTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val payload = """
        {
          "schema": "ask.pending.v1",
          "id": "ask-approval-1",
          "kind": "tool_approval",
          "tool_call_id": "call-9",
          "agent_kind": "ide",
          "status": "pending",
          "questions": [],
          "approval": {
            "mode": "write",
            "tool_name": "bash",
            "command": "ls -la",
            "cwd": "/data/.denova/projects/demo",
            "risk": "medium",
            "rule_id": "bash_unlisted_command",
            "args_hash": "sha256:abc",
            "can_remember": true,
            "rule_matcher_version": 1,
            "rule_match_key": "[{\"command\":\"ls\"}]",
            "rule_display_pattern": "bash: ls *"
          }
        }
    """.trimIndent()

    private fun ask(): AgentAskInteraction = json.decodeFromString(AgentAskInteraction.serializer(), payload)

    @Test
    fun `approval payload parses into interaction`() {
        val ask = ask()
        assertTrue(ask.isToolApproval)
        assertTrue(ask.questions.isEmpty())
        val approval = ask.approval!!
        assertEquals("bash", approval.toolName)
        assertEquals("write", approval.mode)
        assertEquals("ls -la", approval.command)
        assertEquals("medium", approval.risk)
        assertEquals("bash_unlisted_command", approval.ruleId)
        assertEquals("bash: ls *", approval.ruleDisplayPattern)
        assertTrue(approval.canRemember)
        assertEquals(1, approval.ruleMatcherVersion)
        assertEquals("ls -la", AgentApprovalLogic.body(approval))
    }

    @Test
    fun `plain question ask is not an approval`() {
        val question = json.decodeFromString(
            AgentAskInteraction.serializer(),
            """{"schema":"ask.pending.v1","id":"a1","kind":"question","status":"pending",
                "questions":[{"id":"q","question":"?"}]}""",
        )
        assertFalse(question.isToolApproval)
        assertNull(question.approval)
    }

    @Test
    fun `workspace scope only when server allows remembering`() {
        val approval = ask().approval!!
        assertEquals(
            listOf(ApprovalOption.DENY, ApprovalOption.ALLOW_WORKSPACE, ApprovalOption.ALLOW_ONCE),
            AgentApprovalLogic.options(approval),
        )
        assertTrue(AgentApprovalLogic.canAlwaysAllow(approval))

        val noRemember = approval.copy(canRemember = false)
        assertFalse(AgentApprovalLogic.canAlwaysAllow(noRemember))
        assertEquals(
            listOf(ApprovalOption.DENY, ApprovalOption.ALLOW_ONCE),
            AgentApprovalLogic.options(noRemember),
        )

        val blankPattern = approval.copy(ruleDisplayPattern = "  ")
        assertFalse(AgentApprovalLogic.canAlwaysAllow(blankPattern))
        assertEquals(2, AgentApprovalLogic.options(blankPattern).size)
        assertEquals(2, AgentApprovalLogic.options(null).size)
    }

    @Test
    fun `answers use the fixed tool approval question id`() {
        assertEquals(
            "tool-approval",
            AgentApprovalLogic.answer(ApprovalOption.ALLOW_ONCE).questionId,
        )
        assertEquals(
            listOf("allow-workspace"),
            AgentApprovalLogic.answer(ApprovalOption.ALLOW_WORKSPACE).selectedOptionIds,
        )
        assertEquals(listOf("deny"), AgentApprovalLogic.answer(ApprovalOption.DENY).selectedOptionIds)
        assertEquals(ApprovalOption.ALLOW_ONCE, ApprovalOption.fromWire("allow-once"))
        assertNull(ApprovalOption.fromWire("allow-forever"))
    }

    @Test
    fun `answer request serialises approval body`() {
        val body = Json.encodeToString(
            SessionAskAnswerRequest.serializer(),
            SessionAskAnswerRequest(
                sessionId = "default",
                answers = listOf(AgentApprovalLogic.answer(ApprovalOption.ALLOW_WORKSPACE)),
            ),
        )
        assertTrue(body.contains("\"question_id\":\"tool-approval\""))
        assertTrue(body.contains("\"selected_option_ids\":[\"allow-workspace\"]"))
        assertTrue(body.contains("\"session_id\":\"default\""))
    }

    @Test
    fun `state maps resolution status and selected option`() {
        assertEquals(
            ApprovalState.WAITING,
            AgentApprovalLogic.state(AgentAskLogic.STATUS_PENDING, null),
        )
        assertEquals(
            ApprovalState.ALLOWED,
            AgentApprovalLogic.state(AgentAskLogic.STATUS_ANSWERED, "allow-once"),
        )
        assertEquals(
            ApprovalState.ALLOWED_WORKSPACE,
            AgentApprovalLogic.state(AgentAskLogic.STATUS_ANSWERED, "allow-workspace"),
        )
        assertEquals(
            ApprovalState.DENIED,
            AgentApprovalLogic.state(AgentAskLogic.STATUS_ANSWERED, "deny"),
        )
        // 用户点「取消」：没有选中项，显示已取消
        assertEquals(
            ApprovalState.CANCELLED,
            AgentApprovalLogic.state(AgentAskLogic.STATUS_CANCELLED, null),
        )
        // 服务端把 deny 落成 cancelled，但 answers 保留了 deny：用户明确拒绝，不能显示成已取消
        assertEquals(
            ApprovalState.DENIED,
            AgentApprovalLogic.state(AgentAskLogic.STATUS_CANCELLED, "deny"),
        )
    }

    @Test
    fun `reason risk and mode mappings fall back safely`() {
        assertEquals(ApprovalReason.BASH_UNLISTED_COMMAND, AgentApprovalLogic.reason("bash_unlisted_command"))
        assertEquals(ApprovalReason.EXTERNAL_FILESYSTEM_READ, AgentApprovalLogic.reason("external_filesystem_read"))
        assertEquals(ApprovalReason.GENERIC, AgentApprovalLogic.reason("something_new"))
        assertEquals(ApprovalReason.GENERIC, AgentApprovalLogic.reason(null))

        assertEquals(ApprovalRisk.HIGH, AgentApprovalLogic.risk("HIGH"))
        assertEquals(ApprovalRisk.UNKNOWN, AgentApprovalLogic.risk("extreme"))
        assertEquals(ApprovalMode.WRITE, AgentApprovalLogic.mode("write"))
        assertEquals(ApprovalMode.FULL_ACCESS, AgentApprovalLogic.mode("full_access"))
        assertEquals(ApprovalMode.UNKNOWN, AgentApprovalLogic.mode(""))
    }

    @Test
    fun `body prefers command over details`() {
        val approval = AgentToolApproval(command = "ls", details = "details")
        assertEquals("ls", AgentApprovalLogic.body(approval))
        assertEquals("details", AgentApprovalLogic.body(approval.copy(command = " ")))
        assertNull(AgentApprovalLogic.body(approval.copy(command = null, details = null)))
        assertNull(AgentApprovalLogic.body(null))
    }
}
