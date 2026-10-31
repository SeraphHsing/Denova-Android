package com.denova.novara

import com.denova.novara.core.network.AgentRunIssue
import com.denova.novara.core.network.AgentRunSummary
import com.denova.novara.feature.trajectory.RunStatusTone
import com.denova.novara.feature.trajectory.agentOptions
import com.denova.novara.feature.trajectory.formatRunTimestamp
import com.denova.novara.feature.trajectory.groupRunsByProject
import com.denova.novara.feature.trajectory.runStatusTone
import com.denova.novara.feature.trajectory.sessionGroupKey
import com.denova.novara.feature.trajectory.visibleRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行目录（TRJ-001）：项目 → 会话分组、Agent 筛选、状态归一与时间格式。
 *
 * 分组规则对齐基线 `groupRunsByProject`：项目内按创建时间倒序，会话键是
 * `project_id + session_id`（无 session 归到 `no-session` 桶），会话标题取首个非空。
 */
class TrajectoryRunsTest {

    @Test
    fun `groups runs by project then session in reverse chronological order`() {
        val runs = listOf(
            run(id = "r1", project = "p1", projectName = "Alpha", session = "s1", createdAt = "2026-11-01T10:00:00Z"),
            run(id = "r2", project = "p1", projectName = "Alpha", session = "s1", createdAt = "2026-11-01T12:00:00Z", title = "第 2 章"),
            run(id = "r3", project = "p1", projectName = "Alpha", session = "", createdAt = "2026-11-01T09:00:00Z"),
            run(id = "r4", project = "p2", projectName = "Beta", session = "s9", createdAt = "2026-11-01T08:00:00Z"),
        )

        val groups = groupRunsByProject(runs)

        assertEquals(2, groups.size)
        assertEquals("Alpha", groups[0].name)
        assertEquals(3, groups[0].runCount)
        // 会话内倒序：r2 比 r1 新。
        val session = groups[0].sessions.first { it.sessionId == "s1" }
        assertEquals(listOf("r2", "r1"), session.runs.map { it.id })
        // 会话标题取首个非空（r2 带标题）。
        assertEquals("第 2 章", session.sessionTitle)
        // 无 session 的单独成桶。
        assertTrue(groups[0].sessions.any { it.sessionId.isEmpty() && it.runs.single().id == "r3" })
        assertEquals(1, groups[1].runCount)
    }

    @Test
    fun `session key separates projects with the same session id`() {
        val a = run(id = "r1", project = "p1", session = "default")
        val b = run(id = "r2", project = "p2", session = "default")
        val c = run(id = "r3", project = "p1", session = "")
        val d = run(id = "r4", project = "p1", session = "")

        assertTrue(sessionGroupKey(a) != sessionGroupKey(b))
        assertEquals(sessionGroupKey(c), sessionGroupKey(d))
        assertTrue(sessionGroupKey(c).endsWith("no-session"))
    }

    @Test
    fun `agent filter narrows the directory and options are unique sorted`() {
        val runs = listOf(
            run(id = "r1", agentKind = "writing"),
            run(id = "r2", agentKind = "interactive"),
            run(id = "r3", agentKind = "writing"),
            run(id = "r4", agentKind = ""),
        )

        assertEquals(listOf("interactive", "writing"), agentOptions(runs))
        assertEquals(listOf("r1", "r3"), visibleRuns(runs, "writing").map { it.id })
        // 空筛选表示「全部 Agents」。
        assertEquals(4, visibleRuns(runs, "").size)
        assertEquals(0, visibleRuns(runs, "missing").size)
    }

    @Test
    fun `status tone maps server statuses to the three badge tones`() {
        assertEquals(RunStatusTone.SUCCESS, runStatusTone("success"))
        assertEquals(RunStatusTone.SUCCESS, runStatusTone("COMPLETED"))
        assertEquals(RunStatusTone.RUNNING, runStatusTone("running"))
        assertEquals(RunStatusTone.FAILURE, runStatusTone("failed"))
        assertEquals(RunStatusTone.FAILURE, runStatusTone("aborted"))
        assertEquals(RunStatusTone.FAILURE, runStatusTone(""))
    }

    @Test
    fun `timestamp formatting is local and tolerant of bad input`() {
        val formatted = formatRunTimestamp("2026-11-01T10:00:00Z")
        // 只断言形状（MM-dd HH:mm）：具体值取决于设备时区，不能在单测里写死。
        assertTrue(Regex("""\d{2}-\d{2} \d{2}:\d{2}""").matches(formatted))
        assertEquals("", formatRunTimestamp(""))
        assertEquals("", formatRunTimestamp("not-a-date"))
    }

    @Test
    fun `partial project issues keep their own identity`() {
        val issue = AgentRunIssue(projectId = "p9", projectName = "Gamma", message = "unreadable")
        assertEquals("p9", issue.projectId)
        assertEquals("unreadable", issue.message)
    }

    private fun run(
        id: String,
        project: String = "p1",
        projectName: String = "Project",
        session: String = "s1",
        createdAt: String = "2026-11-01T10:00:00Z",
        agentKind: String = "writing",
        title: String = "",
    ): AgentRunSummary = AgentRunSummary(
        id = id,
        projectId = project,
        projectName = projectName,
        sessionId = session,
        sessionTitle = title,
        createdAt = createdAt,
        agentKind = agentKind,
    )
}
