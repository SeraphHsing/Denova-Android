package com.denova.novara.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 版本契约（WRI-016 入口 + VER-001 创建 + VER-002 diff/恢复）。
 * 对齐后端 `internal/book/versions/types.go` 与 `handler_versions.go`，走项目作用域 `/api/projects/:id/versions`。
 * 恢复只认 `restore-plan` 给出并确认过的 `paths`，不客户端自行拼整本范围以外的目标。
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

/** `POST /versions` 的请求体。 */
@Serializable
data class VersionCreateRequest(val message: String = "")

/** `POST /versions/:id/restore` 与 `restore-plan` 共用：空 `paths` 表示整本书范围。 */
@Serializable
data class VersionRestoreRequest(val paths: List<String> = emptyList())

/** `POST /versions` 的返回：新建的版本与服务端重算后的状态（VER-001）。 */
@Serializable
data class VersionCommandResult(
    val message: String = "",
    val version: VersionEntry? = null,
    val status: VersionStatus? = null,
)

/** `GET /versions/:id/diff` 的返回。`changes` 总是全量路径目录，`files` 只在完整请求时才带正文。 */
@Serializable
data class VersionDiff(
    @SerialName("files_complete") val filesComplete: Boolean = false,
    val version: VersionEntry = VersionEntry(),
    @SerialName("base_version") val baseVersion: VersionEntry? = null,
    val comparison: String = "workspace",
    val changes: List<VersionChange> = emptyList(),
    val files: List<VersionFileDiff> = emptyList(),
    val path: String = "",
    val text: Boolean = false,
    val binary: Boolean = false,
)

@Serializable
data class VersionFileDiff(
    val path: String = "",
    val status: String = "",
    val original: String = "",
    val modified: String = "",
    val text: Boolean = false,
    val binary: Boolean = false,
)

/** `POST /versions/:id/restore-plan` 的返回：恢复目标、范围与风险提示（VER-002）。 */
@Serializable
data class VersionRestorePlan(
    val target: VersionEntry = VersionEntry(),
    val scope: String = "workspace",
    val paths: List<String> = emptyList(),
    val changes: List<VersionRestoreChange> = emptyList(),
    @SerialName("will_create_backup") val willCreateBackup: Boolean = false,
    @SerialName("current_dirty") val currentDirty: Boolean = false,
    @SerialName("backup_message") val backupMessage: String = "",
    val warnings: List<String> = emptyList(),
)

@Serializable
data class VersionRestoreChange(
    val path: String = "",
    val status: String = "",
    val text: Boolean = false,
    val binary: Boolean = false,
    @SerialName("missing_in_version") val missingInVersion: Boolean = false,
    @SerialName("missing_in_workspace") val missingInWorkspace: Boolean = false,
)

/** `POST /versions/:id/restore` 的返回：实际恢复的路径、备份版本与最新状态。 */
@Serializable
data class VersionRestoreResult(
    val message: String = "",
    val target: VersionEntry = VersionEntry(),
    val version: VersionEntry? = null,
    @SerialName("backup_version") val backupVersion: VersionEntry? = null,
    @SerialName("restored_paths") val restoredPaths: List<String> = emptyList(),
    val scope: String = "workspace",
    val status: VersionStatus? = null,
)
