package com.denova.novara.feature.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.SettingsRevisions
import com.denova.novara.core.settings.SettingsEditor
import com.denova.novara.core.settings.SettingsPatchResult
import com.denova.novara.core.settings.SettingsSchema
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** 设置页界面状态（P4-D / SET-001…014）。 */
data class SettingsUiState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: UiText? = null,
    val notice: UiText? = null,
    /** 整份 `GET /api/settings` 回包：`user` / `workspace` / `effective` / `paths` / `revisions` 等。 */
    val snapshot: JsonObject? = null,
    val revisions: SettingsRevisions = SettingsRevisions(),
    val sectionId: String = SettingsSchema.SECTIONS.first().id,
    /** 待提交的草稿：路径 → 原始文本（空串 = 清除 / 继承）。 */
    val edits: Map<String, String> = emptyMap(),
    val fieldErrors: Map<String, UiText> = emptyMap(),
    val conflict: Boolean = false,
) {
    val userLayer: JsonObject? get() = snapshot?.get("user") as? JsonObject
    val effective: JsonObject? get() = snapshot?.get("effective") as? JsonObject
    val dirty: Boolean get() = edits.isNotEmpty()

    fun section() = SettingsSchema.section(sectionId) ?: SettingsSchema.SECTIONS.first()

    /** 字段当前编辑文本（JSON 类型给格式化结构，其余给显式值原文）。 */
    fun fieldText(path: String): String {
        edits[path]?.let { return it }
        val spec = SettingsSchema.field(path)
        return if (spec?.kind == com.denova.novara.core.settings.SettingsFieldKind.JSON) {
            SettingsEditor.prettyAt(effective, path)
        } else {
            SettingsEditor.textAt(userLayer, path)
        }
    }

    /** 继承值（占位 / 只读展示）。 */
    fun inheritedText(path: String): String = SettingsEditor.textAt(effective, path)

    /**
     * 只读字段的值：只读项指向整份快照里的其它节点（`paths` / `runtime` / `effective`），
     * 所以按根节点取值，而不是按 `effective` 取值。
     */
    fun readOnlyText(path: String): String = SettingsEditor.textAt(snapshot, path)

    fun isExplicit(path: String): Boolean = SettingsEditor.isExplicit(userLayer, path)
}

/**
 * 设置页：读取分层设置快照、按 14 个 section 逐字段编辑、只把改动以 merge patch 写回 `user` 层。
 *
 * 写入语义（对齐基线 `use-layered-settings-draft` / `use-auto-save-settings`）：
 * - 先本地校验（整数范围、JSON 可解析、局域网访问必须具备用户名与密码），不把必然 400 的表单发出去；
 * - `base_revision` 取 `revisions.user`，409 时**保留草稿**并提示重新加载，不静默覆盖别人的修改；
 * - 成功后用服务端回包重建快照与 revision，草稿清空。
 */
class SettingsPageViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun bindClient(client: DenovaClient) {
        if (this.client === client) return
        this.client = client
        _state.value = SettingsUiState()
        load()
    }

    fun load() {
        val c = client ?: return
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val snapshot = c.settingsSnapshot()
                _state.value = _state.value.copy(
                    loading = false,
                    snapshot = snapshot.raw,
                    revisions = snapshot.revisions,
                    edits = emptyMap(),
                    fieldErrors = emptyMap(),
                    conflict = false,
                    notice = null,
                )
                DenovaLog.i(
                    "Settings",
                    "loaded sections=${SettingsSchema.SECTIONS.size} revision=${snapshot.revisions.user.take(8)}",
                )
            } catch (e: Exception) {
                DenovaLog.w("Settings", "load failed", e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = failureText(e, R.string.settings_load_failed),
                )
            }
        }
    }

    fun selectSection(id: String) {
        if (_state.value.sectionId == id) return
        _state.value = _state.value.copy(sectionId = id)
    }

    /** 编辑一个字段；空串表示清除 / 继承。 */
    fun edit(path: String, text: String) {
        val spec = SettingsSchema.field(path) ?: return
        if (!spec.writable) return
        val edits = LinkedHashMap(_state.value.edits)
        val explicit = SettingsEditor.textAt(_state.value.userLayer, path)
        if (text == explicit) edits.remove(path) else edits[path] = text
        _state.value = _state.value.copy(
            edits = edits,
            fieldErrors = _state.value.fieldErrors - path,
            notice = null,
        )
    }

    /** 撤销某个字段的草稿（回到服务端当前显式值）。 */
    fun revertField(path: String) {
        if (!_state.value.edits.containsKey(path)) return
        _state.value = _state.value.copy(
            edits = _state.value.edits - path,
            fieldErrors = _state.value.fieldErrors - path,
        )
    }

    /** 丢弃全部草稿并重新读服务端。 */
    fun reload() {
        _state.value = _state.value.copy(edits = emptyMap(), fieldErrors = emptyMap(), conflict = false)
        load()
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null, error = null)
    }

    fun save() {
        val c = client ?: return
        if (_state.value.saving) return
        if (!_state.value.dirty) {
            _state.value = _state.value.copy(notice = UiText.of(R.string.settings_no_changes))
            return
        }
        when (val patch = SettingsEditor.buildPatch(_state.value.snapshot, _state.value.edits)) {
            is SettingsPatchResult.Invalid -> {
                _state.value = _state.value.copy(
                    fieldErrors = patch.errors,
                    error = UiText.of(R.string.settings_validation_failed),
                )
                DenovaLog.w("Settings", "patch rejected locally fields=${patch.errors.keys}")
            }

            is SettingsPatchResult.Success -> {
                val revision = _state.value.revisions.user
                viewModelScope.launch {
                    _state.value = _state.value.copy(saving = true, error = null, fieldErrors = emptyMap())
                    try {
                        val snapshot = c.patchSettings(
                            layer = "user",
                            baseRevision = revision,
                            changes = patch.changes,
                        )
                        _state.value = _state.value.copy(
                            saving = false,
                            snapshot = snapshot.raw,
                            revisions = snapshot.revisions,
                            edits = emptyMap(),
                            conflict = false,
                            notice = UiText.of(R.string.settings_saved),
                        )
                        DenovaLog.i(
                            "Settings",
                            "saved fields=${patch.changes.keys} revision=${snapshot.revisions.user.take(8)}",
                        )
                    } catch (e: Exception) {
                        val conflict = (e as? DenovaApiException)?.status == 409
                        _state.value = _state.value.copy(
                            saving = false,
                            conflict = conflict,
                            error = if (conflict) {
                                UiText.of(R.string.settings_conflict)
                            } else {
                                failureText(e, R.string.settings_save_failed)
                            },
                        )
                        DenovaLog.w("Settings", "save failed conflict=$conflict", e)
                    }
                }
            }
        }
    }

    private fun failureText(error: Exception, fallback: Int): UiText =
        (error as? DenovaApiException)?.message?.takeIf { it.isNotBlank() }?.let(UiText::raw)
            ?: UiText.of(fallback)
}
