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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.denova.novara.R
import com.denova.novara.core.editor.EditorSearch
import com.denova.novara.core.editor.MarkdownDocument
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.editor.MarkdownTable
import com.denova.novara.core.network.TextSelectionRef
import com.denova.novara.ui.components.NovaButton
import com.denova.novara.ui.components.NovaIconButton
import com.denova.novara.ui.components.NovaTextAction
import com.denova.novara.ui.components.NovaTextField
import com.denova.novara.ui.components.NovaTopBar
import kotlinx.coroutines.launch
import com.denova.novara.ui.theme.NovaRadius
import com.denova.novara.ui.theme.NovaSize
import com.denova.novara.ui.theme.NovaSpacing
import com.denova.novara.ui.theme.NovaStroke
import com.denova.novara.ui.theme.NovaText
import com.denova.novara.ui.theme.NovaTheme

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
    /** 项目内可用图片素材（WRI-007 插入）；为空时「图」按钮只插手填模板。 */
    assetLister: (suspend (projectId: String) -> List<String>)? = null,
    /**
     * 内嵌模式（P4-B / WB-004）：工作台文件页签里复用同一个编辑器本体。
     * 隐藏工作台页签条已有的顶栏、文件页签条与「项目 ID / 文件路径」输入行，
     * 只保留当前文件路径 + 保存 + 模式行 + 正文；项目与文件由宿主页签决定。
     */
    inline: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsState()
    val tabsState by viewModel.tabs.collectAsState()
    // NAV-010：窄屏或大字体时把顶栏次要动作收进溢出菜单、输入区改纵向，
    // 否则整排动作会被挤出屏幕，输入框只能显示几个字符。
    // 阈值取 440dp：英文「Terminal / Write / Sign out」比中文长，400~430dp 下标题会被压到 0 宽、» 贴边。
    val compactHeader = LocalConfiguration.current.screenWidthDp < 440 ||
        LocalDensity.current.fontScale >= 1.3f
    var mode by remember { mutableStateOf(EditorMode.SOURCE) }
    var projectId by remember { mutableStateOf(currentProjectId) }
    var filePath by remember { mutableStateOf("") }
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    val scope = rememberCoroutineScope()
    // 查找 / 替换（WRI-015）状态：查询词、替换词、正则开关、当前命中序号。
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var searchRegex by remember { mutableStateOf(false) }
    var searchIndex by remember { mutableStateOf(-1) }
    var imagePicker by remember { mutableStateOf(false) }
    var imageCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var imageLoading by remember { mutableStateOf(false) }
    // 光标在当前表格内时才显示行列操作（对齐 Web 里表格作为节点、按单元格操作的行为）。
    val tableBlock = remember(textFieldValue.text, textFieldValue.selection) {
        MarkdownTable.locate(textFieldValue.text, textFieldValue.selection.start)
    }

    /** 应用一次表格改写：正文与光标一起换掉，再交给会话走自动保存。 */
    fun applyTable(edit: MarkdownTable.Edit?) {
        if (edit == null) return
        textFieldValue = TextFieldValue(edit.text, TextRange(edit.caret))
        viewModel.updateContent(edit.text)
    }
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

    // 外部内容变化（打开文件/合并结果/冲突材料/撤销重做）时重置本地编辑态。
    // 光标按状态里的 caret 落位：撤销要回到记录的位置，加载外部则回到文档开头（WRI-014）。
    LaunchedEffect(uiState.loaded, uiState.filePath, uiState.conflictText, uiState.contentEpoch) {
        val caret = uiState.caret.coerceIn(0, uiState.content.length)
        textFieldValue = TextFieldValue(uiState.content, TextRange(caret))
    }

    // 从项目目录打开文件时，把项目 ID / 文件路径回填到输入框。
    LaunchedEffect(uiState.projectId, uiState.filePath) {
        if (uiState.projectId.isNotEmpty()) projectId = uiState.projectId
        if (uiState.filePath.isNotEmpty()) filePath = uiState.filePath
    }

    Column(
        modifier = if (inline) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
        },
    ) {
        if (inline) {
            // 内嵌：文件由页签决定，这里只显示当前路径并提供保存（状态行沿用下面的统一提示）。
            NovaTopBar(
                title = uiState.filePath.ifBlank { stringResource(R.string.workbench_files_unselected) },
                containerColor = NovaTheme.colors.bg,
                actions = {
                NovaTextAction(
                    text = stringResource(R.string.save),
                    onClick = { viewModel.save() },
                    enabled = uiState.loaded &&
                        uiState.saveStatus != SaveStatus.SAVING &&
                        uiState.saveStatus != SaveStatus.CONFLICT,
                )
                },
            )
        } else {
        // VG-005/009：顶栏改用共用件（48dp 高、左右 12dp、标题 17sp/600 参与压缩、
        // 导航/右抽屉动作换成 20dp 图标）。标题必须带 weight，否则窄屏会把右侧动作整排挤出屏幕。
        NovaTopBar(
            title = stringResource(R.string.editor_title),
            navigationIcon = Icons.Filled.Menu,
            navigationContentDescription = stringResource(R.string.drawer_open),
            onNavigationClick = onOpenDrawer,
            actions = {
                // 手动保存（WRI-011）：未打开 / 保存中 / 冲突未解决时禁用；冲突走「已解决，保存」。
                NovaTextAction(
                    text = stringResource(R.string.save),
                    onClick = { viewModel.save() },
                    enabled = uiState.loaded &&
                        uiState.saveStatus != SaveStatus.SAVING &&
                        uiState.saveStatus != SaveStatus.CONFLICT,
                )
                if (compactHeader) {
                    // 窄屏把终端 / 写作 / 退出收进溢出菜单，保证关键动作始终可达。
                    var moreOpen by remember { mutableStateOf(false) }
                    Box {
                        NovaIconButton(
                            icon = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.editor_more_actions),
                            onClick = { moreOpen = true },
                        )
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.terminal_title)) },
                                onClick = {
                                    moreOpen = false
                                    onOpenTerminal(projectId.trim())
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.writing)) },
                                enabled = projectId.isNotBlank(),
                                onClick = {
                                    moreOpen = false
                                    onOpenAgent(projectId.trim(), filePath.trim())
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logout)) },
                                onClick = {
                                    moreOpen = false
                                    onLogout()
                                },
                            )
                        }
                    }
                } else {
                    NovaTextAction(
                        text = stringResource(R.string.terminal_title),
                        onClick = { onOpenTerminal(projectId.trim()) },
                    )
                    NovaTextAction(
                        text = stringResource(R.string.writing),
                        onClick = { onOpenAgent(projectId.trim(), filePath.trim()) },
                        enabled = projectId.isNotBlank(),
                    )
                    NovaTextAction(
                        text = stringResource(R.string.logout),
                        onClick = onLogout,
                    )
                }
                NovaIconButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.drawer_end_open),
                    onClick = onOpenRightDrawer,
                )
            },
        )

        // 文件标签（WRI-003）：顺序与激活项由 ViewModel 持有，选中标签自动滚入可见区。
        EditorTabStrip(
            state = tabsState,
            onActivate = viewModel::activateTab,
            onClose = viewModel::closeTab,
            onMoveLeft = { viewModel.moveActiveTab(-1) },
            onMoveRight = { viewModel.moveActiveTab(1) },
        )

        // 打开文件（NAV-010）：窄屏或大字体下并排会把输入内容压成几个字符（label 还会竖排换行），
        // 改为纵向堆叠；宽屏保持并排。
        if (compactHeader) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.sm)) {
                NovaTextField(
                    value = projectId,
                    onValueChange = { projectId = it; onProjectIdChange(it) },
                    label = stringResource(R.string.editor_project_id),
                    modifier = Modifier.fillMaxWidth(),
                )
                NovaTextField(
                    value = filePath,
                    onValueChange = { filePath = it },
                    label = stringResource(R.string.editor_file_path),
                    modifier = Modifier.fillMaxWidth().padding(top = NovaSpacing.sm),
                )
                NovaButton(
                    text = stringResource(R.string.editor_open),
                    onClick = { viewModel.openFile(projectId.trim(), filePath.trim()) },
                    primary = false,
                    modifier = Modifier.align(Alignment.End).padding(top = NovaSpacing.sm),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.sm),
                verticalAlignment = Alignment.Bottom,
            ) {
                NovaTextField(
                    value = projectId,
                    onValueChange = { projectId = it; onProjectIdChange(it) },
                    label = stringResource(R.string.editor_project_id),
                    modifier = Modifier.weight(1f).padding(end = NovaSpacing.sm),
                )
                NovaTextField(
                    value = filePath,
                    onValueChange = { filePath = it },
                    label = stringResource(R.string.editor_file_path),
                    modifier = Modifier.weight(1f).padding(end = NovaSpacing.sm),
                )
                NovaButton(
                    text = stringResource(R.string.editor_open),
                    onClick = { viewModel.openFile(projectId.trim(), filePath.trim()) },
                    primary = false,
                )
            }
        }
        }

        // 保存状态
        val status = when (uiState.saveStatus) {
            // 内嵌时项目与文件都由宿主页签决定，不能提示用户去「输入项目 ID 与文件路径」。
            SaveStatus.IDLE -> if (uiState.loaded) {
                ""
            } else {
                stringResource(if (inline) R.string.workbench_files_hint else R.string.editor_open_hint)
            }
            SaveStatus.SAVING -> stringResource(R.string.editor_saving)
            SaveStatus.SAVED -> stringResource(R.string.editor_saved)
            SaveStatus.MERGED -> stringResource(R.string.editor_merged)
            SaveStatus.CONFLICT -> stringResource(R.string.editor_conflict)
            SaveStatus.ERROR -> stringResource(R.string.editor_error)
        }
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = NovaSpacing.md))
        }
        uiState.message?.let {
            Text(
                it.resolve(),
                color = if (uiState.saveStatus == SaveStatus.CONFLICT || uiState.saveStatus == SaveStatus.ERROR)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = NovaSpacing.md),
            )
        }
        if (uiState.saveStatus == SaveStatus.CONFLICT) {
            // 冲突态暂停自动保存（对齐 Web `EditorPersistenceNotices`）：
            // 用户要么保留本地合并结果，要么直接加载服务器版本；两种选择都不会丢冲突材料。
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.md)) {
                Text(
                    stringResource(R.string.conflict_hint),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = NovaSpacing.sm),
                )
                uiState.conflict?.recoveryId?.let { id ->
                    Text(
                        stringResource(R.string.conflict_recovery, id),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = NovaSpacing.xxs),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NovaButton(onClick = { viewModel.keepLocalVersion() }, text = stringResource(R.string.conflict_keep_local), primary = false,)
                    Spacer(Modifier.width(NovaSpacing.sm))
                    NovaButton(onClick = { viewModel.loadExternalVersion() }, text = stringResource(R.string.conflict_load_external), primary = false,)
                }
            }
        }

        // 模式切换 + 撤销 / 重做（WRI-014）。
        // 撤销放在这一行而不是顶栏：顶栏已有保存/终端/写作/退出/右抽屉，再塞两个按钮会把
        // 「写作」「»」挤出屏幕（真机复现过顶栏溢出），模式行还有余量且三模式都可见。
        Row(
            // NAV-010：只有窄屏才需要横向滚动（否则「撤销/重做」被挤成竖排两个字）；
            // 宽屏保持原版式，撤销/重做仍靠 Spacer(weight) 右对齐。
            modifier = Modifier.fillMaxWidth()
                .then(if (compactHeader) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = NovaSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeButton(stringResource(R.string.editor_mode_source), mode == EditorMode.SOURCE) { mode = EditorMode.SOURCE }
            ModeButton(stringResource(R.string.editor_mode_rich), mode == EditorMode.RICH) { mode = EditorMode.RICH }
            ModeButton(stringResource(R.string.editor_mode_preview), mode == EditorMode.PREVIEW) { mode = EditorMode.PREVIEW }
            if (compactHeader) Spacer(Modifier.width(NovaSpacing.sm)) else Spacer(Modifier.weight(1f))
            // 可用性来自会话历史栈；加载外部版本 / 采纳合并结果后归零。
            TextButton(onClick = { viewModel.undo() }, enabled = uiState.undoAvailable) {
                Text(
                    stringResource(R.string.editor_undo),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { viewModel.redo() }, enabled = uiState.redoAvailable) {
                Text(
                    stringResource(R.string.editor_redo),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 查找 / 替换（WRI-015）：命中数「当前/总数」+ 逐个跳转 / 替换 / 全部替换。
        // 匹配口径对齐 Web 编辑器内实现（字面量与正则均大小写不敏感），全部替换一次性完成，
        // 因此替换后按一次「撤销」即可整体回退（走 EditorSession 的同一份历史栈）。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { searchOpen = !searchOpen }) {
                Text(stringResource(if (searchOpen) R.string.search_collapse else R.string.search_find))
            }
        }
        if (searchOpen) {
            val matches = EditorSearch.findAll(textFieldValue.text, searchQuery, searchRegex)
            val current = if (searchIndex in matches.indices) searchIndex else -1
            Column(Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NovaTextField(
                        value = searchQuery,
                        onValueChange = { q ->
                            searchQuery = q
                            // 对齐 Web：输入即定位到第一处命中（查找条显示 1/N，正文里选中该处）。
                            val found = EditorSearch.findAll(textFieldValue.text, q, searchRegex)
                            searchIndex = if (found.isEmpty()) -1 else 0
                            found.firstOrNull()?.let {
                                textFieldValue = TextFieldValue(
                                    textFieldValue.text,
                                    TextRange(it.start, it.end),
                                )
                            }
                        },
                        label = stringResource(R.string.search_find),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        // 「结果定位数量和状态对应」：查不到时显示 0/0，而不是停在旧数字。
                        if (matches.isEmpty()) "0/0" else "${current + 1}/${matches.size}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = NovaSpacing.chip),
                    )
                    TextButton(
                        onClick = {
                            searchIndex = EditorSearch.step(matches.size, current, forward = false)
                            matches.getOrNull(searchIndex)?.let {
                                textFieldValue = TextFieldValue(
                                    textFieldValue.text,
                                    TextRange(it.start, it.end),
                                )
                            }
                        },
                        enabled = matches.isNotEmpty(),
                    ) { Text(stringResource(R.string.search_prev)) }
                    TextButton(
                        onClick = {
                            searchIndex = EditorSearch.step(matches.size, current, forward = true)
                            matches.getOrNull(searchIndex)?.let {
                                textFieldValue = TextFieldValue(
                                    textFieldValue.text,
                                    TextRange(it.start, it.end),
                                )
                            }
                        },
                        enabled = matches.isNotEmpty(),
                    ) { Text(stringResource(R.string.search_next)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NovaTextField(
                        value = replaceQuery,
                        onValueChange = { replaceQuery = it },
                        label = stringResource(R.string.search_replace_with),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val match = matches.getOrNull(current) ?: return@TextButton
                            val next = EditorSearch.replaceOne(textFieldValue.text, match, replaceQuery)
                            textFieldValue = TextFieldValue(next, TextRange(match.start + replaceQuery.length))
                            viewModel.updateContent(next, caret = match.start + replaceQuery.length)
                            searchIndex = EditorSearch.step(
                                EditorSearch.findAll(next, searchQuery, searchRegex).size,
                                searchIndex,
                                forward = true,
                            )
                        },
                        enabled = current >= 0,
                    ) { Text(stringResource(R.string.search_replace)) }
                    TextButton(
                        onClick = {
                            val (next, count) = EditorSearch.replaceAll(
                                textFieldValue.text,
                                searchQuery,
                                replaceQuery,
                                searchRegex,
                            )
                            if (count > 0) {
                                textFieldValue = TextFieldValue(next)
                                viewModel.updateContent(next, caret = 0)
                                searchIndex = -1
                            }
                        },
                        enabled = matches.isNotEmpty(),
                    ) { Text(stringResource(R.string.search_replace_all)) }
                    TextButton(onClick = { searchRegex = !searchRegex; searchIndex = -1 }) {
                        Text(if (searchRegex) stringResource(R.string.search_regex) + "✓" else stringResource(R.string.search_regex))
                    }
                }
            }
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NovaButton(onClick = { onQuoteSelection(projectId.trim(), quote) }, text = stringResource(R.string.quote_selection), primary = false,)
                Text(
                    TextSelectionQuote.label(quote),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = NovaSpacing.sm),
                )
            }
        }

        if (mode == EditorMode.RICH) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.md, vertical = NovaSpacing.xs)) {
                FormatButton("B") { textFieldValue = wrapSelection(textFieldValue, "**"); viewModel.updateContent(textFieldValue.text) }
                FormatButton("I") { textFieldValue = wrapSelection(textFieldValue, "*"); viewModel.updateContent(textFieldValue.text) }
                FormatButton("</>") { textFieldValue = wrapSelection(textFieldValue, "`"); viewModel.updateContent(textFieldValue.text) }
                FormatButton("S") { textFieldValue = wrapSelection(textFieldValue, "~~"); viewModel.updateContent(textFieldValue.text) }
            }
            // 结构插入（WRI-005 标题/列表/引用、WRI-006 表格、WRI-007 图片与链接）：
            // 都是往光标处插 Markdown 模板，序列化仍以源文本为准，不引入第二套富文本模型。
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.md)) {
                FormatButton("H2") { textFieldValue = insertBlock(textFieldValue, "## 标题\n"); viewModel.updateContent(textFieldValue.text) }
                FormatButton("•") { textFieldValue = insertBlock(textFieldValue, "- 列表项\n"); viewModel.updateContent(textFieldValue.text) }
                FormatButton(">") { textFieldValue = insertBlock(textFieldValue, "> 引用\n"); viewModel.updateContent(textFieldValue.text) }
                FormatButton(stringResource(R.string.editor_fmt_table)) { textFieldValue = insertBlock(textFieldValue, TABLE_TEMPLATE); viewModel.updateContent(textFieldValue.text) }
                FormatButton(stringResource(R.string.editor_fmt_link)) { textFieldValue = insertBlock(textFieldValue, "[链接文字](assets/)\n"); viewModel.updateContent(textFieldValue.text) }
                FormatButton(stringResource(R.string.editor_fmt_image)) {
                    // 有素材列表时先选文件（WRI-007 的「从项目素材插入」），否则退回手填模板。
                    val lister = assetLister
                    if (lister == null) {
                        textFieldValue = insertBlock(textFieldValue, "![图片说明](assets/)\n")
                        viewModel.updateContent(textFieldValue.text)
                    } else {
                        val pid = uiState.projectId.ifBlank { currentProjectId }
                        imagePicker = true
                        imageLoading = true
                        imageCandidates = emptyList()
                        scope.launch {
                            imageCandidates = runCatching { lister(pid) }.getOrDefault(emptyList())
                            imageLoading = false
                        }
                    }
                }
            }
            if (tableBlock != null) {
                // 光标在表格内：行列增删与删表（WRI-006），改完立刻回到正文并触发自动保存。
                val caretStart = textFieldValue.selection.start
                val text = textFieldValue.text
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = NovaSpacing.md)) {
                    FormatButton(stringResource(R.string.editor_fmt_row_add)) { applyTable(MarkdownTable.insertRow(text, caretStart, below = true)) }
                    FormatButton(stringResource(R.string.editor_fmt_row_del)) { applyTable(MarkdownTable.deleteRow(text, caretStart)) }
                    FormatButton(stringResource(R.string.editor_fmt_col_add)) { applyTable(MarkdownTable.insertColumn(text, caretStart, right = true)) }
                    FormatButton(stringResource(R.string.editor_fmt_col_del)) { applyTable(MarkdownTable.deleteColumn(text, caretStart)) }
                    FormatButton(stringResource(R.string.editor_fmt_table_del)) { applyTable(MarkdownTable.deleteTable(text, caretStart)) }
                }
            }
        }

        when (mode) {
            EditorMode.SOURCE -> BasicTextField(
                value = textFieldValue,
                onValueChange = {
                    textFieldValue = it
                    EditorPerf.markEditStart()
                    viewModel.updateContent(it.text, composing = it.composition != null, caret = it.selection.start)
                },
                textStyle = NovaText.sourceEditor,
                modifier = Modifier.fillMaxSize().padding(NovaSpacing.md).tableTabKeys(textFieldValue) {
                    applyTable(it)
                },
            )
            EditorMode.RICH -> BasicTextField(
                value = textFieldValue,
                onValueChange = {
                    textFieldValue = it
                    EditorPerf.markEditStart()
                    viewModel.updateContent(it.text, composing = it.composition != null, caret = it.selection.start)
                },
                visualTransformation = MarkdownVisualTransformation(),
                textStyle = NovaText.editorBody,
                modifier = Modifier.fillMaxSize().padding(NovaSpacing.md).tableTabKeys(textFieldValue) {
                    applyTable(it)
                },
            )
            EditorMode.PREVIEW -> MarkdownPreview(
                source = uiState.content,
                loader = assetLoader,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (imagePicker) {
            // 从项目素材里选一张插入（不新增上传接口：基线只有书籍封面 / 资料库素材上传）。
            AlertDialog(
                onDismissRequest = { imagePicker = false },
                title = { Text(stringResource(R.string.editor_pick_image)) },
                text = {
                    when {
                        imageLoading -> Text(stringResource(R.string.loading))
                        imageCandidates.isEmpty() -> Text(stringResource(R.string.editor_image_empty))
                        else -> LazyColumn(Modifier.heightIn(max = NovaSize.menuMaxHeight)) {
                            items(imageCandidates, key = { it }) { path ->
                                Text(
                                    path,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val rel = PreviewAssetLoader
                                                .relativeAssetPath(uiState.filePath, path)
                                            val name = path.substringAfterLast('/')
                                            textFieldValue = insertBlock(
                                                textFieldValue,
                                                "![$name]($rel)\n",
                                            )
                                            viewModel.updateContent(textFieldValue.text)
                                            imagePicker = false
                                        }
                                        .padding(vertical = NovaSpacing.sm),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { imagePicker = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

/**
 * 表格内 Tab 跳到下一个单元格（对齐 Web TipTap 表格的默认行为）。
 * 不在表格里时不消费事件，保持焦点移动的原生行为。
 */
@Composable
private fun Modifier.tableTabKeys(
    value: TextFieldValue,
    onEdit: (MarkdownTable.Edit?) -> Unit,
): Modifier = this.onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
        val edit = MarkdownTable.nextCell(value.text, value.selection.start)
        if (edit == null) {
            false
        } else {
            onEdit(edit)
            true
        }
    } else {
        false
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
            .padding(horizontal = NovaSpacing.sm),
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
            NovaIconButton(icon = Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more), onClick = { menuOpen = true })
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
            .padding(end = NovaSpacing.xs)
            .clip(RoundedCornerShape(topStart = NovaRadius.xs, topEnd = NovaRadius.xs))
            .background(background)
            .onGloballyPositioned { onMeasure(it.positionInParent().x.toInt(), it.size.width) }
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(
                start = NovaSpacing.tab,
                end = NovaSpacing.xs,
                top = NovaSpacing.chip,
                bottom = NovaSpacing.chip,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NovaSpacing.xs),
        ) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                modifier = Modifier.widthIn(max = NovaSize.tabMaxWidth),
            )
            Text(
                text = "×",
                fontSize = 14.sp,
                modifier = Modifier
                    .size(NovaSize.tabCloseBox)
                    .clickable(onClick = onClose),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NovaStroke.thick)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                ),
        )
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    NovaButton(
        text = label,
        onClick = onClick,
        primary = selected,
        modifier = Modifier.padding(end = NovaSpacing.sm),
    )
}

@Composable
private fun FormatButton(label: String, onClick: () -> Unit) {
    NovaButton(
        text = label,
        onClick = onClick,
        primary = false,
        bold = true,
        modifier = Modifier.padding(end = NovaSpacing.sm),
    )
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
