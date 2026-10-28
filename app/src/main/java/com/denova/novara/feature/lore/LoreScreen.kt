package com.denova.novara.feature.lore

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.denova.novara.R
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.image.imageLoaderFor
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.LoreImageGenerateRequest
import com.denova.novara.core.network.LoreItemSummary
import com.denova.novara.core.network.LoreMaterial
import com.denova.novara.core.network.LoreOptions
import com.denova.novara.core.network.LoreSpeechRequest
import com.denova.novara.core.network.UploadFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 资料库页面（P4-C / LOR-001…004）。
 *
 * 布局对齐 Web `lore` 页：顶栏（标题 + 条目数 + 索引 / 新建 / 更多）、分类标签行、搜索、条目列表，
 * 点开条目进入表单视图（分类 / 重要度 / 标签 / 关键词 / 加载模式 / 正文 / 配图 / 材料 / 生成）。
 *
 * 三个涉及模型的入口（配图、语音、类型分类）都会显示进度并可取消；取消只放弃本机等待。
 */
@Composable
fun LoreScreen(
    client: DenovaClient?,
    projectId: String,
    onOpenDrawer: () -> Unit,
    // 由 Activity 持有：资料库草稿要跨页面存活（LOR-004），不能绑在导航目的地的作用域上。
    viewModel: LoreViewModel,
) {
    LaunchedEffect(client) { client?.let { viewModel.bindClient(it) } }
    LaunchedEffect(projectId, client) { if (client != null) viewModel.load(projectId) }
    LoreContent(
        state = viewModel.state.collectAsState().value,
        client = client,
        viewModel = viewModel,
        onOpenDrawer = onOpenDrawer,
    )
}

