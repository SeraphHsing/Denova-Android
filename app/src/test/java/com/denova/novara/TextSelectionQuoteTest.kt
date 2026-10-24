package com.denova.novara

import com.denova.novara.feature.editor.TextSelectionQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** WRI-009 选区引用：行号、内容与文案。 */
class TextSelectionQuoteTest {

    private val doc = "# 标题\n\n第一行\n第二行\n第三行"

    @Test
    fun `lineAt is one based and counts newlines`() {
        assertEquals(1, TextSelectionQuote.lineAt(doc, 0))
        assertEquals(1, TextSelectionQuote.lineAt(doc, 4))   // 仍在标题行
        assertEquals(2, TextSelectionQuote.lineAt(doc, 5))   // 换行之后
        assertEquals(3, TextSelectionQuote.lineAt(doc, 7))   // 第一行内容
    }

    @Test
    fun `lineAt clamps out of range offsets`() {
        assertEquals(1, TextSelectionQuote.lineAt(doc, -3))
        assertEquals(5, TextSelectionQuote.lineAt(doc, doc.length + 100))
    }

    @Test
    fun `selection inside one line keeps start equal end`() {
        val start = doc.indexOf("第一行")
        val ref = TextSelectionQuote.of("chapters/001.md", doc, start, start + 3)!!
        assertEquals("chapters/001.md", ref.fileName)
        assertEquals(3, ref.startLine)
        assertEquals(3, ref.endLine)
        assertEquals("第一行", ref.content)
    }

    @Test
    fun `selection spanning lines reports both line numbers`() {
        val start = doc.indexOf("第一行")
        val end = doc.indexOf("第三行") + 3
        val ref = TextSelectionQuote.of("001.md", doc, start, end)!!
        assertEquals(3, ref.startLine)
        assertEquals(5, ref.endLine)
        assertEquals("第一行\n第二行\n第三行", ref.content)
    }

    @Test
    fun `reversed selection is normalised`() {
        val start = doc.indexOf("第二行")
        val end = start + 3
        val ref = TextSelectionQuote.of("001.md", doc, end, start)!!
        assertEquals("第二行", ref.content)
        assertEquals(4, ref.startLine)
    }

    @Test
    fun `blank or empty selection yields no reference`() {
        assertNull(TextSelectionQuote.of("001.md", doc, 3, 3))
        assertNull(TextSelectionQuote.of("001.md", doc, 4, 5))   // 只选中一个换行
        assertNull(TextSelectionQuote.of("", doc, 0, 3))
    }

    @Test
    fun `label and excerpt follow web format`() {
        val single = TextSelectionQuote.of("001.md", doc, 6, 9)!!
        assertEquals("001.md:L3", TextSelectionQuote.label(single))
        val multi = TextSelectionQuote.of("001.md", doc, 6, doc.length)!!
        assertEquals("001.md:L3-L5", TextSelectionQuote.label(multi))
        val long = TextSelectionQuote.of("a.md", "x".repeat(40), 0, 40)!!
        assertEquals(31, TextSelectionQuote.excerpt(long).length)   // 30 字 + 省略号
    }
}
