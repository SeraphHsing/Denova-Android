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
