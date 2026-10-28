package com.denova.novara.feature.lore

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.lore.LoreDraft
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.LoreClassificationApplyRequest
import com.denova.novara.core.network.LoreClassificationPreview
import com.denova.novara.core.network.LoreClassificationRequest
import com.denova.novara.core.network.LoreCategory
import com.denova.novara.core.network.LoreCategoryMutation
import com.denova.novara.core.network.LoreIndexPreview
import com.denova.novara.core.network.LoreIndexSnapshot
import com.denova.novara.core.network.LoreIndexUpdate
import com.denova.novara.core.network.LoreItemSummary
import com.denova.novara.core.network.LoreMaterialMutation
import com.denova.novara.core.network.LoreSpeechRequest
import com.denova.novara.core.network.LoreTypeChange
import com.denova.novara.core.network.LoreImageGenerateRequest
import com.denova.novara.core.network.LoreOptions
import com.denova.novara.core.network.UploadFile
import com.denova.novara.core.persistence.LoreDraftStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 资料库页面状态（P4-C / LOR-001…004）。 */
data class LoreUiState(
    val loading: Boolean = false,
    val projectId: String = "",
    val categories: List<LoreCategory> = emptyList(),
    val items: List<LoreItemSummary> = emptyList(),
    val selectedCategory: String = "",
    val query: String = "",
    val selectedItemId: String? = null,
    /** 编辑中的草稿：换页再回来仍在（LOR-004），并落盘以便进程被杀后恢复。 */
    val draft: LoreDraft? = null,
    val busy: Boolean = false,
    /** 材料上传单独一个标志，界面要显示「上传中」而不是笼统的忙碌。 */
    val uploading: Boolean = false,
    val notice: UiText? = null,
    val error: UiText? = null,
    val index: LoreIndexSnapshot? = null,
    val indexIntro: String = "",
    val indexPreview: LoreIndexPreview? = null,
    val classification: LoreClassificationPreview? = null,
    /** 预览里勾选要应用的条目（只提交确认范围）。 */
    val classificationPicked: Set<String> = emptySet(),
    val assets: List<com.denova.novara.core.network.LoreMaterial> = emptyList(),
    /** 进行中的生成任务：`image` / `speech`；非空时界面显示进度与取消。 */
    val generating: String? = null,
) {
    fun visibleItems(): List<LoreItemSummary> {
        val q = query.trim()
        return items.filter { item ->
            (selectedCategory.isBlank() || item.type == selectedCategory) &&
                (q.isBlank() || item.name.contains(q, true) || item.briefDescription.contains(q, true))
        }
    }

    fun selectedItem(): LoreItemSummary? = items.firstOrNull { it.id == selectedItemId }

    fun categoryLabel(id: String): String = categories.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }
        ?: id

    fun categoryCount(categoryId: String): Int = items.count { it.type == categoryId }

    /** 删除分类时可选的迁移目标：排除自身与 `character`（后端明确拒绝迁往 character）。 */
    fun deleteDestinations(categoryId: String): List<LoreCategory> =
        categories.filter { it.id != categoryId && it.id != com.denova.novara.core.network.LoreOptions.CHARACTER }

    /** 条目可移动到的分类：排除自身当前分类。 */
    fun moveTargets(item: LoreItemSummary): List<LoreCategory> = categories.filter { it.id != item.type }
}

/**
 * 资料库：分类与条目（LOR-001）、材料与媒体（LOR-002）、索引与分类预览（LOR-003）、
 * 跨页草稿（LOR-004）。服务端是唯一事实源，所有写操作完成后都重读。
 */
class LoreViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private var client: DenovaClient? = null
    private val _state = MutableStateFlow(LoreUiState())
    val state: StateFlow<LoreUiState> = _state.asStateFlow()

    private val draftStore = LoreDraftStore(application)
    private var loadJob: Job? = null
    private var draftJob: Job? = null
    private var generateJob: Job? = null
    private var serverScope: String = ""
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        // Activity 重建（返回键 / 配置变化）会重建 ViewModel：草稿从 SavedStateHandle 恢复，
        // 否则「正在编辑的条目」在离开页面后就没了（LOR-004）。
        savedState.get<String>(KEY_DRAFT)?.let { raw ->
            runCatching { json.decodeFromString(LoreDraft.serializer(), raw) }
                .onSuccess { draft -> restoreDraft(draft) }
        }
    }

    private fun restoreDraft(draft: LoreDraft) {
        _state.value = _state.value.copy(draft = draft, selectedItemId = draft.id.ifBlank { null })
    }

    private fun persistDraft(draft: LoreDraft?) {
        if (draft == null) {
            savedState.remove<String>(KEY_DRAFT)
        } else {
            savedState[KEY_DRAFT] = json.encodeToString(LoreDraft.serializer(), draft)
        }
        // 落盘用于进程被杀 / 升级安装后恢复；连续输入按 400ms 合并，避免每次按键都写盘。
        val scope = draftScope()
        draftJob?.cancel()
        if (scope.isBlank()) return
        if (draft == null) {
            viewModelScope.launch { draftStore.clear(scope) }
            return
        }
        draftJob = viewModelScope.launch {
            delay(400)
            draftStore.save(scope, draft)
        }
    }

    private fun draftScope(): String {
        val projectId = _state.value.projectId
        if (serverScope.isBlank() || projectId.isBlank()) return ""
        return "$serverScope|$projectId"
    }

    fun bindClient(client: DenovaClient) {
        val origin = client.serverOrigin
        if (serverScope.isNotEmpty() && serverScope != origin) {
            // 换服务器：不继承上一个服务器的草稿与选中项（AGENTS §4）。
            _state.value = _state.value.copy(draft = null, selectedItemId = null)
            persistDraft(null)
        }
        this.client = client
        this.serverScope = origin
    }

    fun load(projectId: String, force: Boolean = false) {
        val c = client ?: return
        if (projectId.isBlank()) {
            // 冷启动直接进资料库时外壳还没拿到项目：用服务端当前工作区反查，
            // 否则页面会停在「0 个条目」且没有任何提示。
            if (_state.value.loading) return
            _state.value = _state.value.copy(loading = true)
            viewModelScope.launch {
                val pid = runCatching { c.currentWorkspace().projectId }.getOrNull().orEmpty()
                if (pid.isBlank()) {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = UiText.of(R.string.lore_no_project),
                    )
                } else {
                    load(pid, force = true)
                }
            }
            return
        }
        if (!force && _state.value.projectId == projectId && _state.value.items.isNotEmpty()) return
        val previousProject = _state.value.projectId
        if (previousProject.isNotBlank() && previousProject != projectId) {
            // 切作品：草稿按项目隔离，不把上一本的正文带进新书；旧草稿留在盘上，
            // 回到原作品时仍可恢复（LOR-004）。
            savedState.remove<String>(KEY_DRAFT)
            _state.value = _state.value.copy(draft = null, selectedItemId = null)
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, projectId = projectId)
            try {
                val categories = c.loreCategories(projectId)
                val items = c.projectLoreItems(projectId).items
                val categoryIds = categories.map { it.id }.toSet()
                _state.value = _state.value.copy(
                    loading = false,
                    categories = categories,
                    items = items,
                    // 选中的分类如果被删了，退回第一个分类。
                    selectedCategory = _state.value.selectedCategory.takeIf { it in categoryIds }
                        ?: categories.firstOrNull()?.id.orEmpty(),
                )
                DenovaLog.i("Lore", "loaded project=$projectId items=${items.size} categories=${categories.size}")
                // 进程重建后恢复未保存的草稿（LOR-004）。SavedStateHandle 有值时以它为准。
                if (_state.value.draft == null) restorePersistedDraft()
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = failureText(e, R.string.lore_load_failed))
            }
        }
    }

    private fun restorePersistedDraft() {
        val scope = draftScope()
        if (scope.isBlank()) return
        viewModelScope.launch {
            val draft = draftStore.load(scope) ?: return@launch
            if (_state.value.draft != null) return@launch
            restoreDraft(draft)
            DenovaLog.i("Lore", "draft restored id=${draft.id.ifBlank { "new" }} isNew=${draft.isNew}")
        }
    }

    fun selectCategory(categoryId: String) {
        _state.value = _state.value.copy(selectedCategory = categoryId)
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    // ---- 条目（LOR-001 / LOR-004）----

    fun selectItem(item: LoreItemSummary) {
        // 同一个条目的未保存草稿不能被列表点击覆盖（LOR-004）。
        if (_state.value.selectedItemId == item.id && _state.value.draft?.id == item.id) return
        val category = _state.value.categories.firstOrNull { it.id == item.type }?.id
            ?: _state.value.selectedCategory
        _state.value = _state.value.copy(
            selectedItemId = item.id,
            draft = LoreDraft.from(item, category),
            notice = null,
            error = null,
        )
        persistDraft(_state.value.draft)
    }

    fun startNewItem() {
        val category = _state.value.selectedCategory.ifBlank { "world" }
        _state.value = _state.value.copy(
            selectedItemId = null,
            draft = LoreDraft.create(category),
            notice = null,
            error = null,
        )
        persistDraft(_state.value.draft)
    }

    fun closeEditor() {
        _state.value = _state.value.copy(draft = null, selectedItemId = null)
        persistDraft(null)
    }

    fun updateDraft(transform: (LoreDraft) -> LoreDraft) {
        val draft = _state.value.draft ?: return
        _state.value = _state.value.copy(draft = transform(draft))
        persistDraft(_state.value.draft)
    }

    fun saveDraft() {
        val c = client ?: return
        val s = _state.value
        val draft = s.draft ?: return
        // 提交中/上传中都不允许再次提交：一次点击只发一个写请求（LOR-004）。
        if (!draft.canSave() || s.busy) return
        val projectId = s.projectId
        val scope = draftScope()
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null)
            try {
                val item = if (draft.isNew) {
                    c.createLoreItem(projectId, draft.toInput())
                } else {
                    val base = s.selectedItem()?.updatedAt.orEmpty()
                    c.updateLoreItem(projectId, draft.id, draft.toInput(baseRevision = base))
                }
                DenovaLog.i("Lore", "saved item=${item.id} name=${item.name}")
                // 已保存：服务端成为事实源，本地草稿不再需要恢复。
                _state.value = _state.value.copy(draft = null)
                savedState.remove<String>(KEY_DRAFT)
                draftJob?.cancel()
                if (scope.isNotBlank()) draftStore.clear(scope)
                reloadKeepingSelection(item.id, UiText.of(R.string.lore_saved))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(
                    busy = false,
                    error = failureText(e, R.string.lore_save_failed),
                )
            }
        }
    }

    /** 条目移动到另一个分类：等价于把 `type` 改成目标分类 id（后端以分类表校验）。 */
    fun moveItem(item: LoreItemSummary, categoryId: String) {
        val c = client ?: return
        val projectId = _state.value.projectId
        if (categoryId.isBlank() || categoryId == item.type) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null)
            try {
                val input = LoreDraft.from(item, categoryId)
                    .toInput(baseRevision = item.updatedAt)
                    .copy(type = LoreDraft.typeForCategory(categoryId))
                val updated = c.updateLoreItem(projectId, item.id, input)
                DenovaLog.i("Lore", "moved item=${item.id} to=${updated.type}")
                reloadKeepingSelection(updated.id, UiText.of(R.string.lore_item_moved))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(
                    busy = false,
                    error = failureText(e, R.string.lore_item_move_failed),
                )
            }
        }
    }

    fun deleteItem(item: LoreItemSummary) {
        val c = client ?: return
        val projectId = _state.value.projectId
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                c.deleteLoreItem(projectId, item.id)
                DenovaLog.i("Lore", "deleted item=${item.id}")
                reloadKeepingSelection(null, UiText.of(R.string.lore_deleted))
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = failureText(e, R.string.lore_delete_failed))
            }
        }
    }

    fun toggleEnabled(item: LoreItemSummary) {
        val c = client ?: return
        val projectId = _state.value.projectId
        viewModelScope.launch {
            try {
                c.updateLoreItem(
                    projectId,
                    item.id,
                    LoreDraft.from(item, item.type).toInput(baseRevision = item.updatedAt).copy(
                        enabled = !item.enabled,
                    ),
                )
                reloadKeepingSelection(_state.value.selectedItemId, null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = failureText(e, R.string.lore_save_failed))
            }
        }
    }

    // ---- 分类（LOR-001）----

    /**
     * 分类上移 / 下移：`op=move` + 目标下标（服务端 `slices.Move` 语义）。
     * 与 Web `LoreCategoryManager` 的 ArrowUp / ArrowDown 一致。
     */
    fun moveCategory(id: String, delta: Int) {
        val categories = _state.value.categories
        val from = categories.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = from + delta
        if (to < 0 || to >= categories.size) return
        mutateCategory(LoreCategoryMutation(op = "move", id = id, index = to))
    }

    fun mutateCategory(mutation: LoreCategoryMutation) {
        val c = client ?: return
        val projectId = _state.value.projectId
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val categories = c.mutateLoreCategory(projectId, mutation)
                val items = c.projectLoreItems(projectId).items
                _state.value = _state.value.copy(
                    busy = false,
                    categories = categories,
                    items = items,
                    notice = UiText.of(R.string.lore_category_saved),
                )
                DenovaLog.i("Lore", "category ${mutation.op} id=${mutation.id} total=${categories.size}")
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = failureText(e, R.string.lore_category_failed),
                )
            }
        }
    }

    // ---- 材料与媒体（LOR-002）----

    fun refreshAssets() {
        val c = client ?: return
        val projectId = _state.value.projectId
        viewModelScope.launch {
            runCatching { c.loreAssets(projectId) }
                .onSuccess { _state.value = _state.value.copy(assets = it) }
                .onFailure { DenovaLog.w("Lore", "assets failed", it) }
        }
    }

    /** 从项目素材里关联既有材料（`op=add`），不需要重新上传。 */
    fun addMaterialFromLibrary(item: LoreItemSummary, assetId: String) {
        val c = client ?: return
        val projectId = _state.value.projectId
        viewModelScope.launch {
            _state.value = _state.value.copy(error = null, notice = null)
            try {
                val updated = c.mutateLoreMaterial(
                    projectId,
                    item.id,
                    LoreMaterialMutation(op = "add", assetId = assetId),
                )
                DenovaLog.i("Lore", "material linked item=${item.id} asset=$assetId")
                applyUpdatedItem(updated, UiText.of(R.string.lore_material_added))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(error = failureText(e, R.string.lore_material_failed))
            }
        }
    }

    fun uploadMaterial(item: LoreItemSummary, file: UploadFile) {
        val c = client ?: return
        val projectId = _state.value.projectId
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, uploading = true, error = null, notice = null)
            try {
                val updated = c.uploadLoreMaterial(projectId, item.id, file)
                DenovaLog.i("Lore", "material uploaded item=${item.id} name=${file.fileName}")
                applyUpdatedItem(updated, UiText.of(R.string.lore_material_uploaded))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(
                    busy = false,
                    uploading = false,
                    error = failureText(e, R.string.lore_material_failed),
                )
            }
        }
    }

    fun removeMaterial(item: LoreItemSummary, assetId: String) {
        val c = client ?: return
        val projectId = _state.value.projectId
        viewModelScope.launch {
            try {
                val updated = c.mutateLoreMaterial(
                    projectId,
                    item.id,
                    LoreMaterialMutation(op = "remove", assetId = assetId),
                )
                applyUpdatedItem(updated, UiText.of(R.string.lore_material_removed))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(error = failureText(e, R.string.lore_material_failed))
            }
        }
    }

    fun setCoverMaterial(item: LoreItemSummary, assetId: String) {
        val c = client ?: return
        val projectId = _state.value.projectId
        viewModelScope.launch {
            try {
                val updated = c.mutateLoreMaterial(
                    projectId,
                    item.id,
                    LoreMaterialMutation(op = "cover", assetId = assetId),
                )
                applyUpdatedItem(updated, UiText.of(R.string.lore_cover_set))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(error = failureText(e, R.string.lore_material_failed))
            }
        }
    }

    /** 生成配图：`mode=agent` 走图像 Agent（可带附加要求），否则按提示词直接生成。 */
    fun generateImage(item: LoreItemSummary, request: LoreImageGenerateRequest) {
        val c = client ?: return
        val projectId = _state.value.projectId
        startGeneration("image") {
            c.generateLoreImage(projectId, item.id, request)
        }
    }

    /** 生成语音：服务端把朗读文本存成 MP3 材料，失败原因原样呈现（`api.lore.speech*`）。 */
    fun generateSpeech(item: LoreItemSummary, request: LoreSpeechRequest) {
        val c = client ?: return
        val projectId = _state.value.projectId
        startGeneration("speech") {
            c.generateLoreSpeech(projectId, item.id, request)
        }
    }

    /**
     * 生成任务的公共编排：进度、错误与取消。
     *
     * 取消只放弃本机等待（HTTP 请求被取消），服务端可能仍会完成并写入材料；
     * 因此取消后不自动刷新条目，界面按「已在本机取消」如实提示。
     */
    private fun startGeneration(kind: String, call: suspend () -> LoreItemSummary) {
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, generating = kind, error = null, notice = null)
            try {
                val updated = call()
                DenovaLog.i("Lore", "generated $kind item=${updated.id}")
                applyUpdatedItem(
                    updated,
                    UiText.of(if (kind == "image") R.string.lore_image_ready else R.string.lore_speech_ready),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Lore", "generate $kind failed", e)
                _state.value = _state.value.copy(
                    busy = false,
                    generating = null,
                    error = failureText(e, R.string.lore_generate_failed),
                )
            }
        }
    }

    fun cancelGeneration() {
        if (_state.value.generating == null) return
        generateJob?.cancel()
        generateJob = null
        DenovaLog.i("Lore", "generation cancelled by user")
        _state.value = _state.value.copy(
            busy = false,
            generating = null,
            notice = UiText.of(R.string.lore_generate_cancelled),
        )
    }

    // ---- 索引与分类预览（LOR-003）----

    /** 读取服务端索引快照；[keepIntro] 用于冲突后刷新 revision 而不丢用户正在改的前言。 */
    fun loadIndex(keepIntro: Boolean = false) {
        val c = client ?: return
        val projectId = _state.value.projectId
        if (projectId.isBlank()) return
        viewModelScope.launch {
            try {
                val snapshot = c.loreIndex(projectId)
                _state.value = _state.value.copy(
                    index = snapshot,
                    indexIntro = if (keepIntro) _state.value.indexIntro else snapshot.guide.introMarkdown,
                    indexPreview = null,
                )
                DenovaLog.i("Lore", "index loaded groups=${snapshot.guide.groups.size} revision=${snapshot.revision.take(12)}")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(error = failureText(e, R.string.lore_index_failed))
            }
        }
    }

    fun setIndexIntro(text: String) {
        _state.value = _state.value.copy(indexIntro = text)
    }

    /** 预览：把当前草稿交给服务端渲染成模型可见索引（不写盘）。 */
    fun previewIndex() {
        val c = client ?: return
        val s = _state.value
        val snapshot = s.index ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val guide = snapshot.guide.copy(introMarkdown = s.indexIntro)
                val preview = c.previewLoreIndex(s.projectId, guide)
                _state.value = _state.value.copy(busy = false, indexPreview = preview)
                DenovaLog.i("Lore", "index preview tokens=${preview.tokenEstimate} over=${preview.overBudget}")
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = failureText(e, R.string.lore_index_failed))
            }
        }
    }

    fun saveIndex() {
        val c = client ?: return
        val s = _state.value
        val snapshot = s.index ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val saved = c.updateLoreIndex(
                    s.projectId,
                    LoreIndexUpdate(
                        guide = snapshot.guide.copy(introMarkdown = s.indexIntro),
                        baseRevision = snapshot.revision,
                    ),
                )
                _state.value = _state.value.copy(
                    busy = false,
                    index = saved,
                    indexIntro = saved.guide.introMarkdown,
                    notice = UiText.of(R.string.lore_index_saved),
                )
                DenovaLog.i("Lore", "index saved revision=${saved.revision.take(12)}")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (isRevisionConflict(e)) {
                    // 409：别的端已经改过索引。刷新 revision 但不覆盖用户正在编辑的前言。
                    DenovaLog.w("Lore", "index save conflicted; reloading revision")
                    _state.value = _state.value.copy(
                        busy = false,
                        error = UiText.of(R.string.lore_index_conflict),
                        indexPreview = null,
                    )
                    loadIndex(keepIntro = true)
                } else {
                    _state.value = _state.value.copy(busy = false, error = failureText(e, R.string.lore_index_failed))
                }
            }
        }
    }

    fun previewClassification(mode: String = "semantic") {
        val c = client ?: return
        val projectId = _state.value.projectId
        if (projectId.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val preview = c.previewLoreClassification(projectId, LoreClassificationRequest(mode = mode))
                _state.value = _state.value.copy(
                    busy = false,
                    classification = preview,
                    // 默认全部勾选"有建议且与当前不同"的条目，用户可逐条取消。
                    classificationPicked = preview.items
                        .filter { it.suggestedType.isNotBlank() && it.suggestedType != it.currentType }
                        .map { it.id }
                        .toSet(),
                )
                DenovaLog.i("Lore", "classification preview mode=$mode items=${preview.items.size}")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 分类预览要经过模型，失败原因必须能看见（服务端给出的 key/文案），不能只显示「分类失败」。
                DenovaLog.w("Lore", "classification preview failed: ${e.javaClass.simpleName}", e)
                _state.value = _state.value.copy(
                    busy = false,
                    error = failureText(e, R.string.lore_classify_failed),
                )
            }
        }
    }

    fun toggleClassification(id: String) {
        val picked = _state.value.classificationPicked.toMutableSet()
        if (!picked.add(id)) picked.remove(id)
        _state.value = _state.value.copy(classificationPicked = picked)
    }

    fun applyClassification() {
        val c = client ?: return
        val s = _state.value
        val preview = s.classification ?: return
        val changes = preview.items
            .filter { it.id in s.classificationPicked && it.suggestedType.isNotBlank() }
            .map { LoreTypeChange(it.id, it.suggestedType) }
        if (changes.isEmpty()) {
            _state.value = s.copy(error = UiText.of(R.string.lore_classify_none))
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val result = c.applyLoreClassification(
                    s.projectId,
                    LoreClassificationApplyRequest(revision = preview.revision, changes = changes),
                )
                DenovaLog.i("Lore", "classification applied updated=${result.updated.size}")
                _state.value = _state.value.copy(classification = null, classificationPicked = emptySet())
                reloadKeepingSelection(s.selectedItemId, UiText.of(R.string.lore_classify_applied))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (isRevisionConflict(e)) {
                    // 预览之后条目被改过：丢弃旧建议，要求重新预览，避免拿过期 revision 覆盖。
                    DenovaLog.w("Lore", "classification apply conflicted; dropping preview")
                    _state.value = _state.value.copy(
                        busy = false,
                        classification = null,
                        classificationPicked = emptySet(),
                        error = UiText.of(R.string.lore_classify_conflict),
                    )
                } else {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = failureText(e, R.string.lore_classify_failed),
                    )
                }
            }
        }
    }

    fun dismissClassification() {
        _state.value = _state.value.copy(classification = null, classificationPicked = emptySet())
    }

    fun clearMessages() {
        _state.value = _state.value.copy(notice = null, error = null)
    }

    // ---- 内部 ----

    /**
     * 材料 / 媒体类回包只更新列表里的条目（图片与材料挂在那里）。
     *
     * **不动编辑中的草稿**：上传或生成请求在途时用户可能还在改正文，
     * 用服务端回包重建表单会把这些编辑抹掉（基线 `LoreDetailTabs.applyMedia` 同口径）。
     */
    private fun applyUpdatedItem(item: LoreItemSummary, notice: UiText?) {
        val items = _state.value.items.map { if (it.id == item.id) item else it }
        _state.value = _state.value.copy(
            busy = false,
            uploading = false,
            generating = null,
            items = items,
            selectedItemId = item.id,
            notice = notice,
        )
    }

    /** 409 → 服务端 `api.resource.revisionConflict`（索引与分类应用共用）。 */
    private fun isRevisionConflict(error: Exception): Boolean =
        error is DenovaApiException && (error.status == 409 || error.code == "api.resource.revisionConflict")

    private fun reloadKeepingSelection(selectedId: String?, notice: UiText?) {
        val c = client ?: return
        val projectId = _state.value.projectId
        viewModelScope.launch {
            try {
                val items = c.projectLoreItems(projectId).items
                val item = items.firstOrNull { it.id == selectedId }
                val current = _state.value.draft
                // 有未保存编辑时保留表单（用户内容优先），只刷新列表与选中项。
                val keepDraft = current != null && item != null && current.id == item.id && !current.matches(item)
                _state.value = _state.value.copy(
                    busy = false,
                    items = items,
                    selectedItemId = item?.id,
                    draft = when {
                        keepDraft -> current
                        else -> item?.let { LoreDraft.from(it, it.type) }
                    },
                    notice = notice,
                    error = null,
                )
                // 选中项与草稿一起持久化：删除 / 移动后不能把旧草稿留在盘上。
                persistDraft(_state.value.draft)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(busy = false, error = failureText(e, R.string.lore_load_failed))
            }
        }
    }

    private companion object {
        const val KEY_DRAFT = "lore_draft"
    }

    private fun failureText(error: Exception, fallback: Int): UiText {
        val message = (error as? DenovaApiException)?.message?.takeIf { it.isNotBlank() }
        return message?.let { UiText.raw(it) } ?: UiText.of(fallback)
    }
}
