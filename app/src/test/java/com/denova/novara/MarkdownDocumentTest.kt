package com.denova.novara

import com.denova.novara.core.editor.MarkdownDocument
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDocumentTest {

    @Test
    fun `parses headings`() {
        val html = MarkdownDocument.parse("# 标题一\n\n## 标题二").toHtml()
        assertTrue(html.contains("<h1>标题一</h1>"))
        assertTrue(html.contains("<h2>标题二</h2>"))
    }

    @Test
    fun `parses emphasis and strong`() {
        val html = MarkdownDocument.parse("这是 **加粗** 和 *斜体*。").toHtml()
        assertTrue(html.contains("<strong>加粗</strong>"))
        assertTrue(html.contains("<em>斜体</em>"))
    }

    @Test
    fun `parses ordered and unordered lists`() {
        val html = MarkdownDocument.parse("- 甲\n- 乙\n\n1. 一\n2. 二").toHtml()
        assertTrue(html.contains("<ul>"))
        assertTrue(html.contains("<ol>"))
        assertTrue(html.contains("<li>甲</li>"))
    }

    @Test
    fun `parses code block and inline code`() {
        val html = MarkdownDocument.parse("行内 `code`。\n\n```\nval x = 1\n```").toHtml()
        assertTrue(html.contains("<code>code</code>"))
        assertTrue(html.contains("<pre><code>val x = 1\n</code></pre>"))
    }

    @Test
    fun `parses blockquote`() {
        val html = MarkdownDocument.parse("> 引用内容").toHtml()
        assertTrue(html.contains("<blockquote>"))
    }

    @Test
    fun `parses image`() {
        val html = MarkdownDocument.parse("![alt](https://example.com/a.png)").toHtml()
        assertTrue(html.contains("<img src=\"https://example.com/a.png\" alt=\"alt\""))
    }

    @Test
    fun `parses gfm table`() {
        val html = MarkdownDocument.parse("| 列1 | 列2 |\n| --- | --- |\n| a | b |").toHtml()
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<th>列1</th>"))
        assertTrue(html.contains("<td>a</td>"))
    }

    @Test
    fun `plain text strips markdown`() {
        val text = MarkdownDocument.parse("# 标题\n\n正文 **加粗**").toPlainText()
        assertTrue(text.contains("标题"))
        assertTrue(text.contains("加粗"))
        assertTrue(!text.contains("#"))
    }
}
