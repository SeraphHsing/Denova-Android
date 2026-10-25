package com.denova.novara.feature.editor

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.denova.novara.R
import com.denova.novara.core.editor.MarkdownDocument
import com.denova.novara.core.network.TextSelectionRef

private enum class EditorMode { SOURCE, RICH, PREVIEW }

/** 表格模板（WRI-006）：2 行 3 列，插入后每个单元格都可直接编辑。 */
private const val TABLE_TEMPLATE = "| 列1 | 列2 | 列3 |\n| --- | --- | --- |\n|  |  |  |\n"

/**
 * Markdown 编辑器（G2/G3）：打开文件 → 源码/富文本/预览三模式 → 防抖自动保存 + 冲突提示。
 */
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onOpenDrawer: () -> Unit,
    onOpenRightDrawer: () -> Unit,
    onOpenTerminal: (String) -> Unit,
    onLogout: () -> Unit,
    currentProjectId: String = "",
    onProjectIdChange: (String) -> Unit = {},
    onOpenAgent: (projectId: String, filePath: String) -> Unit = { _, _ -> },
    onQuoteSelection: (projectId: String, selection: TextSelectionRef) -> Unit = { _, _ -> },
    /** 预览图片加载器工厂（WRI-007）；为空时预览只渲染语义、不拉取资源。 */
    loaderFactory: ((projectId: String, documentPath: String) -> PreviewAssetLoader?)? = null,
) {
    val uiState by viewModel.uiState.collectAsState()
    val tabsState by viewModel.tabs.collectAsState()
    var mode by remember { mutableStateOf(EditorMode.SOURCE) }
    var projectId by remember { mutableStateOf(currentProjectId) }
    var filePath by remember { mutableStateOf("") }
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    // 资源加载依赖「哪个项目 / 哪个文档」：换文件就重建，相对路径按该文档目录解析。
    val assetLoader = remember(uiState.projectId, uiState.filePath, loaderFactory) {
        val pid = uiState.projectId.ifBlank { currentProjectId }
        if (pid.isBlank() || uiState.filePath.isBlank()) null
        else loaderFactory?.invoke(pid, uiState.filePath)
    }

    // 书籍切换（当前项目切换）后同步项目 ID 到输入框。
    LaunchedEffect(currentProjectId) {
        if (currentProjectId.isNotEmpty()) projectId = currentProjectId
    }

    // D03-W 埋点：本组合已提交绘制，结算「最后一次按键 → 可见」延迟。
    SideEffect { EditorPerf.markDraw() }

    // 外部内容变化（打开文件/合并结果/冲突材料）时重置本地编辑态。
    LaunchedEffect(uiState.loaded, uiState.filePath, uiState.conflictText, uiState.contentEpoch) {
        textFieldValue = TextFieldValue(uiState.content)
    }

    // 从项目目录打开文件时，把项目 ID / 文件路径回填到输入框。
    LaunchedEffect(uiState.projectId, uiState.filePath) {
        if (uiState.projectId.isNotEmpty()) projectId = uiState.projectId
        if (uiState.filePath.isNotEmpty()) filePath = uiState.filePath
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            IconButton(onClick = onOpenDrawer) { Text("☰") }
            Text(stringResource(R.string.editor_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
            // 手动保存（WRI-011）：未打开 / 保存中 / 冲突未解决时禁用；冲突走「已解决，保存」。
            TextButton(
                onClick = { viewModel.save() },
                enabled = uiState.loaded &&
                    uiState.saveStatus != SaveStatus.SAVING &&
                    uiState.saveStatus != SaveStatus.CONFLICT,
            ) { Text(stringResource(R.string.save)) }
            TextButton(onClick = { onOpenTerminal(projectId.trim()) }) { Text(stringResource(R.string.terminal_title)) }
            TextButton(
                onClick = { onOpenAgent(projectId.trim(), filePath.trim()) },
                enabled = projectId.isNotBlank(),
            ) { Text(stringResource(R.string.writing)) }
            TextButton(onClick = onLogout) { Text(stringResource(R.string.logout)) }
            IconButton(onClick = onOpenRightDrawer) { Text("»") }
        }

        // 文件标签（WRI-003）：顺序与激活项由 ViewModel 持有，选中标签自动滚入可见区。
        EditorTabStrip(
            state = tabsState,
            onActivate = viewModel::activateTab,
            onClose = viewModel::closeTab,
            onMoveLeft = { viewModel.moveActiveTab(-1) },
            onMoveRight = { viewModel.moveActiveTab(1) },
        )

        // 打开文件
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = projectId,
                onValueChange = { projectId = it; onProjectIdChange(it) },
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
            // 冲突态暂停自动保存，必须由用户改完标记后显式放行一次（对齐 Web 的「解决后保存」）。
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.conflict_hint),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                OutlinedButton(onClick = { viewModel.resolveConflict() }) {
                    Text(stringResource(R.string.save_resolved))
                }
            }
        }

        // 模式切换
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            ModeButton("源码", mode == EditorMode.SOURCE) { mode = EditorMode.SOURCE }
            ModeButton("富文本", mode == EditorMode.RICH) { mode = EditorMode.RICH }
            ModeButton("预览", mode == EditorMode.PREVIEW) { mode = EditorMode.PREVIEW }
        }

        // 选区引用（WRI-009）：有非空选区时显示「引用到 AI」与定位（file:Lx-Ly）。
        val selection = textFieldValue.selection
        val quote = if (uiState.loaded && !selection.collapsed) {
            TextSelectionQuote.of(uiState.filePath, textFieldValue.text, selection.start, selection.end)
        } else {
            null
        }
        if (quote != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { onQuoteSelection(projectId.trim(), quote) }) {
                    Text(stringResource(R.string.quote_selection))
                }
                Text(
                    TextSelectionQuote.label(quote),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        if (mode == EditorMode.RICH) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                FormatButton("B") { textFieldValue = wrapSelection(textFieldValue, "**"); viewModel.updateContent(textFieldValue.text) }
                FormatButton("I") { textFieldValue = wrapSelection(textFieldValue, "*"); viewModel.updateContent(textFieldValue.text) }
                FormatButton("</>") { textFieldValue = wrapSelection(textFieldValue, "`"); viewModel.updateContent(textFieldValue.text) }
                FormatButton("S") { textFieldValue = wrapSelection(textFieldValue, "~~"); viewModel.updateContent(textFieldValue.text) }
            }
            // 结构插入（WRI-005 标题/列表/引用、WRI-006 表格、WRI-007 图片与链接）：
            // 都是往光标处插 Markdown 模板，序列化仍以源文本为准，不引入第二套富文本模型。
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                FormatButton("H2") { textFieldValue = insertBlock(textFieldValue, "## 标题\n"); viewModel.updateContent(textFieldValue.text) }
                FormatButton("•") { textFieldValue = insertBlock(textFieldValue, "- 列表项\n"); viewModel.updateContent(textFieldValue.text) }
                FormatButton(">") { textFieldValue = insertBlock(textFieldValue, "> 引用\n"); viewModel.updateContent(textFieldValue.text) }
                FormatButton("表") { textFieldValue = insertBlock(textFieldValue, TABLE_TEMPLATE); viewModel.updateContent(textFieldValue.text) }
                FormatButton("链") { textFieldValue = insertBlock(textFieldValue, "[链接文字](assets/)\n"); viewModel.updateContent(textFieldValue.text) }
                FormatButton("图") { textFieldValue = insertBlock(textFieldValue, "![图片说明](assets/)\n"); viewModel.updateContent(textFieldValue.text) }
            }
        }

        when (mode) {
            EditorMode.SOURCE -> BasicTextField(
                value = textFieldValue,
                onValueChange = {
                    textFieldValue = it
                    EditorPerf.markEditStart()
                    viewModel.updateContent(it.text, composing = it.composition != null)
                },
                textStyle = TextStyle(fontSize = 16.sp),
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
            EditorMode.RICH -> BasicTextField(
                value = textFieldValue,
                onValueChange = {
                    textFieldValue = it
                    EditorPerf.markEditStart()
                    viewModel.updateContent(it.text, composing = it.composition != null)
                },
                visualTransformation = MarkdownVisualTransformation(),
                textStyle = TextStyle(fontSize = 16.sp),
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
            EditorMode.PREVIEW -> MarkdownPreview(
                source = uiState.content,
                loader = assetLoader,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 文件标签条（WRI-003），按移动端 Web 基线的形态：可横向滚动的标签 + 右侧「⋮」菜单
 * （左移 / 右移 / 关闭当前）。标签选中后自动滚入可见区，标签多也不丢选中态。
 */
@Composable
private fun EditorTabStrip(
    state: EditorTabsState,
    onActivate: (String) -> Unit,
    onClose: (String) -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    if (state.isEmpty) return
    val scroll = rememberScrollState()
    // 每个标签在滚动内容中的 x 与宽度，用于把激活标签滚到可见处。
    val bounds = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
    var viewport by remember { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    val activeIndex = state.activeIndex

    LaunchedEffect(state.activePath, state.paths.size, viewport) {
        // 等这一帧布局完成后再取位置，避免刚新增的标签还没测量。
        withFrameNanos { }
        val (x, width) = bounds[state.activePath] ?: return@LaunchedEffect
        val target = when {
            x < scroll.value -> x
            x + width > scroll.value + viewport -> x + width - viewport
            else -> -1
        }
        if (target >= 0) scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { viewport = it.size.width }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.paths.forEach { path ->
                EditorTabItem(
                    label = EditorTabs.label(path),
                    selected = state.isActive(path),
                    closeLabel = stringResource(R.string.tab_close, EditorTabs.label(path)),
                    onMeasure = { x, width -> bounds[path] = x to width },
                    onClick = { onActivate(path) },
                    onClose = { onClose(path) },
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) { Text("⋮") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tab_move_left)) },
                    enabled = activeIndex > 0,
                    onClick = { menuOpen = false; onMoveLeft() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tab_move_right)) },
                    enabled = activeIndex >= 0 && activeIndex < state.paths.lastIndex,
                    onClick = { menuOpen = false; onMoveRight() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tab_close_current)) },
                    enabled = activeIndex >= 0,
                    onClick = {
                        menuOpen = false
                        state.activePath.takeIf { it.isNotEmpty() }?.let(onClose)
                    },
                )
            }
        }
    }
}

