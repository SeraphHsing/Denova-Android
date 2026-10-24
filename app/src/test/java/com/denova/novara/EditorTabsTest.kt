package com.denova.novara

import com.denova.novara.feature.editor.EditorTabs
import com.denova.novara.feature.editor.EditorTabsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WRI-003 标签栏纯逻辑：打开去重、关闭后的相邻激活、重排、显示名。 */
class EditorTabsTest {

    @Test
    fun `open appends new path once`() {
        assertEquals(listOf("a.md", "b.md"), EditorTabs.open(listOf("a.md"), "b.md"))
    }

    @Test
    fun `open keeps existing position`() {
        val tabs = listOf("a.md", "b.md", "c.md")
        assertEquals(tabs, EditorTabs.open(tabs, "a.md"))
    }

    @Test
    fun `open ignores blank path`() {
        val tabs = listOf("a.md")
        assertEquals(tabs, EditorTabs.open(tabs, "  "))
    }

    @Test
    fun `close activates right neighbour first`() {
        val tabs = listOf("a.md", "b.md", "c.md")
        assertEquals("c.md", EditorTabs.nextActiveAfterClose(tabs, "b.md"))
    }

    @Test
    fun `close last tab falls back to left neighbour`() {
        val tabs = listOf("a.md", "b.md", "c.md")
        assertEquals("b.md", EditorTabs.nextActiveAfterClose(tabs, "c.md"))
    }

    @Test
    fun `close only tab activates nothing`() {
        assertNull(EditorTabs.nextActiveAfterClose(listOf("a.md"), "a.md"))
    }

    @Test
    fun `close unknown path activates first remaining tab`() {
        assertEquals("a.md", EditorTabs.nextActiveAfterClose(listOf("a.md", "b.md"), "ghost.md"))
    }

    @Test
    fun `move shifts tab to target index`() {
        val tabs = listOf("a.md", "b.md", "c.md")
        assertEquals(listOf("b.md", "a.md", "c.md"), EditorTabs.move(tabs, 1, 0))
        assertEquals(listOf("b.md", "c.md", "a.md"), EditorTabs.move(tabs, 0, 2))
    }

    @Test
    fun `move with same or out of range index keeps list`() {
        val tabs = listOf("a.md", "b.md")
        assertEquals(tabs, EditorTabs.move(tabs, 0, 0))
        assertEquals(tabs, EditorTabs.move(tabs, 0, 5))
        assertEquals(tabs, EditorTabs.move(tabs, -1, 1))
    }

    @Test
    fun `label uses last segment`() {
        assertEquals("001.md", EditorTabs.label("chapters/001.md"))
        assertEquals("a.md", EditorTabs.label("a.md"))
    }

    @Test
    fun `state reports active tab`() {
        val state = EditorTabsState(
            projectId = "p",
            paths = listOf("a.md", "b.md"),
            activePath = "b.md",
        )
        assertEquals(1, state.activeIndex)
        assertTrue(state.isActive("b.md"))
        assertFalse(state.isActive("a.md"))
        assertTrue(EditorTabsState().isEmpty)
    }
}
