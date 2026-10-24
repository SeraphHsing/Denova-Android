package com.denova.novara.feature.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.denova.novara.core.terminal.DEFAULT_BG
import com.denova.novara.core.terminal.TerminalAttr
import com.denova.novara.core.terminal.buildRowRuns
import com.denova.novara.core.terminal.lineAt
import com.denova.novara.core.terminal.TerminalScreen as TerminalBuffer

/**
 * 终端页（G5）：项目 ID → 连接（复用 owner 的存活 PTY，否则新建）→ 渲染网格 + 发送按键。
 *
 * 渲染：等宽字体按「单元格」定位；每行先由 [buildRowRuns] 把 SGR 相同的连续格归并成
 * 一个 [AnnotatedString] span（前景 / 粗体 / 斜体 / 下划线 / 反显），再用一次 drawText 画一个
 * run，避免逐格绘制把帧时间打爆。背景色额外用 drawRect 铺满整格，保证色块边界和网格对齐。
 *
 * 重绘由 [TerminalViewModel.UiState.version] 驱动（16ms 合帧）；竖向拖动手势用于滚动回看，
 * `scrollOffset = 0` 表示贴底跟随输出，此时绘制光标块。
 */
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onBack: () -> Unit,
    initialProjectId: String = "",
) {
    val state by viewModel.state.collectAsState()
    var projectId by remember(initialProjectId) { mutableStateOf(initialProjectId) }
    var input by remember { mutableStateOf("") }

    val measurer = rememberTextMeasurer()
    val monoStyle = remember { TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    val metrics = remember(monoStyle) { measurer.measure("M", monoStyle) }
    val cellW = metrics.size.width.toFloat().coerceAtLeast(1f)
    val cellH = metrics.size.height.toFloat().coerceAtLeast(1f)

    // 从编辑器带着项目进来时直接连接；重连/换项目仍由按钮触发
    LaunchedEffect(initialProjectId) {
        if (initialProjectId.isNotBlank()) viewModel.connect(initialProjectId.trim())
    }

    // 回前台立刻补挂：后台期间进程被冻结、socket 已被系统断开，
    // 等心跳（25–45s）才发现会让 PTY 长时间处于 attached=0（Web 端此时可能回收它）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 键位条贴屏幕底部：手势导航下 navigationBars 常报 0，真正吃掉点击的是 systemGestures 区域，
    // 直接贴底会出现「按钮看得见但点不动」（真机 D03-P 测量时实测到）。
    val bottomInset = maxOf(
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        WindowInsets.systemGestures.asPaddingValues().calculateBottomPadding(),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(bottom = bottomInset)
            .imePadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("终端", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
            TextButton(onClick = onBack) { Text("返回") }
            Text(state.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            Text(
                text = "${state.cols}x${state.rows}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = projectId,
                onValueChange = { projectId = it },
                label = { Text("项目 ID") },
                singleLine = true,
                enabled = !state.connected,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            OutlinedButton(
                onClick = { viewModel.connect(projectId.trim()) },
                enabled = projectId.isNotBlank() && !state.connected,
            ) { Text(if (state.connected) "已连接" else "连接") }
            TextButton(onClick = { viewModel.closeAndRelease() }) { Text("关闭会话") }
        }

        state.error?.let {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                // G6 决策 B：会话被其他端释放后，仍可只读回看最近 64KB 输出
                if (state.historyAvailable) {
                    TextButton(onClick = { viewModel.showHistory() }) { Text("查看历史") }
                }
            }
        }

        state.historyText?.let { history ->
            AlertDialog(
                onDismissRequest = { viewModel.hideHistory() },
                confirmButton = {
                    TextButton(onClick = { viewModel.hideHistory() }) { Text("关闭") }
                },
                title = { Text("终端历史（本地留存，只读）") },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(history, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                },
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(DEFAULT_BG))
                    .pointerInput(cellH) {
                        var accumulated = 0f
                        detectVerticalDragGestures(
                            onDragStart = { accumulated = 0f },
                            onDragEnd = { accumulated = 0f },
                            onDragCancel = { accumulated = 0f },
                        ) { change, dragAmount ->
                            change.consume()
                            // 手指下滑 = 看更早的内容；按整行取整，余量留到下一次事件
                            accumulated += dragAmount
                            val lines = (accumulated / cellH).toInt()
                            if (lines != 0) {
                                viewModel.scrollBy(lines)
                                accumulated -= lines * cellH
                            }
                        }
                    }
                    .onSizeChanged { size ->
                        if (cellW > 0f && cellH > 0f) {
                            viewModel.resizeTo((size.width / cellW).toInt(), (size.height / cellH).toInt())
                        }
                    },
            ) {
                // 读 version 建立绘制依赖：解析出新内容后此处失效并重绘
                val version = state.version
                drawTerminal(
                    buffer = viewModel.screen,
                    offset = state.scrollOffset,
                    measurer = measurer,
                    baseStyle = monoStyle,
                    cellW = cellW,
                    cellH = cellH,
                    version = version,
                )
            }

            if (state.scrollOffset > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "回看 +${state.scrollOffset} 行 / ${state.scrollbackSize}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = { viewModel.scrollToBottom() }) { Text("回到底部") }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("输入（回车发送）") },
                singleLine = true,
                enabled = state.connected,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        // PTY 的行结束符是 \r（cooked 模式下由 ICRNL 转成 \n）
                        viewModel.sendText("$input\r")
                        input = ""
                    },
                ),
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            OutlinedButton(
                onClick = {
                    viewModel.sendText("$input\r")
                    input = ""
                },
                enabled = state.connected,
            ) { Text("发送") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { viewModel.sendKey(3) }, enabled = state.connected) { Text("Ctrl-C") }
            OutlinedButton(onClick = { viewModel.sendKey(4) }, enabled = state.connected) { Text("Ctrl-D") }
            OutlinedButton(onClick = { viewModel.sendKey(26) }, enabled = state.connected) { Text("Ctrl-Z") }
            OutlinedButton(onClick = { viewModel.sendKey(9) }, enabled = state.connected) { Text("Tab") }
            OutlinedButton(onClick = { viewModel.sendKey(27) }, enabled = state.connected) { Text("Esc") }
            OutlinedButton(onClick = { viewModel.sendKey(27, 91, 65) }, enabled = state.connected) { Text("↑") }
            OutlinedButton(onClick = { viewModel.sendKey(27, 91, 66) }, enabled = state.connected) { Text("↓") }
            OutlinedButton(onClick = { viewModel.sendKey(27, 91, 68) }, enabled = state.connected) { Text("←") }
            OutlinedButton(onClick = { viewModel.sendKey(27, 91, 67) }, enabled = state.connected) { Text("→") }
        }
    }
}

