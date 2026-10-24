package com.denova.novara.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 书架契约（对齐固定 Web 基线 `lib/api-client/books.ts` 的 `GET /api/books`）。
 * 书籍 == 工作区：`path` 是 workspace 路径，`project_id` 用于项目作用域接口。
 */
@Serializable
data class BookRecord(
    @SerialName("project_id") val projectId: String,
    val name: String,
    val path: String = "",
    val author: String = "",
    @SerialName("cover_updated_at") val coverUpdatedAt: String? = null,
    @SerialName("last_opened_at") val lastOpenedAt: String = "",
)

@Serializable
data class BookshelfResponse(
    val books: List<BookRecord> = emptyList(),
    @SerialName("sort_mode") val sortMode: String = "recent",
)

/** 切换服务端当前工作区：`POST /api/workspace/switch` body `{path}`。 */
@Serializable
data class WorkspaceSwitchRequest(val path: String)

@Serializable
data class WorkspaceSwitchResponse(
    val workspace: String = "",
    val message: String = "",
)
