package com.denova.novara.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 版本入口契约（WRI-016 的「版本」部分，功能本身留在 VER-001 / VER-002）。
 * 对齐后端 `internal/book/versions/types.go`，走项目作用域 `/api/projects/:id/versions`。
 * 本切片只读列表与状态：不创建版本、不做 diff、不做恢复。
 */
@Serializable
data class VersionEntry(
    val id: String = "",
    val message: String = "",
    @SerialName("created_at") val createdAt: String = "",
    /** manual / auto / pre_restore / pre_review …（服务端 `source` 原样展示）。 */
    val source: String = "",
    @SerialName("file_count") val fileCount: Int = 0,
    @SerialName("total_bytes") val totalBytes: Long = 0,
    @SerialName("changed_paths") val changedPaths: List<String> = emptyList(),
)

@Serializable
data class VersionChange(
    val path: String = "",
    /** added / modified / deleted 等，服务端 `status` 原样展示。 */
    val status: String = "",
)

@Serializable
data class VersionAutoInfo(
    @SerialName("timed_enabled") val timedEnabled: Boolean = false,
    @SerialName("timed_interval_minutes") val timedIntervalMinutes: Int = 0,
    val retention: Int = 0,
    @SerialName("last_auto_at") val lastAutoAt: String = "",
)

@Serializable
data class VersionStatus(
    @SerialName("has_versions") val hasVersions: Boolean = false,
    /** 工作区自上次版本以来是否干净（true = 没有未纳入版本的改动）。 */
    val clean: Boolean = true,
    val changes: List<VersionChange> = emptyList(),
    val latest: VersionEntry? = null,
    val auto: VersionAutoInfo = VersionAutoInfo(),
)

@Serializable
data class VersionHistoryResponse(
    val versions: List<VersionEntry> = emptyList(),
)
