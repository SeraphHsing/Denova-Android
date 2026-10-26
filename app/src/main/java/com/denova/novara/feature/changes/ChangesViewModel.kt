package com.denova.novara.feature.changes

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.ChangeCommentAnchor
import com.denova.novara.core.network.ChangeCommentRequest
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.WorkspaceChangeComment
import com.denova.novara.core.network.WorkspaceChangeGroup
import com.denova.novara.core.network.WorkspaceChangeGroupSummary
import com.denova.novara.core.network.WorkspaceChangeMutationResult
import com.denova.novara.core.network.WorkspaceChangeSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 正在写评论的目标：带锚点时是 diff 行，不带锚点时是整组评论。 */
data class CommentTarget(
    val changeSetId: String,
    val lineLabel: UiText,
    val anchor: ChangeCommentAnchor?,
)

data class ChangesUiState(
    val projectId: String = "",
    val loading: Boolean = false,
    val error: UiText? = null,
    val groups: List<WorkspaceChangeGroupSummary> = emptyList(),
    /** 正在审阅 / 撤销 / 重做的分组，用于禁用重复点击。 */
    val busyGroupId: String? = null,
    val message: UiText? = null,
    /** 打开的详情（含每个文件的 before/after 正文与评论）；null 表示停在列表。 */
    val detail: WorkspaceChangeGroup? = null,
    val detailLoading: Boolean = false,
    val detailError: UiText? = null,
    /** 评论编辑器：目标（可带锚点）、草稿、正在编辑的评论 id。 */
    val commentTarget: CommentTarget? = null,
    val commentDraft: String = "",
    val editingCommentId: String? = null,
    val commentSubmitting: Boolean = false,
    val commentError: UiText? = null,
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
                _state.value = _state.value.copy(loading = false, error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.changes_load_failed))
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
                    detailError = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.changes_detail_failed),
                )
            }
        }
    }

    fun closeDetail() {
        _state.value = _state.value.copy(detail = null, detailError = null, detailLoading = false)
    }

    /**
     * decision: accept / reject；[changeSetId] 非空表示只裁决该文件（局部审阅）。
     * 服务端 `ReviewRequest.change_set_id` 原生支持，界面的「仅此文件」按钮走这条路。
     */
    fun review(groupId: String, decision: String, changeSetId: String? = null) = mutate(groupId) { c, pid ->
        c.reviewChangeGroup(pid, groupId, decision, changeSetId)
    }

    // ---- 评论（WRI-016）：新增（含 diff 行锚点）/ 编辑 / 删除 ----

    /** 选中一行 diff 作为评论目标；空行没有可锚定文本，退化成整组评论。 */
    fun selectDiffLine(set: WorkspaceChangeSet, line: DiffLine) {
        val anchor = ChangeDiff.anchor(set, line)
        val label = (if (line.kind == DiffKind.REMOVE) line.beforeLine else line.afterLine)
            ?.let { UiText.of(R.string.changes_line, it) } ?: UiText.of(R.string.changes_whole_group)
        _state.value = _state.value.copy(
            commentTarget = CommentTarget(set.id, label, anchor),
            editingCommentId = null,
            commentError = null,
        )
    }

    /** 针对整个分组写评论（不锚定具体行）。 */
    fun startGroupComment() {
        val group = _state.value.detail ?: return
        _state.value = _state.value.copy(
            commentTarget = CommentTarget("", UiText.of(R.string.changes_whole_group), null),
            editingCommentId = null,
            commentError = null,
            commentDraft = "",
        )
        if (group.id.isBlank()) return
    }

    fun startEditComment(comment: WorkspaceChangeComment) {
        _state.value = _state.value.copy(
            editingCommentId = comment.id,
            commentTarget = null,
            commentDraft = comment.body,
            commentError = null,
        )
    }

    fun onCommentDraftChange(text: String) {
        _state.value = _state.value.copy(commentDraft = text, commentError = null)
    }

    fun cancelComment() {
        _state.value = _state.value.copy(
            commentTarget = null,
            editingCommentId = null,
            commentDraft = "",
            commentError = null,
        )
    }

    /** 提交草稿：有编辑目标发 PATCH，否则发 POST（带锚点）。成功后重载详情。 */
    fun submitComment() {
        val c = client ?: return
        val s = _state.value
        val pid = s.projectId
        val group = s.detail ?: return
        val body = s.commentDraft.trim()
        if (pid.isBlank() || body.isEmpty() || s.commentSubmitting) return
        val editing = s.editingCommentId
        val target = s.commentTarget
        if (editing == null && target == null) return
        viewModelScope.launch {
            _state.value = s.copy(commentSubmitting = true, commentError = null)
            try {
                if (editing != null) {
                    c.updateChangeComment(pid, editing, body)
                    DenovaLog.i("Changes", "comment updated id=$editing")
                } else {
                    val request = ChangeCommentRequest(
                        groupId = group.id,
                        changeSetId = target?.changeSetId?.takeIf { it.isNotBlank() },
                        body = body,
                        anchor = target?.anchor,
                    )
                    val comment = c.createChangeComment(pid, request)
                    DenovaLog.i(
                        "Changes",
                        "comment created id=${comment.id} anchored=${request.anchor != null}",
                    )
                }
                _state.value = _state.value.copy(
                    commentSubmitting = false,
                    commentDraft = "",
                    commentTarget = null,
                    editingCommentId = null,
                    commentError = null,
                )
                reloadDetail()
            } catch (e: Exception) {
                DenovaLog.w("Changes", "comment submit failed", e)
                _state.value = _state.value.copy(
                    commentSubmitting = false,
                    commentError = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.changes_comment_failed),
                )
            }
        }
    }

    fun deleteComment(commentId: String) {
        val c = client ?: return
        val pid = _state.value.projectId
        if (pid.isBlank() || commentId.isBlank()) return
        viewModelScope.launch {
            try {
                c.deleteChangeComment(pid, commentId)
                DenovaLog.i("Changes", "comment deleted id=$commentId")
                reloadDetail()
            } catch (e: Exception) {
                DenovaLog.w("Changes", "comment delete failed id=$commentId", e)
                _state.value = _state.value.copy(commentError = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.changes_comment_delete_failed))
            }
        }
    }

    private fun reloadDetail() {
        val groupId = _state.value.detail?.id ?: return
        if (groupId.isNotBlank()) openGroup(groupId)
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
                    message = result.message?.let { UiText.raw(it) } ?: UiText.of(R.string.changes_updated),
                    error = null,
                )
                DenovaLog.i("Changes", "mutated group=$groupId paths=${result.affectedPaths.size}")
                load(pid, force = true)
                // 详情里也带着 review_status / apply_state / can_undo，操作后一并刷新。
                if (_state.value.detail?.id == groupId) openGroup(groupId)
            } catch (e: Exception) {
                DenovaLog.w("Changes", "mutate failed group=$groupId", e)
                _state.value = _state.value.copy(busyGroupId = null, error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.changes_action_failed))
            }
        }
    }
}
