package com.denova.novara.feature.changes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.WorkspaceChangeGroup
import com.denova.novara.core.network.WorkspaceChangeGroupSummary
import com.denova.novara.core.network.WorkspaceChangeMutationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChangesUiState(
    val projectId: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val groups: List<WorkspaceChangeGroupSummary> = emptyList(),
    /** 正在审阅 / 撤销 / 重做的分组，用于禁用重复点击。 */
    val busyGroupId: String? = null,
    val message: String? = null,
    /** 打开的详情（含每个文件的 before/after 正文与评论）；null 表示停在列表。 */
    val detail: WorkspaceChangeGroup? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
)

/**
 * 变更审阅（P3 链路）：`/api/projects/:id/changes/groups` 列表 + review / undo / redo。
 * 全部为项目作用域；成功后刷新列表。
 */
class ChangesViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null

    private val _state = MutableStateFlow(ChangesUiState())
    val state: StateFlow<ChangesUiState> = _state.asStateFlow()

    fun bindClient(client: DenovaClient) {
        this.client = client
    }

    fun load(projectId: String, force: Boolean = false) {
        val c = client ?: return
        if (projectId.isBlank()) return
        if (!force && projectId == _state.value.projectId && _state.value.groups.isNotEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(projectId = projectId, loading = true, error = null)
            try {
                val groups = c.changeGroups(projectId).groups
                _state.value = _state.value.copy(loading = false, groups = groups)
                DenovaLog.i("Changes", "loaded project=$projectId groups=${groups.size}")
            } catch (e: Exception) {
                DenovaLog.w("Changes", "load failed project=$projectId", e)
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    /** 打开详情：拉取 `GET /changes/groups/:id`，界面据此渲染 diff 与评论。 */
    fun openGroup(groupId: String) {
        val c = client ?: return
        val pid = _state.value.projectId
        if (pid.isBlank() || groupId.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(detailLoading = true, detailError = null)
            try {
                val group = c.changeGroup(pid, groupId)
                _state.value = _state.value.copy(detail = group, detailLoading = false)
                DenovaLog.i(
                    "Changes",
                    "detail group=$groupId files=${group.changeSets.size} comments=${group.comments.size}",
                )
            } catch (e: Exception) {
                DenovaLog.w("Changes", "detail failed group=$groupId", e)
                _state.value = _state.value.copy(
                    detail = null,
                    detailLoading = false,
                    detailError = e.message ?: "加载详情失败",
                )
            }
        }
    }

    fun closeDetail() {
        _state.value = _state.value.copy(detail = null, detailError = null, detailLoading = false)
    }

    /** decision: accept / reject。 */
    fun review(groupId: String, decision: String) = mutate(groupId) { c, pid ->
        c.reviewChangeGroup(pid, groupId, decision)
    }

    fun undo(groupId: String) = mutate(groupId) { c, pid -> c.undoChangeGroup(pid, groupId) }

    fun redo(groupId: String) = mutate(groupId) { c, pid -> c.redoChangeGroup(pid, groupId) }

    private fun mutate(
        groupId: String,
        block: suspend (DenovaClient, String) -> WorkspaceChangeMutationResult,
    ) {
        val c = client ?: return
        val pid = _state.value.projectId
        if (pid.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busyGroupId = groupId, error = null, message = null)
            try {
                val result = block(c, pid)
                _state.value = _state.value.copy(
                    busyGroupId = null,
                    message = result.message ?: "已更新",
                    error = null,
                )
                DenovaLog.i("Changes", "mutated group=$groupId paths=${result.affectedPaths.size}")
                load(pid, force = true)
                // 详情里也带着 review_status / apply_state / can_undo，操作后一并刷新。
                if (_state.value.detail?.id == groupId) openGroup(groupId)
            } catch (e: Exception) {
                DenovaLog.w("Changes", "mutate failed group=$groupId", e)
                _state.value = _state.value.copy(busyGroupId = null, error = e.message ?: "操作失败")
            }
        }
    }
}
