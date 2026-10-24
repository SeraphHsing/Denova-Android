package com.denova.novara.feature.editor

import com.denova.novara.core.network.TextSelectionRef

/**
 * 正文选区 → 引用（WRI-009）。
 *
 * 对齐 Web `WritingDocumentEditor.quoteCurrentSelection`：用选区起止位置算出行号、
 * 取选中文本作为 `content`；后端把它渲染成 `kind=selection` 的用户引用
 * （label=文件名、detail=内容、start_line / end_line），因此行号必须与文件正文一致。
 *
 * 行号按 `\n` 计数，1 基；空选区或空白内容不产生引用（与 Web `text.trim()` 判定一致）。
 */
object TextSelectionQuote {

    /** 偏移所在行号（1 基）。偏移越界时按就近边界计算。 */
    fun lineAt(text: String, offset: Int): Int {
        if (offset <= 0) return 1
        val end = minOf(offset, text.length)
        var line = 1
        for (i in 0 until end) {
            if (text[i] == '\n') line++
        }
        return line
    }

    /**
     * 构造引用；选区为空、内容纯空白或文件名为空时返回 null（调用方不显示引用卡）。
     * [start] / [end] 会被归一到合法区间，适配 Compose 选区 start > end 的情况。
     */
    fun of(fileName: String, text: String, start: Int, end: Int): TextSelectionRef? {
        if (fileName.isBlank()) return null
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(0, text.length)
        if (from == to) return null
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        val content = text.substring(lo, hi)
        if (content.isBlank()) return null
        return TextSelectionRef(
            fileName = fileName,
            startLine = lineAt(text, lo),
            endLine = lineAt(text, hi),
            content = content,
        )
    }

    /** 引用卡文案：`file:Lx` 或 `file:Lx-Ly`（Web `InputArea` 同款）。 */
    fun label(ref: TextSelectionRef): String =
        if (ref.endLine == ref.startLine) {
            "${ref.fileName}:L${ref.startLine}"
        } else {
            "${ref.fileName}:L${ref.startLine}-L${ref.endLine}"
        }

    /** 引用卡里展示的内容摘要（Web：超 30 字截断加省略号）。 */
    fun excerpt(ref: TextSelectionRef, max: Int = 30): String =
        if (ref.content.length > max) ref.content.take(max) + "…" else ref.content
}
