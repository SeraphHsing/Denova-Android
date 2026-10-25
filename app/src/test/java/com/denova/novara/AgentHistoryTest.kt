package com.denova.novara

import com.denova.novara.core.network.AgentCommandRequest
import com.denova.novara.core.stream.AgentMessage
import com.denova.novara.core.stream.AgentMessagesPage
import com.denova.novara.core.stream.AgentPartLogic
import com.denova.novara.core.stream.mergeOlderMessages
import com.denova.novara.feature.agent.AgentCommands
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AGT-010 历史分页：分页元信息解析与「按 id 去重后前置」的合并口径。 */
class AgentHistoryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun message(id: String) = AgentMessage(id = id, role = "assistant")

    @Test
    fun `paged response carries cursor and has_more`() {
        val raw = """
            {"messages":[{"id":"m3","role":"assistant","parts":[]}],
             "page":{"next_before":"12","has_more":true,"total":40}}
        """.trimIndent()
        val page = json.decodeFromString(AgentMessagesPage.serializer(), raw)
        assertEquals(1, page.messages.size)
        assertEquals("12", page.page?.nextBefore)
        assertEquals(true, page.page?.hasMore)
        assertEquals(40, page.page?.total)
    }

    @Test
    fun `response without page meta stays usable`() {
        // 老服务端 / 未带 limit 的口径：没有 page 字段时不能因为缺省而崩。
        val page = json.decodeFromString(
            AgentMessagesPage.serializer(),
            """{"messages":[{"id":"m1","role":"user","parts":[]}]}""",
        )
        assertNull(page.page)
        assertEquals(1, page.messages.size)
    }

    @Test
    fun `older page is prepended in order`() {
        val current = listOf(message("m3"), message("m4"))
        val older = listOf(message("m1"), message("m2"))
        val merged = mergeOlderMessages(current, older)
        assertEquals(listOf("m1", "m2", "m3", "m4"), merged.map { it.id })
    }

    @Test
    fun `overlapping boundary message is not duplicated`() {
        // 翻页期间有新消息写入时，边界页可能给出已有消息：合并后必须只有一份。
        val current = listOf(message("m3"), message("m4"))
        val older = listOf(message("m2"), message("m3"))
        val merged = mergeOlderMessages(current, older)
        assertEquals(listOf("m2", "m3", "m4"), merged.map { it.id })
    }

    @Test
    fun `identical page adds nothing`() {
        val current = listOf(message("m1"), message("m2"))
        assertEquals(current, mergeOlderMessages(current, current))
        assertEquals(current, mergeOlderMessages(current, emptyList()))
    }

    @Test
    fun `messages without id are dropped instead of colliding`() {
        // 没有 id 的消息无法建立稳定身份，宁可丢弃也不让 LazyColumn 出现重复 key。
        val current = listOf(message("m2"))
        val merged = mergeOlderMessages(current, listOf(AgentMessage(id = ""), message("m1")))
        assertEquals(listOf("m1", "m2"), merged.map { it.id })
    }

    @Test
    fun `returned to editor cancel keeps the deduped reason`() {
        val request = AgentCommands.cancelQueued(
            commandId = "cmd-9",
            sessionId = "default",
            operationId = "op-42",
            targetCommandId = "cmd-1",
            reason = AgentCommands.CANCEL_REASON_EDITOR,
        )
        assertEquals("cancel_queued", request.type)
        assertEquals("returned_to_editor", request.reason)
        assertEquals("cmd-1", request.targetCommandId)
        assertNull(request.input)
    }

    @Test
    fun `message identity survives a prepended page`() {
        // AGT-010：更早的一页插到前面后，已有消息的 id 必须不变，否则锚点定位与 LazyColumn key 都会失效。
        fun textMessage(id: String, text: String) = AgentMessage(
            id = id,
            role = "assistant",
            parts = listOf(
                kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"type":"text","text":"$text"}""",
                ) as kotlinx.serialization.json.JsonObject,
            ),
        )
        val first = textMessage("m2", "second")
        val second = textMessage("m3", "third")
        val before = AgentPartLogic.parseMessages(listOf(first, second)).map { it.id }
        val after = AgentPartLogic.parseMessages(
            listOf(textMessage("m1", "first"), first, second),
        ).map { it.id }
        assertEquals(before, after.takeLast(2))
        assertEquals(3, after.distinct().size)
    }

    @Test
    fun `truncated queue item is parsed`() {
        val raw = """
            {"active":true,"phase":"running","active_operation_id":"op-42",
             "queue":[{"command_id":"cmd-1","operation_id":"op-42","delivery":"follow_up",
                       "message":"很长的一段…","message_truncated":true,"steer_requested":false}]}
        """.trimIndent()
        val view = json.decodeFromString(com.denova.novara.core.stream.AgentActiveView.serializer(), raw)
        assertTrue(view.queue.single().messageTruncated)
        assertFalse(view.queue.single().steerRequested)
    }
}
