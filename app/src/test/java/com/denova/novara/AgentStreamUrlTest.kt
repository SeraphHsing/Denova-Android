package com.denova.novara

import com.denova.novara.core.network.writingAgentStreamUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentStreamUrlTest {

    @Test
    fun `stream url carries session task and cursor`() {
        // AGT-009：服务端 HandleChatStream 先要 session_id，再按 task_id 精确匹配活跃任务。
        // 少任何一个都是 400，所以重挂地址必须三者齐全。
        val url = writingAgentStreamUrl("http://192.168.2.102:18081", "default", "task-1", "131")

        assertEquals("/api/chat/stream", url.encodedPath)
        assertEquals("default", url.queryParameter("session_id"))
        assertEquals("task-1", url.queryParameter("task_id"))
        assertEquals("131", url.queryParameter("after"))
    }

    @Test
    fun `stream url omits cursor when unknown`() {
        val url = writingAgentStreamUrl("http://host:18081", "s1", "task-1")

        assertEquals(null, url.queryParameter("after"))
        assertEquals("s1", url.queryParameter("session_id"))
    }
}
