package com.denova.novara.core.terminal

/**
 * 终端屏幕模型（cols × rows）+ 有界回滚。
 *
 * 只负责「字节解析后的屏幕状态」，不含任何 Android / Compose 依赖，
 * 便于用 JVM 单测覆盖 ANSI 行为，也便于 D03-P 重放时统计吞吐。
 * 光标 / 区域均以 0 基内部表示，对外接口（如 CSI H）保持 ANSI 的 1 基语义由解析器转换。
 */
class TerminalScreen(
    val cols: Int,
    val rows: Int,
    private val scrollbackLimit: Int = 2000,
) {

    private var grid: Array<Array<TerminalCell>> = newGrid()

    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set
    var cursorVisible: Boolean = true
    var autoWrap: Boolean = true

    var attr: TerminalAttr = TerminalAttr()
        private set

    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var wrapPending = false

    private val scrollback = ArrayDeque<Array<TerminalCell>>()

    private var savedRow = 0
    private var savedCol = 0
    private var savedAttr = TerminalAttr()

    private var altActive = false
    private var altGrid: Array<Array<TerminalCell>>? = null
    private var altCursorRow = 0
    private var altCursorCol = 0

    private fun newGrid(): Array<Array<TerminalCell>> =
        Array(rows) { Array(cols) { TerminalCell() } }

    fun cell(row: Int, col: Int): TerminalCell = grid[row.coerceIn(0, rows - 1)][col.coerceIn(0, cols - 1)]

    /** 供渲染层取当前屏（只读视图，按行）。 */
    fun rowsView(): Array<Array<TerminalCell>> = grid

    val scrollbackSize: Int get() = scrollback.size

    fun scrollbackRow(index: Int): Array<TerminalCell> = scrollback[index]

    // ---- 写入 ----

    /** 写入一个字形；[width] 1 或 2（CJK 等宽字占两格）。 */
    fun putText(text: String, width: Int) {
        if (wrapPending) {
            cursorCol = 0
            lineFeedInternal()
            wrapPending = false
        }
        if (width >= 2 && cursorCol >= cols - 1) {
            if (autoWrap) {
                cursorCol = 0
                lineFeedInternal()
            } else {
                cursorCol = cols - 2
            }
        }
        grid[cursorRow][cursorCol].set(text, attr)
        if (width >= 2) {
            grid[cursorRow][cursorCol + 1].set("", attr, continuation = true)
        }
        val next = cursorCol + width
        if (next >= cols) {
            cursorCol = cols - 1
            wrapPending = autoWrap
        } else {
            cursorCol = next
        }
    }

    fun lineFeed() {
        wrapPending = false
        lineFeedInternal()
    }

    private fun lineFeedInternal() {
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    fun reverseIndex() {
        if (cursorRow == scrollTop) scrollDown(1) else if (cursorRow > 0) cursorRow--
    }

    fun carriageReturn() {
        cursorCol = 0
        wrapPending = false
    }

    fun backspace() {
        if (cursorCol > 0) cursorCol-- else if (wrapPending) { cursorCol = cols - 1; wrapPending = false }
        wrapPending = false
    }

    fun tab(count: Int = 1) {
        repeat(count) { cursorCol = ((cursorCol / 8) + 1) * 8 }
        if (cursorCol >= cols) cursorCol = cols - 1
        wrapPending = false
    }

    // ---- 光标 ----

    fun moveTo(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
        wrapPending = false
    }

    fun moveRelative(deltaRow: Int, deltaCol: Int) {
        moveTo((cursorRow + deltaRow).coerceIn(scrollTop, scrollBottom), cursorCol + deltaCol)
    }

    fun setColumn(col: Int) = moveTo(cursorRow, col)

    fun setRow(row: Int) = moveTo(row, cursorCol)

    fun saveCursor() {
        savedRow = cursorRow
        savedCol = cursorCol
        savedAttr = attr
    }

    fun restoreCursor() {
        cursorRow = savedRow.coerceIn(0, rows - 1)
        cursorCol = savedCol.coerceIn(0, cols - 1)
        attr = savedAttr
        wrapPending = false
    }

    // ---- 擦除 ----

    fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (r in cursorRow + 1 until rows) grid[r].forEach { it.blank(attr) }
            }
            1 -> {
                eraseInLine(1)
                for (r in 0 until cursorRow) grid[r].forEach { it.blank(attr) }
            }
            2 -> grid.forEach { row -> row.forEach { it.blank(attr) } }
            3 -> {
                grid.forEach { row -> row.forEach { it.blank(attr) } }
                scrollback.clear()
            }
        }
    }

    fun eraseInLine(mode: Int) {
        val row = grid[cursorRow]
        when (mode) {
            0 -> for (c in cursorCol until cols) row[c].blank(attr)
            1 -> for (c in 0..cursorCol) row[c].blank(attr)
            2 -> row.forEach { it.blank(attr) }
        }
    }

    fun eraseChars(count: Int) {
        val n = count.coerceAtLeast(1)
        for (c in cursorCol until minOf(cols, cursorCol + n)) grid[cursorRow][c].blank(attr)
    }

    // ---- 滚动 ----

    /** 上滚 n 行；仅在滚动区为整屏且非备用屏时写入回滚。 */
    fun scrollUp(count: Int) {
        val n = count.coerceAtLeast(1).coerceAtMost(scrollBottom - scrollTop + 1)
        repeat(n) {
            val top = grid[scrollTop]
            if (scrollTop == 0 && scrollBottom == rows - 1 && !altActive) pushScrollback(top)
            for (r in scrollTop until scrollBottom) {
                val tmp = grid[r]
                grid[r] = grid[r + 1]
                grid[r + 1] = tmp
            }
            grid[scrollBottom].forEach { it.blank(attr) }
        }
    }

    fun scrollDown(count: Int) {
        val n = count.coerceAtLeast(1).coerceAtMost(scrollBottom - scrollTop + 1)
        repeat(n) {
            val bottom = grid[scrollBottom]
            for (r in scrollBottom downTo scrollTop + 1) {
                val tmp = grid[r]
                grid[r] = grid[r - 1]
                grid[r - 1] = tmp
            }
            bottom.forEach { it.blank(attr) }
        }
    }

    private fun pushScrollback(line: Array<TerminalCell>) {
        if (scrollbackLimit <= 0) return
        val copy = Array(cols) { i -> TerminalCell().also { it.set(line[i].text, line[i].attr, line[i].continuation) } }
        scrollback.addLast(copy)
        while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
    }

    fun setScrollRegion(top: Int, bottom: Int) {
        val t = top.coerceIn(0, rows - 1)
        val b = bottom.coerceIn(0, rows - 1)
        if (t < b) {
            scrollTop = t
            scrollBottom = b
            moveTo(t, 0)
        }
    }

    fun resetScrollRegion() {
        scrollTop = 0
        scrollBottom = rows - 1
    }

    // ---- 行/字符插入删除 ----

    fun insertLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val n = count.coerceAtLeast(1)
        repeat(n) {
            val saved = grid[scrollBottom]
            for (r in scrollBottom downTo cursorRow + 1) {
                grid[r] = grid[r - 1]
            }
            grid[cursorRow] = saved
            saved.forEach { it.blank(attr) }
        }
    }

    fun deleteLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val n = count.coerceAtLeast(1)
        repeat(n) {
            val saved = grid[cursorRow]
            for (r in cursorRow until scrollBottom) grid[r] = grid[r + 1]
            grid[scrollBottom] = saved
            saved.forEach { it.blank(attr) }
        }
    }

    fun deleteChars(count: Int) {
        val row = grid[cursorRow]
        val n = count.coerceAtLeast(1).coerceAtMost(cols - cursorCol)
        for (c in cursorCol until cols - n) {
            row[c].set(row[c + n].text, row[c + n].attr, row[c + n].continuation)
        }
        for (c in cols - n until cols) row[c].blank(attr)
    }

    fun insertChars(count: Int) {
        val row = grid[cursorRow]
        val n = count.coerceAtLeast(1).coerceAtMost(cols - cursorCol)
        for (c in cols - 1 downTo cursorCol + n) {
            row[c].set(row[c - n].text, row[c - n].attr, row[c - n].continuation)
        }
        for (c in cursorCol until cursorCol + n) row[c].blank(attr)
    }

    // ---- 模式 ----

    fun setPrivateMode(mode: Int, enabled: Boolean) {
        when (mode) {
            7 -> autoWrap = enabled
            25 -> cursorVisible = enabled
            47, 1047, 1049 -> switchAltScreen(enabled)
            else -> Unit
        }
    }

    private fun switchAltScreen(enabled: Boolean) {
        if (enabled == altActive) return
        if (enabled) {
            altCursorRow = cursorRow
            altCursorCol = cursorCol
            altGrid = grid
            grid = newGrid()
            cursorRow = 0
            cursorCol = 0
        } else {
            val restore = altGrid
            grid = restore ?: newGrid()
            altGrid = null
            cursorRow = altCursorRow.coerceIn(0, rows - 1)
            cursorCol = altCursorCol.coerceIn(0, cols - 1)
        }
        altActive = enabled
        resetScrollRegion()
        wrapPending = false
    }

    /** SGR：只改当前属性，不影响已写入内容。 */
    fun applySgr(params: List<Int>) {
        if (params.isEmpty()) {
            attr = TerminalAttr()
            return
        }
        var i = 0
        while (i < params.size) {
            when (val p = params[i]) {
                0 -> attr = TerminalAttr()
                1 -> attr = attr.copy(bold = true)
                3 -> attr = attr.copy(italic = true)
                4 -> attr = attr.copy(underline = true)
                7 -> attr = attr.copy(inverse = true)
                22 -> attr = attr.copy(bold = false)
                23 -> attr = attr.copy(italic = false)
                24 -> attr = attr.copy(underline = false)
                27 -> attr = attr.copy(inverse = false)
                39 -> attr = attr.copy(fg = DEFAULT_FG)
                49 -> attr = attr.copy(bg = DEFAULT_BG)
                in 30..37 -> attr = attr.copy(fg = TerminalPalette.color256(p - 30))
                in 90..97 -> attr = attr.copy(fg = TerminalPalette.color256(p - 90 + 8))
                in 40..47 -> attr = attr.copy(bg = TerminalPalette.color256(p - 40))
                in 100..107 -> attr = attr.copy(bg = TerminalPalette.color256(p - 100 + 8))
                38, 48 -> {
                    val isFg = p == 38
                    when {
                        i + 2 < params.size && params[i + 1] == 5 -> {
                            val color = TerminalPalette.color256(params[i + 2])
                            attr = if (isFg) attr.copy(fg = color) else attr.copy(bg = color)
                            i += 2
                        }
                        i + 4 < params.size && params[i + 1] == 2 -> {
                            val color = TerminalPalette.rgb(params[i + 2], params[i + 3], params[i + 4])
                            attr = if (isFg) attr.copy(fg = color) else attr.copy(bg = color)
                            i += 4
                        }
                        else -> Unit
                    }
                }
                else -> Unit
            }
            i++
        }
    }

    fun reset() {
        grid = newGrid()
        attr = TerminalAttr()
        cursorRow = 0
        cursorCol = 0
        cursorVisible = true
        autoWrap = true
        resetScrollRegion()
        scrollback.clear()
        altActive = false
        altGrid = null
        wrapPending = false
    }
}
