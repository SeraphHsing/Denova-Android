package com.denova.novara.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `/api/projects/:id/files/resolve` 契约（对齐固定 Web 基线 `project-files.ts`）。
 * 项目目录树的唯一数据源；与 `/book/tree`（书籍快照）不是同一接口。
 */

/** 目录项：`file` 或 `dir`。 */
@Serializable
data class ProjectFileEntry(
    val name: String,
    val path: String,
    val type: String,
    val ignored: Boolean = false,
    val symlink: Boolean = false,
) {
    val isDir: Boolean get() = type == "dir"
}

/**
 * 单个目录页。`childrenState=partial` 时携带 `continuation`（不透明游标，仅在 `revision` 未变时有效），
 * 客户端据此发起「加载更多」而不是把整棵大目录一次拉完（对齐 Web `ProjectDirectoryPage`）。
 */
@Serializable
data class ProjectDirectoryPage(
    val path: String,
    val revision: String = "",
    val entries: List<ProjectFileEntry> = emptyList(),
    @SerialName("children_state") val childrenState: String = "complete",
    val continuation: String? = null,
) {
    /** 还有未返回的子项：界面按 [continuation] 显示「加载更多」。 */
    val hasMore: Boolean get() = childrenState == "partial" && !continuation.isNullOrBlank()
}

@Serializable
data class ProjectFileTreeResolveResult(
    val id: String? = null,
    val path: String,
    val ok: Boolean,
    val directories: List<ProjectDirectoryPage> = emptyList(),
    val code: String? = null,
    val error: String? = null,
)

@Serializable
data class ProjectFileTreeResolveResponse(
    @SerialName("project_id") val projectId: String,
    val results: List<ProjectFileTreeResolveResult> = emptyList(),
)

/** 解析目标；`cursor` 是上一页给出的 `continuation`（「加载更多」时使用）。 */
@Serializable
data class ProjectFileResolveTarget(
    val path: String,
    val cursor: String? = null,
)

@Serializable
data class ProjectFileResolveRequest(
    val targets: List<ProjectFileResolveTarget>,
    @SerialName("include_ignored") val includeIgnored: Boolean = true,
    val recursive: Boolean = true,
    /** 可选条目预算：服务端默认上限 100000，显式给更小的值会返回 `children_state=partial`。 */
    @SerialName("entry_budget") val entryBudget: Int? = null,
)
