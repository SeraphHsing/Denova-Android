package com.denova.novara

import com.denova.novara.core.terminal.AnsiParser
import com.denova.novara.core.terminal.DEFAULT_FG
import com.denova.novara.core.terminal.TerminalPalette
import com.denova.novara.core.terminal.TerminalScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiParserTest {

    private fun screen(cols: Int = 10, rows: Int = 4): TerminalScreen = TerminalScreen(cols, rows)

    @Test
    fun `plain text advances cursor`() {
        val s = screen()
        AnsiParser(s).feed("hi")
        assertEquals("h", s.cell(0, 0).text)
        assertEquals("i", s.cell(0, 1).text)
        assertEquals(0, s.cursorRow)
        assertEquals(2, s.cursorCol)
    }

    @Test
    fun `cr lf moves to next line`() {
        val s = screen()
        AnsiParser(s).feed("ab\r\ncd")
        assertEquals("a", s.cell(0, 0).text)
        assertEquals("c", s.cell(1, 0).text)
        assertEquals("d", s.cell(1, 1).text)
    }

    @Test
    fun `auto wrap continues on next row`() {
        val s = screen(cols = 4, rows = 3)
        val p = AnsiParser(s)
        p.feed("abcd")
        assertEquals("d", s.cell(0, 3).text)
        p.feed("e")
        assertEquals("e", s.cell(1, 0).text)
    }

    @Test
    fun `cjk occupies two cells`() {
        val s = screen()
        AnsiParser(s).feed("中")
        assertEquals("中", s.cell(0, 0).text)
        assertTrue(s.cell(0, 1).continuation)
        assertEquals(2, s.cursorCol)
    }

    @Test
    fun `cjk split across feeds still decodes`() {
        val s = screen()
        val p = AnsiParser(s)
        val bytes = "中".toByteArray(Charsets.UTF_8)
        p.feed(bytes, 1)
        p.feed(bytes.copyOfRange(1, bytes.size), bytes.size - 1)
        assertEquals("中", s.cell(0, 0).text)
    }

    @Test
    fun `sgr sets color and reset restores default`() {
        val s = screen()
        AnsiParser(s).feed("\u001b[31mR\u001b[0mN")
        assertEquals(TerminalPalette.color256(1), s.cell(0, 0).attr.fg)
        assertEquals(DEFAULT_FG, s.cell(0, 1).attr.fg)
    }

    @Test
    fun `split escape sequence across feeds`() {
        val s = screen()
        val p = AnsiParser(s)
        p.feed("\u001b[3")
        p.feed("1mX")
        assertEquals(TerminalPalette.color256(1), s.cell(0, 0).attr.fg)
    }

    @Test
    fun `erase in line from cursor`() {
        val s = screen()
        AnsiParser(s).feed("abc\r\u001b[K")
        assertEquals(" ", s.cell(0, 0).text)
        assertEquals(" ", s.cell(0, 1).text)
        assertEquals(" ", s.cell(0, 2).text)
    }

    @Test
    fun `cursor position via csi H`() {
        val s = screen()
        AnsiParser(s).feed("\u001b[2;3HX")
        assertEquals("X", s.cell(1, 2).text)
    }

    @Test
    fun `reverse index scrolls down at top`() {
        val s = screen(cols = 4, rows = 3)
        val p = AnsiParser(s)
        p.feed("top")
        p.feed("\u001b[1;1H")
        p.feed("\u001bM")
        // 区域顶部反向索引：内容整体下移一行，首行空出
        assertEquals(" ", s.cell(0, 0).text)
        assertEquals("t", s.cell(1, 0).text)
        assertEquals("o", s.cell(1, 1).text)
    }

    @Test
    fun `scroll region keeps rows outside untouched`() {
        val s = TerminalScreen(cols = 5, rows = 4)
        val p = AnsiParser(s)
        p.feed("\u001b[2;3r")   // 区域 = 行 1..2（0 基）
        p.feed("\u001b[3;1Ha\nb")
        assertEquals("a", s.cell(1, 0).text)
        assertEquals("b", s.cell(2, 1).text)
    }

    @Test
    fun `alternate screen restores main content`() {
        val s = screen()
        val p = AnsiParser(s)
        p.feed("main")
        p.feed("\u001b[?1049h")
        assertEquals(" ", s.cell(0, 0).text)
        p.feed("alt")
        p.feed("\u001b[?1049l")
        assertEquals("m", s.cell(0, 0).text)
        assertEquals("a", s.cell(0, 1).text)
    }

    @Test
    fun `full screen scroll pushes scrollback`() {
        val s = TerminalScreen(cols = 5, rows = 2)
        val p = AnsiParser(s)
        p.feed("a\nb\nc")
        assertEquals(1, s.scrollbackSize)
        assertEquals("a", s.scrollbackRow(0)[0].text)
    }

    @Test
    fun `osc title is ignored`() {
        val s = screen()
        AnsiParser(s).feed("\u001b]0;my title\u0007ok")
        assertEquals("o", s.cell(0, 0).text)
        assertEquals("k", s.cell(0, 1).text)
    }

    @Test
    fun `cursor visibility mode`() {
        val s = screen()
        val p = AnsiParser(s)
        p.feed("\u001b[?25l")
        assertFalse(s.cursorVisible)
        p.feed("\u001b[?25h")
        assertTrue(s.cursorVisible)
    }

    @Test
    fun `combining mark merges into previous cell`() {
        val s = screen()
        AnsiParser(s).feed("e\u0301")
        assertEquals("e\u0301", s.cell(0, 0).text)
        assertEquals(1, s.cursorCol)
    }
}
