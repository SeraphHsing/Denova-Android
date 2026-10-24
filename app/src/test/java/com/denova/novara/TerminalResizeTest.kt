package com.denova.novara

import com.denova.novara.core.terminal.AnsiParser
import com.denova.novara.core.terminal.TerminalScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 尺寸变化的内容保留语义：旋转、IME 弹出、attach 对齐都会走 `resize`，
 * 这条路径一旦整屏重建，用户就会丢掉滚动历史与当前输出。
 */
class TerminalResizeTest {

    private fun screen(cols: Int = 10, rows: Int = 4): TerminalScreen = TerminalScreen(cols, rows)

    @Test
    fun `growing columns keeps content and pads blanks`() {
        val s = screen()
        AnsiParser(s).feed("ab\r\ncd")
        s.resize(16, 4)

        assertEquals(16, s.cols)
        assertEquals("a", s.cell(0, 0).text)
        assertEquals("d", s.cell(1, 1).text)
        assertEquals(" ", s.cell(0, 15).text)
        assertEquals(1, s.cursorRow)
        assertEquals(2, s.cursorCol)
    }

    @Test
    fun `shrinking columns crops right edge`() {
        val s = screen(cols = 10, rows = 3)
        AnsiParser(s).feed("0123456789")
        s.resize(4, 3)

        assertEquals(4, s.cols)
        assertEquals("0", s.cell(0, 0).text)
        assertEquals("3", s.cell(0, 3).text)
        // 光标原在末列（wrapPending），裁剪后收敛到新的末列
        assertEquals(3, s.cursorCol)
    }

    @Test
    fun `shrinking rows keeps newest lines and pushes dropped rows to scrollback`() {
        val s = screen(cols = 6, rows = 4)
        AnsiParser(s).feed("r0\r\nr1\r\nr2\r\nr3")
        s.resize(6, 2)

        assertEquals(2, s.rows)
        assertEquals("r2", rowText(s, 0))
        assertEquals("r3", rowText(s, 1))
        assertEquals(2, s.scrollbackSize)
        assertEquals("r", s.scrollbackRow(0)[0].text)
        assertEquals("0", s.scrollbackRow(0)[1].text)
        assertEquals("r", s.scrollbackRow(1)[0].text)
        assertEquals("1", s.scrollbackRow(1)[1].text)
        assertEquals(1, s.cursorRow)
    }

    @Test
    fun `growing rows appends blank lines`() {
        val s = screen(cols = 6, rows = 2)
        AnsiParser(s).feed("top")
        s.resize(6, 5)

        assertEquals(5, s.rows)
        assertEquals("t", s.cell(0, 0).text)
        assertEquals(" ", s.cell(4, 0).text)
        assertEquals(0, s.cursorRow)
    }

    @Test
    fun `cropped wide glyph degrades to space`() {
        val s = screen(cols = 4, rows = 2)
        AnsiParser(s).feed("a中")
        assertEquals("中", s.cell(0, 1).text)
        assertTrue(s.cell(0, 2).continuation)

        // 宽字只剩一列位置：不能只留前导格（渲染出半个字），退化为空格
        s.resize(2, 2)
        assertEquals("a", s.cell(0, 0).text)
        assertEquals(" ", s.cell(0, 1).text)
        assertFalse(s.cell(0, 1).continuation)
    }

    @Test
    fun `resize is no-op for identical size`() {
        val s = screen(cols = 8, rows = 3)
        AnsiParser(s).feed("keep")
        s.resize(8, 3)
        assertEquals("k", s.cell(0, 0).text)
    }

    private fun rowText(screen: TerminalScreen, row: Int): String =
        buildString {
            for (c in 0 until screen.cols) {
                if (!screen.cell(row, c).continuation) append(screen.cell(row, c).text)
            }
        }.trimEnd()
}
