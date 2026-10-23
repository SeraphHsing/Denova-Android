package com.denova.novara

import com.denova.novara.core.editor.InlineStyle
import com.denova.novara.core.editor.parseInlineSpans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownInlineTest {

    @Test
    fun `parses bold and italic`() {
        val spans = parseInlineSpans("**粗体** 和 *斜体*")
        val bold = spans.first { it.style == InlineStyle.BOLD }
        val italic = spans.first { it.style == InlineStyle.ITALIC }
        assertEquals("粗体", bold.let { "**粗体**".substring(it.contentStart, it.contentEnd) })
        assertEquals("斜体", "**粗体** 和 *斜体*".substring(italic.contentStart, italic.contentEnd))
    }

    @Test
    fun `parses inline code and strike`() {
        val spans = parseInlineSpans("行内 `code` 和 ~~删除~~")
        val code = spans.first { it.style == InlineStyle.CODE }
        val strike = spans.first { it.style == InlineStyle.STRIKETHROUGH }
        assertEquals("code", "行内 `code` 和 ~~删除~~".substring(code.contentStart, code.contentEnd))
        assertEquals("删除", "行内 `code` 和 ~~删除~~".substring(strike.contentStart, strike.contentEnd))
    }

    @Test
    fun `delimiter length is correct`() {
        val spans = parseInlineSpans("**a** *b* `c` ~~d~~")
        assertEquals(2, spans.first { it.style == InlineStyle.BOLD }.delimiterLen)
        assertEquals(1, spans.first { it.style == InlineStyle.ITALIC }.delimiterLen)
        assertEquals(1, spans.first { it.style == InlineStyle.CODE }.delimiterLen)
        assertEquals(2, spans.first { it.style == InlineStyle.STRIKETHROUGH }.delimiterLen)
    }

    @Test
    fun `no spans for plain text`() {
        assertTrue(parseInlineSpans("普通文本没有标记").isEmpty())
    }
}
