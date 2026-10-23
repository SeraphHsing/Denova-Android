package com.denova.novara.core.editor

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.renderer.text.TextContentRenderer

/**
 * Markdown 源文档：源文本为权威可编辑形态，parse 得到 AST 用于校验与渲染。
 * 序列化 = parse(source) → AST → toHtml()；源文本即规范形态（对应 Web 的 markdownSourceDocument）。
 */
class MarkdownDocument private constructor(
    val source: String,
    private val root: Node,
) {

    fun toHtml(): String = renderer.render(root)

    fun toPlainText(): String = textRenderer.render(root)

    companion object {
        private val extensions = listOf(TablesExtension.create())

        private val parser: Parser = Parser.builder()
            .extensions(extensions)
            .build()

        private val renderer: HtmlRenderer = HtmlRenderer.builder()
            .extensions(extensions)
            .build()

        private val textRenderer: TextContentRenderer = TextContentRenderer.builder()
            .extensions(extensions)
            .build()

        fun parse(source: String): MarkdownDocument =
            MarkdownDocument(source, parser.parse(source))
    }
}
