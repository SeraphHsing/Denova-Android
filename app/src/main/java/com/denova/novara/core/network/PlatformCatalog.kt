package com.denova.novara.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 资源市场与扩展契约（P4-D / MKT-001…005），对齐固定基线 `85ec97c`：
 *
 * - `GET /api/resource-market/catalog` → `{registry_url, schema_version, entries[], fetched_at, stale, error_key}`
 *   （`POST /catalog/refresh` 本机限定）
 * - `GET /api/resource-exchange/installations` → `Installation[]`（`POST /:id/check`、`POST /:id/policy` 本机限定）
 * - `GET /api/resource-exchange/installations/:id/backups` → `[{backup_id,state,created_at,files}]`（远程可读）
 * - `GET /api/platform/manage/{catalog,packages/:kind,plugin-pages,runtimes}` 远程可读；
 *   `packages/...` 的安装 / 预览 / 权限 / 配置写操作均为**本机限定**（403 `LOCAL_HOST_ONLY`）
 * - `GET /api/update/{status,check}` 远程可读；`/update/install|apply` 不挂本机限制但会真实升级服务端
 *
 * 名称 / 描述是本地化对象（`{"zh-CN":…,"en-US":…}`），这里原样保留再由界面按语言挑选，
 * 避免把某种语言写死在 DTO 里。
 */
@Serializable
data class MarketEntry(
    val id: String = "",
    val name: JsonObject = JsonObject(emptyMap()),
    val description: JsonObject = JsonObject(emptyMap()),
    val author: String = "",
    /** 兼容说明同样是本地化对象（`{"zh-CN":…,"en-US":…}`）。 */
    val compatibility: JsonObject = JsonObject(emptyMap()),
    val featured: Boolean = false,
    val format: String = "",
    val kinds: List<String> = emptyList(),
    val source: JsonObject? = null,
    val tags: List<String> = emptyList(),
    @SerialName("updated_at") val updatedAt: String = "",
    /** 用法说明也是本地化对象。 */
    val usage: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class MarketCatalog(
    @SerialName("registry_url") val registryUrl: String = "",
    @SerialName("schema_version") val schemaVersion: Int = 0,
    val entries: List<MarketEntry> = emptyList(),
    @SerialName("fetched_at") val fetchedAt: String = "",
    val stale: Boolean = false,
    @SerialName("error_key") val errorKey: String = "",
)

/** 已安装资源的包描述（字段随格式演进，只取展示需要的部分）。 */
@Serializable
data class MarketPackage(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val author: String = "",
)

@Serializable
data class InstallationSource(
    val kind: String = "",
    val url: String = "",
    val ref: String = "",
    val path: String = "",
    val commit: String = "",
    val filename: String = "",
)

@Serializable
data class Installation(
    @SerialName("installation_id") val installationId: String = "",
    val `package`: MarketPackage = MarketPackage(),
    val source: InstallationSource = InstallationSource(),
    @SerialName("project_id") val projectId: String = "",
    val tracking: String = "",
    @SerialName("update_mode") val updateMode: String = "manual",
    @SerialName("local_state") val localState: String = "",
    @SerialName("remote_state") val remoteState: String = "",
    @SerialName("checked_at") val checkedAt: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("reviewed_source") val reviewedSource: String = "",
) {
    val label: String get() = `package`.name.ifBlank { `package`.id.ifBlank { installationId.take(8) } }
}

@Serializable
data class InstallationBackup(
    @SerialName("backup_id") val backupId: String = "",
    val state: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val files: Int = 0,
)

/** `PluginPage`：插件贡献的页面（`agent=true` 表示带 Agent 指令）。 */
@Serializable
data class PluginPage(
    val id: String = "",
    @SerialName("pluginId") val pluginId: String = "",
    @SerialName("releaseId") val releaseId: String = "",
    val title: JsonObject = JsonObject(emptyMap()),
    val agent: Boolean = false,
)

/** 程序自身的更新状态（`GET /api/update/status`）。 */
@Serializable
data class UpdateStatus(
    @SerialName("current_version") val currentVersion: String = "",
    val phase: String = "",
    val busy: Boolean = false,
    @SerialName("checked_at") val checkedAt: String = "",
)

/** `GET /api/update/check`：只读检查，不触发安装。 */
@Serializable
data class UpdateCheck(
    @SerialName("current_version") val currentVersion: String = "",
    @SerialName("latest_version") val latestVersion: String = "",
    @SerialName("update_available") val updateAvailable: Boolean = false,
    @SerialName("can_install") val canInstall: Boolean = false,
    val platform: String = "",
    @SerialName("release_url") val releaseUrl: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("release_notes") val releaseNotes: String = "",
)

/** `POST /api/resource-exchange/installations/:id/policy`：`manual|notify|auto_apply`。 */
@Serializable
data class InstallationPolicyRequest(
    @SerialName("update_mode") val updateMode: String,
)

/**
 * 项目级扩展配置（`/api/platform/manage/projects/:id/extensions`）。
 *
 * 这是**远程可写**的少数扩展配置：禁用插件清单 + 项目模型映射。写入带 `expectedRevision`
 * 做乐观锁，冲突返回 409 `DOCUMENT_CONFLICT`。
 */
@Serializable
data class ProjectExtensionSettings(
    @SerialName("disabledPlugins") val disabledPlugins: List<String> = emptyList(),
    val models: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ProjectExtensionConfig(
    val revision: String = "",
    val extensions: ProjectExtensionSettings = ProjectExtensionSettings(),
)

@Serializable
data class ProjectExtensionInput(
    @SerialName("expectedRevision") val expectedRevision: String,
    val extensions: ProjectExtensionSettings,
)
