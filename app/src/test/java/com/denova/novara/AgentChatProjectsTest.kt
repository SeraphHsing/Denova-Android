package com.denova.novara

import com.denova.novara.core.network.AgentChatHistoryPage
import com.denova.novara.core.network.AgentChatProjectsResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作台项目 / 会话 / 历史 DTO 的线上字段解析（P4-B）。
 *
 * payload 取自后端 `internal/app/agentchat/contracts.go` 的 `Project` / `Session` /
 * `HistoryItem` 序列化形状（含 `error` 缺省与 `excerpt` 可选）。
 */
class AgentChatProjectsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `projects payload keeps every summary field`() {
        val payload = """
            {"projects":[{
              "id":"project-efb2","type":"book","path":"/data/.denova/projects/1","name":"灵渊劫-问道",
              "status":"available","current":true,"total":3,
              "sessions":[
                {"id":"default","title":"默认会话","created_at":"2026-10-26T12:00:00Z","updated_at":"2026-10-27T08:30:00Z","message_count":48,"active":false,"running":true},
                {"id":"s-agent-1","title":"","created_at":"2026-10-26T13:00:00Z","updated_at":"2026-10-26T13:00:00Z","message_count":0,"active":false,"running":false}
              ]
            },{
              "id":"project-world","type":"general","path":"/data/.denova/projects/world","name":"world",
              "status":"missing","current":false,"total":0,"sessions":[],"error":"目录不存在"
            }]}
        """.trimIndent()

        val projects = json.decodeFromString(AgentChatProjectsResponse.serializer(), payload).projects

        assertEquals(2, projects.size)
        val book = projects.first()
        assertEquals("book", book.type)
        assertTrue(book.current)
        assertEquals(2, book.sessions.size)
        assertEquals(48, book.sessions.first().messageCount)
        assertTrue(book.sessions.first().running)
        assertEquals("", book.sessions[1].title)
        val broken = projects[1]
        assertEquals("missing", broken.status)
        assertEquals("目录不存在", broken.error)
        assertNull(book.error)
    }

    @Test
    fun `history page reports paging and optional excerpt`() {
        val payload = """
            {"items":[
              {"project_id":"project-efb2","project_name":"灵渊劫-问道",
               "session":{"id":"default","title":"默认会话","created_at":"","updated_at":"","message_count":4,"active":false,"running":false},
               "excerpt":"…雨落在石头上…"}
            ],"total":41,"offset":0,"has_more":true}
        """.trimIndent()

        val page = json.decodeFromString(AgentChatHistoryPage.serializer(), payload)

        assertEquals(41, page.total)
        assertEquals(0, page.offset)
        assertTrue(page.hasMore)
        assertEquals("…雨落在石头上…", page.items.single().excerpt)
        assertEquals("默认会话", page.items.single().session.title)
    }

    @Test
    fun `empty history page decodes without items`() {
        val page = json.decodeFromString(AgentChatHistoryPage.serializer(), """{"items":[],"total":0,"offset":0,"has_more":false}""")

        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
    }
}
