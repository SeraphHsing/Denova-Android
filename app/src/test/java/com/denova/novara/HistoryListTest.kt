package com.denova.novara

import com.denova.novara.core.network.AgentChatHistoryItem
import com.denova.novara.core.network.AgentChatHistoryPage
import com.denova.novara.core.network.AgentChatSession
import com.denova.novara.core.workbench.HistoryList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工作台历史分页与导出命名（P4-B / WB-006）。 */
class HistoryListTest {

    private fun item(projectId: String, sessionId: String, excerpt: String = "") = AgentChatHistoryItem(
        projectId = projectId,
        projectName = "项目 $projectId",
        session = AgentChatSession(id = sessionId, title = "会话 $sessionId"),
        excerpt = excerpt,
    )

    @Test
    fun `appending a page keeps server order and drops duplicates`() {
        val first = HistoryList.replace(
            AgentChatHistoryPage(items = listOf(item("p1", "a"), item("p1", "b")), total = 5, hasMore = true),
        )
        // 第二页与第一页重叠了一条（翻页期间有新消息插入），不能出现两个 b。
        val merged = HistoryList.append(
            first,
            AgentChatHistoryPage(items = listOf(item("p1", "b"), item("p1", "c")), total = 5, hasMore = false),
        )

        assertEquals(listOf("a", "b", "c"), merged.items.map { it.session.id })
        assertEquals(5, merged.total)
        assertFalse(merged.hasMore)
        assertEquals(3, merged.offset)
    }

    @Test
    fun `same session id in different projects is kept apart`() {
        val page = HistoryList.replace(
            AgentChatHistoryPage(items = listOf(item("p1", "s"), item("p2", "s")), total = 2, hasMore = false),
        )

        assertEquals(2, page.items.size)
    }

    @Test
    fun `query is trimmed and capped at the server limit`() {
        assertEquals("abc", HistoryList.normalizeQuery("  abc  "))
        assertEquals(HistoryList.QUERY_MAX_LENGTH, HistoryList.normalizeQuery("x".repeat(500)).length)
    }

    @Test
    fun `export file name strips illegal characters and keeps a suffix`() {
        assertEquals("第一章 开端-abc12345.md", HistoryList.exportFileName("第一章/开端", "abc12345-6789"))
        // 标题全被过滤时退回会话 id，不能生成 ".md"
        assertEquals("abc12345.md", HistoryList.exportFileName("///", "abc12345-6789"))
        assertEquals("session.md", HistoryList.exportFileName("", ""))
    }

    @Test
    fun `highlight finds every case insensitive occurrence`() {
        val ranges = HistoryList.highlightRanges("Denova denova DENOVA", "denova")

        assertEquals(3, ranges.size)
        assertEquals(0 until 6, ranges[0])
        assertEquals(7 until 13, ranges[1])
        assertEquals(14 until 20, ranges[2])
        assertTrue(HistoryList.highlightRanges("abc", "").isEmpty())
        assertTrue(HistoryList.highlightRanges("abc", "zz").isEmpty())
    }
}
