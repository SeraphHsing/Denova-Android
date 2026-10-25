package com.denova.novara.core.editor

/**
 * GFM 表格的行列操作（WRI-006 的行列选择对应能力）。
 *
 * 固定 Web 基线用 TipTap `TableKit` 把表格当节点编辑；Android 侧正文是 Markdown 源文本，
 * 因此这里把「表格节点操作」实现为对源文本的确定性改写：定位光标所在表格 → 增删行列 →
 * 返回新正文与光标位置。全部是纯函数，便于单测覆盖边界（首行 / 末列 / 只剩表头）。
 */
object MarkdownTable {

    /** 改写结果：新正文与建议光标位置。 */
    data class Edit(val text: String, val caret: Int)

    /** 光标所在的表格块（行号含表头与分隔行）。 */
    data class Block(
        val startLine: Int,
        val endLine: Int,
        val row: Int,
        val cell: Int,
        val columnCount: Int,
    )

    /** 表格行的单元格内容（去掉首尾竖线与空白）。 */
    fun cellsOf(line: String): List<String> {
        val trimmed = line.trim().trimStart('|').trimEnd('|')
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split('|').map { it.trim() }
    }

    private fun isRow(line: String) = line.trimStart().startsWith("|")

    private fun isSeparator(line: String): Boolean {
        val cells = cellsOf(line)
        if (cells.isEmpty()) return false
        return cells.all { cell -> cell.isNotEmpty() && cell.all { it == '-' || it == ':' } && cell.contains('-') }
    }

    /** 光标是否落在表格里（含表头与分隔行）。 */
    fun locate(text: String, caret: Int): Block? {
        val lines = text.split('\n')
        val offsets = lineOffsets(text)
        val index = offsets.indexOfLast { it <= caret }.coerceAtLeast(0)
        val line = lines.getOrNull(index) ?: return null
        if (!isRow(line)) return null

        var start = index
        while (start > 0 && isRow(lines[start - 1])) start--
        var end = index
        while (end < lines.size - 1 && isRow(lines[end + 1])) end++
        // 至少要有表头 + 分隔行，否则不是 GFM 表格。
        if (end - start < 1) return null
        if (!isSeparator(lines[start + 1])) return null

        val columnCount = cellsOf(lines[start]).size
        val rowOffset = index - start
        val cellIndex = cellIndexAt(line, caret - offsets[index])
        return Block(start, end, rowOffset, cellIndex, columnCount)
    }

    /** 光标在本行第几个单元格（0 起）：数光标之前的竖线，首个竖线属于行首边框。 */
    fun cellIndexAt(line: String, column: Int): Int {
        var pipes = 0
        for (i in 0 until column.coerceAtMost(line.length)) {
            if (line[i] == '|') pipes++
        }
        return (pipes - 1).coerceAtLeast(0)
    }

    private fun lineOffsets(text: String): List<Int> {
        val list = mutableListOf(0)
        text.forEachIndexed { i, c -> if (c == '\n') list += i + 1 }
        return list
    }

    private fun renderCells(cells: List<String>): String = "| " + cells.joinToString(" | ") + " |"

    private fun blankRow(columns: Int) = renderCells(List(columns) { "" })

    /** 在光标所在行上方/下方插入一行（列数与表格一致）。 */
    fun insertRow(text: String, caret: Int, below: Boolean): Edit? {
        val block = locate(text, caret) ?: return null
        val lines = text.split('\n').toMutableList()
        val insertAt = if (below) block.row + 1 else block.row
        // 表头上一行会被 GFM 当成新表头，插在表头前没有意义：落到分隔行之后。
        val target = (block.startLine + insertAt).coerceAtLeast(block.startLine + 2)
        lines.add(target, blankRow(block.columnCount))
        val newText = lines.joinToString("\n")
        val caretInNewRow = lineOffsets(newText)[target] + 2
        return Edit(newText, caretInNewRow)
    }

