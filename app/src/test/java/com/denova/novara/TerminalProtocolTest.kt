package com.denova.novara

import com.denova.novara.core.terminal.TerminalSessionsPage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机联调暴露的契约回归：`GET /api/terminal/sessions` 的 `commands` 是对象数组。
 *
 * 这段 JSON 来自 0.6.3 @ 85ec97c 的实测响应。此前按 `List<String>` 声明，
 * 解析直接抛异常，整个「列出会话 → 复用/新建」路径在客户端就断了（服务端完全正常）。
 */
class TerminalProtocolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `sessions page decodes command objects from the live server`() {
        val body = """
            {"commands":[{"id":"codex","name":"Codex CLI"},{"id":"claude","name":"Claude Code"}],
             "default_cwd":"/data/.denova/projects/我的异世界生活","enabled":true,"max_sessions":8,
             "scrollback_kb":256,"sessions":[],"shell":"/bin/bash"}
        """.trimIndent()

        val page = json.decodeFromString(TerminalSessionsPage.serializer(), body)

        assertTrue(page.enabled)
        assertEquals(2, page.commands.size)
        assertEquals("codex", page.commands[0].id)
        assertEquals("Codex CLI", page.commands[0].name)
        assertEquals(8, page.maxSessions)
        assertEquals("/bin/bash", page.shell)
        assertTrue(page.sessions.isEmpty())
    }

    @Test
    fun `session entry keeps attach token and project scope`() {
        val body = """
            {"enabled":true,"shell":"/bin/bash","commands":[],
             "sessions":[{"id":"term-1","owner_tab_id":"android-main","profile_id":"shell",
                          "project_id":"project-abc","cols":80,"rows":24,"attached":0,
                          "exited":false,"token":"tok-123"}]}
        """.trimIndent()

        val page = json.decodeFromString(TerminalSessionsPage.serializer(), body)
        val session = page.sessions.single()

        assertEquals("project-abc", session.projectId)
        assertEquals("android-main", session.ownerTabId)
        assertEquals("tok-123", session.token)
        assertEquals(80, session.cols)
    }
}
