package com.denova.novara.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import com.denova.novara.core.editor.InlineStyle
import com.denova.novara.core.editor.parseInlineSpans

/**
 * 富文本视觉变换：把 Markdown 标记（`**` `*` `` ` `` `~~`）渲染为内联样式并隐藏标记本身。
 * 标记字符逐字符替换为零宽字符，保证长度与源文本一致，因此偏移映射为恒等，光标/选区天然对齐。
 */
class MarkdownVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = buildStyled(text.text)
        return TransformedText(styled, OffsetMapping.Identity)
    }

    private fun buildStyled(source: String): AnnotatedString {
        val spans = parseInlineSpans(source)
        if (spans.isEmpty()) return AnnotatedString(source)

        val builder = AnnotatedString.Builder()
        var cursor = 0
        for (span in spans) {
            val openStart = span.contentStart - span.delimiterLen
            val closeEnd = span.contentEnd + span.delimiterLen
            if (openStart < cursor) continue // 跳过重叠（正则不重叠，防御性）
            // 前缀
            builder.append(source.substring(cursor, openStart))
            // 起始标记 → 零宽
            appendZeroWidth(builder, span.delimiterLen)
            // 内容 → 样式
            builder.pushStyle(span.style.toSpanStyle())
            builder.append(source.substring(span.contentStart, span.contentEnd))
            builder.pop()
            // 结束标记 → 零宽
            appendZeroWidth(builder, span.delimiterLen)
            cursor = closeEnd
        }
        builder.append(source.substring(cursor))
        return builder.toAnnotatedString()
    }

    private fun appendZeroWidth(builder: AnnotatedString.Builder, count: Int) {
        repeat(count) { builder.append('\u200B') }
    }
}

private fun InlineStyle.toSpanStyle(): SpanStyle = when (this) {
    InlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    InlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    InlineStyle.CODE -> SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = Color(0xFFEEEEEE),
    )
    InlineStyle.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
}