@Composable
private fun EditorTabItem(
    label: String,
    selected: Boolean,
    closeLabel: String,
    onMeasure: (Int, Int) -> Unit,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }
    Column(
        modifier = Modifier
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .background(background)
            .onGloballyPositioned { onMeasure(it.positionInParent().x.toInt(), it.size.width) }
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            Text(
                text = "×",
                fontSize = 14.sp,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onClose),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                ),
        )
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

/** 在光标处插入块级模板（标题/列表/引用/表格/图片/链接）：需要独占一行，插完光标停在末尾。 */
private fun insertBlock(value: TextFieldValue, block: String): TextFieldValue {
    val text = value.text
    val at = value.selection.start.coerceIn(0, text.length)
    val prefix = if (at > 0 && text[at - 1] != '\n') "\n" else ""
    val insert = prefix + block
    val newText = text.substring(0, at) + insert + text.substring(at)
    val caret = at + insert.length
    return TextFieldValue(newText, TextRange(caret, caret))
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MarkdownPreview(
    source: String,
    loader: PreviewAssetLoader?,
    modifier: Modifier = Modifier,
) {
    val html = remember(source, loader) { buildPreviewHtml(source, loader) }
    // base URL 用服务器 origin：data: 文档（base=null）会让 WebView 丢掉 http 子资源请求。
    val baseUrl = loader?.baseUrl
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                // 图片走项目资源接口，由 OkHttp 代取（WebView 拿不到 OkHttp 的会话 Cookie）。
                loader?.let { webViewClient = it.webViewClient() }
                loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            }
        },
        update = { view ->
            if (loader != null) view.webViewClient = loader.webViewClient()
            view.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
        },
    )
}

private fun buildPreviewHtml(markdown: String, loader: PreviewAssetLoader?): String {
    val raw = MarkdownDocument.parse(markdown).toHtml()
    val body = loader?.let { it.decorate(it.rewrite(raw)) } ?: raw
    com.denova.novara.core.logging.DenovaLog.i(
        "Editor",
        "preview build images=${Regex("<img").findAll(raw).count()} tables=${Regex("<table>").findAll(raw).count()} " +
            "loader=${loader?.describe() ?: "none"}",
    )
    return """
        <html><head><meta charset="utf-8">
        <style>
        ${PreviewAssetLoader.STYLE}
        </style></head>
        <body>$body</body></html>
    """.trimIndent()
}
