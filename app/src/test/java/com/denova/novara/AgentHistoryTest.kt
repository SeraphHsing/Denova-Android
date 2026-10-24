package com.denova.novara

import com.denova.novara.core.stream.AgentMessage
import com.denova.novara.core.stream.buildAgentHistoryNarrative
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentHistoryTest {

    private fun textPart(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun reasoningPart(text: String) = buildJsonObject {
        put("type", "reasoning")
        put("text", text)
    }

    @Test
    fun `empty messages produce empty narrative`() {
        assertEquals("", buildAgentHistoryNarrative(emptyList()))
    }

    @Test
    fun `user message gets quote prefix`() {
        val messages = listOf(AgentMessage(id = "1", role = "user", parts = listOf(textPart("帮我写开场"))))
        assertEquals("> 帮我写开场", buildAgentHistoryNarrative(messages))
    }

    @Test
    fun `assistant text without prefix`() {
        val messages = listOf(AgentMessage(id = "2", role = "assistant", parts = listOf(textPart("好的，开场如下"))))
        assertEquals("好的，开场如下", buildAgentHistoryNarrative(messages))
    }

    @Test
    fun `joins multiple messages with blank line`() {
        val messages = listOf(
            AgentMessage(id = "1", role = "user", parts = listOf(textPart("问题"))),
            AgentMessage(id = "2", role = "assistant", parts = listOf(textPart("回答"))),
        )
        assertEquals("> 问题\n\n回答", buildAgentHistoryNarrative(messages))
    }

    @Test
    fun `ignores non text parts`() {
        val messages = listOf(
            AgentMessage(id = "1", role = "assistant", parts = listOf(reasoningPart("思考"))),
        )
        assertEquals("", buildAgentHistoryNarrative(messages))
    }

    @Test
    fun `concatenates multiple text parts`() {
        val messages = listOf(
            AgentMessage(id = "1", role = "assistant", parts = listOf(textPart("第一段"), textPart("第二段"))),
        )
        assertEquals("第一段第二段", buildAgentHistoryNarrative(messages))
    }
}
