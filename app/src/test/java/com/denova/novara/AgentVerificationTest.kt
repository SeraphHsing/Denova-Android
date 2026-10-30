package com.denova.novara

import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.stream.AgentAskInteraction
import com.denova.novara.core.stream.AgentAskLogic
import com.denova.novara.core.stream.AgentAskResolution
import com.denova.novara.core.stream.AgentVerificationLogic
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具副作用验证（AGT-004 补验）。
 *
 * 契约取自后端 `agent/lifecycle/lifecycle_tool_facts.go`（`verify-<callId>` +
 * `ToolEffectVerification`）与 Web `AskInteractionCard`（`chat.verification.*`）。
 * 只覆盖解码、文案映射与答案形状；真实场景需要「有副作用工具执行中断 + 运行恢复」。
 */
class AgentVerificationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val verificationJson = """
        {
          "schema": "ask.pending.v1",
          "id": "verify-call-1",
          "kind": "ask",
          "tool_call_id": "call-1",
          "agent_kind": "ide",
          "status": "pending",
          "allow_other": false,
          "verification": {
            "execution_id": "call-1",
            "tool": "publish_document",
            "arguments": {"title": "Chapter 1", "path": "book/ch1.md"}
          },
          "questions": [
            {"id": "effect", "question": "Did this tool operation take effect before execution stopped?",
             "options": [
               {"id": "executed", "label": "It took effect"},
               {"id": "not_executed", "label": "It did not take effect"},
               {"id": "unknown", "label": "I cannot determine yet"}
             ]}
          ]
        }
    """.trimIndent()

    @Test
    fun `verification ask decodes host evidence and questions`() {
        val ask = json.decodeFromString(AgentAskInteraction.serializer(), verificationJson)

        assertTrue(ask.isVerification)
        assertFalse(ask.isToolApproval)
        assertEquals("verify-call-1", ask.id)
        assertEquals("call-1", ask.toolCallId)
        assertEquals("publish_document", ask.verification?.tool)
        assertEquals("call-1", ask.verification?.executionId)
        assertEquals("effect", ask.questions.single().id)
        assertEquals(3, ask.questions.single().options.size)
        // 验证场景强制关闭自由输入（Web 同样把 allow_other 置 false）。
        assertFalse(ask.allowOther)
    }

    @Test
    fun `verification arguments render as truncated pretty json`() {
        val ask = json.decodeFromString(AgentAskInteraction.serializer(), verificationJson)
        val text = AgentVerificationLogic.argumentsText(ask.verification)

        assertNotNull(text)
        assertTrue(text!!.contains("\"title\""))
        assertTrue(text.contains("Chapter 1"))
        // prettyPrint 会插入换行；单行展示说明没走 pretty。
        assertTrue(text.contains("\n"))

        val clipped = AgentVerificationLogic.argumentsText(ask.verification, limit = 12)
        assertEquals(13, clipped!!.length)
        assertTrue(clipped.endsWith("…"))
    }

    @Test
    fun `verification options map to localized labels and unknown ids fall back`() {
        assertEquals(R.string.verify_executed, AgentVerificationLogic.optionLabelRes("executed"))
        assertEquals(R.string.verify_not_executed, AgentVerificationLogic.optionLabelRes("not_executed"))
        assertEquals(R.string.verify_unknown, AgentVerificationLogic.optionLabelRes("unknown"))
        assertNull(AgentVerificationLogic.optionLabelRes("other"))
        assertNull(AgentVerificationLogic.optionLabelRes("pending"))
    }

    @Test
    fun `verification cancel uses task_aborted while normal ask keeps user_cancelled`() {
        val verification = json.decodeFromString(AgentAskInteraction.serializer(), verificationJson)
        assertEquals("task_aborted", AgentVerificationLogic.cancelReason(verification))

        val normal = json.decodeFromString(
            AgentAskInteraction.serializer(),
            """{"schema":"ask.pending.v1","id":"ask-1","status":"pending","questions":[{"id":"q1","question":"去哪？"}]}""",
        )
        assertFalse(normal.isVerification)
        assertEquals("user_cancelled", AgentVerificationLogic.cancelReason(normal))
    }

    @Test
    fun `unknown answer stays pending and executed answer resolves`() {
        val ask = json.decodeFromString(AgentAskInteraction.serializer(), verificationJson)
        val answer = AgentAskLogic.answers(ask, mapOf("effect" to listOf(AgentVerificationLogic.UNKNOWN)))

        assertEquals("effect", answer.single().questionId)
        assertEquals(listOf("unknown"), answer.single().selectedOptionIds)

        // 服务端对 unknown / 取消 / 多答案回 pending：卡片必须留在待答位。
        assertTrue(
            AgentVerificationLogic.stillPending(
                AgentAskResolution(schema = "ask.result.v1", id = ask.id, status = "pending"),
            ),
        )
        assertFalse(
            AgentVerificationLogic.stillPending(
                AgentAskResolution(
                    schema = "ask.result.v1",
                    id = ask.id,
                    status = "answered",
                    answers = listOf(
                        com.denova.novara.core.stream.AgentAskAnswerResult(
                            questionId = "effect",
                            selectedOptions = listOf(
                                com.denova.novara.core.stream.AgentAskSelectedOption("executed", "It took effect"),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `answered verification text localizes the submitted choice`() {
        val base = json.decodeFromString(AgentAskInteraction.serializer(), verificationJson)

        fun answered(optionId: String) = base.copy(
            status = "answered",
            answers = listOf(
                com.denova.novara.core.stream.AgentAskAnswerResult(
                    questionId = "effect",
                    selectedOptions = listOf(
                        com.denova.novara.core.stream.AgentAskSelectedOption(optionId, "It took effect"),
                    ),
                ),
            ),
        )

        assertEquals(UiText.of(R.string.verify_state_executed), AgentAskLogic.statusText(answered("executed")))
        assertEquals(
            UiText.of(R.string.verify_state_not_executed),
            AgentAskLogic.statusText(answered("not_executed")),
        )
        assertEquals(UiText.of(R.string.verify_state_unknown), AgentAskLogic.statusText(answered("unknown")))

        // 普通提问仍按服务端 label 列出（不受验证分支影响）。
        val normal = json.decodeFromString(
            AgentAskInteraction.serializer(),
            """{"schema":"ask.pending.v1","id":"ask-2","status":"answered",
                "questions":[{"id":"q1","question":"去哪？"}],
                "answers":[{"question_id":"q1","question":"去哪？","selected_options":[{"id":"north","label":"北上"}]}]}""".trimIndent(),
        )
        assertEquals(UiText.of(R.string.ask_status_answered_items, "北上"), AgentAskLogic.statusText(normal))
    }

    @Test
    fun `verification evidence survives an unrelated unknown field`() {
        val payload = """
            {"id":"verify-2","status":"pending","future_field":{"a":1},
             "verification":{"execution_id":"exec-2","tool":"edit_file","arguments":["a","b"]},
             "questions":[{"id":"effect","question":"?","options":[{"id":"unknown","label":"Unknown"}]}]}
        """.trimIndent()
        val ask = json.decodeFromString(AgentAskInteraction.serializer(), payload)

        assertTrue(ask.isVerification)
        assertEquals("edit_file", ask.verification?.tool)
        // arguments 保持原样（数组也是合法 JSON），不影响渲染。
        assertTrue(ask.verification?.arguments is kotlinx.serialization.json.JsonArray)
        assertTrue(AgentVerificationLogic.argumentsText(ask.verification).orEmpty().contains("\"a\""))
    }
}
