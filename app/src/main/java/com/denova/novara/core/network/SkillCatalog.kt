package com.denova.novara.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Skills 资源契约（P4-C / SKL-001·002），来源基线 `web/src/lib/api-client/skills.ts` 与
 * 后端 `internal/api/handlers/handler_skills.go`、`internal/agents/skills/types.go`：
 *
 * - `GET|POST /api/skills`、`PATCH /api/skills/preferences`、`GET|PUT /api/skills/document`、
 *   `GET|PUT /api/skills/file`、`DELETE /api/skills/document`
 * - 项目作用域同形状，前缀换成 `/api/projects/:id/skills`
 *
 * 作用域（scope）四层：`shared` / `builtin` / `user` / `workspace`，后者覆盖前者；
 * 只有 `user` / `workspace` 可写（`scopes[].writable`）。偏好只写目标作用域：
 * `enabled` 写单个 Skill，`shared_enabled` 写全局「是否使用通用 Skills」。
 */
object SkillScopes {
    const val SHARED = "shared"
    const val BUILTIN = "builtin"
    const val USER = "user"
    const val WORKSPACE = "workspace"

    /** 基线的展示顺序（`skillScopes`）。 */
    val ordered: List<String> = listOf(SHARED, BUILTIN, USER, WORKSPACE)

    /** 可写作用域（新建 / 覆盖只能落在这些位置）。 */
    val writable: Set<String> = setOf(USER, WORKSPACE)
}

@Serializable
data class SkillScopeInfo(
    val scope: String = "",
    val path: String = "",
    val writable: Boolean = false,
)

@Serializable
data class SkillSummary(
    val name: String = "",
    val description: String = "",
    val category: String = "",
    val capabilities: List<String> = emptyList(),
    val context: String = "",
    val agent: String = "",
    val model: String = "",
    val scope: String = "",
    val path: String = "",
    val editable: Boolean = false,
    /** 是否当前生效（同名下层被上层覆盖时为 false）。 */
    val active: Boolean = true,
    val enabled: Boolean = true,
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class SkillSnapshot(
    val scopes: List<SkillScopeInfo> = emptyList(),
    val skills: List<SkillSummary> = emptyList(),
    @SerialName("shared_enabled") val sharedEnabled: Boolean = false,
)

/** Skill 目录内的一个文件（`SKILL.md` 或 reference）。 */
@Serializable
data class SkillFileEntry(
    val path: String = "",
    val size: Long = 0,
    /** 目录项（`SKILL.md` 与 `references/` 目录本身为 true）。 */
    val entry: Boolean = false,
    val editable: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
)

/** `GET /api/skills/document`：摘要字段平铺 + 正文 + revision + 附属文件表。 */
@Serializable
data class SkillDocument(
    val name: String = "",
    val description: String = "",
    val category: String = "",
    val capabilities: List<String> = emptyList(),
    val context: String = "",
    val agent: String = "",
    val model: String = "",
    val scope: String = "",
    val path: String = "",
    val editable: Boolean = false,
    val active: Boolean = true,
    val enabled: Boolean = true,
    @SerialName("updated_at") val updatedAt: String = "",
    val content: String = "",
    val revision: String = "",
    val files: List<SkillFileEntry> = emptyList(),
)

/** `GET /api/skills/file`：所属 Skill 摘要 + 文件信息 + 正文 + revision。 */
@Serializable
data class SkillFileDocument(
    val skill: SkillSummary = SkillSummary(),
    val file: SkillFileEntry = SkillFileEntry(),
    val content: String = "",
    val revision: String = "",
)

// ---- 请求体：与基线 `createSkill` / `saveSkillDocument` / `saveSkillFileDocument` 逐字对齐 ----

@Serializable
data class SkillCreateRequest(
    val scope: String,
    val name: String,
    val description: String = "",
    val agents: List<String> = emptyList(),
    val category: String = "",
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class SkillDocumentSaveRequest(
    val scope: String,
    val name: String,
    val content: String,
    @SerialName("target_scope") val targetScope: String = "",
    @SerialName("target_name") val targetName: String = "",
    @SerialName("base_revision") val baseRevision: String = "",
)

@Serializable
data class SkillFileSaveRequest(
    val scope: String,
    val name: String,
    val path: String,
    val content: String,
    @SerialName("base_revision") val baseRevision: String = "",
)

/** 偏好枚举：恰好一项为 true（服务端 `SetSkillPreference` 要求 exactly one）。 */
sealed interface SkillPreference {
    /** 单个 Skill 的启用开关（写目标作用域的 `settings.json`）。 */
    data class Skill(val scope: String, val name: String, val enabled: Boolean) : SkillPreference

    /** 全局「在 Denova 中使用通用 Skills」开关。 */
    data class Shared(val enabled: Boolean) : SkillPreference
}
