package com.denova.novara.feature.game

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.denova.novara.R
import com.denova.novara.core.game.StoryCreationForm
import com.denova.novara.core.game.StoryLibrary
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.stream.AgentApprovalLogic
import com.denova.novara.core.stream.AgentAskInteraction
import com.denova.novara.core.stream.AgentAskLogic
import com.denova.novara.core.stream.AgentVerificationLogic
import com.denova.novara.core.stream.ApprovalMode
import com.denova.novara.core.stream.ApprovalOption
import com.denova.novara.core.stream.ApprovalReason
import com.denova.novara.core.stream.ApprovalRisk
import com.denova.novara.core.stream.ApprovalState
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.StorySummary
import com.denova.novara.core.stream.GameActivity
import com.denova.novara.core.stream.GamePhase
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.denova.novara.core.game.GameAttachmentLimits
import com.denova.novara.core.image.imageLoaderFor
import com.denova.novara.core.network.DenovaClient
import java.util.Locale

/**
 * 游戏舞台（P4-A）：故事库驱动入口（GAM-002）→ 挂接活动流 → 投影阶段与累积叙事。
 * 叙事按 chunk 累积；断线由 [GameViewModel] 内部恢复协调器自动重挂。
 */
@Composable
fun GameScreen(
    viewModel: GameViewModel,
    projectId: String,
    client: DenovaClient?,
    onOpenDrawer: () -> Unit,
    onOpenRightDrawer: () -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val library by viewModel.library.collectAsState()
    val creation by viewModel.creation.collectAsState()
    val media by viewModel.media.collectAsState()
    // 配图走项目受保护资源（带会话 Cookie），复用 AUTH-002 的 Coil 加载器。
    val context = LocalContext.current
    val imageLoader = remember(client) { client?.let { imageLoaderFor(context, it) } }
    // GAM-016：附件由系统选择器读取后随下一条输入一次性发送。
    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.addAttachments(uris) }

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
            IconButton(onClick = { viewModel.openCreation(projectId) }, enabled = !creation.submitting) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.game_create))
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

        // 0 轮故事还没有任何轮次：给出「生成开场」入口（GAM-003），否则舞台是空的一片。
        if (uiState.snapshotLoaded && uiState.turnCount == 0 &&
            !uiState.sending && uiState.stage.phase != GamePhase.RUNNING
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { viewModel.startOpening() }) {
                    Text(stringResource(R.string.game_start_opening))
                }
                Text(
                    stringResource(R.string.game_start_opening_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // 运行时控制（GAM-012 / GAM-013）：运行中可暂停；挂起或存在待续接输入时可续接 / 放弃。
        val hasPendingInterruption = !uiState.pendingInterruptionId.isNullOrBlank()
        if (uiState.stage.phase == GamePhase.RUNNING ||
            uiState.stage.phase == GamePhase.SUSPENDED ||
            hasPendingInterruption
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (uiState.stage.phase == GamePhase.RUNNING) {
                    TextButton(
                        onClick = { viewModel.suspendGame() },
                        enabled = !uiState.runtimeBusy,
                    ) { Text(stringResource(R.string.game_runtime_suspend)) }
                } else {
                    TextButton(
                        onClick = { viewModel.resumeGame() },
                        enabled = !uiState.runtimeBusy,
                    ) { Text(stringResource(R.string.game_runtime_resume)) }
                    if (hasPendingInterruption) {
                        TextButton(
                            onClick = { viewModel.abandonPendingTurn() },
                            enabled = !uiState.runtimeBusy,
                        ) { Text(stringResource(R.string.game_runtime_abandon)) }
                    }
                }
            }
        }
        // 附件 / 配图 / 上下文压缩（GAM-016）
        GameMediaPanel(
            media = media,
            latestTurnId = uiState.turnAnchors.lastOrNull()?.turnId,
            latestTurnNumber = uiState.turnAnchors.lastOrNull()?.number,
            busy = uiState.stage.phase == GamePhase.RUNNING || uiState.sending,
            imageUrl = client?.let { activeClient ->
                // 优先用游戏状态里的服务端工作区项目（冷启动时外壳的 currentProjectId 可能为空）。
                val assetProjectId = uiState.projectId.ifBlank { projectId }
                media.images.values.lastOrNull()?.takeIf { assetProjectId.isNotBlank() }?.let { image ->
                    activeClient.projectAssetUrl(assetProjectId, image.imagePath).toString()
                }
            },
            imageLoader = imageLoader,
            onPickAttachments = { attachLauncher.launch(arrayOf("*/*")) },
            onRemoveAttachment = viewModel::removeAttachment,
            onClearAttachments = viewModel::clearAttachments,
            onDismissError = viewModel::dismissMediaError,
            onGenerateImage = viewModel::generateImage,
            onCompact = viewModel::compactContext,
            onRemoveCompaction = viewModel::removeCompaction,
        )

        uiState.runtimeError?.let {
            Text(
                it.resolve(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        // 提问卡片（GAM-011）：挂起时铺在输入区上方；已解决时只留一行状态。
        uiState.ask?.let { ask ->
            AskCard(
                ask = ask,
                busy = uiState.runtimeBusy,
                onSubmit = { selected -> viewModel.answerAsk(selected) },
                onApprove = { option -> viewModel.approveAsk(option) },
                onCancel = { viewModel.cancelAsk() },
            )
        }

        // 叙事单独收集 blocks 流：高频更新只重组本面板
        NarrativePane(viewModel = viewModel, modifier = Modifier.weight(1f))

        // 推荐行动（GAM-005）：点选只填入输入框供改写，不直接发送（与 Web 一致）。
        // 运行中 / 暂停中不展示——那是上一轮的选项，此时点击只会误导（Web 同样隐藏）。
        val choicesAvailable = uiState.choices.isNotEmpty() &&
            !uiState.sending &&
            uiState.stage.phase != GamePhase.RUNNING &&
            uiState.stage.phase != GamePhase.SUSPENDED
        if (choicesAvailable) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.choices) { choice ->
                    AssistChip(
                        onClick = { input = choice },
                        label = {
                            Text(
                                choice,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 260.dp),
                            )
                        },
                    )
                }
            }
        }

        // 发送玩家输入（POST 实时流；先落本地命令事务再发送）
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            // GAM-004：Enter 只换行、点「发送」才提交（与 Web 一致）。因此不能用 singleLine，
            // 否则回车被输入框吞掉，既发不出去也换不了行。
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.game_player_input)) },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            OutlinedButton(
                onClick = {
                    viewModel.sendCurrent(input)
                    input = ""
                },
                // 服务端要求正文非空：仅附件会以 `player input is empty` 失败（2026-10-29 实测）。
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

    if (creation.visible) {
        StoryCreationDialog(
            state = creation,
            onUpdate = viewModel::updateCreationForm,
            onSubmit = viewModel::createStory,
            onDismiss = viewModel::closeCreation,
        )
    }
}

