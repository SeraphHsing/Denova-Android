package com.denova.novara.core.editor

enum class InlineStyle { BOLD, ITALIC, CODE, STRIKETHROUGH }

/** 一个内联标记 span：content 范围 + 分隔符长度 + 样式。全文范围含分隔符 = [contentStart-delim, contentEnd+delim]。 */
data class InlineSpan(
    val contentStart: Int,
    val contentEnd: Int,
    val delimiterLen: Int,
    val style: InlineStyle,
)

private val INLINE_REGEX = Regex(
    """\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~|`([^`\n]+)`|\*([^*]+)\*|_([^_]+)_""",
)

/** 解析内联 Markdown（粗体/斜体/行内代码/删除线），返回不重叠的 span 列表。 */
fun parseInlineSpans(source: String): List<InlineSpan> {
    val spans = mutableListOf<InlineSpan>()
    for (m in INLINE_REGEX.findAll(source)) {
        val start = m.range.first
        val end = m.range.last + 1
        val (style, delim) = when {
            m.groups[1] != null || m.groups[2] != null -> InlineStyle.BOLD to 2
            m.groups[3] != null -> InlineStyle.STRIKETHROUGH to 2
            m.groups[4] != null -> InlineStyle.CODE to 1
            else -> InlineStyle.ITALIC to 1
        }
        spans.add(InlineSpan(start + delim, end - delim, delim, style))
    }
    return spans
}
