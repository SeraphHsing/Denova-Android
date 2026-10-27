package com.denova.novara

import com.denova.novara.core.network.GameCommandRequest
import com.denova.novara.core.network.SendInteractiveChatRequest
import com.denova.novara.core.stream.AgentAskInteraction
import com.denova.novara.core.stream.AgentAskLogic
import com.denova.novara.core.stream.GameActiveView
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提问回答与运行时命令（P4-A / GAM-011、GAM-012、GAM-013）。 */
class GameRuntimeControlTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val askJson = """
        {
          "schema": "ask.pending.v1",
          "id": "ask-1",
          "tool_call_id": "call-1",
          "agent_kind": "interactive_story",
          "status": "pending",
          "allow_other": true,
          "questions": [
            {"id": "q1", "question": "接下来往哪走？", "multi_select": false,
             "recommended_option_id": "north",
             "options": [{"id": "north", "label": "北上"}, {"id": "south", "label": "南下"}]}
          ]
        }
    """.trimIndent()

    @Test
    fun `ask event decodes and submits only answered questions`() {
        val ask = json.decodeFromString(AgentAskInteraction.serializer(), askJson)

        assertEquals("ask-1", ask.id)
        assertTrue(ask.isPending)
        assertEquals(1, ask.questions.size)
        // 未作答不能提交。
        assertFalse(AgentAskLogic.canSubmit(ask, emptyMap()))

        val selected = mapOf("q1" to listOf("north"))
        assertTrue(AgentAskLogic.canSubmit(ask, selected))
        val answers = AgentAskLogic.answers(ask, selected)
        assertEquals("q1", answers[0].questionId)
        assertEquals(listOf("north"), answers[0].selectedOptionIds)
    }

    @Test
    fun `ask resolved merges without wiping previous answers`() {
        val pending = json.decodeFromString(AgentAskInteraction.serializer(), askJson)
        val answered = pending.copy(
            status = "answered",
            answers = listOf(
                com.denova.novara.core.stream.AgentAskAnswerResult(
                    questionId = "q1",
                    question = "接下来往哪走？",
                    selectedOptions = listOf(com.denova.novara.core.stream.AgentAskSelectedOption("north", "北上")),
                ),
            ),
        )
        // 流里的 ask_resolved 常常只带状态，不能把已有答案抹掉。
        val resolved = AgentAskLogic.merge(answered, pending.copy(status = "answered"))
        assertEquals("answered", resolved.status)
        assertEquals(1, resolved.answers.size)
    }

    @Test
    fun `suspend command carries target operation`() {
        val command = GameCommandRequest(
            type = "suspend",
            commandId = "c1",
            targetOperationId = "op-9",
            storyId = "s1",
            branchId = "main",
        )
        val text = json.encodeToString(GameCommandRequest.serializer(), command)

        assertTrue(text.contains("\"type\":\"suspend\""))
        assertTrue(text.contains("\"target_operation_id\":\"op-9\""))
        // 未使用的字段不上送：避免服务端把 null 当显式值。
        assertFalse(text.contains("resume_interruption_id"))
        assertFalse(text.contains("reason"))
    }

    @Test
    fun `abandon pending carries only interruption id`() {
        val command = GameCommandRequest(
            type = "abandon_pending",
            storyId = "s1",
            branchId = "main",
            resumeInterruptionId = "intr-3",
        )
        val text = json.encodeToString(GameCommandRequest.serializer(), command)

        assertTrue(text.contains("\"type\":\"abandon_pending\""))
        assertTrue(text.contains("\"resume_interruption_id\":\"intr-3\""))
        assertFalse(text.contains("target_operation_id"))
        assertFalse(text.contains("command_id"))
    }

    @Test
    fun `resume send includes interruption id and continue message`() {
        val request = SendInteractiveChatRequest(
            commandId = "c2",
            storyId = "s1",
            branch = "main",
            message = "Continue.",
            resumeInterruptionId = "intr-3",
        )
        val text = json.encodeToString(SendInteractiveChatRequest.serializer(), request)

        assertTrue(text.contains("\"resume_interruption_id\":\"intr-3\""))
        assertTrue(text.contains("\"message\":\"Continue.\""))
        assertFalse(text.contains("regenerate_from_turn_id"))
    }

    @Test
    fun `active view exposes operation and interruption ids`() {
        val active = json.decodeFromString(
            GameActiveView.serializer(),
            """{"active":true,"task_id":"t1","active_operation_id":"op-9","pending_interruption_id":"intr-3","phase":"running"}""",
        )

        assertEquals("op-9", active.activeOperationId)
        assertEquals("intr-3", active.pendingInterruptionId)
        assertEquals("running", active.phase)
    }
}
