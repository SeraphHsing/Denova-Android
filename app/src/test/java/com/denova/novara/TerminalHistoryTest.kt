package com.denova.novara

import com.denova.novara.core.terminal.AnsiParser
import com.denova.novara.core.terminal.TerminalHistory
import com.denova.novara.core.terminal.TerminalScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端历史快照（G6 决策 B）：会话被 Web 回收后，本地留存的内容要能看、且不能是半截字符。
 */
class TerminalHistoryTest {

    @Test
    fun `snapshot keeps screen and scrollback in order`() {
        val screen = TerminalScreen(12, 3)
        AnsiParser(screen).feed("old1\r\nold2\r\nold3\r\nold4\r\nnew")

        val text = TerminalHistory.build(screen)

        assertTrue(text, text.startsWith("old1"))
        assertTrue(text, text.endsWith("new"))
    }

    @Test
    fun `trailing blank lines are dropped`() {
        val screen = TerminalScreen(12, 4)
        AnsiParser(screen).feed("only-line")

        val text = TerminalHistory.build(screen)

        assertEquals("only-line", text)
    }

    @Test
    fun `byte cap keeps the newest lines only`() {
        val screen = TerminalScreen(20, 8)
        val payload = buildString {
            repeat(8) { append("line-").append(it).append("-0123456789\r\n") }
        }
        AnsiParser(screen).feed(payload)

        val text = TerminalHistory.build(screen, maxBytes = 40)

        assertTrue("应保留最新内容：$text", text.contains("line-7") || text.contains("line-6"))
        assertFalse("超出上限的旧行应被丢弃：$text", text.startsWith("line-0"))
        assertTrue("不能超过上限：${text.length}", text.toByteArray(Charsets.UTF_8).size <= 40)
    }

    @Test
    fun `cjk lines are not cut mid character`() {
        val screen = TerminalScreen(16, 4)
        AnsiParser(screen).feed("中文内容测试\r\n第二行中文")

        val text = TerminalHistory.build(screen)

        assertTrue(text, text.contains("中文内容测试"))
        // 截断只按整行，不会出现替换字符
        assertFalse(text, text.contains('\uFFFD'))
    }

    @Test
    fun `empty screen yields empty snapshot`() {
        val screen = TerminalScreen(8, 2)
        assertEquals("", TerminalHistory.build(screen))
    }
}
