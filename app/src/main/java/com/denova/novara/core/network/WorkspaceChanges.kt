package com.denova.novara.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 变更审阅契约（对齐固定 Web 基线 `features/changes/types.ts` 与 `features/changes/api.ts`）。
 * 全部走项目作用域 `/api/projects/:id/changes/`。
 */

/** `ChangeReviewStatus`：pending / accepted / rejected / mixed。 */
@Serializable
data class WorkspaceChangeGroupSummary(
    val id: String,
    val origin: String? = null,
    @SerialName("review_thread_id") val reviewThreadId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("review_status") val reviewStatus: String = "pending",
    /** `ChangeApplyState`：prepared / applied / reverted / conflicted。 */
    @SerialName("apply_state") val applyState: String = "prepared",
    @SerialName("change_set_count") val changeSetCount: Int = 0,
    val paths: List<String> = emptyList(),
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("can_undo") val canUndo: Boolean = false,
    @SerialName("can_redo") val canRedo: Boolean = false,
    @SerialName("pending_edit_count") val pendingEditCount: Int = 0,
)

@Serializable
data class ChangeGroupsResponse(
    @SerialName("project_id") val projectId: String = "",
    val workspace: String = "",
    val groups: List<WorkspaceChangeGroupSummary> = emptyList(),
)

/** 单个文件的变更集（`GET /changes/groups/:id` 的 `change_sets[]`）。 */
@Serializable
data class WorkspaceChangeSet(
    val id: String,
    val sequence: Long = 0,
    @SerialName("group_id") val groupId: String = "",
    val path: String = "",
    @SerialName("base_revision") val baseRevision: String = "",
    val revision: String = "",
    @SerialName("before_exists") val beforeExists: Boolean = true,
    @SerialName("after_exists") val afterExists: Boolean = true,
    /** 服务端按需回填的历史正文；缺失时为空串。 */
    @SerialName("before_content") val beforeContent: String = "",
    @SerialName("after_content") val afterContent: String = "",
    @SerialName("review_status") val reviewStatus: String = "pending",
    @SerialName("apply_state") val applyState: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val origin: String? = null,
)

/** 行内评论（只读展示；锚点字段保留原样，服务端是唯一事实源）。 */
@Serializable
data class WorkspaceChangeComment(
    val id: String,
    @SerialName("group_id") val groupId: String = "",
    @SerialName("change_set_id") val changeSetId: String? = null,
    val body: String = "",
    val author: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    val deleted: Boolean = false,
)

/** 变更分组详情（对齐 Go `workspacechange.ChangeGroup`）。 */
@Serializable
data class WorkspaceChangeGroup(
    val id: String,
    val origin: String? = null,
    @SerialName("review_thread_id") val reviewThreadId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("review_status") val reviewStatus: String = "pending",
    @SerialName("apply_state") val applyState: String = "prepared",
    @SerialName("can_undo") val canUndo: Boolean = false,
    @SerialName("can_redo") val canRedo: Boolean = false,
    @SerialName("pending_edit_count") val pendingEditCount: Int = 0,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("change_sets") val changeSets: List<WorkspaceChangeSet> = emptyList(),
    val comments: List<WorkspaceChangeComment> = emptyList(),
)

@Serializable
data class ChangeGroupResponse(
    @SerialName("project_id") val projectId: String = "",
    val workspace: String = "",
    val group: WorkspaceChangeGroup,
)

/** `ChangeReviewDecision`：accept / reject。 */
@Serializable
data class ReviewChangeRequest(val decision: String)

@Serializable
data class WorkspaceChangeMutationResult(
    @SerialName("project_id") val projectId: String? = null,
    val workspace: String? = null,
    val message: String? = null,
    @SerialName("affected_paths") val affectedPaths: List<String> = emptyList(),
)
