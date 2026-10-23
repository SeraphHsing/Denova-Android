package com.denova.novara.feature.editor

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.denova.novara.core.editor.MarkdownDocument

private const val SAMPLE = """# 第一章

这是一段 **加粗** 和 *斜体* 的正文，含行内 `代码` 与 ~~删除线~~。

## 小节

- 列表项甲
- 列表项乙

1. 有序一
2. 有序二

| 列1 | 列2 |
| --- | --- |
| a   | b   |

> 引用块

```kotlin
val x = 1
```

![示例](https://example.com/img.png)
"""

private enum class EditorMode { SOURCE, RICH, PREVIEW }

/**
 * Markdown 编辑器（G2）：源码 / 富文本（隐藏标记 + 内联样式 + 格式化工具栏）/ 预览 三模式。
 */
@Composable
fun EditorScreen(onLogout: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(SAMPLE)) }
    var mode by remember { mutableStateOf(EditorMode.SOURCE) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // 顶栏
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
                "Novara 编辑器",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(8.dp),
            )
            TextButton(onClick = onLogout) { Text("退出") }
        }

        // 模式切换
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            ModeButton("源码", mode == EditorMode.SOURCE) { mode = EditorMode.SOURCE }
            ModeButton("富文本", mode == EditorMode.RICH) { mode = EditorMode.RICH }
            ModeButton("预览", mode == EditorMode.PREVIEW) { mode = EditorMode.PREVIEW }
        }

        // 富文本格式化工具栏
        if (mode == EditorMode.RICH) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                FormatButton("B") { value = wrapSelection(value, "**") }
                FormatButton("I") { value = wrapSelection(value, "*") }
                FormatButton("</>") { value = wrapSelection(value, "`") }
                FormatButton("S") { value = wrapSelection(value, "~~") }
            }
        }

        when (mode) {
            EditorMode.SOURCE -> BasicTextField(
                value = value,
                onValueChange = { value = it },
                textStyle = TextStyle(fontSize = 16.sp),
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
            EditorMode.RICH -> BasicTextField(
                value = value,
                onValueChange = { value = it },
                visualTransformation = MarkdownVisualTransformation(),
                textStyle = TextStyle(fontSize = 16.sp),
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
            EditorMode.PREVIEW -> MarkdownPreview(
                source = value.text,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(end = 8.dp)) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun FormatButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(end = 8.dp)) {
        Text(label, style = TextStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
    }
}

/** 用分隔符包裹当前选区；选区为空则在分隔符中间插入光标。 */
private fun wrapSelection(value: TextFieldValue, delimiter: String): TextFieldValue {
    val sel = value.selection
    val text = value.text
    val selected = text.substring(sel.min, sel.max)
    val newText = text.substring(0, sel.min) + delimiter + selected + delimiter + text.substring(sel.max)
    val newStart = sel.min + delimiter.length
    val newEnd = newStart + selected.length
    return TextFieldValue(newText, TextRange(newStart, newEnd))
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MarkdownPreview(source: String, modifier: Modifier = Modifier) {
    val html = remember(source) { buildPreviewHtml(source) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        update = { it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
    )
}

private fun buildPreviewHtml(markdown: String): String {
    val body = MarkdownDocument.parse(markdown).toHtml()
    return """
        <html><head><meta charset="utf-8">
        <style>
        body { font-family: sans-serif; padding: 16px; line-height: 1.6; }
        table { border-collapse: collapse; }
        th, td { border: 1px solid #ccc; padding: 6px; }
        img { max-width: 100%; }
        pre { background: #f5f5f5; padding: 8px; overflow-x: auto; }
        blockquote { border-left: 3px solid #ccc; margin: 0; padding-left: 12px; color: #555; }
        </style></head>
        <body>$body</body></html>
    """.trimIndent()
}
