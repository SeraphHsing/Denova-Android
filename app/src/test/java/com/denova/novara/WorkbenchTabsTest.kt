package com.denova.novara

import com.denova.novara.core.workbench.WorkbenchGroup
import com.denova.novara.core.workbench.WorkbenchTab
import com.denova.novara.core.workbench.WorkbenchTabKind
import com.denova.novara.core.workbench.WorkbenchTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工作台页签纯逻辑（P4-B / WB-002 / WB-003）。 */
class WorkbenchTabsTest {

    private fun agent(projectId: String, sessionId: String, group: WorkbenchGroup = WorkbenchGroup.PRIMARY) =
        WorkbenchTab(
            id = "agent:$projectId:$sessionId",
            projectId = projectId,
            group = group,
            kind = WorkbenchTabKind.Agent(sessionId),
        )

    private fun files(projectId: String, group: WorkbenchGroup = WorkbenchGroup.PRIMARY, path: String = "") =
        WorkbenchTab(
            id = "files:$projectId",
            projectId = projectId,
            group = group,
            kind = WorkbenchTabKind.Files(path),
        )

    @Test
    fun `opening the same conversation twice keeps one tab`() {
        val tabs = WorkbenchTabs.open(emptyList(), agent("p1", "s1"))
        val again = WorkbenchTabs.open(tabs, agent("p1", "s1"))

        assertEquals(1, again.size)
        assertEquals("agent:p1:s1", again.single().id)
    }

    @Test
    fun `same session in another project is a separate tab`() {
        val tabs = WorkbenchTabs.open(WorkbenchTabs.open(emptyList(), agent("p1", "s1")), agent("p2", "s1"))

        assertEquals(2, tabs.size)
        assertEquals(listOf("p1", "p2"), tabs.map { it.projectId })
    }

    @Test
    fun `files tab is unique per project but distinct across projects`() {
        val tabs = WorkbenchTabs.open(WorkbenchTabs.open(emptyList(), files("p1")), files("p1"))
        assertEquals(1, tabs.size)

        val both = WorkbenchTabs.open(tabs, files("p2"))
        assertEquals(2, both.size)
    }

    @Test
    fun `opening an existing tab in the other group moves it`() {
        val tabs = WorkbenchTabs.open(
            WorkbenchTabs.open(emptyList(), agent("p1", "s1")),
            agent("p1", "s2"),
        )
        val moved = WorkbenchTabs.open(tabs, agent("p1", "s1", WorkbenchGroup.SECONDARY))

        assertEquals(2, moved.size)
        assertEquals(WorkbenchGroup.SECONDARY, moved.single { it.id == "agent:p1:s1" }.group)
    }

    @Test
    fun `closing activates the right neighbour then the left`() {
        val tabs = listOf(agent("p1", "a"), agent("p1", "b"), agent("p1", "c"))

        assertEquals("agent:p1:c", WorkbenchTabs.activeAfterClose(tabs, "agent:p1:b"))
        assertEquals("agent:p1:b", WorkbenchTabs.activeAfterClose(tabs, "agent:p1:c"))
    }

    @Test
    fun `closing the last tab of a group yields no active tab`() {
        val tabs = listOf(agent("p1", "a"))

        assertNull(WorkbenchTabs.activeAfterClose(tabs, "agent:p1:a"))
        assertTrue(WorkbenchTabs.close(tabs, "agent:p1:a").isEmpty())
    }

    @Test
    fun `close only removes the target tab`() {
        val tabs = listOf(agent("p1", "a"), agent("p1", "b"))

        assertEquals(listOf("agent:p1:b"), WorkbenchTabs.close(tabs, "agent:p1:a").map { it.id })
    }

    @Test
    fun `move reorders inside a group without touching the other group`() {
        val tabs = listOf(
            agent("p1", "a"),
            agent("p1", "b", WorkbenchGroup.SECONDARY),
            agent("p1", "c"),
        )
        val moved = WorkbenchTabs.move(tabs, "agent:p1:c", -1)

        assertEquals(
            listOf("agent:p1:c", "agent:p1:b", "agent:p1:a"),
            moved.map { it.id },
        )
        assertEquals(WorkbenchGroup.SECONDARY, moved[1].group)
    }

