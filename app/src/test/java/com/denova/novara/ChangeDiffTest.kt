package com.denova.novara

import com.denova.novara.core.network.WorkspaceChangeComment
import com.denova.novara.core.network.WorkspaceChangeGroup
import com.denova.novara.core.network.WorkspaceChangeSet
import com.denova.novara.feature.changes.ChangeDiff
import com.denova.novara.feature.changes.DiffKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WRI-016 审阅投影：diff 行、增删计数、详情契约解析。 */
class ChangeDiffTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `unchanged file yields context only`() {
        val text = "a\nb\nc\n"
        val lines = ChangeDiff.unified(text, text)
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.kind == DiffKind.CONTEXT })
        assertEquals(0, ChangeDiff.stats(lines).additions)
        assertEquals(0, ChangeDiff.stats(lines).deletions)
        assertEquals(1, lines.first().beforeLine)
        assertEquals(3, lines.last().afterLine)
    }

    @Test
    fun `inserted line is marked add with after line number`() {
        val lines = ChangeDiff.unified("a\nc\n", "a\nb\nc\n")
        val added = lines.filter { it.kind == DiffKind.ADD }
        assertEquals(listOf("b"), added.map { it.text })
        assertEquals(2, added.single().afterLine)
        assertEquals(null, added.single().beforeLine)
        assertEquals(1, ChangeDiff.stats(lines).additions)
        assertEquals(0, ChangeDiff.stats(lines).deletions)
    }

    @Test
    fun `removed line is marked remove with before line number`() {
        val lines = ChangeDiff.unified("a\nb\nc\n", "a\nc\n")
        val removed = lines.filter { it.kind == DiffKind.REMOVE }
        assertEquals(listOf("b"), removed.map { it.text })
        assertEquals(2, removed.single().beforeLine)
        assertEquals(1, ChangeDiff.stats(lines).deletions)
    }

    @Test
    fun `replaced line shows both sides`() {
        val lines = ChangeDiff.unified("a\nold\nc\n", "a\nnew\nc\n")
        assertEquals(listOf("old"), lines.filter { it.kind == DiffKind.REMOVE }.map { it.text })
        assertEquals(listOf("new"), lines.filter { it.kind == DiffKind.ADD }.map { it.text })
        val stats = ChangeDiff.stats(lines)
        assertEquals(1, stats.additions)
        assertEquals(1, stats.deletions)
    }

    @Test
    fun `new and deleted files are classified`() {
        val created = ChangeDiff.file(
            WorkspaceChangeSet(id = "cs1", path = "new.md", beforeExists = false, afterExists = true, afterContent = "x\ny\n"),
        )
        assertTrue(created.isNew)
        assertFalse(created.isDeleted)
        assertEquals(2, created.stats.additions)
        assertEquals(0, created.stats.deletions)

        val deleted = ChangeDiff.file(
            WorkspaceChangeSet(id = "cs2", path = "old.md", beforeExists = true, afterExists = false, beforeContent = "x\ny\n"),
        )
        assertTrue(deleted.isDeleted)
        assertFalse(deleted.isNew)
        assertEquals(2, deleted.stats.deletions)
    }

    @Test
    fun `long files are truncated instead of pretending to show everything`() {
        val before = (1..ChangeDiff.MAX_LINES + 50).joinToString("\n") { "line $it" }
        val after = before + "\nlast"
        val diff = ChangeDiff.file(
            WorkspaceChangeSet(id = "cs3", path = "big.md", beforeContent = before, afterContent = after),
        )
        assertTrue(diff.truncated)
        assertEquals(ChangeDiff.MAX_LINES, diff.lines.size)
    }

    @Test
    fun `splitLines ignores the trailing newline`() {
        assertEquals(emptyList<String>(), ChangeDiff.splitLines(""))
        assertEquals(listOf("a"), ChangeDiff.splitLines("a\n"))
        assertEquals(listOf("a", ""), ChangeDiff.splitLines("a\n\n"))
        assertEquals(listOf("a", "b"), ChangeDiff.splitLines("a\nb"))
    }

    @Test
    fun `group detail parses change sets and comments`() {
        val payload = """
            {"project_id":"p1","workspace":"/w","group":{
              "id":"g1","origin":"agent","review_thread_id":"t1","created_at":"2026-10-25T01:00:00Z",
              "review_status":"pending","apply_state":"applied","can_undo":true,"can_redo":false,
              "pending_edit_count":2,"comment_count":1,
              "change_sets":[
                {"id":"cs1","sequence":3,"group_id":"g1","path":"chapters/a.md",
                 "base_revision":"r0","revision":"r1","before_exists":true,"after_exists":true,
                 "before_content":"line1\nline2\n","after_content":"line1\nline2 edited\n",
                 "review_status":"pending","apply_state":"applied","created_at":"2026-10-25T01:00:00Z","origin":"agent"}
              ],
              "comments":[{"id":"c1","group_id":"g1","change_set_id":"cs1","body":"请保留原句","author":"Hsing",
                           "created_at":"2026-10-25T01:01:00Z"}]
            }}
        """.trimIndent()
        val group = json.decodeFromString(
            com.denova.novara.core.network.ChangeGroupResponse.serializer(),
            payload,
        ).group
        assertEquals("g1", group.id)
        assertEquals("applied", group.applyState)
        assertTrue(group.canUndo)
        assertFalse(group.canRedo)
        assertEquals(1, group.changeSets.size)
        val set = group.changeSets.single()
        assertEquals("chapters/a.md", set.path)
        assertEquals("line1\nline2 edited\n", set.afterContent)
        assertEquals(1, group.comments.size)
        assertEquals("请保留原句", group.comments.single().body)

        val diff = ChangeDiff.files(group).single()
        assertEquals("chapters/a.md", diff.path)
        assertEquals(1, diff.stats.additions)
        assertEquals(1, diff.stats.deletions)
    }

    @Test
    fun `group without comments decodes as empty list`() {
        val group = WorkspaceChangeGroup(
            id = "g2",
            comments = emptyList<WorkspaceChangeComment>(),
        )
        assertTrue(ChangeDiff.files(group).isEmpty())
    }
}
