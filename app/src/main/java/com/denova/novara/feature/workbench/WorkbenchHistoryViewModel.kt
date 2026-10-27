package com.denova.novara.feature.workbench

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.R
import com.denova.novara.core.export.MarkdownExporter
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.AgentChatHistoryItem
import com.denova.novara.core.network.AgentChatProject
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.workbench.HistoryList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WorkbenchHistoryUiState(
    val projects: List<AgentChatProject> = emptyList(),
    val selectedProjectId: String = "",
    val query: String = "",
    val page: HistoryList.Page = HistoryList.Page(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: UiText? = null,
    val message: UiText? = null,
    /** 正在导出的会话 key（`HistoryList.key`），用于禁用重复点击。 */
    val exportingKey: String? = null,
    val renaming: AgentChatHistoryItem? = null,
    val renameDraft: String = "",
    val pendingDelete: AgentChatHistoryItem? = null,
)

/**
 * 工作台历史（P4-B / WB-006）。
 *
 * 对齐基线 `AgentChatSessionHistoryDialog`：**按项目**检索历史（跨项目 = 切换项目），
 * 搜索 100ms 防抖、一次 80 条、`has_more` 挂「加载更多」，行内可重命名 / 导出 Markdown / 删除。
 * 查询与翻页都以当前选中项目为作用域，切换项目立即重查，迟到响应按请求序号丢弃。
 */
class WorkbenchHistoryViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null
    private var searchJob: Job? = null

    /** 请求序号：切项目 / 改查询都会自增，迟到响应直接丢弃（AGENTS §4）。 */
    private var sequence: Int = 0

    private val _state = MutableStateFlow(WorkbenchHistoryUiState())
    val state: StateFlow<WorkbenchHistoryUiState> = _state.asStateFlow()

    fun bindClient(
        client: DenovaClient,
        projects: List<AgentChatProject>,
        initialProjectId: String,
    ) {
        val sameClient = this.client === client
        this.client = client
        val selected = _state.value.selectedProjectId
            .takeIf { it.isNotBlank() && projects.any { p -> p.id == it } }
            ?: initialProjectId.takeIf { it.isNotBlank() }
            ?: projects.firstOrNull { it.status == "available" }?.id
            ?: ""
        _state.value = _state.value.copy(projects = projects, selectedProjectId = selected)
        if (!sameClient || _state.value.page.items.isEmpty()) search(immediate = true)
    }

    fun onQueryChange(raw: String) {
        val query = HistoryList.normalizeQuery(raw)
        if (query == _state.value.query) return
        _state.value = _state.value.copy(query = query, error = null)
        search(immediate = false)
    }

    fun selectProject(projectId: String) {
        if (projectId.isBlank() || projectId == _state.value.selectedProjectId) return
        _state.value = _state.value.copy(
            selectedProjectId = projectId,
            page = HistoryList.Page(),
            error = null,
            message = null,
            renaming = null,
            renameDraft = "",
        )
        search(immediate = true)
    }

    fun refresh() = search(immediate = true)

    /** 基线 `HISTORY_SEARCH_DELAY_MS` 防抖；首次进入与切项目立即查。 */
    private fun search(immediate: Boolean) {
        val c = client ?: return
        val projectId = _state.value.selectedProjectId
        if (projectId.isBlank()) return
        searchJob?.cancel()
        val requestId = ++sequence
        searchJob = viewModelScope.launch {
            if (!immediate) delay(HistoryList.SEARCH_DEBOUNCE_MS)
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val response = c.agentChatHistory(
                    query = _state.value.query,
                    projectId = projectId,
                    limit = HistoryList.PAGE_SIZE,
                )
                if (requestId != sequence) return@launch
                _state.value = _state.value.copy(
                    loading = false,
                    page = HistoryList.replace(response),
                )
                DenovaLog.i(
                    "History",
                    "loaded project=${projectId.take(12)} items=${response.items.size} total=${response.total}",
                )
            } catch (e: Exception) {
                if (requestId != sequence) return@launch
                DenovaLog.w("History", "load failed project=${projectId.take(12)}", e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.workbench_history_load_failed),
                )
            }
        }
    }

    fun loadMore() {
        val c = client ?: return
        val s = _state.value
        if (s.loadingMore || !s.page.hasMore || s.selectedProjectId.isBlank()) return
        val requestId = ++sequence
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true, error = null)
            try {
                val response = c.agentChatHistory(
                    query = _state.value.query,
                    projectId = _state.value.selectedProjectId,
                    offset = _state.value.page.offset,
                    limit = HistoryList.PAGE_SIZE,
                )
                if (requestId != sequence) return@launch
                _state.value = _state.value.copy(
                    loadingMore = false,
                    page = HistoryList.append(_state.value.page, response),
                )
                DenovaLog.i("History", "loaded more offset=${response.offset} items=${response.items.size}")
            } catch (e: Exception) {
                if (requestId != sequence) return@launch
                DenovaLog.w("History", "load more failed", e)
                _state.value = _state.value.copy(
                    loadingMore = false,
                    error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.workbench_history_load_failed),
                )
            }
        }
    }

    // ---- 行内动作：重命名 / 删除 / 导出 ----

    fun startRename(item: AgentChatHistoryItem) {
        _state.value = _state.value.copy(renaming = item, renameDraft = item.session.title, error = null)
    }

    fun onRenameDraftChange(text: String) {
        _state.value = _state.value.copy(renameDraft = text, error = null)
    }

    fun cancelRename() {
        _state.value = _state.value.copy(renaming = null, renameDraft = "")
    }

    fun submitRename() {
        val c = client ?: return
        val item = _state.value.renaming ?: return
        val title = _state.value.renameDraft.trim()
        if (title.isEmpty()) return
        if (title == item.session.title) {
            cancelRename()
            return
        }
        viewModelScope.launch {
            try {
                c.projectAgentRenameSession(item.projectId, item.session.id, title)
                DenovaLog.i("History", "renamed session=${item.session.id.take(12)}")
                _state.value = _state.value.copy(
                    renaming = null,
                    renameDraft = "",
                    message = UiText.of(R.string.workbench_history_renamed),
                )
                search(immediate = true)
            } catch (e: Exception) {
                DenovaLog.w("History", "rename failed session=${item.session.id.take(12)}", e)
                _state.value = _state.value.copy(
                    error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.workbench_history_rename_failed),
                )
            }
        }
    }

    fun requestDelete(item: AgentChatHistoryItem) {
        _state.value = _state.value.copy(pendingDelete = item)
    }

    fun cancelDelete() {
        _state.value = _state.value.copy(pendingDelete = null)
    }

    fun confirmDelete() {
        val c = client ?: return
        val item = _state.value.pendingDelete ?: return
        _state.value = _state.value.copy(pendingDelete = null)
        viewModelScope.launch {
            try {
                c.projectAgentDeleteSession(item.projectId, item.session.id)
                DenovaLog.i("History", "deleted session=${item.session.id.take(12)}")
                _state.value = _state.value.copy(message = UiText.of(R.string.workbench_history_deleted))
                search(immediate = true)
            } catch (e: Exception) {
                DenovaLog.w("History", "delete failed session=${item.session.id.take(12)}", e)
                _state.value = _state.value.copy(
                    error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.workbench_history_delete_failed),
                )
            }
        }
    }

    /**
     * 导出为 Markdown：请求作用域用**该行自己的** `project_id`（历史是跨项目的），
     * 不能用界面当前选中的项目，否则会导出成别的项目的同名会话。
     */
    fun export(item: AgentChatHistoryItem) {
        val c = client ?: return
        val key = HistoryList.key(item)
        if (_state.value.exportingKey != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(exportingKey = key, error = null, message = null)
            try {
                val markdown = c.agentChatExport(item.projectId, item.session.id)
                val name = HistoryList.exportFileName(item.session.title, item.session.id)
                val result = MarkdownExporter.save(getApplication(), name, markdown)
                _state.value = _state.value.copy(
                    exportingKey = null,
                    message = when (result) {
                        is MarkdownExporter.Result.Saved ->
                            UiText.raw(getApplication<Application>().getString(R.string.workbench_history_export_saved, result.displayPath))
                        is MarkdownExporter.Result.Failed ->
                            UiText.raw(
                                getApplication<Application>().getString(
                                    R.string.workbench_history_export_failed,
                                    result.reason,
                                ),
                            )
                    },
                )
                DenovaLog.i("History", "export session=${item.session.id.take(12)} name=$name ${result::class.simpleName}")
            } catch (e: Exception) {
                DenovaLog.w("History", "export failed session=${item.session.id.take(12)}", e)
                _state.value = _state.value.copy(
                    exportingKey = null,
                    error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.workbench_history_export_failed_short),
                )
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
