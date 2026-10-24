package com.denova.novara.core.terminal

/**
 * 可视窗口映射：把「回滚 + 主屏」拼成一条逻辑行序列，按相对底部的 [offset] 取 rows 行。
 *
 * 渲染层只认这条映射，因此滚动回看不需要复制整屏内容；
 * 备用屏（全屏 TUI，如 vim / less）没有回滚语义，[maxScrollOffset] 恒为 0。
 */
val TerminalScreen.maxScrollOffset: Int
    get() = if (altScreenActive) 0 else scrollbackSize

/**
 * 取可视窗口第 [viewRow] 行（0 基，由上到下）。
 *
 * [offset] = 0 表示贴底跟随输出；`offset = n` 表示整体上移 n 行（看到更早的历史）。
 * [offset] 超出 [maxScrollOffset] 时按上限收敛，不会越界取行。
 */
fun TerminalScreen.lineAt(viewRow: Int, offset: Int): Array<TerminalCell> {
    val history = maxScrollOffset
    val shift = offset.coerceIn(0, history)
    val index = history + viewRow - shift
    return if (index < history) scrollbackRow(index) else rowsView()[index - history]
}

/**
 * 一行单元格按 SGR 属性归并出的绘制片段。
 *
 * [startCol] / [spanCols] 以单元格为单位，宽字（CJK）占 2 格但只产出 1 个字形。
 */
data class TerminalRun(
    val startCol: Int,
    val spanCols: Int,
    val text: String,
    val attr: TerminalAttr,
)

/**
 * 把一行归并成绘制片段：属性相同的连续格合成一个 run，减少 drawText 次数。
 *
 * 纯逻辑（无 Compose 依赖），便于 JVM 单测覆盖 CJK 宽字与属性切换。
 * 默认背景的空白 run 直接丢弃——它们不需要绘制，长滚动历史里这类行占多数。
 */
fun buildRowRuns(row: Array<TerminalCell>, cols: Int): List<TerminalRun> {
    val runs = ArrayList<TerminalRun>(cols / 2 + 1)
    var col = 0
    while (col < cols) {
        val cell = row.getOrNull(col) ?: break
        if (cell.continuation) {
            col++
            continue
        }
        val attr = cell.attr
        val startCol = col
        val text = StringBuilder()
        while (col < cols) {
            val current = row.getOrNull(col) ?: break
            if (current.continuation || current.attr != attr) break
            text.append(current.text)
            val wide = col + 1 < cols && row[col + 1].continuation
            col += if (wide) 2 else 1
        }
        val spanCols = col - startCol
        val blank = text.isBlank()
        if (blank && attr.bg == DEFAULT_BG && !attr.inverse) continue
        runs += TerminalRun(startCol, spanCols, text.toString(), attr)
    }
    return runs
}
