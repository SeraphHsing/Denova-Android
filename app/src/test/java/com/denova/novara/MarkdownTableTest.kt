package com.denova.novara

import com.denova.novara.core.editor.MarkdownTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WRI-006 行列操作的纯函数判定。 */
class MarkdownTableTest {

    private val table = """
        | 列1 | 列2 | 列3 |
        | --- | --- | --- |
        | 甲 | 乙 | 丙 |
        | 丁 | 戊 | 己 |
    """.trimIndent()

    private fun caretAt(text: String, marker: String): Int = text.indexOf(marker)

    @Test
    fun `locate finds table for header body and separator rows`() {
        val header = MarkdownTable.locate(table, caretAt(table, "列1"))
        assertEquals(0, header?.startLine)
        assertEquals(3, header?.endLine)
        assertEquals(3, header?.columnCount)

        // 戊 位于第 3 行（表头 0、分隔 1、甲 2、丁 3）第 2 个单元格
        val body = MarkdownTable.locate(table, caretAt(table, "戊"))
        assertEquals(3, body?.row)
        assertEquals(1, body?.cell)

        val separator = MarkdownTable.locate(table, caretAt(table, "---"))
        assertEquals(1, separator?.row)
    }

    @Test
    fun `locate rejects plain text and separator without header`() {
        assertNull(MarkdownTable.locate("普通段落", 1))
        assertNull(MarkdownTable.locate("| --- | --- |", 2))
    }

    @Test
    fun `insert row below keeps column count and puts caret in new row`() {
        // 戊 在第 3 行（0 起），下方插入 → 新行落到第 4 行、原「丁」行下移
        val edit = MarkdownTable.insertRow(table, caretAt(table, "戊"), below = true)!!
        val lines = edit.text.split('\n')
        assertEquals(5, lines.size)
        assertEquals("|  |  |  |", lines[4])
        assertTrue(edit.caret > edit.text.indexOf("|  |  |  |"))
    }

    @Test
    fun `insert row above header falls after separator`() {
        val edit = MarkdownTable.insertRow(table, caretAt(table, "列2"), below = false)!!
        val lines = edit.text.split('\n')
        // 表头之上不是合法表体，插入点被限制到分隔行之后。
        assertEquals("| --- | --- | --- |", lines[1])
        assertEquals("|  |  |  |", lines[2])
    }

    @Test
    fun `delete row removes only the body row under caret`() {
        val edit = MarkdownTable.deleteRow(table, caretAt(table, "戊"))!!
        assertTrue(edit.text.contains("甲"))
        assertTrue(!edit.text.contains("丁"))
        assertEquals(3, edit.text.split('\n').size)
    }

    @Test
    fun `delete last body row removes whole table`() {
        val single = "| a | b |\n| --- | --- |\n| 1 | 2 |"
        val edit = MarkdownTable.deleteRow(single, 10)!!
        assertEquals("", edit.text.trim())
    }

    @Test
    fun `insert column right adds separator dashes and blank cells`() {
        val edit = MarkdownTable.insertColumn(table, caretAt(table, "乙"), right = true)!!
        val lines = edit.text.split('\n')
        assertEquals("| 列1 | 列2 |  | 列3 |", lines[0])
        // 分隔行同步补一格 `---`，否则新列在任何渲染器里都不成立。
        assertEquals(4, MarkdownTable.cellsOf(lines[1]).size)
        assertTrue(MarkdownTable.cellsOf(lines[1]).all { it == "---" })
        assertEquals(4, MarkdownTable.cellsOf(lines[2]).size)
    }

    @Test
    fun `delete column removes that column from every row`() {
        val edit = MarkdownTable.deleteColumn(table, caretAt(table, "乙"))!!
        val lines = edit.text.split('\n')
        assertEquals(listOf("列1", "列3"), MarkdownTable.cellsOf(lines[0]))
        assertEquals(listOf("甲", "丙"), MarkdownTable.cellsOf(lines[2]))
        assertEquals(listOf("丁", "己"), MarkdownTable.cellsOf(lines[3]))
    }

    @Test
    fun `delete last column removes whole table`() {
        val single = "| a |\n| --- |\n| 1 |"
        val edit = MarkdownTable.deleteColumn(single, 3)!!
        assertEquals("", edit.text.trim())
    }

    @Test
    fun `tab moves between cells then appends row at the end`() {
        // 单元格内 → 下一个单元格的首字符
        val next = MarkdownTable.nextCell(table, caretAt(table, "乙"))!!
        assertEquals("丙", table.substring(next.caret, next.caret + 1))

        // 本行最后一格 → 下一行首格
        val nextLine = MarkdownTable.nextCell(table, caretAt(table, "丙"))!!
        assertEquals("丁", table.substring(nextLine.caret, nextLine.caret + 1))

        // 整表最后一格 → 追加一行
        val appended = MarkdownTable.nextCell(table, caretAt(table, "己"))!!
        assertEquals(5, appended.text.split('\n').size)
        assertEquals("|  |  |  |", appended.text.split('\n')[4])
    }
}
