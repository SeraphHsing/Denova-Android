package com.denova.novara.feature.presets

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.PresetKind
import com.denova.novara.core.network.PresetResource
import com.denova.novara.core.presets.PresetEditError
import com.denova.novara.core.presets.PresetEditor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** 创作方案页面状态（P4-C / PLN-001·002）。 */
data class PresetsUiState(
    val loading: Boolean = false,
    val error: UiText? = null,
    val notice: UiText? = null,
    val kind: PresetKind = PresetKind.TELLER,
    /** 每类资源各一份缓存（切换类型不重复拉取）。 */
    val resources: Map<PresetKind, List<PresetResource>> = emptyMap(),
    val query: String = "",
    /** 列表里选中的条目（查看态）。 */
    val selectedId: String = "",
    val selected: PresetResource? = null,
    val busy: Boolean = false,
    val conflict: Boolean = false,
    // ---- 编辑表单 ----
    val editing: Boolean = false,
    val creating: Boolean = false,
    /** 编辑基准（新建时为 null）；保存时用它的 `revision` 做乐观锁。 */
    val base: PresetResource? = null,
    val editId: String = "",
    val editName: String = "",
    val editDescription: String = "",
    val editDetail: String = "",
) {
    fun list(): List<PresetResource> = PresetEditor.filter(resources[kind].orEmpty(), query)

    val canDelete: Boolean get() = selected != null && !busy
}

/**
 * 创作方案：六类预设资源（导演 / 策划模板 / 事件包 / TRPG 检定 / 状态系统 / 图像方案）的
 * 目录、查看、新建、编辑与删除。
 *
 * 事实源是服务端：每次写操作成功后重新拉取该类型列表；409 时保留用户编辑的正文、只刷新
 * 编辑基准的 revision 并提示冲突（不静默覆盖别人的修改）。
 */
class PresetsViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null

    private val _state = MutableStateFlow(PresetsUiState())
    val state: StateFlow<PresetsUiState> = _state.asStateFlow()

    fun bindClient(client: DenovaClient) {
        if (this.client === client) return
        this.client = client
        val kind = _state.value.kind
        // 不能先置 loading=true 再 load()：load 里的「已在加载即返回」守卫会把它挡住，
        // 真机上表现为页面一直停在「正在加载…」（2026-10-28 修复）。
        _state.value = PresetsUiState(kind = kind)
        load(kind, force = true)
    }

    /** 切换资源类型（六类标签）。 */
    fun selectKind(kind: PresetKind) {
        if (_state.value.kind == kind && _state.value.resources.containsKey(kind)) return
        _state.value = _state.value.copy(
            kind = kind,
            selected = null,
            selectedId = "",
            editing = false,
            creating = false,
            base = null,
            conflict = false,
            error = null,
        )
        load(kind, force = !_state.value.resources.containsKey(kind))
    }

    fun load(kind: PresetKind = _state.value.kind, force: Boolean = true) {
        val c = client ?: return
        if (_state.value.loading) return
        if (!force && _state.value.resources.containsKey(kind)) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val items = PresetEditor.sort(c.presetList(kind))
                _state.value = _state.value.copy(
                    loading = false,
                    resources = _state.value.resources + (kind to items),
                )
                DenovaLog.i("Presets", "loaded kind=${kind.name} count=${items.size}")
            } catch (e: Exception) {
                DenovaLog.w("Presets", "load failed kind=${kind.name}", e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = failureText(e, R.string.presets_load_failed),
                )
            }
        }
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    /** 选中条目查看（重新读一次单项，确保 revision 与结构是最新的）。 */
    fun select(id: String) {
        val c = client ?: return
        val kind = _state.value.kind
        if (_state.value.selectedId == id && _state.value.selected != null && !_state.value.editing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(selectedId = id, error = null, notice = null, conflict = false)
            try {
                val resource = c.presetGet(kind, id)
                _state.value = _state.value.copy(selected = resource)
                DenovaLog.i(
                    "Presets",
                    "opened kind=${kind.name} id=$id revision=${resource.revision.take(8)} custom=${resource.custom}",
                )
            } catch (e: Exception) {
                DenovaLog.w("Presets", "open failed kind=${kind.name} id=$id", e)
                _state.value = _state.value.copy(error = failureText(e, R.string.presets_open_failed))
            }
        }
    }

    /** 开始编辑选中条目。 */
    fun beginEdit() {
        val resource = _state.value.selected ?: return
        val kind = _state.value.kind
        _state.value = _state.value.copy(
            editing = true,
            creating = false,
            base = resource,
            editId = resource.id,
            editName = resource.name,
            editDescription = resource.description,
            editDetail = PresetEditor.detailText(kind, resource),
            conflict = false,
            error = null,
        )
    }

    /** 开始新建（ID 可由用户指定，留空则服务端生成）。 */
    fun beginCreate() {
        val kind = _state.value.kind
        _state.value = _state.value.copy(
            editing = true,
            creating = true,
            base = null,
            editId = "",
            editName = "",
            editDescription = "",
            editDetail = PresetEditor.defaultDetailText(kind),
            conflict = false,
            error = null,
            notice = null,
        )
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(editing = false, creating = false, base = null, conflict = false)
    }

    fun editName(value: String) {
        _state.value = _state.value.copy(editName = value)
    }

    /** 新建时指定 ID（留空由服务端生成；编辑时禁用）。 */
    fun editId(value: String) {
        if (!_state.value.creating) return
        _state.value = _state.value.copy(editId = value)
    }

    fun editDescription(value: String) {
        _state.value = _state.value.copy(editDescription = value)
    }

    fun editDetail(value: String) {
        _state.value = _state.value.copy(editDetail = value)
    }

    /** 解析结构 JSON（界面可在提示里展示语法错误）。 */
    fun parseError(): UiText? {
        val (error, _) = PresetEditor.parseDetail(_state.value.editDetail)
        return error?.let { editErrorText(it) }
    }

    /** 保存：新建走 `POST`，编辑走 `PATCH`（带 `base_revision`）。 */
    fun save() {
        val c = client ?: return
        val current = _state.value
        if (current.busy) return
        val kind = current.kind
        val (parseFailure, detail) = PresetEditor.parseDetail(current.editDetail)
        if (parseFailure != null) {
            _state.value = current.copy(error = editErrorText(parseFailure))
            return
        }
        val detailObject: JsonObject = detail ?: JsonObject(emptyMap())
        val validation = PresetEditor.validate(kind, current.editId, current.editName, detailObject)
        if (validation != null) {
            _state.value = current.copy(error = editErrorText(validation))
            return
        }
        val body = PresetEditor.buildSaveBody(
            kind = kind,
            base = current.base?.raw,
            id = current.editId,
            name = current.editName,
            description = current.editDescription,
            detail = detailObject,
        )
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null, conflict = false)
            try {
                val saved = if (current.creating || current.base == null) {
                    c.presetCreate(kind, body)
                } else {
                    c.presetUpdate(kind, current.base.id, body, current.base.revision)
                }
                DenovaLog.i(
                    "Presets",
                    "saved kind=${kind.name} id=${saved.id} revision=${saved.revision.take(8)}",
                )
                _state.value = _state.value.copy(
                    busy = false,
                    editing = false,
                    creating = false,
                    base = null,
                    selectedId = saved.id,
                    selected = saved,
                    notice = UiText.of(R.string.presets_saved, saved.name.ifBlank { saved.id }),
                )
                load(kind, force = true)
            } catch (e: Exception) {
                handleSaveFailure(e, kind, current)
            }
        }
    }

    private suspend fun handleSaveFailure(
        e: Exception,
        kind: PresetKind,
        current: PresetsUiState,
    ) {
        val conflict = e is DenovaApiException && e.status == 409
        if (conflict && current.base != null) {
            DenovaLog.w("Presets", "save conflict kind=${kind.name} id=${current.base.id}")
            val latest = runCatching { client?.presetGet(kind, current.base.id) }.getOrNull()
            _state.value = _state.value.copy(
                busy = false,
                conflict = true,
                base = latest ?: current.base,
                error = UiText.of(R.string.presets_conflict),
            )
            return
        }
        if (conflict) {
            // 新建时撞上同名 ID：服务端已有该文件，提示换 ID。
            DenovaLog.w("Presets", "create conflict kind=${kind.name} id=${current.editId}")
            _state.value = _state.value.copy(
                busy = false,
                conflict = true,
                error = UiText.of(R.string.presets_id_exists, current.editId),
            )
            return
        }
        DenovaLog.w("Presets", "save failed kind=${kind.name}", e)
        _state.value = _state.value.copy(
            busy = false,
            error = failureText(e, R.string.presets_save_failed),
        )
    }

    /** 删除选中条目（二次确认由界面负责）。 */
    fun delete(id: String) {
        val c = client ?: return
        val kind = _state.value.kind
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null)
            try {
                c.presetDelete(kind, id)
                DenovaLog.i("Presets", "deleted kind=${kind.name} id=$id")
                _state.value = _state.value.copy(
                    busy = false,
                    selectedId = "",
                    selected = null,
                    editing = false,
                    creating = false,
                    base = null,
                    notice = UiText.of(R.string.presets_deleted, id),
                )
                load(kind, force = true)
            } catch (e: Exception) {
                DenovaLog.w("Presets", "delete failed kind=${kind.name} id=$id", e)
                _state.value = _state.value.copy(
                    busy = false,
                    error = failureText(e, R.string.presets_delete_failed),
                )
            }
        }
    }

    fun consumeError() {
        _state.value = _state.value.copy(error = null)
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    private fun editErrorText(error: PresetEditError): UiText = when (error) {
        PresetEditError.NAME_REQUIRED -> UiText.of(R.string.presets_name_required)
        PresetEditError.INVALID_ID -> UiText.of(R.string.presets_invalid_id)
        PresetEditError.INVALID_JSON -> UiText.of(R.string.presets_invalid_json)
        PresetEditError.NOT_OBJECT -> UiText.of(R.string.presets_json_not_object)
        PresetEditError.SLOTS_REQUIRED -> UiText.of(R.string.presets_slots_required)
        PresetEditError.INVALID_SLOT_TARGET -> UiText.of(R.string.presets_slot_target_invalid)
        PresetEditError.SECTIONS_REQUIRED -> UiText.of(R.string.presets_sections_required)
        PresetEditError.SECTION_FIELDS_REQUIRED -> UiText.of(R.string.presets_section_fields_required)
        PresetEditError.TEMPLATES_REQUIRED -> UiText.of(R.string.presets_templates_required)
    }

    private fun failureText(error: Exception, fallback: Int): UiText {
        val message = (error as? DenovaApiException)?.message?.takeIf { it.isNotBlank() }
        return message?.let { UiText.raw(it) } ?: UiText.of(fallback)
    }
}
