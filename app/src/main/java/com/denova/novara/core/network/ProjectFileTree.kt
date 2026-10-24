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

/** 单个目录页；`childrenState=partial` 时携带 `continuation`（第一版递归一次铺开，不做懒加载）。 */
@Serializable
data class ProjectDirectoryPage(
    val path: String,
    val revision: String = "",
    val entries: List<ProjectFileEntry> = emptyList(),
    @SerialName("children_state") val childrenState: String = "complete",
    val continuation: String? = null,
)

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

@Serializable
data class ProjectFileResolveTarget(val path: String)

@Serializable
data class ProjectFileResolveRequest(
    val targets: List<ProjectFileResolveTarget>,
    @SerialName("include_ignored") val includeIgnored: Boolean = true,
    val recursive: Boolean = true,
)
