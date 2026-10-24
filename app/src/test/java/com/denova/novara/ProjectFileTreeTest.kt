package com.denova.novara

import com.denova.novara.core.network.ProjectDirectoryPage
import com.denova.novara.core.network.ProjectFileEntry
import com.denova.novara.core.project.ProjectFileTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFileTreeTest {

    private val directories = mapOf(
        "" to ProjectDirectoryPage(
            path = "",
            revision = "r0",
            entries = listOf(
                ProjectFileEntry("chapters", "chapters", "dir"),
                ProjectFileEntry("ideas.md", "ideas.md", "file"),
            ),
        ),
        "chapters" to ProjectDirectoryPage(
            path = "chapters",
            revision = "r1",
            entries = listOf(
                ProjectFileEntry("ch1.md", "chapters/ch1.md", "file"),
                ProjectFileEntry("ch2.md", "chapters/ch2.md", "file"),
            ),
        ),
    )

    @Test
    fun `build preserves server order and nests directory children`() {
        val nodes = ProjectFileTree.build(directories)
        assertEquals(listOf("chapters", "ideas.md"), nodes.map { it.path })
        val chapters = nodes.first { it.path == "chapters" }
        assertTrue(chapters.isDir)
        assertEquals(listOf("chapters/ch1.md", "chapters/ch2.md"), chapters.children.map { it.path })
        val ideas = nodes.first { it.path == "ideas.md" }
        assertTrue(!ideas.isDir)
        assertEquals(emptyList<String>(), ideas.children.map { it.path })
    }

    @Test
    fun `build returns empty when root directory missing`() {
        assertEquals(emptyList<Any>(), ProjectFileTree.build(emptyMap()))
    }

    @Test
    fun `visible rows collapse directories by default`() {
        val nodes = ProjectFileTree.build(directories)
        val rows = ProjectFileTree.visibleRows(nodes, emptySet())
        assertEquals(listOf("chapters", "ideas.md"), rows.map { it.node.path })
        assertEquals(listOf(0, 0), rows.map { it.depth })
        assertTrue(!rows.first().expanded)
    }

    @Test
    fun `visible rows expand directory children when expanded`() {
        val nodes = ProjectFileTree.build(directories)
        val rows = ProjectFileTree.visibleRows(nodes, setOf("chapters"))
        assertEquals(
            listOf("chapters", "chapters/ch1.md", "chapters/ch2.md", "ideas.md"),
            rows.map { it.node.path },
        )
        assertEquals(listOf(0, 1, 1, 0), rows.map { it.depth })
        assertTrue(rows.first().expanded)
    }

    @Test
    fun `directory paths collects every dir`() {
        val nodes = ProjectFileTree.build(directories)
        assertEquals(setOf("chapters"), ProjectFileTree.directoryPaths(nodes))
    }
}
