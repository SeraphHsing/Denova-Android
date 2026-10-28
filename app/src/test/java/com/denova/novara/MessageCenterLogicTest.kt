package com.denova.novara

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.messages.MessageCenterLogic
import com.denova.novara.core.messages.MessageFilter
import com.denova.novara.core.network.ProductMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息中心的过滤 / 已读边界（MSG-001…003）。
 *
 * 关键口径：打开列表不置已读、进详情才写 `read`、窄屏不预选、未知类型原样显示。
 */
class MessageCenterLogicTest {

    private fun message(
        id: String = "m1",
        type: String = "automation",
        readAt: String = "",
        actionRequired: Boolean = false,
        publishedAt: String = "2026-10-29T10:00:00Z",
        taskId: String = "",
        projectId: String = "",
    ) = ProductMessage(
        id = id,
        type = type,
        title = "title",
        body = "body",
        readAt = readAt,
        actionRequired = actionRequired,
        publishedAt = publishedAt,
        taskId = taskId,
        projectId = projectId,
    )

    @Test
    fun `filters split action automation and product messages`() {
        val items = listOf(
            message(id = "run", type = "automation"),
            message(id = "todo", type = "automation_action", actionRequired = true),
            message(id = "news", type = "changelog"),
        )
        assertEquals(3, MessageCenterLogic.filtered(items, MessageFilter.ALL).size)
        assertEquals(listOf("todo"), MessageCenterLogic.filtered(items, MessageFilter.ACTION).map { it.id })
        assertEquals(
            listOf("run", "todo"),
            MessageCenterLogic.filtered(items, MessageFilter.AUTOMATION).map { it.id }.sorted(),
        )
        assertEquals(listOf("news"), MessageCenterLogic.filtered(items, MessageFilter.PRODUCT).map { it.id })
    }

    @Test
    fun `unknown type is shown verbatim and only matches all`() {
        val unknown = message(type = "experimental")
        assertEquals(UiText.raw("experimental"), MessageCenterLogic.typeLabel(unknown))
        assertTrue(MessageCenterLogic.matches(MessageFilter.ALL, unknown))
        assertFalse(MessageCenterLogic.matches(MessageFilter.AUTOMATION, unknown))
        assertEquals(UiText.of(R.string.messages_type_action), MessageCenterLogic.typeLabel(message(type = "automation_action")))
        assertEquals(UiText.of(R.string.messages_type_update), MessageCenterLogic.typeLabel(message(type = "changelog")))
    }

    @Test
    fun `list is sorted by published time descending`() {
        val older = message(id = "old", publishedAt = "2026-10-01T00:00:00Z")
        val newer = message(id = "new", publishedAt = "2026-10-29T00:00:00Z")
        assertEquals(listOf("new", "old"), MessageCenterLogic.sorted(listOf(older, newer)).map { it.id })
    }

    @Test
    fun `opening a read message does not trigger another read`() {
        assertTrue(MessageCenterLogic.shouldMarkRead(message()))
        assertFalse(MessageCenterLogic.shouldMarkRead(message(readAt = "2026-10-29T10:00:00Z")))
        assertFalse(MessageCenterLogic.shouldMarkRead(null))
    }

    @Test
    fun `applying read only touches the target message and lowers unread`() {
        val items = listOf(message(id = "a"), message(id = "b"))
        val updated = MessageCenterLogic.applyRead(items, "a", "2026-10-29T11:00:00Z")
        assertFalse(updated.first { it.id == "a" }.unread)
        assertTrue(updated.first { it.id == "b" }.unread)
        assertEquals(1, MessageCenterLogic.unreadCount(updated))
    }

    @Test
    fun `mark all read clears every unread message`() {
        val items = listOf(message(id = "a"), message(id = "b", readAt = "2026-10-01T00:00:00Z"))
        val updated = MessageCenterLogic.markAllRead(items, "2026-10-29T11:00:00Z")
        assertEquals(0, MessageCenterLogic.unreadCount(updated))
        assertEquals("2026-10-01T00:00:00Z", updated.first { it.id == "b" }.readAt)
    }

    @Test
    fun `narrow layout preselects nothing but wide layout picks first unread`() {
        val items = listOf(
            message(id = "read", readAt = "2026-10-29T10:00:00Z"),
            message(id = "unread"),
        )
        assertEquals("", MessageCenterLogic.firstSelection(items, wideLayout = false))
        assertEquals("unread", MessageCenterLogic.firstSelection(items, wideLayout = true))
        assertEquals("read", MessageCenterLogic.firstSelection(listOf(items.first()), wideLayout = true))
    }

    @Test
    fun `jump target only appears when the message carries a task id`() {
        assertTrue(message(taskId = "auto-1", projectId = "project-1").canOpenAutomation)
        assertFalse(message(taskId = "").canOpenAutomation)
    }

    @Test
    fun `body text unescapes entities and keeps markdown content`() {
        val message = message().copy(
            body = "### 简要说明\n\n- 第一条\n- 第二条",
        )
        val text = MessageCenterLogic.bodyText(message)
        assertTrue(text.contains("简要说明"))
        assertTrue(text.contains("第一条"))
        val escaped = message.copy(body = "第一行&#10;&#10;第二行")
        assertTrue(MessageCenterLogic.bodyText(escaped).contains("第二行"))
    }

    @Test
    fun `index lookup supports restoring the selected row after back`() {
        val items = listOf(message(id = "a"), message(id = "b"), message(id = "c"))
        assertEquals(1, MessageCenterLogic.indexOf(items, "b"))
        assertEquals(-1, MessageCenterLogic.indexOf(items, "missing"))
    }
}
