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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.denova.novara.core.editor.MarkdownDocument

private enum class EditorMode { SOURCE, RICH, PREVIEW }

/**
 * Markdown 编辑器（G2/G3）：打开文件 → 源码/富文本/预览三模式 → 防抖自动保存 + 冲突提示。
 */
@Composable
fun EditorScreen(viewModel: EditorViewModel, onLogout: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var mode by remember { mutableStateOf(EditorMode.SOURCE) }
    var projectId by remember { mutableStateOf("") }
    var filePath by remember { mutableStateOf("") }
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }

    // 外部内容变化（打开文件/冲突合并）时重置本地编辑态。
    LaunchedEffect(uiState.loaded, uiState.filePath, uiState.conflictText) {
        android.util.Log.d("EditorScreen", "sync: loaded=${uiState.loaded} contentLen=${uiState.content.length}")
        textFieldValue = TextFieldValue(uiState.content)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("Novara 编辑器", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
            TextButton(onClick = { viewModel.save() }) { Text("保存") }
            TextButton(onClick = onLogout) { Text("退出") }
        }

        // 打开文件
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = projectId,
                onValueChange = { projectId = it },
                label = { Text("项目ID") },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            OutlinedTextField(
                value = filePath,
                onValueChange = { filePath = it },
                label = { Text("文件路径") },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            OutlinedButton(onClick = { viewModel.openFile(projectId.trim(), filePath.trim()) }) { Text("打开") }
        }

        // 保存状态
        val status = when (uiState.saveStatus) {
            SaveStatus.IDLE -> if (uiState.loaded) "" else "输入项目 ID 与文件路径后打开"
            SaveStatus.SAVING -> "保存中…"
            SaveStatus.SAVED -> "已保存"
            SaveStatus.MERGED -> "已自动合并保存"
            SaveStatus.CONFLICT -> "冲突"
            SaveStatus.ERROR -> "错误"
        }
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
        }
        uiState.message?.let {
            Text(
                it,
                color = if (uiState.saveStatus == SaveStatus.CONFLICT || uiState.saveStatus == SaveStatus.ERROR)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        if (uiState.saveStatus == SaveStatus.CONFLICT) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(
                    "发现冲突，请在下方编辑解决冲突标记",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        // 模式切换
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            ModeButton("源码", mode == EditorMode.SOURCE) { mode = EditorMode.SOURCE }
            ModeButton("富文本", mode == EditorMode.RICH) { mode = EditorMode.RICH }
            ModeButton("预览", mode == EditorMode.PREVIEW) { mode = EditorMode.PREVIEW }
        }

        if (mode == EditorMode.RICH) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                FormatButton("B") { textFieldValue = wrapSelection(textFieldValue, "**"); viewModel.updateContent(textFieldValue.text) }
                FormatButton("I") { textFieldValue = wrapSelection(textFieldValue, "*"); viewModel.updateContent(textFieldValue.text) }
                FormatButton("</>") { textFieldValue = wrapSelection(textFieldValue, "`"); viewModel.updateContent(textFieldValue.text) }
                FormatButton("S") { textFieldValue = wrapSelection(textFieldValue, "~~"); viewModel.updateContent(textFieldValue.text) }
            }
        }

        when (mode) {
            EditorMode.SOURCE -> BasicTextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it; viewModel.updateContent(it.text) },
                textStyle = TextStyle(fontSize = 16.sp),
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
            EditorMode.RICH -> BasicTextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it; viewModel.updateContent(it.text) },
                visualTransformation = MarkdownVisualTransformation(),
                textStyle = TextStyle(fontSize = 16.sp),
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
            EditorMode.PREVIEW -> MarkdownPreview(
                source = uiState.content,
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
        Text(label, style = TextStyle(fontWeight = FontWeight.Bold))
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
