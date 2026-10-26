package com.denova.novara.feature.game

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.denova.novara.R
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.stream.GameActivity
import com.denova.novara.core.stream.GamePhase

/**
 * 游戏舞台（G4）：输入 story/branch → 挂接活动流 → 展示投影阶段与累积叙事。
 * 叙事按 chunk 累积；断线由 [GameViewModel] 内部恢复协调器自动重挂。
 */
@Composable
fun GameScreen(viewModel: GameViewModel, onOpenDrawer: () -> Unit, onOpenRightDrawer: () -> Unit, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    // 后台被系统掐断连接后，回前台自动重挂（身份/结果仍对应）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var storyId by remember { mutableStateOf("") }
    var branchId by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            IconButton(onClick = onOpenDrawer) { Text("☰") }
            Text(stringResource(R.string.game_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            IconButton(onClick = onOpenRightDrawer) { Text("»") }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = storyId,
                onValueChange = { storyId = it },
                label = { Text("story ID") },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            OutlinedTextField(
                value = branchId,
                onValueChange = { branchId = it },
                label = { Text(stringResource(R.string.game_branch_optional)) },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            OutlinedButton(onClick = { viewModel.start(storyId.trim(), branchId.trim()) }) { Text(stringResource(R.string.common_attach)) }
        }

        // 投影状态
        val phaseText = when (uiState.stage.phase) {
            GamePhase.IDLE -> stringResource(R.string.game_phase_idle)
            GamePhase.RUNNING -> stringResource(R.string.game_phase_running)
            GamePhase.SUSPENDED -> stringResource(R.string.game_phase_suspended)
            GamePhase.DONE -> stringResource(R.string.game_phase_done)
            GamePhase.ABORTED -> stringResource(R.string.game_phase_aborted)
            GamePhase.ERROR -> stringResource(R.string.common_error)
        }
        val activityText = when (uiState.stage.activity) {
            GameActivity.IDLE -> ""
            GameActivity.THINKING -> stringResource(R.string.game_activity_thinking)
            GameActivity.TOOL -> stringResource(R.string.game_activity_tool)
            GameActivity.REPAIRING -> stringResource(R.string.game_activity_repairing)
            GameActivity.RECOVERING -> stringResource(R.string.game_activity_recovering)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text(
                stringResource(R.string.game_status, phaseText + (if (activityText.isNotEmpty()) " · " + activityText else "") + (if (uiState.connected) " · " + stringResource(R.string.common_connected) else "")),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        uiState.error?.let {
            Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
        }

        // 叙事单独收集 blocks 流：高频更新只重组本面板
        NarrativePane(viewModel = viewModel, modifier = Modifier.weight(1f))

        // 发送玩家输入（POST 实时流；先落本地命令事务再发送）
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.game_player_input)) },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            OutlinedButton(
                onClick = {
                    viewModel.send(storyId.trim(), branchId.trim(), input)
                    input = ""
                },
                enabled = !uiState.sending && storyId.isNotBlank() && input.isNotBlank(),
            ) { Text(if (uiState.sending) stringResource(R.string.game_sending) else stringResource(R.string.common_send)) }
        }
    }
}

/**
 * 叙事面板：只有它收集高频 [GameViewModel.blocks] 流，
 * chunk 更新不会连带重组状态行与输入框（含 IME 布局，代价高）。
 */
@Composable
private fun NarrativePane(viewModel: GameViewModel, modifier: Modifier = Modifier) {
    val blocks by viewModel.blocks.collectAsState()
    // D03 恢复耗时埋点：叙事首次可见 = 恢复后「可操作」终点（每次进入写作/游戏界面记一次）。
    var visibilityMarked by remember { mutableStateOf(false) }
    if (!visibilityMarked && blocks.isNotEmpty()) {
        SideEffect {
            if (!visibilityMarked) {
                visibilityMarked = true
                DenovaLog.i("Perf", "narrative-visible blocks=${blocks.size}")
            }
        }
    }
    if (blocks.isEmpty()) {
        Text(
            text = stringResource(R.string.game_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.padding(12.dp),
        )
        return
    }
    // reverseLayout：新块追加后视图天然锚定底部，无需每次 flush 调 scrollToItem。
    // 每次成功重组后结算「事件接收 → 投影可见」延迟（D03 指标埋点）。
    // 用 SideEffect 而非 drawWithContent：LazyColumn 增量追加时整节点不一定重绘。
    SideEffect { viewModel.onNarrativeDrawn() }

    // 渐进恢复会先放少量基线、再整段替换为全量；此时需锚回最新内容，
    // 否则列表会停在旧端（空白）。只在「批量变更」时滚动，避免流式追加时逐帧重排。
    val listState = rememberLazyListState()
    var lastBlockCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(blocks.size) {
        if (blocks.size > lastBlockCount + 20) listState.scrollToItem(0)
        lastBlockCount = blocks.size
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        itemsIndexed(blocks.asReversed()) { _, block ->
            Text(
                text = block,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}