/** 按单元格绘制：每行的同属性连续格归并成 span，run 内一次 drawText，末尾绘制光标块。 */
private fun DrawScope.drawTerminal(
    buffer: TerminalBuffer,
    offset: Int,
    measurer: TextMeasurer,
    baseStyle: TextStyle,
    cellW: Float,
    cellH: Float,
    @Suppress("UNUSED_PARAMETER") version: Int,
) {
    drawRect(color = Color(DEFAULT_BG), size = size)
    val spanCache = HashMap<TerminalAttr, SpanStyle>()
    val rowCount = buffer.rows

    for (row in 0 until rowCount) {
        val runs = buildRowRuns(buffer.lineAt(row, offset), buffer.cols)
        if (runs.isEmpty()) continue
        val top = row * cellH
        for (run in runs) {
            val left = run.startCol * cellW
            val width = run.spanCols * cellW
            val bg = if (run.attr.inverse) run.attr.fg else run.attr.bg
            if (bg != DEFAULT_BG || run.attr.inverse) {
                drawRect(
                    color = Color(bg),
                    topLeft = Offset(left, top),
                    size = Size(width, cellH),
                )
            }
            val span = spanCache.getOrPut(run.attr) { run.attr.toSpanStyle() }
            val text = buildAnnotatedString {
                withStyle(span) { append(run.text) }
            }
            drawText(
                textMeasurer = measurer,
                text = text,
                topLeft = Offset(left, top),
                style = baseStyle,
                overflow = TextOverflow.Clip,
                softWrap = false,
                maxLines = 1,
                size = Size(width, cellH),
            )
        }
    }

    // 只有贴底跟随时才画光标：回看历史时光标位置不代表正在看的那一屏
    if (offset == 0 && buffer.cursorVisible) {
        drawRect(
            color = Color(0x66E0E0E0),
            topLeft = Offset(buffer.cursorCol * cellW, buffer.cursorRow * cellH),
            size = Size(cellW, cellH),
        )
    }
}

private fun TerminalAttr.toSpanStyle(): SpanStyle = SpanStyle(
    color = Color(if (inverse) bg else fg),
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    textDecoration = if (underline) TextDecoration.Underline else null,
)
