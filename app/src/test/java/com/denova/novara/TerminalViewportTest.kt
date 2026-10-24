package com.denova.novara

import com.denova.novara.core.terminal.AnsiParser
import com.denova.novara.core.terminal.DEFAULT_BG
import com.denova.novara.core.terminal.TerminalCell
import com.denova.novara.core.terminal.TerminalPalette
import com.denova.novara.core.terminal.TerminalScreen
import com.denova.novara.core.terminal.buildRowRuns
import com.denova.novara.core.terminal.lineAt
import com.denova.novara.core.terminal.maxScrollOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染窗口映射：滚动回看把「回滚 + 主屏」拼成一条逻辑行序列。
 *
 * 这条路径一旦算错，用户滚动时看到的是错位的行（尤其在刚滚屏、回滚刚增长的时候），
 * 而 UI 上不易察觉，所以放在纯逻辑层固定住。
 */
class TerminalViewportTest {

    @Test
    fun `offset zero maps view rows onto the main screen`() {
        val s = scrolledScreen()

        assertEquals(1, s.maxScrollOffset)
        assertEquals("r1", rowText(s.lineAt(0, 0)))
        assertEquals("r2", rowText(s.lineAt(1, 0)))
        assertEquals("r3", rowText(s.lineAt(2, 0)))
    }

    @Test
    fun `offset shifts the window into scrollback`() {
        val s = scrolledScreen()

        assertEquals("r0", rowText(s.lineAt(0, 1)))
        assertEquals("r1", rowText(s.lineAt(1, 1)))
        assertEquals("r2", rowText(s.lineAt(2, 1)))
    }

    @Test
    fun `offset beyond scrollback is clamped`() {
        val s = scrolledScreen()

        // 最多只能上移到回滚起点，再多也没有内容可看
        assertEquals("r0", rowText(s.lineAt(0, 99)))
    }

    @Test
    fun `alt screen has no scrollback to review`() {
        val s = scrolledScreen()
        assertTrue(s.scrollbackSize > 0)

        AnsiParser(s).feed("\u001b[?1049h")
        assertEquals(0, s.maxScrollOffset)
        // 备用屏第一行就是程序自己画的内容，不会串到主屏历史
        assertEquals("", rowText(s.lineAt(0, 5)))
    }

    @Test
    fun `runs merge same attribute cells and keep wide glyph geometry`() {
        // 宽度刚好放满 a + 中(2 格) + b，没有尾随空白干扰归并结果
        val s = TerminalScreen(4, 2)
        AnsiParser(s).feed("a中b")

        val runs = buildRowRuns(s.rowsView()[0], s.cols)

        assertEquals(1, runs.size)
        assertEquals("a中b", runs[0].text)
        // 3 个字形覆盖 4 列：宽字仍按两格算，后续列位置才不会漂
        assertEquals(3, runs[0].text.length)
        assertEquals(4, runs[0].spanCols)
    }

    @Test
    fun `runs split on attribute change`() {
        val s = TerminalScreen(8, 2)
        AnsiParser(s).feed("a\u001b[31mb")

        val runs = buildRowRuns(s.rowsView()[0], s.cols)

        assertEquals(2, runs.size)
        assertEquals("a", runs[0].text)
        assertEquals("b", runs[1].text)
        assertEquals(1, runs[1].startCol)
        assertEquals(TerminalPalette.color256(1), runs[1].attr.fg)
    }

    @Test
    fun `default blank cells produce no runs`() {
        val s = TerminalScreen(8, 2)
        assertEquals(0, buildRowRuns(s.rowsView()[0], s.cols).size)
    }

    @Test
    fun `highlighted background cells are kept even when blank`() {
        val s = TerminalScreen(4, 1)
        // 反显的空格在 TUI 里是有效的选中条，不能被「空白丢弃」优化掉
        AnsiParser(s).feed("\u001b[7m  ")
        val runs = buildRowRuns(s.rowsView()[0], s.cols)

        assertTrue(runs.isNotEmpty())
        assertTrue(runs[0].attr.inverse)
        assertEquals(DEFAULT_BG, runs[0].attr.bg)
    }

    private fun scrolledScreen(): TerminalScreen {
        val s = TerminalScreen(6, 3)
        AnsiParser(s).feed("r0\r\nr1\r\nr2\r\nr3")
        return s
    }

    private fun rowText(cells: Array<TerminalCell>): String =
        buildString {
            for (cell in cells) {
                if (!cell.continuation) append(cell.text)
            }
        }.trimEnd()
}
