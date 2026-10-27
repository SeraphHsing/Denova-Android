package com.denova.novara.feature.lore

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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
import com.denova.novara.core.network.UploadFile
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
    /** 编辑中的草稿：换页再回来仍在（LOR-004）。 */
    val draft: LoreDraft? = null,
    val busy: Boolean = false,
    val notice: UiText? = null,
    val error: UiText? = null,
    val index: LoreIndexSnapshot? = null,
    val indexIntro: String = "",
    val indexPreview: LoreIndexPreview? = null,
    val classification: LoreClassificationPreview? = null,
    /** 预览里勾选要应用的条目（只提交确认范围）。 */
    val classificationPicked: Set<String> = emptySet(),
    val assets: List<com.denova.novara.core.network.LoreMaterial> = emptyList(),
    val generating: String? = null,
) {
    fun categoriesWithAll(): List<LoreCategory> = categories

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
}

/**
 * 资料库：分类与条目（LOR-001）、材料与媒体（LOR-002）、索引与分类预览（LOR-003）、
 * 跨页草稿（LOR-004）。服务端是唯一事实源，所有写操作完成后都重读。
 */
class LoreViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null
    private val _state = MutableStateFlow(LoreUiState())
    val state: StateFlow<LoreUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun bindClient(client: DenovaClient) {
        this.client = client
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
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = failureText(e, R.string.lore_load_failed))
            }
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
        val category = _state.value.categories.firstOrNull { it.id == item.type }?.id
            ?: _state.value.selectedCategory
        _state.value = _state.value.copy(
            selectedItemId = item.id,
            draft = LoreDraft.from(item, category),
            notice = null,
            error = null,
        )
    }

    fun startNewItem() {
        val category = _state.value.selectedCategory.ifBlank { "world" }
        _state.value = _state.value.copy(
            selectedItemId = null,
            draft = LoreDraft.create(category),
            notice = null,
            error = null,
        )
    }

    fun closeEditor() {
        _state.value = _state.value.copy(draft = null, selectedItemId = null)
    }

    fun updateDraft(transform: (LoreDraft) -> LoreDraft) {
        val draft = _state.value.draft ?: return
        _state.value = _state.value.copy(draft = transform(draft))
    }

    fun saveDraft() {
        val c = client ?: return
        val s = _state.value
        val draft = s.draft ?: return
        if (!draft.canSave() || s.busy) return
        val projectId = s.projectId
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
                reloadKeepingSelection(item.id, UiText.of(R.string.lore_saved))
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = failureText(e, R.string.lore_save_failed),
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

    fun uploadMaterial(item: LoreItemSummary, file: UploadFile) {
        val c = client ?: return
        val projectId = _state.value.projectId
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null)
            try {
                val updated = c.uploadLoreMaterial(projectId, item.id, file)
                DenovaLog.i("Lore", "material uploaded item=${item.id} name=${file.fileName}")
                applyUpdatedItem(updated, UiText.of(R.string.lore_material_uploaded))
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
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
                _state.value = _state.value.copy(error = failureText(e, R.string.lore_material_failed))
            }
        }
    }

    /** 生成配图 / 语音：服务端把失败原因写在 `api.lore.image*` / `speech.*` 里，原样呈现。 */
    fun generate(item: LoreItemSummary, speech: LoreSpeechRequest? = null) {
        val c = client ?: return
        val projectId = _state.value.projectId
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, generating = if (speech == null) "image" else "speech", error = null)
            try {
                val updated = if (speech == null) {
                    c.generateLoreImage(projectId, item.id)
                } else {
                    c.generateLoreSpeech(projectId, item.id, speech)
                }
                DenovaLog.i("Lore", "generated ${if (speech == null) "image" else "speech"} item=${item.id}")
                applyUpdatedItem(
                    updated,
                    UiText.of(if (speech == null) R.string.lore_image_ready else R.string.lore_speech_ready),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    generating = null,
                    error = failureText(e, R.string.lore_generate_failed),
                )
            }
        }
    }

    // ---- 索引与分类预览（LOR-003）----

    fun loadIndex() {
        val c = client ?: return
        val projectId = _state.value.projectId
        if (projectId.isBlank()) return
        viewModelScope.launch {
            try {
                val snapshot = c.loreIndex(projectId)
                _state.value = _state.value.copy(
                    index = snapshot,
                    indexIntro = snapshot.guide.introMarkdown,
                    indexPreview = null,
                )
                DenovaLog.i("Lore", "index loaded groups=${snapshot.guide.groups.size}")
            } catch (e: Exception) {
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
                _state.value = _state.value.copy(busy = false, error = failureText(e, R.string.lore_index_failed))
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
                _state.value = _state.value.copy(
                    busy = false,
                    error = failureText(e, R.string.lore_classify_failed),
                )
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

    private fun applyUpdatedItem(item: LoreItemSummary, notice: UiText?) {
        val items = _state.value.items.map { if (it.id == item.id) item else it }
        val draft = _state.value.draft
        _state.value = _state.value.copy(
            busy = false,
            generating = null,
            items = items,
            selectedItemId = item.id,
            // 只在没改过表单时同步，避免覆盖正在编辑的内容。
            draft = if (draft != null && draft.id == item.id) LoreDraft.from(item, draft.categoryId) else draft,
            notice = notice,
        )
    }

    private fun reloadKeepingSelection(selectedId: String?, notice: UiText?) {
        val c = client ?: return
        val projectId = _state.value.projectId
        viewModelScope.launch {
            try {
                val items = c.projectLoreItems(projectId).items
                val item = items.firstOrNull { it.id == selectedId }
                _state.value = _state.value.copy(
                    busy = false,
                    items = items,
                    selectedItemId = item?.id,
                    draft = item?.let { LoreDraft.from(it, it.type) },
                    notice = notice,
                    error = null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = failureText(e, R.string.lore_load_failed))
            }
        }
    }

    private fun failureText(error: Exception, fallback: Int): UiText {
        val message = (error as? DenovaApiException)?.message?.takeIf { it.isNotBlank() }
        return message?.let { UiText.raw(it) } ?: UiText.of(fallback)
    }
}
