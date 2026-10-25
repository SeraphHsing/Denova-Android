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

    // ---- WRI-002 懒加载分页 ----

    private val partial = directories + (
        "chapters" to ProjectDirectoryPage(
            path = "chapters",
            revision = "r1",
            entries = listOf(ProjectFileEntry("ch1.md", "chapters/ch1.md", "file")),
            childrenState = "partial",
            continuation = "cursor-1",
        )
        )

    @Test
    fun `pending more lists only partial pages with a cursor`() {
        assertEquals(setOf("chapters"), ProjectFileTree.pendingMore(partial))
        // complete 页即便带了残留 cursor 也不算「还有下一页」
        val completeWithCursor = directories + (
            "chapters" to ProjectDirectoryPage("chapters", "r1", emptyList(), "complete", "stale")
            )
        assertEquals(emptySet<String>(), ProjectFileTree.pendingMore(completeWithCursor))
    }

    @Test
    fun `merge appends the next page when the revision is unchanged`() {
        val next = ProjectDirectoryPage(
            path = "chapters",
            revision = "r1",
            entries = listOf(
                ProjectFileEntry("ch2.md", "chapters/ch2.md", "file"),
                ProjectFileEntry("ch3.md", "chapters/ch3.md", "file"),
            ),
            childrenState = "complete",
        )
        val merged = ProjectFileTree.merge(partial, listOf(next), appendPath = "chapters")
        val page = merged.getValue("chapters")
        assertEquals(listOf("chapters/ch1.md", "chapters/ch2.md", "chapters/ch3.md"), page.entries.map { it.path })
        assertTrue(!page.hasMore)
        assertEquals("complete", page.childrenState)
    }

    @Test
    fun `merge de-duplicates entries repeated across pages`() {
        val next = ProjectDirectoryPage(
            path = "chapters",
            revision = "r1",
            entries = listOf(ProjectFileEntry("ch1.md", "chapters/ch1.md", "file")),
            childrenState = "complete",
        )
        val merged = ProjectFileTree.merge(partial, listOf(next), appendPath = "chapters")
        assertEquals(listOf("chapters/ch1.md"), merged.getValue("chapters").entries.map { it.path })
    }

    @Test
    fun `merge replaces the page when the revision changed`() {
        val next = ProjectDirectoryPage(
            path = "chapters",
            revision = "r2",
            entries = listOf(ProjectFileEntry("ch9.md", "chapters/ch9.md", "file")),
            childrenState = "complete",
        )
        // 游标只在 revision 未变时有效：变了就整页替换，不能把旧页追加成幽灵文件。
        val merged = ProjectFileTree.merge(partial, listOf(next), appendPath = "chapters")
        assertEquals(listOf("chapters/ch9.md"), merged.getValue("chapters").entries.map { it.path })
        assertEquals("r2", merged.getValue("chapters").revision)
    }

    @Test
    fun `merge without appendPath keeps other directories untouched`() {
        val refreshed = ProjectDirectoryPage(
            path = "",
            revision = "r9",
            entries = listOf(ProjectFileEntry("ideas.md", "ideas.md", "file")),
        )
        val merged = ProjectFileTree.merge(directories, listOf(refreshed))
        assertEquals("r9", merged.getValue("").revision)
        assertEquals("r1", merged.getValue("chapters").revision)
        assertEquals(2, merged.getValue("chapters").entries.size)
    }

    @Test
    fun `visible rows insert a load more row after the expanded children`() {
        val nodes = ProjectFileTree.build(partial)
        val rows = ProjectFileTree.visibleRows(nodes, setOf("chapters"), ProjectFileTree.pendingMore(partial))
        assertEquals(
            listOf("chapters", "chapters/ch1.md", "chapters" + ProjectFileTree.LOAD_MORE_SUFFIX, "ideas.md"),
            rows.map { it.node.path },
        )
        assertEquals(listOf(0, 1, 1, 0), rows.map { it.depth })
        assertEquals(listOf(false, false, true, false), rows.map { it.loadMore })
    }

    @Test
    fun `collapsed partial directory shows no load more row`() {
        val nodes = ProjectFileTree.build(partial)
        val rows = ProjectFileTree.visibleRows(nodes, emptySet(), ProjectFileTree.pendingMore(partial))
        assertEquals(listOf("chapters", "ideas.md"), rows.map { it.node.path })
        assertTrue(rows.none { it.loadMore })
    }

    @Test
    fun `partial root appends a load more row at tree tail`() {
        val rootPartial = partial + (
            "" to ProjectDirectoryPage(
                path = "",
                revision = "r0",
                entries = directories.getValue("").entries,
                childrenState = "partial",
                continuation = "cursor-root",
            )
            )
        val nodes = ProjectFileTree.build(rootPartial)
        val rows = ProjectFileTree.visibleRows(nodes, setOf("chapters"), ProjectFileTree.pendingMore(rootPartial))
        assertEquals(
            listOf("chapters", "chapters/ch1.md", "chapters" + ProjectFileTree.LOAD_MORE_SUFFIX, "ideas.md", ProjectFileTree.LOAD_MORE_SUFFIX),
            rows.map { it.node.path },
        )
        assertEquals(0, rows.last().depth)
        assertTrue(rows.last().loadMore)
    }
}