/** 新建故事表单（P4-A / GAM-001）：基础（主角 / 开场）+ 可折叠高级（导演模块 / 生成参数 / 检定 / 呈现）。 */
@Composable
private fun StoryCreationDialog(
    state: StoryCreationState,
    onUpdate: ((StoryCreationForm) -> StoryCreationForm) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var advanced by remember { mutableStateOf(false) }
    val form = state.form
    val resources = state.resources

    Dialog(
        onDismissRequest = { if (!state.submitting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            // 键盘弹出时靠 imePadding 抬升、靠固定页脚保证按钮可达：
            // AlertDialog 的按钮区会被 IME 顶出屏幕（真机复现过点不到「创建」）。
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .imePadding()
                .padding(horizontal = 12.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    stringResource(R.string.game_create_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp),
                ) {
                if (state.loadingResources) {
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.game_create_loading),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                OutlinedTextField(
                    value = form.title,
                    onValueChange = { text -> onUpdate { it.copy(title = text) } },
                    label = { Text(stringResource(R.string.game_create_story_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                OutlinedTextField(
                    value = form.origin,
                    onValueChange = { text -> onUpdate { it.copy(origin = text) } },
                    label = { Text(stringResource(R.string.game_create_origin)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )

                CreationSection(stringResource(R.string.game_create_protagonist))
                ChoiceRow(
                    options = listOf(
                        "default" to stringResource(R.string.game_create_protagonist_default),
                        "custom" to stringResource(R.string.game_create_protagonist_custom),
                        "lore" to stringResource(R.string.game_create_protagonist_lore),
                    ),
                    selected = form.protagonistMode,
                    onSelect = { mode -> onUpdate { it.copy(protagonistMode = mode) } },
                )
                when (form.protagonistMode) {
                    "custom" -> {
                        OutlinedTextField(
                            value = form.protagonistName,
                            onValueChange = { text -> onUpdate { it.copy(protagonistName = text) } },
                            label = { Text(stringResource(R.string.game_create_protagonist_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        )
                        OutlinedTextField(
                            value = form.protagonistProfile,
                            onValueChange = { text -> onUpdate { it.copy(protagonistProfile = text) } },
                            label = { Text(stringResource(R.string.game_create_protagonist_profile)) },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        )
                    }

                    "lore" -> ResourceDropdown(
                        label = stringResource(R.string.game_create_lore_pick),
                        options = resources.loreCharacters.map { it.id to it.name },
                        selectedId = form.protagonistLoreId,
                        onSelect = { id -> onUpdate { it.copy(protagonistLoreId = id) } },
                    )
                }

                CreationSection(stringResource(R.string.game_create_opening))
                ChoiceRow(
                    options = listOf(
                        "ai" to stringResource(R.string.game_create_opening_ai),
                        "preset" to stringResource(R.string.game_create_opening_preset),
                        "custom" to stringResource(R.string.game_create_opening_custom),
                    ),
                    selected = form.openingMode,
                    onSelect = { mode -> onUpdate { it.copy(openingMode = mode) } },
                )
                when (form.openingMode) {
                    "preset" -> OutlinedTextField(
                        value = form.openingPresetText,
                        onValueChange = { text -> onUpdate { it.copy(openingPresetText = text) } },
                        label = { Text(stringResource(R.string.game_create_opening_preset_text)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )

                    "custom" -> OutlinedTextField(
                        value = form.openingCustomText,
                        onValueChange = { text -> onUpdate { it.copy(openingCustomText = text) } },
                        label = { Text(stringResource(R.string.game_create_opening_custom_text)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                }

                TextButton(onClick = { advanced = !advanced }) {
                    Text((if (advanced) "▾ " else "▸ ") + stringResource(R.string.game_create_section_advanced))
                }

                if (advanced) {
                    CreationSection(stringResource(R.string.game_create_section_modules))
                    ResourceDropdown(
                        label = stringResource(R.string.game_create_planning_template),
                        options = resources.planningTemplates.map { it.id to it.name },
                        selectedId = form.planningTemplateId,
                        onSelect = { id -> onUpdate { it.copy(planningTemplateId = id) } },
                    )
                    ResourceDropdown(
                        label = stringResource(R.string.game_create_narrative_style),
                        options = resources.tellers.map { it.id to it.name },
                        selectedId = form.narrativeStyleId,
                        onSelect = { id -> onUpdate { it.copy(narrativeStyleId = id) } },
                    )
                    SwitchRow(
                        label = stringResource(R.string.game_create_planning_enabled),
                        checked = form.planningMode == "enabled",
                        onCheckedChange = { on ->
                            onUpdate { it.copy(planningMode = if (on) "enabled" else "disabled") }
                        },
                    )
                    SwitchRow(
                        label = stringResource(R.string.game_create_rule_enabled),
                        checked = form.ruleSystemEnabled,
                        onCheckedChange = { on -> onUpdate { it.copy(ruleSystemEnabled = on) } },
                    )
                    ResourceDropdown(
                        label = stringResource(R.string.game_create_rule_system),
                        options = resources.ruleSystems.map { it.id to it.name },
                        selectedId = form.ruleSystemId,
                        onSelect = { id -> onUpdate { it.copy(ruleSystemId = id) } },
                    )
                    SwitchRow(
                        label = stringResource(R.string.game_create_actor_enabled),
                        checked = form.actorStateEnabled,
                        onCheckedChange = { on -> onUpdate { it.copy(actorStateEnabled = on) } },
                    )
                    ResourceDropdown(
                        label = stringResource(R.string.game_create_actor_state),
                        options = resources.actorStates.map { it.id to it.name },
                        selectedId = form.actorStateId,
                        onSelect = { id -> onUpdate { it.copy(actorStateId = id) } },
                    )

                    CreationSection(stringResource(R.string.game_create_event_packages))
                    if (resources.eventPackages.isEmpty()) {
                        Text(stringResource(R.string.game_create_none), style = MaterialTheme.typography.bodySmall)
                    } else {
                        resources.eventPackages.forEach { pkg ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = pkg.id in form.eventPackageIds,
                                    onCheckedChange = { checked ->
                                        onUpdate { current ->
                                            val next = if (checked) {
                                                current.eventPackageIds + pkg.id
                                            } else {
                                                current.eventPackageIds - pkg.id
                                            }
                                            current.copy(eventPackageIds = next)
                                        }
                                    },
                                )
                                Text(
                                    pkg.name.ifBlank { pkg.id },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    CreationSection(stringResource(R.string.game_create_generation))
                    ResourceDropdown(
                        label = stringResource(R.string.game_create_image_preset),
                        options = resources.imagePresets.map { it.id to it.name },
                        selectedId = form.imagePresetId,
                        onSelect = { id -> onUpdate { it.copy(imagePresetId = id) } },
                    )
                    SwitchRow(
                        label = stringResource(R.string.game_create_image_auto),
                        checked = form.imageMode == "interval",
                        onCheckedChange = { on ->
                            onUpdate { it.copy(imageMode = if (on) "interval" else "manual") }
                        },
                    )
                    if (form.imageMode == "interval") {
                        StepperRow(
                            label = stringResource(R.string.game_create_image_interval),
                            value = form.imageIntervalTurns,
                            range = 1..50,
                            onChange = { value -> onUpdate { it.copy(imageIntervalTurns = value) } },
                        )
                    }
                    StepperRow(
                        label = stringResource(R.string.game_create_reply_chars),
                        value = form.replyTargetChars,
                        range = 100..20000,
                        step = 100,
                        onChange = { value -> onUpdate { it.copy(replyTargetChars = value) } },
                    )
                    StepperRow(
                        label = stringResource(R.string.game_create_choice_count),
                        value = form.choiceCount,
                        range = 2..10,
                        onChange = { value -> onUpdate { it.copy(choiceCount = value) } },
                    )

                    CreationSection(stringResource(R.string.game_create_checks))
                    StepperRow(
                        label = stringResource(R.string.game_create_difficulty),
                        value = form.difficultyShift,
                        range = -2..2,
                        onChange = { value -> onUpdate { it.copy(difficultyShift = value) } },
                    )
                    StepperRow(
                        label = stringResource(R.string.game_create_roll_modifier),
                        value = form.rollModifier,
                        range = -20..20,
                        onChange = { value -> onUpdate { it.copy(rollModifier = value) } },
                    )

                    CreationSection(stringResource(R.string.game_create_state_schema))
                    ChoiceRow(
                        options = listOf(
                            "adapt_template" to stringResource(R.string.game_create_state_adapt),
                            "fixed_template" to stringResource(R.string.game_create_state_fixed),
                            "generate" to stringResource(R.string.game_create_state_generate),
                        ),
                        selected = form.stateSchemaMode,
                        onSelect = { mode -> onUpdate { it.copy(stateSchemaMode = mode) } },
                    )

                    CreationSection(stringResource(R.string.game_create_presentation))
                    SwitchRow(
                        label = stringResource(R.string.game_create_presentation_bg),
                        checked = form.presentationBackground,
                        onCheckedChange = { on -> onUpdate { it.copy(presentationBackground = on) } },
                    )
                    SwitchRow(
                        label = stringResource(R.string.game_create_presentation_chars),
                        checked = form.presentationCharacters,
                        onCheckedChange = { on -> onUpdate { it.copy(presentationCharacters = on) } },
                    )
                }

                state.error?.let {
                    Text(
                        it.resolve(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, enabled = !state.submitting) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    TextButton(
                        onClick = onSubmit,
                        enabled = !state.submitting && !state.loadingResources,
                    ) { Text(stringResource(R.string.game_create_submit)) }
                }
            }
        }
    }
}

/**
 * 提问卡片（GAM-011）：每个问题一行选项；单选点选替换、多选切换。
 * 未全部作答时提交按钮不可用，避免发空答案被服务端以 `invalid_ask_answer` 拒绝。
 */
@Composable
private fun AskCard(
    ask: AgentAskInteraction,
    busy: Boolean,
    onSubmit: (Map<String, List<String>>) -> Unit,
    onApprove: (ApprovalOption) -> Unit,
    onCancel: () -> Unit,
) {
    var selected by remember(ask.id) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                when {
                    ask.isPending && ask.isToolApproval -> stringResource(R.string.approval_title)
                    ask.isPending && ask.isVerification -> stringResource(R.string.verify_title)
                    ask.isPending -> stringResource(R.string.game_ask_title)
                    ask.isToolApproval -> approvalStateLabel(ask)
                    else -> AgentAskLogic.statusText(ask).resolve()
                },
                style = MaterialTheme.typography.titleSmall,
            )
            // 工具审批（GAM-011）：questions 为空，决策在 approval 里；选项与 AGT-003 一致。
            if (ask.isToolApproval) {
                val approval = ask.approval
                Text(
                    "${stringResource(R.string.approval_label_tool)}: ${approval?.toolName.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "${stringResource(R.string.approval_label_mode)}: ${approvalModeLabel(approval?.mode)}" +
                        " · ${approvalRiskLabel(approval?.risk)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                AgentApprovalLogic.body(approval)?.let { body ->
                    Text(
                        body,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                AgentApprovalLogic.reason(approval?.ruleId).takeIf { it != ApprovalReason.GENERIC }?.let { reason ->
                    Text(
                        "${stringResource(R.string.approval_reason_label)}: ${approvalReasonLabel(reason)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (ask.isPending) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onCancel, enabled = !busy) {
                            Text(stringResource(R.string.common_cancel))
                        }
                        AgentApprovalLogic.options(approval).forEach { option ->
                            TextButton(onClick = { onApprove(option) }, enabled = !busy) {
                                Text(approvalOptionLabel(option))
                            }
                        }
                    }
                }
                return@Column
            }
            // 工具副作用验证（AGT-004 补验）：与 Agent 卡片同一套文案与证据展示。
            if (ask.isVerification) {
                val verification = ask.verification
                Text(
                    stringResource(R.string.verify_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (!verification?.tool.isNullOrBlank()) {
                    Text(verification?.tool.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
                AgentVerificationLogic.argumentsText(verification)?.let { arguments ->
                    Text(
                        arguments,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ask.questions.forEach { question ->
                Text(
                    if (ask.isVerification) stringResource(R.string.verify_question) else question.question,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    question.options.forEach { option ->
                        val localized = AgentVerificationLogic.optionLabelRes(option.id)
                            ?.takeIf { ask.isVerification }
                            ?.let { stringResource(it) }
                        FilterChip(
                            selected = selected[question.id]?.contains(option.id) == true,
                            onClick = {
                                selected = selected + (
                                    question.id to AgentAskLogic.toggle(
                                        question,
                                        selected[question.id].orEmpty(),
                                        option.id,
                                    )
                                    )
                            },
                            label = { Text(localized ?: option.label.ifBlank { option.id }, maxLines = 1) },
                        )
                    }
                }
            }
            if (ask.isPending) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel, enabled = !busy) {
                        Text(
                            stringResource(
                                if (ask.isVerification) R.string.verify_cancel_task else R.string.common_cancel,
                            ),
                        )
                    }
                    TextButton(
                        onClick = { onSubmit(selected) },
                        enabled = !busy && AgentAskLogic.canSubmit(ask, selected),
                    ) { Text(stringResource(R.string.game_ask_submit)) }
                }
            }
        }
    }
}

@Composable
private fun CreationSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/** 单选行（主角模式 / 开场模式 / 状态结构）：窄屏可横向滚动，不换行折断。 */
@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

/** 资源下拉：空列表时禁用（例如当前书没有资料库角色）。 */
@Composable
private fun ResourceDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = options.firstOrNull { it.first == selectedId }?.second
        ?: selectedId.ifBlank { stringResource(R.string.game_create_none) }
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "$label: $selectedName",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name.ifBlank { id }) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 数值步进（避免负号键盘与 IME 干扰；范围在按钮上直接夹紧）。 */
@Composable
private fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(
            onClick = { onChange((value - step).coerceIn(range.first, range.last)) },
            enabled = value > range.first,
        ) { Text("−") }
        Text(value.toString(), style = MaterialTheme.typography.bodyMedium)
        TextButton(
            onClick = { onChange((value + step).coerceIn(range.first, range.last)) },
            enabled = value < range.last,
        ) { Text("+") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    val scrollTarget by viewModel.scrollTarget.collectAsState()
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
    // 轮次跳转（GAM-007）优先：reverseLayout 下块 b 的位置是 size-1-b。
    val listState = rememberLazyListState()
    var lastBlockCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(blocks.size, scrollTarget) {
        val target = scrollTarget
        if (target != null) {
            if (blocks.isNotEmpty()) {
                listState.scrollToItem((blocks.size - 1 - target).coerceIn(0, blocks.size - 1))
            }
            viewModel.onScrollTargetHandled()
            lastBlockCount = blocks.size
            return@LaunchedEffect
        }
        if (blocks.size > lastBlockCount + 20) listState.scrollToItem(0)
        lastBlockCount = blocks.size
    }

    // 滚到最旧端（reverseLayout 的末端）就继续向前翻页（GAM-007）：
    // 只在「到达」瞬间触发，是否真的发请求由 ViewModel 的 hasMore/loadingEarlier 决定。
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= 0 && last >= info.totalItemsCount - 2
        }
            .distinctUntilChanged()
            .collect { atOldest -> if (atOldest) viewModel.onNarrativeTopReached() }
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

/**
 * 附件 / 配图 / 上下文压缩（GAM-016）。
 *
 * 三者都是「用户可见进度 + 结果 + 错误」的显式状态：附件读取与预算校验在前，
 * 配图与压缩的命令 id 由 ViewModel 缓存（失败重试复用，成功后换新），
 * 因此重复点击不会生成两个实体，也不会把 `skipped_reason` 当成成功。
 */
@Composable
private fun GameMediaPanel(
    media: GameMediaState,
    latestTurnId: String?,
    latestTurnNumber: Int?,
    busy: Boolean,
    imageUrl: String?,
    imageLoader: ImageLoader?,
    onPickAttachments: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onClearAttachments: () -> Unit,
    onDismissError: () -> Unit,
    onGenerateImage: (String) -> Unit,
    onCompact: () -> Unit,
    onRemoveCompaction: () -> Unit,
) {
    val idle = !busy && !media.compacting && !media.removingCompaction &&
        media.generatingTurnId == null && !media.reading
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPickAttachments, enabled = !media.reading && !busy) {
                Text(stringResource(R.string.game_media_attach))
            }
            if (media.attachments.isNotEmpty()) {
                Text(
                    stringResource(R.string.game_media_count, media.attachments.size, GameAttachmentLimits.MAX_FILES),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                TextButton(onClick = onClearAttachments, enabled = idle) {
                    Text(stringResource(R.string.game_media_clear))
                }
            }
            TextButton(
                onClick = { latestTurnId?.let(onGenerateImage) },
                enabled = idle && latestTurnId != null,
            ) {
                Text(
                    if (media.generatingTurnId != null) {
                        stringResource(R.string.game_media_image_generating)
                    } else {
                        val suffix = latestTurnNumber?.let { " #$it" }.orEmpty()
                        stringResource(R.string.game_media_image_generate) + suffix
                    },
                )
            }
            TextButton(onClick = onCompact, enabled = idle) {
                Text(
                    if (media.compacting) {
                        stringResource(R.string.game_media_compacting)
                    } else {
                        stringResource(R.string.game_media_compact)
                    },
                )
            }
            TextButton(onClick = onRemoveCompaction, enabled = idle) {
                Text(
                    if (media.removingCompaction) {
                        stringResource(R.string.game_media_compact_removing)
                    } else {
                        stringResource(R.string.game_media_compact_remove)
                    },
                )
            }
        }

        if (media.attachments.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(
                    items = media.attachments,
                    key = { index, item -> "att-$index-${item.name}-${item.sizeBytes}" },
                ) { index, item ->
                    AssistChip(
                        onClick = { onRemoveAttachment(index) },
                        label = {
                            Text(
                                text = "${item.name} · ${formatAttachmentSize(item.sizeBytes)} ×",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 220.dp),
                            )
                        },
                    )
                }
            }
        }

        if (media.reading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.game_media_reading),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        media.attachmentError?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    error.resolve(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissError) { Text("×") }
            }
        }
        if (media.generatingTurnId != null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        media.imageNotices.values.lastOrNull()?.let { notice ->
            Text(
                notice.resolve(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val imageAlt = stringResource(R.string.game_media_image_alt)
        if (imageUrl != null && imageLoader != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(imageUrl).build(),
                imageLoader = imageLoader,
                contentDescription = stringResource(R.string.game_media_image_alt),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(180.dp).padding(vertical = 4.dp),
            )
            media.images.values.lastOrNull()?.let { image ->
                Text(
                    text = buildString {
                        append(imageAlt)
                        val model = image.model.ifBlank { image.provider }
                        if (model.isNotBlank()) append(" · ").append(model)
                        if (image.sizeBytes > 0) {
                            append(" · ").append(formatAttachmentSize(image.sizeBytes))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        media.compactionNotice?.let { notice ->
            Text(
                notice.resolve(),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        media.compactionError?.let { error ->
            Text(
                error.resolve(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        media.compactionSummary?.takeIf { it.isNotBlank() }?.let { summary ->
            Text(
                stringResource(R.string.game_media_compact_summary, summary),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 审批选项文案（顺序由 [AgentApprovalLogic.options] 决定：拒绝 → 始终允许 → 允许本次）。 */
@Composable
private fun approvalOptionLabel(option: ApprovalOption): String = stringResource(
    when (option) {
        ApprovalOption.DENY -> R.string.approval_deny
        ApprovalOption.ALLOW_WORKSPACE -> R.string.approval_allow_workspace
        ApprovalOption.ALLOW_ONCE -> R.string.approval_allow_once
    },
)

@Composable
private fun approvalModeLabel(value: String?): String = stringResource(
    when (AgentApprovalLogic.mode(value)) {
        ApprovalMode.ASK -> R.string.approval_mode_ask
        ApprovalMode.WRITE -> R.string.approval_mode_write
        ApprovalMode.FULL_ACCESS -> R.string.approval_mode_full
        ApprovalMode.UNKNOWN -> R.string.approval_mode_unknown
    },
)

@Composable
private fun approvalRiskLabel(value: String?): String = stringResource(
    when (AgentApprovalLogic.risk(value)) {
        ApprovalRisk.LOW -> R.string.approval_risk_low
        ApprovalRisk.MEDIUM -> R.string.approval_risk_medium
        ApprovalRisk.HIGH -> R.string.approval_risk_high
        ApprovalRisk.CRITICAL -> R.string.approval_risk_critical
        ApprovalRisk.UNKNOWN -> R.string.approval_risk_unknown
    },
)

@Composable
private fun approvalReasonLabel(reason: ApprovalReason): String = stringResource(
    when (reason) {
        ApprovalReason.WORKSPACE_UNAVAILABLE -> R.string.approval_reason_workspace_unavailable
        ApprovalReason.INVALID_WORKSPACE_BOUNDARY -> R.string.approval_reason_invalid_boundary
        ApprovalReason.BASH_PARSE_FAILED -> R.string.approval_reason_bash_parse_failed
        ApprovalReason.BASH_DYNAMIC_SYNTAX -> R.string.approval_reason_bash_dynamic
        ApprovalReason.BASH_UNLISTED_COMMAND -> R.string.approval_reason_bash_unlisted
        ApprovalReason.BASH_UNCLASSIFIED -> R.string.approval_reason_bash_unclassified
        ApprovalReason.PWSH_DYNAMIC_SYNTAX -> R.string.approval_reason_pwsh_dynamic
        ApprovalReason.PWSH_PARSE_FAILED -> R.string.approval_reason_pwsh_parse_failed
        ApprovalReason.PWSH_UNLISTED_COMMAND -> R.string.approval_reason_pwsh_unlisted
        ApprovalReason.BROWSER_ARGUMENTS_UNKNOWN -> R.string.approval_reason_browser_args
        ApprovalReason.BROWSER_REMOTE_MUTATION -> R.string.approval_reason_browser_mutation
        ApprovalReason.BROWSER_COMMAND_UNKNOWN -> R.string.approval_reason_browser_command
        ApprovalReason.BROWSER_ACTION_UNKNOWN -> R.string.approval_reason_browser_action
        ApprovalReason.BROWSER_NETWORK_ACCESS -> R.string.approval_reason_browser_network
        ApprovalReason.EXTERNAL_FILESYSTEM_READ -> R.string.approval_reason_external_fs
        ApprovalReason.GENERIC -> R.string.approval_reason_generic
    },
)

/** 已解决态：以用户实际选中的选项为先（服务端把「拒绝」记成 cancelled 但保留 answers）。 */
@Composable
private fun approvalStateLabel(ask: AgentAskInteraction): String {
    val selected = ask.answers.flatMap { it.selectedOptions }.firstOrNull()?.id
    return stringResource(
        when (AgentApprovalLogic.state(ask.status, selected)) {
            ApprovalState.WAITING -> R.string.approval_state_waiting
            ApprovalState.ALLOWED -> R.string.approval_state_allowed
            ApprovalState.ALLOWED_WORKSPACE -> R.string.approval_state_allowed_workspace
            ApprovalState.DENIED -> R.string.approval_state_denied
            ApprovalState.CANCELLED -> R.string.approval_state_cancelled
        },
    )
}

/** 附件 / 图片体积展示（1 KiB = 1024 B，与服务端上限口径一致）。 */
private fun formatAttachmentSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MiB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
