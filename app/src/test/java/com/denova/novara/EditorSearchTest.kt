package com.denova.novara

import com.denova.novara.core.editor.EditorSearch
import com.denova.novara.core.editor.SearchMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器内查找 / 替换（WRI-015）：对齐 Web `editorDecorations.ts` 的编辑器内口径
 * （字面量与正则都大小写不敏感；全部替换一次性完成，因此只需一次撤销）。
 */
class EditorSearchTest {

    @Test
    fun `literal search is case insensitive and covers every occurrence`() {
        val matches = EditorSearch.findAll("Rain rain RAIN", "rain")
        assertEquals(listOf(SearchMatch(0, 4), SearchMatch(5, 9), SearchMatch(10, 14)), matches)
    }

    @Test
    fun `blank query and blank text yield no matches`() {
        assertTrue(EditorSearch.findAll("abc", "   ").isEmpty())
        assertTrue(EditorSearch.findAll("", "a").isEmpty())
    }

    @Test
    fun `regex search is case insensitive like the web editor`() {
        val matches = EditorSearch.findAll("第1章 第2章", "第\\d章", regex = true)
        assertEquals(2, matches.size)
        assertEquals("第1章", "第1章 第2章".substring(matches[0].start, matches[0].end))
    }

    @Test
    fun `invalid regex yields no matches instead of throwing`() {
        assertTrue(EditorSearch.findAll("abc", "([", regex = true).isEmpty())
    }

    @Test
    fun `regex matching empty string is rejected like the server does`() {
        assertTrue(EditorSearch.findAll("abc", "a*", regex = true).isEmpty())
    }

    @Test
    fun `step wraps around in both directions`() {
        assertEquals(0, EditorSearch.step(3, -1, forward = true))
        assertEquals(2, EditorSearch.step(3, -1, forward = false))
        assertEquals(0, EditorSearch.step(3, 2, forward = true))
        assertEquals(2, EditorSearch.step(3, 0, forward = false))
        assertEquals(-1, EditorSearch.step(0, 0, forward = true))
    }

    @Test
    fun `caret index resolves to the containing match`() {
        val matches = EditorSearch.findAll("abc abc", "abc")
        assertEquals(1, EditorSearch.indexOfCaret(matches, 5))
        assertEquals(-1, EditorSearch.indexOfCaret(matches, 3))
    }

    @Test
    fun `replace one only touches the targeted match`() {
        val text = "abc abc"
        val matches = EditorSearch.findAll(text, "abc")
        assertEquals("abc XYZ", EditorSearch.replaceOne(text, matches[1], "XYZ"))
    }

    @Test
    fun `replace all rewrites every match and reports the count`() {
        val (out, count) = EditorSearch.replaceAll("Rain rain RAIN", "rain", "snow")
        assertEquals("snow snow snow", out)
        assertEquals(3, count)
    }

    @Test
    fun `replace all with regex supports group references`() {
        val (out, count) = EditorSearch.replaceAll("第1章 第2章", "第(\\d)章", "Chapter $1", regex = true)
        assertEquals("Chapter 1 Chapter 2", out)
        assertEquals(2, count)
    }

    @Test
    fun `replace all with no match is a no-op`() {
        val (out, count) = EditorSearch.replaceAll("abc", "zzz", "y")
        assertEquals("abc", out)
        assertEquals(0, count)
    }
}
