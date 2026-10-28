package com.denova.novara.feature.lore

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.denova.novara.R
import com.denova.novara.core.i18n.resolve
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.LoreCategoryMutation
import com.denova.novara.core.network.LoreItemSummary
import com.denova.novara.core.network.LoreOptions
import com.denova.novara.core.network.LoreSpeechRequest
import com.denova.novara.core.network.UploadFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 资料库页面（P4-C / LOR-001…004）。
 *
 * 布局对齐 Web `lore` 页：顶栏（标题 + 条目数 + 索引/分类/新建）、分类标签行、搜索、条目列表，
 * 点开条目进入表单视图（类型 / 重要度 / 标签 / 关键词 / 加载模式 / 正文 / 材料 / 生成）。
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
    LoreContent(state = viewModel.state.collectAsState().value, viewModel = viewModel, onOpenDrawer = onOpenDrawer)
}

@Composable
private fun LoreContent(
    state: LoreUiState,
    viewModel: LoreViewModel,
    onOpenDrawer: () -> Unit,
) {
    var showIndex by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    var renameCategory by remember { mutableStateOf<String?>(null) }
    var deleteCategory by remember { mutableStateOf<String?>(null) }
    var newCategory by remember { mutableStateOf(false) }
    var manageCategories by remember { mutableStateOf(false) }
    var confirmDeleteItem by remember { mutableStateOf<LoreItemSummary?>(null) }
    var speechFor by remember { mutableStateOf<LoreItemSummary?>(null) }
    // 顶栏「索引」只切视图，必须同时把服务端索引读回来；否则预览 / 保存都因为没有快照而静默失效。
    LaunchedEffect(showIndex) { if (showIndex) viewModel.loadIndex() }

    Column(
        // imePadding：表单底部（保存 / 材料）要能被键盘顶上来，否则手机上永远点不到。
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
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
            // 类型分类直接放顶栏：菜单里再点一次在手机上经常失败（弹层锚点在顶栏右侧）。
            TextButton(onClick = { viewModel.previewClassification() }) {
                Text(stringResource(R.string.lore_classify))
            }
            Box {
                TextButton(onClick = { categoryMenu = true }) { Text(stringResource(R.string.lore_field_type)) }
                DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lore_new_category)) },
                        onClick = {
                            categoryMenu = false
                            newCategory = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lore_classify)) },
                        onClick = {
                            categoryMenu = false
                            viewModel.previewClassification()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lore_category_manage)) },
                        onClick = {
                            categoryMenu = false
                            manageCategories = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lore_index_preview)) },
                        onClick = {
                            categoryMenu = false
                            showIndex = true
                            viewModel.loadIndex()
                        },
                    )
                }
            }
            TextButton(onClick = viewModel::startNewItem) { Text(stringResource(R.string.lore_new_item)) }
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
                viewModel = viewModel,
                onDelete = { item -> confirmDeleteItem = item },
                onGenerateSpeech = { item -> speechFor = item },
            )
            showIndex -> LoreIndexPanel(state = state, viewModel = viewModel, onClose = { showIndex = false })
            else -> LoreList(
                state = state,
                viewModel = viewModel,
                onLongPressCategory = { deleteCategory = it },
                onRenameCategory = { renameCategory = it },
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
                viewModel.mutateCategory(
                    // 服务端 create 会自己分配 uuid，客户端不需要造 id。
                    LoreCategoryMutation(op = "create", name = name),
                )
            },
        )
    }
    if (manageCategories) {
        CategoryManagerDialog(
            state = state,
            onDismiss = { manageCategories = false },
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
                viewModel.mutateCategory(LoreCategoryMutation(op = "rename", id = id, name = name))
            },
        )
    }
    deleteCategory?.let { id ->
        // 服务端禁止迁移到 character（「choose an existing destination category」），
        // 所以优先选一个非 character 的现有分类。
        val destination = state.categories.firstOrNull { it.id != id && it.id != "character" }?.id
            ?: state.categories.firstOrNull { it.id != id }?.id.orEmpty()
        AlertDialog(
            onDismissRequest = { deleteCategory = null },
            title = { Text(stringResource(R.string.lore_field_type)) },
            text = {
                Text(
                    stringResource(
                        R.string.lore_category_delete_confirm,
                        state.categoryLabel(id),
                        state.categoryLabel(destination),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteCategory = null
                    viewModel.mutateCategory(
                        LoreCategoryMutation(op = "delete", id = id, destinationId = destination),
                    )
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCategory = null }) { Text(stringResource(R.string.common_cancel)) }
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
    speechFor?.let { item ->
        SpeechDialog(
            item = item,
            onDismiss = { speechFor = null },
            onConfirm = { name, text ->
                speechFor = null
                viewModel.generate(item, LoreSpeechRequest(name = name, text = text))
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
    onLongPressCategory: (String) -> Unit,
    onRenameCategory: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.categories, key = { it.id }) { category ->
                var menu by remember(category.id) { mutableStateOf(false) }
                Box {
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
                    ) { Text(state.categoryLabel(category.id)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_rename)) },
                            onClick = {
                                menu = false
                                onRenameCategory(category.id)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_delete)) },
                            onClick = {
                                menu = false
                                onLongPressCategory(category.id)
                            },
                        )
                    }
                    // 分类管理入口：点分类名切换筛选，点「⋮」改名/删除（移动端替代右键菜单）。
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮") }
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectItem(item) }
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
                    Switch(checked = item.enabled, onCheckedChange = { viewModel.toggleEnabled(item) })
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LoreEditor(
    state: LoreUiState,
    draft: com.denova.novara.core.lore.LoreDraft,
    viewModel: LoreViewModel,
    onDelete: (LoreItemSummary) -> Unit,
    onGenerateSpeech: (LoreItemSummary) -> Unit,
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
        OptionRow(stringResource(R.string.lore_field_type), LoreOptions.TYPES, draft.type) { value ->
            viewModel.updateDraft { it.copy(type = value) }
        }
        OptionRow(stringResource(R.string.lore_field_importance), LoreOptions.IMPORTANCE, draft.importance) { value ->
            viewModel.updateDraft { it.copy(importance = value) }
        }
        OptionRow(stringResource(R.string.lore_field_load_mode), LoreOptions.LOAD_MODES, draft.loadMode) { value ->
            viewModel.updateDraft { it.copy(loadMode = value) }
        }
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
                    enabled = !state.busy,
                    onClick = { viewModel.generate(item) },
                ) { Text(stringResource(R.string.lore_generate_image)) }
                TextButton(
                    enabled = !state.busy,
                    onClick = { onGenerateSpeech(item) },
                ) { Text(stringResource(R.string.lore_generate_speech)) }
                TextButton(
                    enabled = !state.busy,
                    onClick = { picker.launch(arrayOf("*/*")) },
                ) { Text(stringResource(R.string.lore_material_upload)) }
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
            TextButton(onClick = viewModel::previewIndex) { Text(stringResource(R.string.lore_index_preview)) }
            TextButton(onClick = viewModel::saveIndex) { Text(stringResource(R.string.lore_index_save)) }
            TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) }
        }
        state.index?.revision?.takeIf { it.isNotBlank() }?.let { revision ->
            Text(
                revision.take(12),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
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

/** 分类管理：列出全部分类，重命名 / 删除（删除时条目迁往第一个其余分类）。 */
@Composable
private fun CategoryManagerDialog(
    state: LoreUiState,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lore_category_manage)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(state.categories, key = { it.id }) { category ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.categoryLabel(category.id),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onRename(category.id) }) {
                            Text(stringResource(R.string.common_rename))
                        }
                        TextButton(
                            enabled = state.categories.size > 1,
                            onClick = { onDelete(category.id) },
                        ) { Text(stringResource(R.string.common_delete)) }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
private fun OptionRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
        Box {
            TextButton(onClick = { open = true }) { Text(selected.ifBlank { "-" }) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
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