    /** 删除光标所在行；删掉表体最后一行后只剩表头时，整张表一并删除。 */
    fun deleteRow(text: String, caret: Int): Edit? {
        val block = locate(text, caret) ?: return null
        val lines = text.split('\n').toMutableList()
        val index = block.startLine + block.row
        val bodyRows = block.endLine - (block.startLine + 1)
        if (bodyRows <= 1) return deleteTable(text, caret)
        lines.removeAt(index)
        val newText = lines.joinToString("\n")
        val caretAt = lineOffsets(newText).getOrElse(block.startLine) { 0 } + 2
        return Edit(newText, caretAt)
    }

    /** 在光标所在单元格左/右侧插入一列：表头补空标题、分隔行补 `---`、表体补空单元格。 */
    fun insertColumn(text: String, caret: Int, right: Boolean): Edit? {
        val block = locate(text, caret) ?: return null
        val lines = text.split('\n').toMutableList()
        val insertAt = (block.cell + if (right) 1 else 0).coerceIn(0, block.columnCount)
        for (lineIndex in block.startLine..block.endLine) {
            val cells = cellsOf(lines[lineIndex]).toMutableList()
            while (cells.size < block.columnCount) cells += ""
            cells.add(insertAt, if (lineIndex == block.startLine + 1) "---" else "")
            lines[lineIndex] = renderCells(cells)
        }
        val newText = lines.joinToString("\n")
        val caretAt = lineOffsets(newText)[block.startLine + block.row] + 2
        return Edit(newText, caretAt)
    }

    /** 删除光标所在列；最后一列被删时整张表删除。 */
    fun deleteColumn(text: String, caret: Int): Edit? {
        val block = locate(text, caret) ?: return null
        if (block.columnCount <= 1) return deleteTable(text, caret)
        val lines = text.split('\n').toMutableList()
        for (lineIndex in block.startLine..block.endLine) {
            val cells = cellsOf(lines[lineIndex]).toMutableList()
            if (block.cell < cells.size) cells.removeAt(block.cell)
            lines[lineIndex] = renderCells(cells)
        }
        val newText = lines.joinToString("\n")
        val caretAt = lineOffsets(newText)[block.startLine + block.row] + 2
        return Edit(newText, caretAt)
    }

    /** 删除整个表格块。 */
    fun deleteTable(text: String, caret: Int): Edit? {
        val block = locate(text, caret) ?: return null
        val lines = text.split('\n').toMutableList()
        repeat(block.endLine - block.startLine + 1) { lines.removeAt(block.startLine) }
        val newText = lines.joinToString("\n")
        val caretAt = lineOffsets(newText).getOrElse(block.startLine) { newText.length }
            .coerceAtMost(newText.length)
        return Edit(newText, caretAt)
    }

    /**
     * Tab 在单元格之间移动（对齐 TipTap 表格的默认行为）：
     * 段内下一格的末尾；本行最后一格则跳到下一行首格；表格最后一格则新增一行并把光标放进去。
     */
    fun nextCell(text: String, caret: Int): Edit? {
        val block = locate(text, caret) ?: return null
        val lines = text.split('\n')
        val line = lines[block.startLine + block.row]
        val cellCount = cellsOf(line).size

        if (block.cell + 1 < cellCount) {
            val target = cellStartOffset(line, block.cell + 1)
            if (target >= 0) {
                return Edit(text, lineOffsets(text)[block.startLine + block.row] + target)
            }
        }
        // 本行最后一格 → 下一行首格
        if (block.row < block.endLine - block.startLine) {
            val nextLine = lines[block.startLine + block.row + 1]
            val target = cellStartOffset(nextLine, 0)
            if (target >= 0) {
                return Edit(text, lineOffsets(text)[block.startLine + block.row + 1] + target)
            }
        }
        // 表格最后一格 → 追加一行
        return insertRow(text, caret, below = true)
    }

    /** 第 index 个单元格正文的起点偏移（跳过 `| ` 与前面的单元格）。 */
    private fun cellStartOffset(line: String, index: Int): Int {
        var seen = -1
        for (i in line.indices) {
            if (line[i] == '|') {
                seen++
                if (seen == index) {
                    var pos = i + 1
                    while (pos < line.length && line[pos] == ' ') pos++
                    return pos
                }
            }
        }
        return -1
    }
}
