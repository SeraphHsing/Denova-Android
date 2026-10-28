package com.denova.novara.feature.skills

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.SkillCreateRequest
import com.denova.novara.core.network.SkillDocument
import com.denova.novara.core.network.SkillDocumentSaveRequest
import com.denova.novara.core.network.SkillFileDocument
import com.denova.novara.core.network.SkillFileSaveRequest
import com.denova.novara.core.network.SkillPreference
import com.denova.novara.core.network.SkillSnapshot
import com.denova.novara.core.network.SkillSummary
import com.denova.novara.core.skills.SkillKey
import com.denova.novara.core.skills.SkillLibrary
import com.denova.novara.core.skills.SkillNameError
import com.denova.novara.core.skills.SkillStatusFilter
import com.denova.novara.core.skills.key
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 技能库目标作用域：全局目录或某个项目（项目层会合并全局层）。 */
object SkillTargets {
    const val GLOBAL = "global"
    const val PROJECT = "project"
}

/** 技能库页面状态（P4-C / SKL-001·002）。 */
data class SkillsUiState(
    val loading: Boolean = false,
    val error: UiText? = null,
    val notice: UiText? = null,
    val target: String = SkillTargets.GLOBAL,
    val projectId: String = "",
    val snapshot: SkillSnapshot = SkillSnapshot(),
    val query: String = "",
    val category: String = "all",
    val status: SkillStatusFilter = SkillStatusFilter.ALL,
    val selected: SkillKey? = null,
    val document: SkillDocument? = null,
    val content: String = "",
    val dirty: Boolean = false,
    val documentLoading: Boolean = false,
    /** SKILL.md 之外的附属文件（空串表示正在看 SKILL.md 本身）。 */
    val filePath: String = "",
    val fileDocument: SkillFileDocument? = null,
    val fileContent: String = "",
    val fileDirty: Boolean = false,
    val fileLoading: Boolean = false,
    val busy: Boolean = false,
    /** 最近一次保存因服务端 revision 变化被判冲突（保留本地正文）。 */
    val conflict: Boolean = false,
) {
    /** 当前可写的保存位置（服务端 `scopes[].writable`）。 */
    val writableScopes: List<String>
        get() = SkillLibrary.writableScopes(snapshot.scopes.filter { it.writable }.map { it.scope })
}

/**
 * 技能库：目录 / 目标作用域切换 / 搜索筛选 / 偏好开关 / SKILL.md 与附属文件读写 / 新建 / 删除。
 *
 * 关键边界（对齐基线 `SkillsView` 与后端契约）：
 * - 偏好只写目标作用域：`enabled` 与 `shared_enabled` 恰好一个，服务端回最新 snapshot；
 * - 文档保存带 `base_revision`；409 时**保留本地正文**、只刷新服务端 revision 并提示冲突；
 * - 未保存的正文按 `scope+name` 暂存在内存里，切换 Skill 不丢（离开页面即丢弃）。
 */
class SkillsViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null

    private val _state = MutableStateFlow(SkillsUiState())
    val state: StateFlow<SkillsUiState> = _state.asStateFlow()

    /** 未保存的正文（按 Skill 身份暂存，避免切列表项时丢失）。 */
    private val drafts = mutableMapOf<SkillKey, String>()

    private val projectIdOrNull: String?
        get() = _state.value.projectId.takeIf { _state.value.target == SkillTargets.PROJECT }

    fun bindClient(client: DenovaClient) {
        if (this.client === client) return
        this.client = client
        drafts.clear()
        _state.value = SkillsUiState(target = _state.value.target, projectId = _state.value.projectId)
        load(force = true)
    }

    fun setProject(projectId: String) {
        if (_state.value.projectId == projectId) return
        _state.value = _state.value.copy(projectId = projectId)
        if (_state.value.target == SkillTargets.PROJECT) {
            clearSelection()
            load(force = true)
        }
    }

    /** 切换全局 / 项目作用域（SKL-001「切全局项目选择」）。 */
    fun setTarget(target: String) {
        if (_state.value.target == target) return
        if (target == SkillTargets.PROJECT && _state.value.projectId.isBlank()) {
            _state.value = _state.value.copy(error = UiText.of(R.string.skills_project_required))
            return
        }
        _state.value = _state.value.copy(target = target, selected = null, document = null)
        clearSelection()
        load(force = true)
    }

    fun load(force: Boolean = true) {
        val c = client ?: return
        if (_state.value.loading) return
        if (!force && _state.value.snapshot.skills.isNotEmpty()) return
        val projectId = projectIdOrNull
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val snapshot = c.skillCatalog(projectId)
                _state.value = _state.value.copy(loading = false, snapshot = snapshot)
                DenovaLog.i(
                    "Skills",
                    "loaded target=${_state.value.target} skills=${snapshot.skills.size} " +
                        "scopes=${snapshot.scopes.size} shared=${snapshot.sharedEnabled}",
                )
            } catch (e: Exception) {
                DenovaLog.w("Skills", "load failed", e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = failureText(e, R.string.skills_load_failed),
                )
            }
        }
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun setCategory(category: String) {
        _state.value = _state.value.copy(category = category)
    }

    fun setStatusFilter(filter: SkillStatusFilter) {
        _state.value = _state.value.copy(status = filter)
    }

    /** 选中一个 Skill 并加载 SKILL.md。 */
    fun select(key: SkillKey) {
        val c = client ?: return
        if (_state.value.selected == key) return
        if (_state.value.documentLoading) return
        clearSelection()
        _state.value = _state.value.copy(selected = key, documentLoading = true, error = null, notice = null)
        viewModelScope.launch {
            try {
                val doc = c.skillDocument(projectIdOrNull, key.scope, key.name)
                val draft = drafts[key] ?: doc.content
                _state.value = _state.value.copy(
                    documentLoading = false,
                    document = doc,
                    content = draft,
                    dirty = draft != doc.content,
                )
                DenovaLog.i(
                    "Skills",
                    "opened scope=${key.scope} name=${key.name} files=${doc.files.size} " +
                        "revision=${doc.revision.take(8)} editable=${doc.editable}",
                )
            } catch (e: Exception) {
                DenovaLog.w("Skills", "open failed scope=${key.scope} name=${key.name}", e)
                _state.value = _state.value.copy(
                    documentLoading = false,
                    error = failureText(e, R.string.skills_document_failed),
                )
            }
        }
    }

    fun editContent(text: String) {
        val key = _state.value.selected ?: return
        drafts[key] = text
        _state.value = _state.value.copy(
            content = text,
            dirty = text != (_state.value.document?.content ?: ""),
        )
    }

    /** 保存 SKILL.md（按 revision 乐观锁）。 */
    fun saveDocument() {
        val c = client ?: return
        val doc = _state.value.document ?: return
        if (_state.value.busy) return
        val content = _state.value.content
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null, conflict = false)
            try {
                val saved = c.saveSkillDocument(
                    projectIdOrNull,
                    SkillDocumentSaveRequest(
                        scope = doc.scope,
                        name = doc.name,
                        content = content,
                        targetScope = doc.scope,
                        targetName = doc.name,
                        baseRevision = doc.revision,
                    ),
                )
                drafts.remove(SkillKey(doc.scope, doc.name))
                _state.value = _state.value.copy(
                    busy = false,
                    document = saved,
                    content = saved.content,
                    dirty = false,
                    notice = UiText.of(R.string.skills_saved),
                )
                DenovaLog.i(
                    "Skills",
                    "saved scope=${saved.scope} name=${saved.name} revision=${saved.revision.take(8)}",
                )
                refreshCatalog()
            } catch (e: Exception) {
                handleSaveFailure(e, doc, content)
            }
        }
    }

    /**
     * 保存失败：409 只刷新服务端 revision、保留用户正文；其他错误原样呈现原因。
     */
    private suspend fun handleSaveFailure(e: Exception, doc: SkillDocument, content: String) {
        val conflict = e is DenovaApiException && e.status == 409
        if (conflict) {
            DenovaLog.w("Skills", "save conflict scope=${doc.scope} name=${doc.name}")
            val latest = runCatching { client?.skillDocument(projectIdOrNull, doc.scope, doc.name) }.getOrNull()
            _state.value = _state.value.copy(
                busy = false,
                conflict = true,
                document = latest ?: doc,
                content = content,
                dirty = content != (latest?.content ?: doc.content),
                error = UiText.of(R.string.skills_conflict),
            )
            return
        }
        DenovaLog.w("Skills", "save failed scope=${doc.scope} name=${doc.name}", e)
        _state.value = _state.value.copy(
            busy = false,
            error = failureText(e, R.string.skills_save_failed),
        )
    }

    /** 新建 Skill：客户端先校验名称，再交给服务端生成 SKILL.md。 */
    fun create(
        scope: String,
        name: String,
        description: String,
        category: String,
        capabilities: List<String>,
        agents: List<String>,
    ) {
        val c = client ?: return
        val error = SkillLibrary.validateName(name)
        if (error != null) {
            _state.value = _state.value.copy(error = nameErrorText(error))
            return
        }
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null)
            try {
                val doc = c.createSkill(
                    projectIdOrNull,
                    SkillCreateRequest(
                        scope = scope,
                        name = name.trim(),
                        description = description.trim(),
                        agents = agents,
                        category = category,
                        capabilities = capabilities,
                    ),
                )
                DenovaLog.i(
                    "Skills",
                    "created scope=${doc.scope} name=${doc.name} path=${doc.path}",
                )
                _state.value = _state.value.copy(
                    busy = false,
                    selected = SkillKey(doc.scope, doc.name),
                    document = doc,
                    content = doc.content,
                    dirty = false,
                    notice = UiText.of(R.string.skills_created, doc.name),
                )
                refreshCatalog()
            } catch (e: Exception) {
                DenovaLog.w("Skills", "create failed name=$name", e)
                _state.value = _state.value.copy(
                    busy = false,
                    error = failureText(e, R.string.skills_create_failed),
                )
            }
        }
    }

    /** 删除当前 Skill 目录（含附属文件）。 */
    fun deleteSelected() {
        val c = client ?: return
        val key = _state.value.selected ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null)
            try {
                c.deleteSkill(projectIdOrNull, key.scope, key.name)
                DenovaLog.i("Skills", "deleted scope=${key.scope} name=${key.name}")
                drafts.remove(key)
                _state.value = _state.value.copy(
                    busy = false,
                    notice = UiText.of(R.string.skills_deleted, key.name),
                )
                clearSelection()
                refreshCatalog()
            } catch (e: Exception) {
                DenovaLog.w("Skills", "delete failed scope=${key.scope} name=${key.name}", e)
                _state.value = _state.value.copy(
                    busy = false,
                    error = failureText(e, R.string.skills_delete_failed),
                )
            }
        }
    }

    /** 偏好在 target 作用域里切换单个 Skill 的启用状态（SKL-001）。 */
    fun setEnabled(skill: SkillSummary, enabled: Boolean) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                val snapshot = c.setSkillPreference(
                    projectIdOrNull,
                    SkillPreference.Skill(skill.scope, skill.name, enabled),
                )
                _state.value = _state.value.copy(snapshot = snapshot, error = null)
                DenovaLog.i("Skills", "preference scope=${skill.scope} name=${skill.name} enabled=$enabled")
            } catch (e: Exception) {
                DenovaLog.w("Skills", "preference failed name=${skill.name}", e)
                _state.value = _state.value.copy(
                    error = failureText(e, R.string.skills_preference_failed),
                )
            }
        }
    }

    /** 全局「在 Denova 中使用通用 Skills」。 */
    fun setSharedEnabled(enabled: Boolean) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                val snapshot = c.setSkillPreference(projectIdOrNull, SkillPreference.Shared(enabled))
                _state.value = _state.value.copy(snapshot = snapshot, error = null)
                DenovaLog.i("Skills", "shared_enabled=$enabled target=${_state.value.target}")
            } catch (e: Exception) {
                DenovaLog.w("Skills", "shared preference failed", e)
                _state.value = _state.value.copy(
                    error = failureText(e, R.string.skills_preference_failed),
                )
            }
        }
    }

    /** 打开附属文件（reference 等）。 */
    fun openFile(path: String) {
        val c = client ?: return
        val key = _state.value.selected ?: return
        if (_state.value.filePath == path) return
        viewModelScope.launch {
            _state.value = _state.value.copy(filePath = path, fileLoading = true, error = null)
            try {
                val doc = c.skillFileDocument(projectIdOrNull, key.scope, key.name, path)
                _state.value = _state.value.copy(
                    fileLoading = false,
                    fileDocument = doc,
                    fileContent = doc.content,
                    fileDirty = false,
                )
                DenovaLog.i(
                    "Skills",
                    "file opened path=$path size=${doc.content.length} revision=${doc.revision.take(8)}",
                )
            } catch (e: Exception) {
                DenovaLog.w("Skills", "file open failed path=$path", e)
                _state.value = _state.value.copy(
                    fileLoading = false,
                    error = failureText(e, R.string.skills_file_failed),
                )
            }
        }
    }

    fun closeFile() {
        _state.value = _state.value.copy(filePath = "", fileDocument = null, fileContent = "", fileDirty = false)
    }

    fun editFileContent(text: String) {
        _state.value = _state.value.copy(
            fileContent = text,
            fileDirty = text != (_state.value.fileDocument?.content ?: ""),
        )
    }

    /** 保存附属文件（同样按 revision 乐观锁；409 只刷新 revision 并保留正文）。 */
    fun saveFile() {
        val c = client ?: return
        val key = _state.value.selected ?: return
        val doc = _state.value.fileDocument ?: return
        if (_state.value.busy) return
        val content = _state.value.fileContent
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null, conflict = false)
            try {
                val saved = c.saveSkillFile(
                    projectIdOrNull,
                    SkillFileSaveRequest(
                        scope = key.scope,
                        name = key.name,
                        path = doc.file.path,
                        content = content,
                        baseRevision = doc.revision,
                    ),
                )
                _state.value = _state.value.copy(
                    busy = false,
                    fileDocument = saved,
                    fileContent = saved.content,
                    fileDirty = false,
                    notice = UiText.of(R.string.skills_file_saved),
                )
                DenovaLog.i("Skills", "file saved path=${saved.file.path} revision=${saved.revision.take(8)}")
            } catch (e: Exception) {
                val conflict = e is DenovaApiException && e.status == 409
                if (conflict) {
                    val latest = runCatching {
                        client?.skillFileDocument(projectIdOrNull, key.scope, key.name, doc.file.path)
                    }.getOrNull()
                    _state.value = _state.value.copy(
                        busy = false,
                        conflict = true,
                        fileDocument = latest ?: doc,
                        fileContent = content,
                        error = UiText.of(R.string.skills_conflict),
                    )
                } else {
                    DenovaLog.w("Skills", "file save failed path=${doc.file.path}", e)
                    _state.value = _state.value.copy(
                        busy = false,
                        error = failureText(e, R.string.skills_save_failed),
                    )
                }
            }
        }
    }

    /**
     * 把当前 SKILL.md 写到可写作用域（覆盖内置 / 通用 Skill）：走同一个保存端点，
     * `target_scope` 换成可写作用域、`base_revision` 留空（目标不存在，等价新建覆盖）。
     */
    fun saveAsOverride(targetScope: String) {
        val c = client ?: return
        val key = _state.value.selected ?: return
        val doc = _state.value.document ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null)
            try {
                val saved = c.saveSkillDocument(
                    projectIdOrNull,
                    SkillDocumentSaveRequest(
                        scope = doc.scope,
                        name = doc.name,
                        content = _state.value.content,
                        targetScope = targetScope,
                        targetName = doc.name,
                        baseRevision = "",
                    ),
                )
                drafts.remove(key)
                DenovaLog.i("Skills", "override saved target=$targetScope name=${saved.name} path=${saved.path}")
                _state.value = _state.value.copy(
                    busy = false,
                    selected = SkillKey(saved.scope, saved.name),
                    document = saved,
                    content = saved.content,
                    dirty = false,
                    notice = UiText.of(R.string.skills_override_saved, targetScope),
                )
                refreshCatalog()
            } catch (e: Exception) {
                DenovaLog.w("Skills", "override failed target=$targetScope", e)
                _state.value = _state.value.copy(
                    busy = false,
                    error = failureText(e, R.string.skills_save_failed),
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

    private fun clearSelection() {
        _state.value = _state.value.copy(
            selected = null,
            document = null,
            content = "",
            dirty = false,
            filePath = "",
            fileDocument = null,
            fileContent = "",
            fileDirty = false,
        )
    }

    private fun refreshCatalog() {
        val c = client ?: return
        val projectId = projectIdOrNull
        viewModelScope.launch {
            try {
                val snapshot = c.skillCatalog(projectId)
                _state.value = _state.value.copy(snapshot = snapshot)
            } catch (e: Exception) {
                DenovaLog.w("Skills", "refresh failed", e)
            }
        }
    }

    private fun nameErrorText(error: SkillNameError): UiText = when (error) {
        SkillNameError.EMPTY -> UiText.of(R.string.skills_name_required)
        SkillNameError.INVALID_START -> UiText.of(R.string.skills_name_invalid_start)
        SkillNameError.INVALID_CHAR -> UiText.of(R.string.skills_name_invalid_char)
        SkillNameError.TOO_LONG -> UiText.of(R.string.skills_name_too_long)
        SkillNameError.TOO_MANY_BYTES -> UiText.of(R.string.skills_name_too_long)
    }

    private fun failureText(error: Exception, fallback: Int): UiText {
        val message = (error as? DenovaApiException)?.message?.takeIf { it.isNotBlank() }
        return message?.let { UiText.raw(it) } ?: UiText.of(fallback)
    }
}