    @Test
    fun `move clamps at the group edge`() {
        val tabs = listOf(agent("p1", "a"), agent("p1", "b"))

        assertEquals(listOf("agent:p1:a", "agent:p1:b"), WorkbenchTabs.move(tabs, "agent:p1:a", -1).map { it.id })
        assertEquals(listOf("agent:p1:a", "agent:p1:b"), WorkbenchTabs.move(tabs, "agent:p1:b", 1).map { it.id })
    }

    @Test
    fun `closing a project removes all of its tabs`() {
        val tabs = listOf(agent("p1", "a"), agent("p2", "b"), files("p1"))

        assertEquals(listOf("agent:p2:b"), WorkbenchTabs.closeProject(tabs, "p1").map { it.id })
    }

    @Test
    fun `title prefers custom then session title`() {
        val tab = agent("p1", "s1")

        assertEquals("服务端标题", WorkbenchTabs.titleOf(tab, mapOf("s1" to "服务端标题")))
        assertEquals("自定义", WorkbenchTabs.titleOf(tab.copy(customTitle = "自定义"), mapOf("s1" to "服务端标题")))
        assertEquals("", WorkbenchTabs.titleOf(tab, emptyMap()))
    }

    // ---- 终端页签（P4-B / WB-007 / WB-008）----

    private fun terminal(projectId: String, tabId: String, group: WorkbenchGroup = WorkbenchGroup.PRIMARY) =
        WorkbenchTab(
            id = tabId,
            projectId = projectId,
            group = group,
            kind = WorkbenchTabKind.Terminal(profileId = "shell", label = "终端"),
        )

    @Test
    fun `two terminals in the same project stay separate tabs`() {
        val tabs = WorkbenchTabs.open(
            WorkbenchTabs.open(emptyList(), terminal("p1", "term:a")),
            terminal("p1", "term:b"),
        )

        // 每个终端页签是独立 owner（独立 PTY），不能像会话那样按项目去重。
        assertEquals(listOf("term:a", "term:b"), tabs.map { it.id })
    }

    @Test
    fun `terminal tab title falls back to its label`() {
        val tab = terminal("p1", "term:a")

        assertEquals("终端", WorkbenchTabs.titleOf(tab, emptyMap()))
        assertEquals("自定义", WorkbenchTabs.titleOf(tab.copy(customTitle = "自定义"), emptyMap()))
    }

    @Test
    fun `closing a terminal tab activates its neighbour`() {
        val tabs = listOf(agent("p1", "s1"), terminal("p1", "term:a"), agent("p1", "s2"))

        assertEquals("agent:p1:s2", WorkbenchTabs.activeAfterClose(tabs, "term:a"))
    }

    // ---- 文件页签（P4-B / WB-004 工作台宿主形态）----

    @Test
    fun `files tab is unique per project and keeps the selected file`() {
        val first = WorkbenchTabs.open(emptyList(), files("p1", path = "a.md"))
        val reopened = WorkbenchTabs.open(first, files("p1", path = "a.md"))

        assertEquals(1, reopened.size)
        // 同一项目再次打开只切换选中文件，不新建页签。
        val switched = WorkbenchTabs.withSelectedPath(reopened, "files:p1", "ch/01.md")
        assertEquals("ch/01.md", (switched.single().kind as WorkbenchTabKind.Files).selectedPath)
        assertEquals("01.md", WorkbenchTabs.titleOf(switched.single(), emptyMap()))
    }

    @Test
    fun `files tab in another project is a separate tab`() {
        val tabs = WorkbenchTabs.open(WorkbenchTabs.open(emptyList(), files("p1")), files("p2"))

        assertEquals(listOf("files:p1", "files:p2"), tabs.map { it.id })
    }
}
