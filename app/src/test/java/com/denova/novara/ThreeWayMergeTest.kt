package com.denova.novara

import com.denova.novara.core.editor.MergeResult
import com.denova.novara.core.editor.ThreeWayMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeWayMergeTest {

    private val base = "标题\n段落一\n段落二\n段落三\n结尾"

    @Test
    fun `non-overlapping changes merge cleanly`() {
        val ours = "标题\n段落一\n段落二改\n段落三\n结尾"
        val theirs = "标题\n段落一\n段落二\n段落三改\n结尾"
        val result = ThreeWayMerge.merge(base, ours, theirs)
        assertTrue(result is MergeResult.Clean)
        assertEquals("标题\n段落一\n段落二改\n段落三改\n结尾", (result as MergeResult.Clean).text)
    }

    @Test
    fun `identical changes merge without conflict`() {
        val ours = "标题\n段落一\n段落二改\n段落三\n结尾"
        val theirs = "标题\n段落一\n段落二改\n段落三\n结尾"
        val result = ThreeWayMerge.merge(base, ours, theirs)
        assertTrue(result is MergeResult.Clean)
        assertEquals(ours, (result as MergeResult.Clean).text)
    }

    @Test
    fun `overlapping different changes produce conflict`() {
        val ours = "标题\n段落一\n段落二本地\n段落三\n结尾"
        val theirs = "标题\n段落一\n段落二服务器\n段落三\n结尾"
        val result = ThreeWayMerge.merge(base, ours, theirs)
        assertTrue(result is MergeResult.Conflicts)
        val r = result as MergeResult.Conflicts
        assertEquals(1, r.conflictCount)
        assertTrue(r.text.contains("<<<<<<< 本地修改"))
        assertTrue(r.text.contains("段落二本地"))
        assertTrue(r.text.contains("段落二服务器"))
        assertTrue(r.text.contains(">>>>>>> 服务器修改"))
    }

    @Test
    fun `ours deletes line while theirs modifies another line`() {
        val ours = "标题\n段落一\n段落三\n结尾" // 删除段落二
        val theirs = "标题\n段落一\n段落二\n段落三改\n结尾" // 改段落三
        val result = ThreeWayMerge.merge(base, ours, theirs)
        assertTrue(result is MergeResult.Clean)
        assertEquals("标题\n段落一\n段落三改\n结尾", (result as MergeResult.Clean).text)
    }

    @Test
    fun `insertions at different positions both apply`() {
        val ours = "标题\n新增A\n段落一\n段落二\n段落三\n结尾"
        val theirs = "标题\n段落一\n段落二\n段落三\n新增B\n结尾"
        val result = ThreeWayMerge.merge(base, ours, theirs)
        assertTrue(result is MergeResult.Clean)
        assertEquals("标题\n新增A\n段落一\n段落二\n段落三\n新增B\n结尾", (result as MergeResult.Clean).text)
    }
}
