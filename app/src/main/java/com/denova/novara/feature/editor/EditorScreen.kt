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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.denova.novara.core.editor.MarkdownDocument

private const val SAMPLE = """# 第一章

这是一段 **加粗** 和 *斜体* 的正文，含行内 `代码`。

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

/**
 * Markdown 编辑器（G2 首轮）：源码模式（可编辑 Markdown）+ 预览（WebView 渲染 HTML）。
 * 富文本模式与原生渲染在后续迭代接入。
 */
@Composable
fun EditorScreen(onLogout: () -> Unit) {
    var source by remember { mutableStateOf(SAMPLE) }
    var preview by remember { mutableStateOf(false) }

    // Android 15+ 强制 edge-to-edge，顶栏必须让出状态栏，否则按钮被状态栏拦截点不到。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("Novara 编辑器", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
            TextButton(onClick = { preview = !preview }) {
                Text(if (preview) "源码" else "预览")
            }
            TextButton(onClick = onLogout) {
                Text("退出")
            }
        }

        if (preview) {
            MarkdownPreview(source = source, modifier = Modifier.weight(1f))
        } else {
            OutlinedTextField(
                value = source,
                onValueChange = { source = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
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
