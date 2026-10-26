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

/**
 * 建书（WRI-001）：`POST /api/books/create` body `{title, author?, description?}`
 * → `{project_id, workspace, book_meta}`。后端只接受这三个字段（`handler_books.go:36-40`），
 * 且**创建会切换服务端当前 workspace**，因此后续请求一律带 `project_id`。
 */
@Serializable
data class CreateBookRequest(
    val title: String,
    val author: String = "",
    val description: String = "",
)

@Serializable
data class BookMeta(
    val title: String = "",
    val author: String = "",
    val description: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class CreateBookResponse(
    @SerialName("project_id") val projectId: String = "",
    val workspace: String = "",
    @SerialName("book_meta") val bookMeta: BookMeta = BookMeta(),
)

/**
 * 章节摘要（WRI-015）：`GET /api/projects/:id/book/summary` 的 `summary.chapters[]`。
 *
 * `status` 是后端直出的中文字符串（`空章`/`初稿`/`成章`），**不可回写**；
 * 可写字段只有 `confirmed`（`PATCH …/book/chapter-status`）。
 */
@Serializable
data class ChapterSummary(
    val path: String = "",
    @SerialName("file_name") val fileName: String = "",
    @SerialName("display_title") val displayTitle: String = "",
    val index: Int = 0,
    val words: Int = 0,
    val status: String = "",
    val confirmed: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
    val volume: String = "",
    @SerialName("volume_path") val volumePath: String = "",
)

@Serializable
data class BookSummary(
    val title: String = "",
    val author: String = "",
    @SerialName("chapter_count") val chapterCount: Int = 0,
    @SerialName("total_words") val totalWords: Int = 0,
    val chapters: List<ChapterSummary> = emptyList(),
)

@Serializable
data class BookSummaryResponse(
    @SerialName("project_id") val projectId: String = "",
    val workspace: String = "",
    val summary: BookSummary = BookSummary(),
)

/** 章节状态写回：`PATCH /api/projects/:id/book/chapter-status` body `{path, confirmed}`。 */
@Serializable
data class ChapterStatusRequest(
    val path: String,
    val confirmed: Boolean,
)

@Serializable
data class ChapterStatusResponse(
    @SerialName("project_id") val projectId: String = "",
    val path: String = "",
    val confirmed: Boolean = false,
    val message: String = "",
)
