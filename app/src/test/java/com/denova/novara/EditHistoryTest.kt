package com.denova.novara

import com.denova.novara.core.editor.EditHistory
import com.denova.novara.core.editor.EditSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 撤销 / 重做栈（WRI-014「选区和撤销按基线更新」）。
 *
 * 记录的是**改动前**的快照，所以撤销回到「这次编辑突发开始之前」；
 * `reset` 对应基线 resetHistory：加载外部版本后旧正文不能再撤销回来。
 */
class EditHistoryTest {

    @Test
    fun `fresh history has nothing to undo or redo`() {
        val h = EditHistory()
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
    }

    @Test
    fun `undo returns the snapshot recorded before the edit`() {
        val h = EditHistory()
        h.record(0, EditSnapshot("abc", 0))
        val prev = h.undo(EditSnapshot("abcd", 3))
        assertEquals(EditSnapshot("abc", 0), prev)
        assertFalse(h.canUndo)
        assertTrue(h.canRedo)
    }

    @Test
    fun `redo returns the snapshot that was current when undoing`() {
        val h = EditHistory()
        h.record(0, EditSnapshot("abc", 0))
        h.undo(EditSnapshot("abcd", 3))
        assertEquals(EditSnapshot("abcd", 3), h.redo(EditSnapshot("abc", 0)))
        assertTrue(h.canUndo)
        assertFalse(h.canRedo)
    }

    @Test
    fun `continuous typing inside the coalesce window collapses into one step`() {
        val h = EditHistory()
        h.record(1_000, EditSnapshot("a", 1))
        h.record(1_100, EditSnapshot("ab", 2)) // 合并：不再压栈
        h.record(1_200, EditSnapshot("abc", 3)) // 合并
        assertEquals(EditSnapshot("a", 1), h.undo(EditSnapshot("abcd", 4)))
        assertFalse(h.canUndo)
    }

    @Test
    fun `a pause longer than the window starts a new undo step`() {
        val h = EditHistory()
        h.record(1_000, EditSnapshot("a", 1))
        h.record(1_000 + EditHistory.COALESCE_MILLIS + 1, EditSnapshot("ab", 2))
        assertEquals(EditSnapshot("ab", 2), h.undo(EditSnapshot("abc", 3)))
        assertEquals(EditSnapshot("a", 1), h.undo(EditSnapshot("ab", 2)))
    }

    @Test
    fun `a new edit after undo drops the redo branch`() {
        val h = EditHistory()
        h.record(1_000, EditSnapshot("a", 1))
        h.undo(EditSnapshot("ab", 2))
        assertTrue(h.canRedo)

        h.record(9_000, EditSnapshot("a", 1))
        assertFalse(h.canRedo)
    }

    @Test
    fun `reset drops both sides so discarded content cannot be restored`() {
        val h = EditHistory()
        h.record(1_000, EditSnapshot("local", 5))
        h.undo(EditSnapshot("local+", 6))
        h.reset()

        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertNull(h.undo(EditSnapshot("external", 0)))
    }

    @Test
    fun `history is bounded by capacity keeping the newest steps`() {
        val h = EditHistory(capacity = 3, coalesceMillis = 0)
        // 每次编辑记录「改动前」的状态：v1..v4，当前正文是 v5。
        for (i in 1..4) {
            h.record(i.toLong(), EditSnapshot("v$i", 0))
        }
        // 只保留最近 3 步：v4 → v3 → v2，更早的 v1 已丢弃。
        assertEquals(EditSnapshot("v4", 0), h.undo(EditSnapshot("v5", 0)))
        assertEquals(EditSnapshot("v3", 0), h.undo(EditSnapshot("v4", 0)))
        assertEquals(EditSnapshot("v2", 0), h.undo(EditSnapshot("v3", 0)))
        assertNull(h.undo(EditSnapshot("v2", 0)))
    }
}
