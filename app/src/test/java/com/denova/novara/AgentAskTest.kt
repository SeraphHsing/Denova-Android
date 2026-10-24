package com.denova.novara

import com.denova.novara.core.network.SessionAskAnswerRequest
import com.denova.novara.core.network.SessionAskCancelRequest
import com.denova.novara.core.stream.AgentAskAnswer
import com.denova.novara.core.stream.AgentAskInteraction
import com.denova.novara.core.stream.AgentAskLogic
import com.denova.novara.core.stream.AgentAskResolution
import com.denova.novara.core.stream.AgentAskSelectedOption
import com.denova.novara.core.stream.AgentAskAnswerResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AGT-004 提问交互：解析、选项切换、答案体与已解决状态。 */
class AgentAskTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val payload = """
        {
          "schema": "ask.pending.v1",
          "id": "ask-1",
          "kind": "question",
          "tool_call_id": "call-1",
          "agent_kind": "ide",
          "status": "pending",
          "allow_other": true,
          "questions": [
            {
              "id": "direction",
              "question": "选择方向",
              "multi_select": false,
              "recommended_option_id": "continue",
              "options": [
                {"id": "continue", "label": "继续", "description": "沿当前方向推进。"},
                {"id": "switch", "label": "切换"}
              ]
            }
          ]
        }
    """.trimIndent()

    private fun ask(): AgentAskInteraction = json.decodeFromString(AgentAskInteraction.serializer(), payload)

    @Test
    fun `data-ask payload parses to pending interaction`() {
        val ask = ask()
        assertEquals("ask.pending.v1", ask.schema)
        assertEquals("ask-1", ask.id)
        assertEquals("call-1", ask.toolCallId)
        assertEquals("ide", ask.agentKind)
        assertTrue(ask.isPending)
        assertTrue(ask.allowOther)
        assertEquals(1, ask.questions.size)
        val question = ask.questions.first()
        assertEquals("direction", question.id)
        assertEquals("continue", question.recommendedOptionId)
        assertFalse(question.multiSelect)
        assertEquals(listOf("continue", "switch"), question.options.map { it.id })
        assertEquals("沿当前方向推进。", question.options.first().description)
    }

    @Test
    fun `single select replaces previous choice and can be cleared`() {
        val question = ask().questions.first()
        assertEquals(listOf("continue"), AgentAskLogic.toggle(question, emptyList(), "continue"))
        assertEquals(listOf("switch"), AgentAskLogic.toggle(question, listOf("continue"), "switch"))
        assertEquals(emptyList<String>(), AgentAskLogic.toggle(question, listOf("switch"), "switch"))
    }

    @Test
    fun `multi select toggles options`() {
        val question = ask().questions.first().copy(multiSelect = true)
        val once = AgentAskLogic.toggle(question, emptyList(), "continue")
        val twice = AgentAskLogic.toggle(question, once, "switch")
        assertEquals(listOf("continue", "switch"), twice)
        assertEquals(listOf("switch"), AgentAskLogic.toggle(question, twice, "continue"))
    }

    @Test
    fun `submit requires every question answered`() {
        val ask = ask()
        assertFalse(AgentAskLogic.canSubmit(ask, emptyMap()))
        assertFalse(AgentAskLogic.canSubmit(ask, mapOf("direction" to emptyList())))
        assertTrue(AgentAskLogic.canSubmit(ask, mapOf("direction" to listOf("continue"))))
        // 已解决的提问不再可提交
        assertFalse(AgentAskLogic.canSubmit(ask.copy(status = AgentAskLogic.STATUS_ANSWERED), mapOf("direction" to listOf("continue"))))
    }

    @Test
    fun `answers follow question order and skip unanswered`() {
        val multi = ask().copy(
            questions = ask().questions + ask().questions.first().copy(id = "tone", question = "语气"),
        )
        val answers = AgentAskLogic.answers(multi, mapOf("direction" to listOf("continue")))
        assertEquals(1, answers.size)
        assertEquals(AgentAskAnswer(questionId = "direction", selectedOptionIds = listOf("continue")), answers.first())
    }

    @Test
    fun `answer request serialises server field names`() {
        val body = Json.encodeToString(
            SessionAskAnswerRequest.serializer(),
            SessionAskAnswerRequest(
                sessionId = "default",
                answers = listOf(AgentAskAnswer("direction", listOf("continue"))),
            ),
        )
        assertTrue(body.contains("\"session_id\":\"default\""))
        assertTrue(body.contains("\"question_id\":\"direction\""))
        assertTrue(body.contains("\"selected_option_ids\":[\"continue\"]"))
    }

    @Test
    fun `cancel request uses user cancelled reason`() {
        val body = Json.encodeToString(
            SessionAskCancelRequest.serializer(),
            SessionAskCancelRequest("default", AgentAskLogic.CANCEL_REASON_USER),
        )
        assertTrue(body.contains("\"reason\":\"user_cancelled\""))
    }

    @Test
    fun `resolution updates status and status text`() {
        val ask = ask()
        val answered = AgentAskLogic.applyResolution(
            ask,
            AgentAskResolution(
                schema = "ask.result.v1",
                id = ask.id,
                status = AgentAskLogic.STATUS_ANSWERED,
                answers = listOf(
                    AgentAskAnswerResult(
                        questionId = "direction",
                        question = "选择方向",
                        selectedOptions = listOf(AgentAskSelectedOption("continue", "继续")),
                    ),
                ),
            ),
        )
        assertEquals(AgentAskLogic.STATUS_ANSWERED, answered.status)
        assertFalse(answered.isPending)
        assertEquals("已回答：继续", AgentAskLogic.statusText(answered))

        val cancelled = AgentAskLogic.applyResolution(
            ask,
            AgentAskResolution(id = ask.id, status = AgentAskLogic.STATUS_CANCELLED, cancelReason = "user_cancelled"),
        )
        assertEquals("已取消（user_cancelled）", AgentAskLogic.statusText(cancelled))
        assertEquals("已取消", AgentAskLogic.statusText(cancelled.copy(cancelReason = null)))
    }

    @Test
    fun `merge keeps local answers when stream chunk is thinner`() {
        val cancelled = ask().copy(
            status = AgentAskLogic.STATUS_CANCELLED,
            cancelReason = AgentAskLogic.CANCEL_REASON_USER,
        )
        val thin = ask().copy(status = AgentAskLogic.STATUS_CANCELLED)
        val merged = AgentAskLogic.merge(cancelled, thin)
        assertEquals(AgentAskLogic.CANCEL_REASON_USER, merged.cancelReason)
        assertEquals("已取消（user_cancelled）", AgentAskLogic.statusText(merged))

        val answered = ask().copy(
            status = AgentAskLogic.STATUS_ANSWERED,
            answers = listOf(
                AgentAskAnswerResult(
                    questionId = "direction",
                    question = "选择方向",
                    selectedOptions = listOf(AgentAskSelectedOption("continue", "继续")),
                ),
            ),
        )
        val mergedAnswers = AgentAskLogic.merge(answered, ask().copy(status = AgentAskLogic.STATUS_ANSWERED))
        assertEquals(1, mergedAnswers.answers.size)
        assertEquals("已回答：继续", AgentAskLogic.statusText(mergedAnswers))
        // 无历史时原样接受
        assertEquals(thin, AgentAskLogic.merge(null, thin))
    }

    @Test
    fun `ask error codes map and stale needs refresh`() {
        assertEquals("该提问已被处理或已过期", AgentAskLogic.askError("agent_runtime.ask_stale"))
        assertEquals("答案不符合提问选项", AgentAskLogic.askError("agent_runtime.invalid_ask_answer"))
        assertEquals("自定义", AgentAskLogic.askError("agent_runtime.unknown", "自定义"))
        assertTrue(AgentAskLogic.needsRefresh("agent_runtime.ask_stale"))
        assertTrue(AgentAskLogic.needsRefresh("agent_runtime.ask_not_found"))
        assertFalse(AgentAskLogic.needsRefresh("agent_runtime.invalid_ask_answer"))
    }
}
