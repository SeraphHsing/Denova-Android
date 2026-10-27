package com.denova.novara.feature.game

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.denova.novara.R
import com.denova.novara.core.game.StoryLibrary
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.StorySummary
import com.denova.novara.core.stream.GameActivity
import com.denova.novara.core.stream.GamePhase

/**
 * 游戏舞台（P4-A）：故事库驱动入口（GAM-002）→ 挂接活动流 → 投影阶段与累积叙事。
 * 叙事按 chunk 累积；断线由 [GameViewModel] 内部恢复协调器自动重挂。
 */
@Composable
fun GameScreen(viewModel: GameViewModel, onOpenDrawer: () -> Unit, onOpenRightDrawer: () -> Unit, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val library by viewModel.library.collectAsState()

    // 后台被系统掐断连接后，回前台自动重挂（身份/结果仍对应）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 进入页面加载故事列表；服务端 current_story_id 命中时自动挂接该故事。
    LaunchedEffect(Unit) { viewModel.loadStories(autoAttach = true) }

    var showPicker by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<StorySummary?>(null) }
    var deleteTarget by remember { mutableStateOf<StorySummary?>(null) }
    var input by remember { mutableStateOf("") }

    val currentStory = library.stories.firstOrNull { it.id == library.currentStoryId }
    val storyId = library.currentStoryId

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

        // 故事选择行：显示当前故事与轮次，点开列表切换 / 改名 / 删除。
        // 标题用 weight(1f) + 单行省略：服务端标题可能很长（AI 生成的叙事句），
        // 否则会把轮次/更新时间挤成竖排单字（真机复现过）。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { showPicker = true },
                enabled = !library.busy,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text(
                    text = currentStory?.title?.ifBlank { currentStory.id }
                        ?: stringResource(R.string.game_story_pick_placeholder),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            currentStory?.let {
                Text(
                    storySubtitle(it),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (library.loading || library.busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            IconButton(onClick = { viewModel.loadStories() }, enabled = !library.loading) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
            }
        }
        library.error?.let {
            Text(
                it.resolve(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
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
                    viewModel.sendCurrent(input)
                    input = ""
                },
                enabled = !uiState.sending && storyId.isNotBlank() && input.isNotBlank(),
            ) { Text(if (uiState.sending) stringResource(R.string.game_sending) else stringResource(R.string.common_send)) }
        }
    }

    if (showPicker) {
        StoryPickerDialog(
            state = library,
            onSelect = {
                viewModel.selectStory(it)
                showPicker = false
            },
            onRename = { renameTarget = it },
            onDelete = { deleteTarget = it },
            onRefresh = { viewModel.loadStories() },
            onDismiss = {
                showPicker = false
                viewModel.dismissLibraryError()
            },
        )
    }

    renameTarget?.let { story ->
        RenameStoryDialog(
            story = story,
            onConfirm = {
                viewModel.renameStory(story.id, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { story ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.game_story_delete_title)) },
            text = { Text(stringResource(R.string.game_story_delete_confirm, story.title.ifBlank { story.id })) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteStory(story.id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.game_story_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** 副标题：轮次数 + 最近更新时间（时间戳解析失败时只显示轮次数）。 */
@Composable
private fun storySubtitle(story: StorySummary): String {
    val updated = StoryLibrary.formatUpdatedAt(story.updatedAt)
    return if (updated.isEmpty()) {
        stringResource(R.string.game_story_turns, story.turnCount)
    } else {
        stringResource(R.string.game_story_summary, story.turnCount, updated)
    }
}

/** 故事列表：选中打勾、逐项改名/删除，底部刷新。删除动作在页面层确认。 */
@Composable
private fun StoryPickerDialog(
    state: StoryLibraryState,
    onSelect: (String) -> Unit,
    onRename: (StorySummary) -> Unit,
    onDelete: (StorySummary) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_story_picker_title)) },
        text = {
            Column {
                // 打开时把当前故事滚进可见区：列表可能很长，当前项常在末尾。
                val listState = rememberLazyListState()
                LaunchedEffect(state.currentStoryId, state.stories.size) {
                    val index = state.stories.indexOfFirst { it.id == state.currentStoryId }
                    if (index >= 0) listState.scrollToItem(index)
                }
                when {
                    state.stories.isEmpty() && state.loading -> Text(stringResource(R.string.game_story_loading))
                    state.stories.isEmpty() -> Text(stringResource(R.string.game_story_empty))
                    else -> LazyColumn(state = listState, modifier = Modifier.heightIn(max = 380.dp)) {
                        items(state.stories, key = { it.id }) { story ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.busy) { onSelect(story.id) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = story.title.ifBlank { story.id },
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (story.id == state.currentStoryId) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        storySubtitle(story),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                    )
                                }
                                if (story.id == state.currentStoryId) {
                                    Text("✓", modifier = Modifier.padding(horizontal = 4.dp))
                                }
                                TextButton(onClick = { onRename(story) }) {
                                    Text(stringResource(R.string.game_story_rename))
                                }
                                TextButton(onClick = { onDelete(story) }) {
                                    Text(stringResource(R.string.game_story_delete))
                                }
                            }
                        }
                    }
                }
                state.error?.let {
                    Text(
                        it.resolve(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text(stringResource(R.string.common_refresh)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun RenameStoryDialog(story: StorySummary, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember(story.id) { mutableStateOf(story.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_story_rename_title)) },
        text = {
            OutlinedTextField(
                value = title,
                // Web 入口 maxLength=80，保持同一上限。
                onValueChange = { if (it.length <= 80) title = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
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
