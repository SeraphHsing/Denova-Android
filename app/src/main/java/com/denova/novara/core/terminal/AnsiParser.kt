package com.denova.novara.core.terminal

/**
 * 增量 ANSI/VT 解析器：PTY 字节流 → [TerminalScreen]。
 *
 * 覆盖范围（G5 验收所需的最小完整集）：
 * - CSI：光标移动/定位（A B C D E F G d H f）、擦除（J K X）、插入删除（L M P @）、滚动（S T）、
 *   滚动区（r）、SGR（m）、模式（?7 ?25 ?47 ?1047 ?1049 h/l）、保存恢复（s u）
 * - ESC：7 8 c M D E
 * - OSC：整段丢弃（标题等不影响屏幕）
 * - UTF-8 增量解码（跨帧安全的 CJK 宽字 = 2 格；组合字符并入前一格）
 *
 * 不依赖 Android / Compose，可用 JVM 单测；也是 D03-P 重放时统计解析吞吐的入口。
 */
class AnsiParser(private val screen: TerminalScreen) {

    private enum class State { GROUND, ESCAPE, CSI, OSC, OSC_ESCAPE }

    private var state = State.GROUND
    private val params = StringBuilder()

    private val pending = ByteArray(4)
    private var pendingLen = 0
    private var pendingNeed = 0

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        for (i in 0 until length) step(bytes[i].toInt() and 0xFF)
    }

    fun feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    /** 重连 / 复位时丢弃未完成的转义与多字节序列。 */
    fun reset() {
        state = State.GROUND
        params.setLength(0)
        pendingLen = 0
        pendingNeed = 0
    }

    private fun step(b: Int) {
        when (state) {
            State.OSC -> {
                if (b == 0x07) state = State.GROUND else if (b == 0x1B) state = State.OSC_ESCAPE
                return
            }
            State.OSC_ESCAPE -> {
                state = if (b == '\\'.code) State.GROUND else State.OSC
                return
            }
            State.CSI -> {
                if (b in 0x40..0x7E) {
                    dispatchCsi(b.toChar())
                    state = State.GROUND
                } else {
                    params.append(b.toChar())
                }
                return
            }
            State.ESCAPE -> {
                // 状态由 handleEscape 决定（CSI/OSC 需继续收集后续字节）。
                handleEscape(b.toChar())
                return
            }
            State.GROUND -> Unit
        }

        when {
            b == 0x1B -> state = State.ESCAPE
            b < 0x20 || b == 0x7F -> {
                pendingLen = 0
                pendingNeed = 0
                handleControl(b)
            }
            b < 0x80 -> writeCodePoint(b)
            else -> decodeUtf8(b)
        }
    }

    private fun handleControl(b: Int) {
        when (b) {
            0x08 -> screen.backspace()
            0x09 -> screen.tab()
            0x0A, 0x0B, 0x0C -> screen.lineFeed()
            0x0D -> screen.carriageReturn()
            else -> Unit
        }
    }

    private fun handleEscape(c: Char) {
        when (c) {
            '[' -> {
                params.setLength(0)
                state = State.CSI
            }
            ']' -> state = State.OSC
            '7' -> {
                screen.saveCursor()
                state = State.GROUND
            }
            '8' -> {
                screen.restoreCursor()
                state = State.GROUND
            }
            'c' -> {
                screen.reset()
                state = State.GROUND
            }
            'M' -> {
                screen.reverseIndex()
                state = State.GROUND
            }
            'D' -> {
                screen.lineFeed()
                state = State.GROUND
            }
            'E' -> {
                screen.carriageReturn()
                screen.lineFeed()
                state = State.GROUND
            }
            else -> state = State.GROUND
        }
    }

    private fun decodeUtf8(b: Int) {
        if (pendingNeed == 0) {
            pendingNeed = when {
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> 0
            }
            if (pendingNeed == 0) {
                writeCodePoint(0xFFFD)
                return
            }
            pending[0] = b.toByte()
            pendingLen = 1
            return
        }
        pending[pendingLen++] = b.toByte()
        if (pendingLen < pendingNeed) return
        val text = String(pending, 0, pendingLen, Charsets.UTF_8)
        pendingLen = 0
        pendingNeed = 0
        if (text.isNotEmpty()) writeCodePoint(Character.codePointAt(text, 0))
    }

    private fun writeCodePoint(codePoint: Int) {
        if (isCombining(codePoint)) {
            appendCombining(String(Character.toChars(codePoint)))
            return
        }
        screen.putText(String(Character.toChars(codePoint)), if (isWide(codePoint)) 2 else 1)
    }

    /** 组合字符（如重音）并入前一格，不占新列。 */
    private fun appendCombining(mark: String) {
        var col = screen.cursorCol - 1
        val row = screen.rowsView()[screen.cursorRow]
        while (col >= 0 && row[col].continuation) col--
        if (col >= 0) row[col].text = row[col].text + mark
    }

    private fun dispatchCsi(final: Char) {
        val raw = params.toString()
        params.setLength(0)
        var body = raw
        var private = false
        when {
            body.startsWith("?") -> {
                private = true
                body = body.substring(1)
            }
            body.startsWith(">") || body.startsWith("!") -> body = body.substring(1)
        }
        val list = parseParams(body)

        fun n(index: Int, default: Int = 1): Int {
            val v = list.getOrElse(index) { 0 }
            return if (v == 0) default else v
        }

        when (final) {
            'A' -> screen.moveRelative(-n(0), 0)
            'B' -> screen.moveRelative(n(0), 0)
            'C' -> screen.moveRelative(0, n(0))
            'D' -> screen.moveRelative(0, -n(0))
            'E' -> screen.moveRelative(n(0), -screen.cursorCol)
            'F' -> screen.moveRelative(-n(0), -screen.cursorCol)
            'G' -> screen.setColumn(n(0) - 1)
            'd' -> screen.setRow(n(0) - 1)
            'H', 'f' -> screen.moveTo(n(0) - 1, n(1) - 1)
            'J' -> screen.eraseInDisplay(list.getOrElse(0) { 0 })
            'K' -> screen.eraseInLine(list.getOrElse(0) { 0 })
            'L' -> screen.insertLines(n(0))
            'M' -> screen.deleteLines(n(0))
            'P' -> screen.deleteChars(n(0))
            '@' -> screen.insertChars(n(0))
            'X' -> screen.eraseChars(n(0))
            'S' -> screen.scrollUp(n(0))
            'T' -> screen.scrollDown(n(0))
            'm' -> screen.applySgr(list)
            'r' -> {
                if (list.isEmpty()) {
                    screen.resetScrollRegion()
                } else {
                    val top = n(0)
                    val bottom = if (list.size > 1 && list[1] != 0) list[1] else screen.rows
                    screen.setScrollRegion(top - 1, bottom - 1)
                }
            }
            's' -> screen.saveCursor()
            'u' -> screen.restoreCursor()
            'h', 'l' -> if (private) {
                val enabled = final == 'h'
                list.forEach { screen.setPrivateMode(it, enabled) }
            }
            else -> Unit
        }
    }

    private fun parseParams(body: String): List<Int> {
        if (body.isEmpty()) return emptyList()
        return body.split(';').map { token ->
            token.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    private fun isWide(codePoint: Int): Boolean = WIDE_RANGES.any { codePoint in it }

    private fun isCombining(codePoint: Int): Boolean =
        codePoint in 0x0300..0x036F || codePoint in 0x1AB0..0x1AFF || codePoint in 0x20D0..0x20FF

    private companion object {
        /** East Asian Wide / Fullwidth 的常用区间（CJK、假名、谚文、全角标点、Emoji）。 */
        val WIDE_RANGES = listOf(
            0x1100..0x115F,
            0x2E80..0x303E,
            0x3041..0x33FF,
            0x3400..0x4DBF,
            0x4E00..0x9FFF,
            0xA000..0xA4CF,
            0xAC00..0xD7A3,
            0xF900..0xFAFF,
            0xFE30..0xFE4F,
            0xFF00..0xFF60,
            0xFFE0..0xFFE6,
            0x1F300..0x1F64F,
            0x1F900..0x1F9FF,
            0x20000..0x2FFFD,
            0x30000..0x3FFFD,
        )
    }
}