@Composable
private fun LoreContent(
    state: LoreUiState,
    client: DenovaClient?,
    viewModel: LoreViewModel,
    onOpenDrawer: () -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = remember(client) { client?.let { imageLoaderFor(context, it) } }

    var showIndex by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renameCategory by remember { mutableStateOf<String?>(null) }
    var deleteCategory by remember { mutableStateOf<String?>(null) }
    var newCategory by remember { mutableStateOf(false) }
    var manageCategories by remember { mutableStateOf(false) }
    var confirmDeleteItem by remember { mutableStateOf<LoreItemSummary?>(null) }
    var moveTarget by remember { mutableStateOf<LoreItemSummary?>(null) }
    var speechFor by remember { mutableStateOf<LoreItemSummary?>(null) }
    var imageFor by remember { mutableStateOf<LoreItemSummary?>(null) }
    var assetsFor by remember { mutableStateOf<LoreItemSummary?>(null) }
    // 顶栏「索引」只切视图，必须同时把服务端索引读回来；否则预览 / 保存都因为没有快照而静默失效。
    LaunchedEffect(showIndex) { if (showIndex) viewModel.loadIndex() }

    Column(
        // imePadding：表单底部（保存 / 材料）要能被键盘顶上来，否则手机上永远点不到。
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) { Text("☰") }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.lore_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.lore_subtitle, state.items.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            TextButton(onClick = { showIndex = !showIndex }) { Text(stringResource(R.string.lore_index)) }
            TextButton(onClick = viewModel::startNewItem) { Text("＋") }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lore_new_item)) },
                        onClick = {
                            menu = false
                            viewModel.startNewItem()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lore_classify)) },
                        onClick = {
                            menu = false
                            viewModel.previewClassification()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lore_new_category)) },
                        onClick = {
                            menu = false
                            newCategory = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lore_category_manage)) },
                        onClick = {
                            menu = false
                            manageCategories = true
                        },
                    )
                }
            }
        }
        HorizontalDivider()

        val notice = state.notice?.resolve()
        val error = state.error?.resolve()
        if (notice != null || error != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = error ?: notice.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::clearMessages) { Text(stringResource(R.string.common_close)) }
            }
        }

        when {
            state.draft != null -> LoreEditor(
                state = state,
                draft = state.draft,
                client = client,
                imageLoader = imageLoader,
                viewModel = viewModel,
                onDelete = { item -> confirmDeleteItem = item },
                onGenerateSpeech = { item -> speechFor = item },
                onGenerateImage = { item -> imageFor = item },
                onPickAsset = { item ->
                    assetsFor = item
                    viewModel.refreshAssets()
                },
            )
            showIndex -> LoreIndexPanel(state = state, viewModel = viewModel, onClose = { showIndex = false })
            else -> LoreList(
                state = state,
                viewModel = viewModel,
                onLongPressItem = { moveTarget = it },
                onRenameCategory = { renameCategory = it },
                onDeleteCategory = { deleteCategory = it },
            )
        }
    }

    // ---- 分类对话框 ----
    if (newCategory) {
        LoreTextDialog(
            title = stringResource(R.string.lore_new_category),
            label = stringResource(R.string.lore_category_name),
            initial = "",
            onDismiss = { newCategory = false },
            onConfirm = { name ->
                newCategory = false
                // 服务端 create 会自己分配 uuid，客户端不需要造 id。
                viewModel.mutateCategory(com.denova.novara.core.network.LoreCategoryMutation(op = "create", name = name))
            },
        )
    }
    if (manageCategories) {
        CategoryManagerDialog(
            state = state,
            onDismiss = { manageCategories = false },
            onMove = { id, delta -> viewModel.moveCategory(id, delta) },
            onRename = { id ->
                manageCategories = false
                renameCategory = id
            },
            onDelete = { id ->
                manageCategories = false
                deleteCategory = id
            },
        )
    }
    renameCategory?.let { id ->
        val current = state.categories.firstOrNull { it.id == id }?.name.orEmpty()
        LoreTextDialog(
            title = stringResource(R.string.lore_category_name),
            label = stringResource(R.string.lore_category_name),
            initial = current,
            onDismiss = { renameCategory = null },
            onConfirm = { name ->
                renameCategory = null
                viewModel.mutateCategory(
                    com.denova.novara.core.network.LoreCategoryMutation(op = "rename", id = id, name = name),
                )
            },
        )
    }
    deleteCategory?.let { id ->
        CategoryDeleteDialog(
            state = state,
            categoryId = id,
            onDismiss = { deleteCategory = null },
            onConfirm = { destination ->
                deleteCategory = null
                viewModel.mutateCategory(
                    com.denova.novara.core.network.LoreCategoryMutation(
                        op = "delete",
                        id = id,
                        destinationId = destination,
                    ),
                )
            },
        )
    }
    confirmDeleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDeleteItem = null },
            title = { Text(stringResource(R.string.common_delete)) },
            text = { Text(stringResource(R.string.lore_delete_item_confirm, item.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteItem = null
                    viewModel.deleteItem(item)
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteItem = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    moveTarget?.let { item ->
        val targets = state.moveTargets(item)
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text(stringResource(R.string.lore_item_move)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (targets.isEmpty()) {
                        Text(stringResource(R.string.lore_no_project))
                    }
                    targets.forEach { category ->
                        Row(
                            Modifier.fillMaxWidth().clickableRow {
                                moveTarget = null
                                viewModel.moveItem(item, category.id)
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                state.categoryLabel(category.id),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                state.categoryCount(category.id).toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { moveTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    speechFor?.let { item ->
        SpeechDialog(
            item = item,
            onDismiss = { speechFor = null },
            onConfirm = { name, text ->
                speechFor = null
                viewModel.generateSpeech(item, LoreSpeechRequest(name = name, text = text))
            },
        )
    }
    imageFor?.let { item ->
        ImageGenerateDialog(
            item = item,
            onDismiss = { imageFor = null },
            onConfirm = { request ->
                imageFor = null
                viewModel.generateImage(item, request)
            },
        )
    }
    assetsFor?.let { item ->
        AssetPickerDialog(
            assets = state.assets,
            onDismiss = { assetsFor = null },
            onPick = { asset ->
                assetsFor = null
                viewModel.addMaterialFromLibrary(item, asset.id)
            },
        )
    }
    state.classification?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::dismissClassification,
            title = { Text(stringResource(R.string.lore_classify)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (preview.warning.isNotBlank()) {
                        Text(preview.warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    preview.items.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = item.id in state.classificationPicked,
                                onCheckedChange = { viewModel.toggleClassification(item.id) },
                                enabled = item.suggestedType.isNotBlank(),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${item.currentType.ifBlank { "-" }} → ${item.suggestedType.ifBlank { "-" }}" +
                                        " · " + stringResource(R.string.lore_classify_confidence, item.confidence),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                                if (item.reason.isNotBlank()) {
                                    Text(
                                        item.reason,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.busy,
                    onClick = viewModel::applyClassification,
                ) { Text(stringResource(R.string.lore_classify_apply, state.classificationPicked.size)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClassification) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun LoreList(
    state: LoreUiState,
    viewModel: LoreViewModel,
    onLongPressItem: (LoreItemSummary) -> Unit,
    onRenameCategory: (String) -> Unit,
    onDeleteCategory: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.categories, key = { it.id }) { category ->
                var menu by remember(category.id) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { viewModel.selectCategory(category.id) },
                        modifier = Modifier.background(
                            if (category.id == state.selectedCategory) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            RoundedCornerShape(8.dp),
                        ),
                    ) {
                        Text("${state.categoryLabel(category.id)} ${state.categoryCount(category.id)}")
                    }
                    // 分类管理入口：点分类名切换筛选，点「⋮」改名/删除（移动端替代右键菜单）。
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_rename)) },
                                enabled = category.id != LoreOptions.CHARACTER,
                                onClick = {
                                    menu = false
                                    onRenameCategory(category.id)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_delete)) },
                                enabled = category.id != LoreOptions.CHARACTER,
                                onClick = {
                                    menu = false
                                    onDeleteCategory(category.id)
                                },
                            )
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            label = { Text(stringResource(R.string.lore_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        HorizontalDivider()
        if (state.loading && state.items.isEmpty()) {
            Text(
                stringResource(R.string.loading),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.visibleItems(), key = { it.id }) { item ->
                LoreRow(
                    state = state,
                    item = item,
                    onOpen = { viewModel.selectItem(item) },
                    onLongPress = { onLongPressItem(item) },
                    onToggle = { viewModel.toggleEnabled(item) },
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LoreRow(
    state: LoreUiState,
    item: LoreItemSummary,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${state.categoryLabel(item.type)} · ${item.importance.ifBlank { "-" }}" +
                    (if (item.keywords.isNotEmpty()) " · " + item.keywords.joinToString("、") else ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (item.briefDescription.isNotBlank()) {
                Text(
                    item.briefDescription,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(checked = item.enabled, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun LoreEditor(
    state: LoreUiState,
    draft: com.denova.novara.core.lore.LoreDraft,
    client: DenovaClient?,
    imageLoader: coil3.ImageLoader?,
    viewModel: LoreViewModel,
    onDelete: (LoreItemSummary) -> Unit,
    onGenerateSpeech: (LoreItemSummary) -> Unit,
    onGenerateImage: (LoreItemSummary) -> Unit,
    onPickAsset: (LoreItemSummary) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val item = state.selectedItem() ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            readUploadFile(context, uri)?.let { viewModel.uploadMaterial(item, it) }
        }
    }
    val item = state.selectedItem()
    val coverUrl = remember(item, client) { client?.let { loreImageUrl(it, state.projectId, item) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (draft.isNew) stringResource(R.string.lore_new_item) else draft.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (item != null) {
                TextButton(onClick = { onDelete(item) }) { Text(stringResource(R.string.common_delete)) }
            }
            TextButton(onClick = viewModel::closeEditor) { Text(stringResource(R.string.common_close)) }
        }

        // 进度与取消：上传 / 配图 / 语音在手机上可能持续数十秒，必须有可见状态与退出方式。
        if (state.uploading || state.generating != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    text = when {
                        state.uploading -> stringResource(R.string.lore_upload_running)
                        state.generating == "speech" -> stringResource(R.string.lore_generate_running_speech)
                        else -> stringResource(R.string.lore_generate_running_image)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                if (state.generating != null) {
                    TextButton(onClick = viewModel::cancelGeneration) {
                        Text(stringResource(R.string.lore_generate_cancel))
                    }
                }
            }
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (coverUrl != null && imageLoader != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(coverUrl).build(),
                imageLoader = imageLoader,
                contentDescription = stringResource(R.string.lore_image_label),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(140.dp).padding(vertical = 4.dp),
            )
        }

        OutlinedTextField(
            value = draft.name,
            onValueChange = { value -> viewModel.updateDraft { it.copy(name = value) } },
            label = { Text(stringResource(R.string.lore_field_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.lore_enabled), style = MaterialTheme.typography.bodySmall)
            Switch(checked = draft.enabled, onCheckedChange = { value -> viewModel.updateDraft { it.copy(enabled = value) } })
        }
        // 分类即条目的 type：选另一个分类就是把它移动到该分类（后端以分类表校验）。
        OptionRow(
            label = stringResource(R.string.lore_field_type),
            options = state.categories.map { it.id },
            selected = draft.type,
            onSelect = { value -> viewModel.updateDraft { it.copy(type = value, categoryId = value) } },
            render = { id -> state.categoryLabel(id) },
        )
        OptionRow(
            label = stringResource(R.string.lore_field_importance),
            options = LoreOptions.IMPORTANCE,
            selected = draft.importance,
            onSelect = { value -> viewModel.updateDraft { it.copy(importance = value) } },
        )
        OptionRow(
            label = stringResource(R.string.lore_field_load_mode),
            options = LoreOptions.LOAD_MODES,
            selected = draft.loadMode,
            onSelect = { value -> viewModel.updateDraft { it.copy(loadMode = value) } },
        )
        OutlinedTextField(
            value = draft.tagsText,
            onValueChange = { value -> viewModel.updateDraft { it.copy(tagsText = value) } },
            label = { Text(stringResource(R.string.lore_field_tags)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        OutlinedTextField(
            value = draft.keywordsText,
            onValueChange = { value -> viewModel.updateDraft { it.copy(keywordsText = value) } },
            label = { Text(stringResource(R.string.lore_field_keywords)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        OutlinedTextField(
            value = draft.briefDescription,
            onValueChange = { value -> viewModel.updateDraft { it.copy(briefDescription = value) } },
            label = { Text(stringResource(R.string.lore_field_brief)) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        OutlinedTextField(
            value = draft.content,
            onValueChange = { value -> viewModel.updateDraft { it.copy(content = value) } },
            label = { Text(stringResource(R.string.lore_field_content)) },
            minLines = 6,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = draft.canSave() && !state.busy,
                onClick = viewModel::saveDraft,
            ) { Text(stringResource(R.string.save)) }
            if (item != null) {
                TextButton(
                    enabled = !state.busy && state.generating == null,
                    onClick = { onGenerateImage(item) },
                ) { Text(stringResource(R.string.lore_generate_image)) }
                TextButton(
                    enabled = !state.busy && state.generating == null,
                    onClick = { onGenerateSpeech(item) },
                ) { Text(stringResource(R.string.lore_generate_speech)) }
                TextButton(
                    enabled = !state.busy,
                    onClick = { picker.launch(arrayOf("*/*")) },
                ) { Text(stringResource(R.string.lore_material_upload)) }
                TextButton(
                    enabled = !state.busy,
                    onClick = { onPickAsset(item) },
                ) { Text(stringResource(R.string.lore_materials_library)) }
            }
        }
        if (item != null) {
            HorizontalDivider()
            Text(
                stringResource(R.string.lore_materials, item.resolvedMaterials.size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            item.resolvedMaterials.forEach { material ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val thumb = remember(material, client) {
                        client?.let { loreMaterialUrl(it, state.projectId, material) }
                    }
                    if (thumb != null && imageLoader != null && material.mimeType.startsWith("image/")) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(thumb).build(),
                            imageLoader = imageLoader,
                            contentDescription = material.name.ifBlank { material.originalName },
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).padding(end = 8.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            material.name.ifBlank { material.originalName },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            material.mimeType + " · " + material.sizeBytes + "B",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    TextButton(onClick = { viewModel.setCoverMaterial(item, material.id) }) {
                        Text(stringResource(R.string.lore_set_cover))
                    }
                    TextButton(onClick = { viewModel.removeMaterial(item, material.id) }) {
                        Text(stringResource(R.string.common_delete))
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LoreIndexPanel(state: LoreUiState, viewModel: LoreViewModel, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.lore_index), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(enabled = !state.busy, onClick = viewModel::previewIndex) {
                Text(stringResource(R.string.lore_index_preview))
            }
            TextButton(enabled = !state.busy, onClick = viewModel::saveIndex) {
                Text(stringResource(R.string.lore_index_save))
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) }
        }
        state.index?.revision?.takeIf { it.isNotBlank() }?.let { revision ->
            Text(
                revision.take(12),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
        OutlinedTextField(
            value = state.indexIntro,
            onValueChange = viewModel::setIndexIntro,
            label = { Text(stringResource(R.string.lore_index_intro)) },
            minLines = 4,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        state.index?.guide?.groups?.forEach { group ->
            HorizontalDivider()
            Text(group.name.ifBlank { group.id }, style = MaterialTheme.typography.titleSmall)
            if (group.purpose.isNotBlank()) {
                Text(group.purpose, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (group.bodyMarkdown.isNotBlank()) {
                Text(group.bodyMarkdown, style = MaterialTheme.typography.bodySmall)
            }
        }
        state.indexPreview?.let { preview ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.lore_index_tokens, preview.tokenEstimate),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                if (preview.overBudget) {
                    Text(
                        stringResource(R.string.lore_index_over_budget),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(preview.markdown, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 分类管理：上移 / 下移 / 重命名 / 删除（对齐 Web `LoreCategoryManager`）。 */
@Composable
private fun CategoryManagerDialog(
    state: LoreUiState,
    onDismiss: () -> Unit,
    onMove: (String, Int) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lore_category_manage)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(state.categories, key = { it.id }) { category ->
                    val index = state.categories.indexOfFirst { it.id == category.id }
                    val locked = category.id == LoreOptions.CHARACTER
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.categoryLabel(category.id),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = !state.busy && index > 0,
                            onClick = { onMove(category.id, -1) },
                        ) { Text("↑") }
                        TextButton(
                            enabled = !state.busy && index in 0 until state.categories.lastIndex,
                            onClick = { onMove(category.id, 1) },
                        ) { Text("↓") }
                        TextButton(enabled = !state.busy && !locked, onClick = { onRename(category.id) }) {
                            Text(stringResource(R.string.common_rename))
                        }
                        TextButton(
                            enabled = !state.busy && !locked && state.categories.size > 1,
                            onClick = { onDelete(category.id) },
                        ) { Text(stringResource(R.string.common_delete)) }
                    }
                    if (locked) {
                        Text(
                            stringResource(R.string.lore_category_character_locked),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

/** 删除分类：必须先选条目迁移目标（后端拒绝迁往 character）。 */
@Composable
private fun CategoryDeleteDialog(
    state: LoreUiState,
    categoryId: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val destinations = state.deleteDestinations(categoryId)
    var destination by remember(categoryId, destinations.size) {
        mutableStateOf(destinations.firstOrNull()?.id.orEmpty())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lore_field_type)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        R.string.lore_category_delete_confirm,
                        state.categoryLabel(categoryId),
                        state.categoryLabel(destination),
                    ),
                )
                Text(
                    stringResource(R.string.lore_category_destination),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                destinations.forEach { category ->
                    Row(
                        Modifier.fillMaxWidth().clickableRow { destination = category.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = destination == category.id,
                            onClick = { destination = category.id },
                        )
                        Text(state.categoryLabel(category.id), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = destination.isNotBlank(), onClick = { onConfirm(destination) }) {
                Text(stringResource(R.string.common_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun OptionRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    render: (String) -> String = { it },
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
        Box {
            TextButton(onClick = { open = true }) { Text(render(selected).ifBlank { "-" }) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(render(option)) },
                        onClick = {
                            open = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoreTextDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value.trim()) }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun SpeechDialog(
    item: LoreItemSummary,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(item.name) }
    var text by remember { mutableStateOf(item.briefDescription.ifBlank { item.name }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lore_generate_speech)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.lore_speech_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.lore_speech_text)) },
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && text.isNotBlank(),
                onClick = { onConfirm(name.trim(), text.trim()) },
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** 配图生成：图像 Agent（可带附加要求）或按提示词直接生成。 */
@Composable
private fun ImageGenerateDialog(
    item: LoreItemSummary,
    onDismiss: () -> Unit,
    onConfirm: (LoreImageGenerateRequest) -> Unit,
) {
    var mode by remember { mutableStateOf("agent") }
    var instruction by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    val custom = mode != "agent"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lore_generate_image)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !custom, onClick = { mode = "agent" })
                    Text(stringResource(R.string.lore_generate_mode_agent))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = custom, onClick = { mode = "" })
                    Text(stringResource(R.string.lore_generate_mode_custom))
                }
                OutlinedTextField(
                    value = if (custom) prompt else instruction,
                    onValueChange = { if (custom) prompt = it else instruction = it },
                    label = {
                        Text(
                            stringResource(
                                if (custom) R.string.lore_generate_prompt else R.string.lore_generate_instruction,
                            ),
                        )
                    },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !custom || prompt.isNotBlank(),
                onClick = {
                    onConfirm(
                        LoreImageGenerateRequest(
                            mode = mode,
                            instruction = if (custom) "" else instruction.trim(),
                            prompt = if (custom) prompt.trim() else "",
                        ),
                    )
                },
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** 从项目素材里挑一个已有文件关联为材料（`op=add`），不重复上传。 */
@Composable
private fun AssetPickerDialog(
    assets: List<LoreMaterial>,
    onDismiss: () -> Unit,
    onPick: (LoreMaterial) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lore_materials_library)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (assets.isEmpty()) {
                    item { Text(stringResource(R.string.lore_material_empty)) }
                }
                items(assets, key = { it.id.ifBlank { it.path } }) { asset ->
                    Row(
                        Modifier.fillMaxWidth().clickableRow { onPick(asset) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                asset.name.ifBlank { asset.originalName }.ifBlank { asset.path },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                asset.mimeType + " · " + asset.sizeBytes + "B",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Text("›")
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

/** 行点击修饰（对话框里的列表项）。 */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

/** 配图地址：优先服务端给的 `image_url`，否则按项目资产接口取字节（需会话 Cookie）。 */
private fun loreImageUrl(client: DenovaClient, projectId: String, item: LoreItemSummary?): String? {
    val image = item?.image ?: return null
    return image.imageUrl.takeIf { it.isNotBlank() }
        ?: image.imagePath.takeIf { it.isNotBlank() }?.let { client.projectAssetUrl(projectId, it).toString() }
}

private fun loreMaterialUrl(client: DenovaClient, projectId: String, material: LoreMaterial): String? =
    material.url.takeIf { it.isNotBlank() }
        ?: material.path.takeIf { it.isNotBlank() }?.let { client.projectAssetUrl(projectId, it).toString() }

/** 从 content:// 读出上传字节（文件名 / MIME 供服务端判定材料类型）。 */
private suspend fun readUploadFile(context: Context, uri: Uri): UploadFile? =
    withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0).orEmpty()
            }
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            if (bytes.isEmpty()) return@runCatching null
            UploadFile(
                fileName = name.ifBlank { "material.bin" },
                mimeType = resolver.getType(uri) ?: "application/octet-stream",
                bytes = bytes,
            )
        }.getOrNull()
    }
