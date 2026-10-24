package com.denova.novara.core.terminal

/** 默认前景 / 背景（未指定 SGR 时）。 */
const val DEFAULT_FG: Int = 0xFFD8D8D8.toInt()
const val DEFAULT_BG: Int = 0xFF141414.toInt()

/** 单元格属性；[fg]/[bg] 为 ARGB，[inverse] 在渲染期交换前景背景。 */
data class TerminalAttr(
    val fg: Int = DEFAULT_FG,
    val bg: Int = DEFAULT_BG,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
) {
    fun withForeground(color: Int): TerminalAttr = copy(fg = color)
    fun withBackground(color: Int): TerminalAttr = copy(bg = color)
}

/**
 * 一个单元格。[text] 为单字形（CJK 等宽字占两格，第二格 [continuation] = true 且 text 为空）；
 * 渲染层据此决定是否跳过绘制。
 */
class TerminalCell {
    var text: String = " "
    var continuation: Boolean = false
    var attr: TerminalAttr = TerminalAttr()

    fun set(text: String, attr: TerminalAttr, continuation: Boolean = false) {
        this.text = text
        this.attr = attr
        this.continuation = continuation
    }

    fun blank(attr: TerminalAttr) = set(" ", attr, false)
}

/** xterm 256 色调色板（0–15 基础色 + 6×6×6 立方 + 24 灰阶）。 */
object TerminalPalette {
    private val base = intArrayOf(
        0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
        0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
    )

    fun color256(index: Int): Int {
        val i = index.coerceIn(0, 255)
        if (i < 16) return 0xFF000000.toInt() or base[i]
        if (i < 232) {
            val n = i - 16
            val r = (n / 36) % 6
            val g = (n / 6) % 6
            val b = n % 6
            fun step(v: Int) = if (v == 0) 0 else 55 + v * 40
            return (0xFF shl 24) or (step(r) shl 16) or (step(g) shl 8) or step(b)
        }
        val gray = 8 + (i - 232) * 10
        return (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
    }

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
