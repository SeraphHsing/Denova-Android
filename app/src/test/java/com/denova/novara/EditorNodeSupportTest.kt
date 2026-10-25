package com.denova.novara

import com.denova.novara.core.editor.MarkdownDocument
import com.denova.novara.feature.editor.PreviewAssetLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G2-c 客户端侧可离线判定的部分：预览资源路径换算（WRI-007）、
 * 表格模板与横向滚动容器（WRI-006）、结构语料渲染（WRI-005）。
 */
class EditorNodeSupportTest {

    private val tableTemplate = "| 列1 | 列2 | 列3 |\n| --- | --- | --- |\n|  |  |  |\n"

    @Test
    fun `relative image path resolves against document directory`() {
        assertEquals("assets/a.png", PreviewAssetLoader.resolveAssetPath("chapter-01.md", "assets/a.png"))
        assertEquals("chapters/assets/a.png", PreviewAssetLoader.resolveAssetPath("chapters/01.md", "assets/a.png"))
        assertEquals("chapters/assets/a.png", PreviewAssetLoader.resolveAssetPath("chapters/01.md", "./assets/a.png"))
    }

    @Test
    fun `parent segments collapse and escaping the project root is rejected`() {
        assertEquals("assets/a.png", PreviewAssetLoader.resolveAssetPath("chapters/01.md", "../assets/a.png"))
        assertNull(PreviewAssetLoader.resolveAssetPath("01.md", "../outside.png"))
        assertNull(PreviewAssetLoader.resolveAssetPath("chapters/01.md", "../../outside.png"))
    }

    @Test
    fun `absolute and remote sources are not rewritten`() {
        // 前导斜杠按项目根处理；外链与 data URI 原样交给浏览器。
        assertEquals("assets/a.png", PreviewAssetLoader.resolveAssetPath("chapters/01.md", "/assets/a.png"))
        assertNull(PreviewAssetLoader.resolveAssetPath("01.md", "https://example.com/a.png"))
        assertNull(PreviewAssetLoader.resolveAssetPath("01.md", "data:image/png;base64,AAAA"))
        assertNull(PreviewAssetLoader.resolveAssetPath("01.md", "  "))
    }

    @Test
    fun `table template parses to a GFM table and is wrapped for horizontal scroll`() {
        val doc = MarkdownDocument.parse(tableTemplate)
        val html = PreviewAssetLoader.wrapTables(doc.toHtml())
        assertTrue("表格应渲染为 table 节点", html.contains("<table>"))
        assertTrue("表格必须被可滚动容器包裹", html.contains("<div class=\"table-scroll\"><table>"))
        assertTrue(html.contains("</table></div>"))
        assertEquals(1, "<div class=\"table-scroll\">".toRegex().findAll(html).count())
    }

    @Test
    fun `structural corpus renders headings lists quotes and code`() {
        val corpus = """
            # 标题

            - 列表项

            > 引用

            ```kotlin
            val a = 1
            ```
        """.trimIndent()
        val html = MarkdownDocument.parse(corpus).toHtml()
        assertTrue(html.contains("<h1>"))
        assertTrue(html.contains("<ul>"))
        assertTrue(html.contains("<blockquote>"))
        assertTrue(html.contains("<pre><code"))
    }

    @Test
    fun `relative path for inserted image round-trips through resolve`() {
        val rel = PreviewAssetLoader.relativeAssetPath("chapters/01.md", "assets/redpanda.png")
        assertEquals("../assets/redpanda.png", rel)
        assertEquals(
            "assets/redpanda.png",
            PreviewAssetLoader.resolveAssetPath("chapters/01.md", rel),
        )
        assertEquals("assets/redpanda.png", PreviewAssetLoader.relativeAssetPath("01.md", "assets/redpanda.png"))
        assertEquals("d.png", PreviewAssetLoader.relativeAssetPath("a/b/c.md", "a/b/d.png"))
        assertEquals(
            "a/b/d.png",
            PreviewAssetLoader.resolveAssetPath("a/b/c.md", "d.png"),
        )
    }

    @Test
    fun `image html keeps alt text so failed loads stay readable`() {
        val html = MarkdownDocument.parse("![封面](assets/cover.png)").toHtml()
        assertTrue(html.contains("<img"))
        assertTrue(html.contains("alt=\"封面\""))
        assertTrue(html.contains("src=\"assets/cover.png\""))
    }
}
