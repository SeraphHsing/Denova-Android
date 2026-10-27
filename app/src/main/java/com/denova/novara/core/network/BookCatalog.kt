package com.denova.novara.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 书籍管理页（P4-C / BOK-001·002·003）的契约，全部对齐固定 Web 基线：
 * `internal/api/handlers/handler_books.go`、`handler_novel_import.go`、`handler_character_card.go`。
 *
 * 与 [BookShelf] 的分工：`GET /api/books`、`POST /api/books/create`、`POST /api/workspace/switch`
 * 已在写作链路（WRI-001）落地，这里补书籍管理页新增的动作。
 */

/** `PUT /api/books/info` body `{path,title,author,description}`；响应是 book_meta。 */
@Serializable
data class BookInfoRequest(
    val path: String,
    val title: String = "",
    val author: String = "",
    val description: String = "",
)

/** `POST /api/books/remove` body `{path}`：只移除记录，磁盘目录保留。 */
@Serializable
data class BookRemoveRequest(val path: String)

@Serializable
data class BookRemoveResponse(
    val message: String = "",
    val workspace: String = "",
)

/** `POST /api/books/reorder` body `{paths}`：提交完整顺序（服务端只换 Book 槽位）。 */
@Serializable
data class BookReorderRequest(val paths: List<String>)

/** `POST /api/books/sort-mode` body `{mode}`；取值 `manual` / `recent`。 */
@Serializable
data class BookSortModeRequest(val mode: String)

/** `POST /api/books/cover/generate` 的请求（`bookapp.CoverGenerateRequest`）。 */
@Serializable
data class BookCoverGenerateRequest(
    val path: String,
    val mode: String = "agent",
    @SerialName("command_id") val commandId: String = "",
    val prompt: String = "",
    @SerialName("image_preset_id") val imagePresetId: String = "",
    val instruction: String = "",
    @SerialName("profile_id") val profileId: String = "",
)

/** 封面上传 / 生成后的返回：新封面版本用于刷新图片 URL。 */
@Serializable
data class BookCoverResult(
    val path: String = "",
    @SerialName("cover_updated_at") val coverUpdatedAt: String = "",
    val message: String = "",
)

/** `POST /api/books/import-novel/preview`，以及提交导入前服务端重算的同构预览。 */
@Serializable
data class NovelImportPreview(
    val title: String = "",
    val language: String = "",
    @SerialName("split_strategy") val splitStrategy: String = "",
    @SerialName("split_regex") val splitRegex: String = "",
    @SerialName("sample_chars") val sampleChars: Int = 0,
    @SerialName("chapter_count") val chapterCount: Int = 0,
    @SerialName("total_chars") val totalChars: Int = 0,
    val chapters: List<NovelImportChapter> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class NovelImportChapter(
    val index: Int = 0,
    val title: String = "",
    val chars: Int = 0,
    val path: String = "",
    val volume: String = "",
)

/** `POST /api/books/import-novel` 的结果（`book.NovelImportResult`）。 */
@Serializable
data class NovelImportResult(
    val workspace: String = "",
    @SerialName("book_meta") val bookMeta: BookMeta = BookMeta(),
    val title: String = "",
    @SerialName("chapter_count") val chapterCount: Int = 0,
    @SerialName("total_chars") val totalChars: Int = 0,
    @SerialName("chapter_paths") val chapterPaths: List<String> = emptyList(),
    val message: String = "",
)

/** `POST /api/imports/character-card/preview`（`character.Preview`）：只解析、不写入。 */
@Serializable
data class CharacterCardPreview(
    val name: String = "",
    @SerialName("entry_count") val entryCount: Int = 0,
    val tags: List<String> = emptyList(),
    @SerialName("opening_preset_count") val openingPresetCount: Int = 0,
    @SerialName("user_placeholder_found") val userPlaceholderFound: Boolean = false,
    @SerialName("will_import_cover") val willImportCover: Boolean = false,
    @SerialName("enabled_entry_count") val enabledEntryCount: Int = 0,
    @SerialName("disabled_entry_count") val disabledEntryCount: Int = 0,
    @SerialName("classification_mode") val classificationMode: String = "",
    @SerialName("uncertain_type_count") val uncertainTypeCount: Int = 0,
    val compatibility: CharacterCardCompatibility = CharacterCardCompatibility(),
)

@Serializable
data class CharacterCardCompatibility(
    val capabilities: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** `POST /api/books/import-character-card`（target=new_book）的返回（`character.ImportResult`）。 */
@Serializable
data class CharacterCardImportResult(
    @SerialName("project_id") val projectId: String = "",
    val name: String = "",
    @SerialName("target_path") val targetPath: String = "",
    @SerialName("entry_count") val entryCount: Int = 0,
    @SerialName("item_count") val itemCount: Int = 0,
    val workspace: String = "",
    @SerialName("book_meta") val bookMeta: BookMeta = BookMeta(),
    val message: String = "",
)

/** 导出结果：文件名取自 `Content-Disposition`，失败时退回 `book.txt`。 */
data class BookExportFile(
    val fileName: String,
    val bytes: ByteArray,
)

/** 上传给服务端的本地文件（小说 / 角色卡 / 封面共用）。 */
data class UploadFile(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/** `GET /api/workspace/current`：服务端当前工作区与项目标识（书籍页据此高亮当前书籍）。 */
@Serializable
data class WorkspaceCurrentResponse(
    val workspace: String = "",
    @SerialName("project_id") val projectId: String = "",
    @SerialName("has_state") val hasState: Boolean = false,
)