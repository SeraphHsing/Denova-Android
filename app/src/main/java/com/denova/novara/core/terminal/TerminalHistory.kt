package com.denova.novara.core.terminal

/**
 * 终端历史快照（G6 决策 B）。
 *
 * 背景：Web 端挂载工作台会把 `attached=0` 的会话当孤儿回收，Android 的 PTY 与 scrollback
 * 会一起消失。既然无法阻止对方删除，就在断开/被释放的那一刻把最近的屏幕内容落到本地，
 * 把「内容彻底丢失」降级为「只读可回看」。
 *
 * 只保留纯文本（不含颜色/光标），并按字节上限截断：终端里最值钱的是命令与输出本身，
 * 重放样式没有收益，反而让文件变大。
 */
object TerminalHistory {

    /** 保留上限：约 64KB（冻结口径，够放数千行命令输出）。 */
    const val MAX_BYTES = 64 * 1024

    /**
     * 从当前屏幕（含回滚）生成快照文本。
     *
     * 行序：回滚（最旧）→ 主屏（最新）。超出 [maxBytes] 时**从最旧的行开始丢**，
     * 且只按整行裁剪，避免把多字节字符或转义残片截断成乱码。
     */
    fun build(screen: TerminalScreen, maxBytes: Int = MAX_BYTES): String {
        val lines = ArrayList<String>(screen.scrollbackSize + screen.rows)
        for (i in 0 until screen.scrollbackSize) {
            lines += renderLine(screen.scrollbackRow(i))
        }
        val grid = screen.rowsView()
        for (r in grid.indices) {
            lines += renderLine(grid[r])
        }
        // 去掉尾部连续空行（提示符后的空白区没有信息量）
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)

        var total = 0
        var start = lines.size
        for (i in lines.indices.reversed()) {
            val size = lines[i].toByteArray(Charsets.UTF_8).size + 1
            if (total + size > maxBytes) break
            total += size
            start = i
        }
        return lines.subList(start, lines.size).joinToString("\n")
    }

    private fun renderLine(cells: Array<TerminalCell>): String {
        val sb = StringBuilder(cells.size)
        for (cell in cells) {
            if (cell.continuation) continue
            sb.append(cell.text)
        }
        return sb.toString().trimEnd()
    }
}
