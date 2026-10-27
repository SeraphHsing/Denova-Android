package com.denova.novara.feature.books

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.R
import com.denova.novara.core.books.BookOrder
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.BookExportFile
import com.denova.novara.core.network.BookMeta
import com.denova.novara.core.network.BookRecord
import com.denova.novara.core.network.CharacterCardImportResult
import com.denova.novara.core.network.CharacterCardPreview
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.NovelImportPreview
import com.denova.novara.core.network.UploadFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 书籍管理页状态（P4-C / BOK-001·002·003）。 */
data class BooksUiState(
    val loading: Boolean = false,
    val error: UiText? = null,
    val notice: UiText? = null,
    val books: List<BookRecord> = emptyList(),
    /** `manual`（手动排序，可上移/下移） / `recent`（最近打开）。 */
    val sortMode: String = "recent",
    /** 当前书籍的项目标识（稳定身份；不使用列表下标或显示名）。 */
    val currentProjectId: String = "",
    val busy: Boolean = false,
    /** 小说导入预览；非空表示预览就绪、等待确认（预览不等同提交）。 */
    val novelPreview: NovelImportPreview? = null,
    val novelFile: UploadFile? = null,
    val cardPreview: CharacterCardPreview? = null,
    val cardFile: UploadFile? = null,
    val progress: UiText? = null,
)

/** 当前书籍：按项目标识匹配（书籍 == 工作区，`project_id` 才是稳定身份）。 */
fun BooksUiState.currentBook(): BookRecord? = books.firstOrNull { it.projectId == currentProjectId }

/**
 * 书籍管理：列表 / 新建 / 切换 / 信息 / 封面 / 导入（小说、角色卡）/ 导出 / 排序 / 移除。
 *
 * 服务端是唯一事实源：写操作成功后一律重新读取 `GET /api/books`，
 * 不用本地猜测的顺序或当前书籍。
 */
class BooksViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null

    private val _state = MutableStateFlow(BooksUiState())
    val state: StateFlow<BooksUiState> = _state.asStateFlow()

    fun bindClient(client: DenovaClient, currentProjectId: String) {
        val changed = this.client !== client
        this.client = client
        _state.value = _state.value.copy(
            currentProjectId = if (changed) currentProjectId else currentProjectId.ifBlank {
                _state.value.currentProjectId
            },
        )
        load(force = changed || _state.value.books.isEmpty())
    }

    fun load(force: Boolean = true) {
        val c = client ?: return
        if (_state.value.loading || (!force && _state.value.books.isNotEmpty())) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val resp = c.bookshelf()
                _state.value = _state.value.copy(
                    loading = false,
                    books = resp.books,
                    sortMode = resp.sortMode.ifBlank { "recent" },
                )
                DenovaLog.i(
                    "Books",
                    "loaded books=${resp.books.size} sort=${resp.sortMode} covers=" +
                        resp.books.count { !it.coverUpdatedAt.isNullOrBlank() },
                )
                // 外壳还没确定当前项目时（冷启动直接进书籍页），向服务端要当前工作区，
                // 用 workspace → project_id 反查，避免把「当前书籍」显示成未设置。
                if (_state.value.currentProjectId.isBlank()) {
                    val current = runCatching { c.currentWorkspace() }.getOrNull()
                    val projectId = current?.projectId.orEmpty().ifBlank {
                        resp.books.firstOrNull { it.path == current?.workspace }?.projectId.orEmpty()
                    }
                    if (projectId.isNotBlank()) {
                        _state.value = _state.value.copy(currentProjectId = projectId)
                    }
                }
            } catch (e: Exception) {
                DenovaLog.w("Books", "load failed", e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.books_load_failed),
                )
            }
        }
    }

    fun create(title: String, author: String, description: String) {
        val c = client ?: return
        val t = title.trim()
        if (t.isEmpty()) {
            _state.value = _state.value.copy(error = UiText.of(R.string.books_title_required))
            return
        }
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null)
            try {
                val resp = c.createBook(t, author.trim(), description.trim())
                DenovaLog.i("Books", "created project=${resp.projectId} workspace=${resp.workspace}")
                _state.value = _state.value.copy(
                    busy = false,
                    currentProjectId = resp.projectId,
                    notice = UiText.of(R.string.books_created, resp.bookMeta.title.ifBlank { t }),
                )
                load(force = true)
            } catch (e: Exception) {
                fail("create", e, R.string.books_create_failed)
            }
        }
    }

    /** 切换当前书籍：服务端 `POST /api/workspace/switch` 成功后才认为已切换。 */
    fun switchTo(book: BookRecord) {
        val c = client ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null)
            try {
                val resp = c.switchWorkspace(book.path)
                DenovaLog.i("Books", "switched name=${book.name} workspace=${resp.workspace}")
                _state.value = _state.value.copy(
                    busy = false,
                    currentProjectId = book.projectId,
                    notice = UiText.of(R.string.books_switched, book.name),
                )
            } catch (e: Exception) {
                fail("switch", e, R.string.books_switch_failed)
            }
        }
    }

    fun info(path: String, onLoaded: (BookMeta) -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                onLoaded(c.bookInfo(path))
            } catch (e: Exception) {
                DenovaLog.w("Books", "info failed path=$path", e)
                _state.value = _state.value.copy(error = e.message?.let { UiText.raw(it) })
            }
        }
    }

    fun updateInfo(path: String, title: String, author: String, description: String) {
        val c = client ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val meta = c.updateBookInfo(path, title.trim(), author.trim(), description.trim())
                DenovaLog.i("Books", "info updated path=$path title=${meta.title}")
                _state.value = _state.value.copy(busy = false, notice = UiText.of(R.string.books_info_saved))
                load(force = true)
            } catch (e: Exception) {
                fail("updateInfo", e, R.string.books_save_failed)
            }
        }
    }

    /** 从书架移除：二次确认后调用；只移除记录，磁盘目录保留。 */
    fun remove(book: BookRecord) {
        val c = client ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val resp = c.removeBook(book.path)
                DenovaLog.i("Books", "removed name=${book.name} newWorkspace=${resp.workspace}")
                _state.value = _state.value.copy(
                    busy = false,
                    notice = UiText.of(R.string.books_removed, book.name),
                )
                val refreshed = loadAndReturn()
                // 移除的是当前书籍时，服务端会把工作区切到别的书（或空）；以重读结果为准。
                if (book.projectId == _state.value.currentProjectId || resp.workspace.isNotBlank()) {
                    val next = refreshed.firstOrNull { it.path == resp.workspace }
                    if (next != null) {
                        _state.value = _state.value.copy(currentProjectId = next.projectId)
                    } else if (book.projectId == _state.value.currentProjectId) {
                        _state.value = _state.value.copy(currentProjectId = "")
                    }
                }
            } catch (e: Exception) {
                fail("remove", e, R.string.books_remove_failed)
            }
        }
    }

    /** 手动排序：手机端用上移 / 下移替代拖拽，提交完整路径表。 */
    fun move(book: BookRecord, delta: Int) {
        val c = client ?: return
        val next = BookOrder.move(_state.value.books, book.path, delta)
        if (next === _state.value.books) return
        _state.value = _state.value.copy(books = next)
        viewModelScope.launch {
            try {
                c.reorderBooks(BookOrder.paths(next))
                DenovaLog.i("Books", "reordered path=${book.path} delta=$delta")
                load(force = true)
            } catch (e: Exception) {
                DenovaLog.w("Books", "reorder failed path=${book.path}", e)
                _state.value = _state.value.copy(
                    error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.books_sort_failed),
                )
                load(force = true)
            }
        }
    }

    fun setSortMode(mode: String) {
        val c = client ?: return
        if (mode == _state.value.sortMode) return
        viewModelScope.launch {
            try {
                c.setBookSortMode(mode)
                DenovaLog.i("Books", "sort mode=$mode")
                _state.value = _state.value.copy(sortMode = mode)
                load(force = true)
            } catch (e: Exception) {
                DenovaLog.w("Books", "sort mode failed mode=$mode", e)
                _state.value = _state.value.copy(
                    error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.books_sort_failed),
                )
            }
        }
    }

    /** 导出 TXT：返回文件名与字节，由界面写入 Download/Denova。 */
    suspend fun export(path: String): BookExportFile? {
        val c = client ?: return null
        _state.value = _state.value.copy(progress = UiText.of(R.string.home_exporting))
        return try {
            val file = c.exportBook(path, "txt")
            _state.value = _state.value.copy(progress = null)
            DenovaLog.i("Books", "exported path=$path bytes=${file.bytes.size} name=${file.fileName}")
            file
        } catch (e: Exception) {
            DenovaLog.w("Books", "export failed path=$path", e)
            _state.value = _state.value.copy(
                progress = null,
                error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.home_exportError),
            )
            null
        }
    }

    // ---- 小说导入（BOK-002）：预览不等同提交 ----

    fun previewNovel(file: UploadFile) {
        val c = client ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                busy = true,
                error = null,
                progress = UiText.of(R.string.novel_import_previewing),
            )
            try {
                val preview = c.previewNovelImport(file)
                DenovaLog.i("Books", "novel preview chapters=${preview.chapterCount} chars=${preview.totalChars}")
                _state.value = _state.value.copy(
                    busy = false,
                    progress = null,
                    novelPreview = preview,
                    novelFile = file,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(progress = null)
                fail("novelPreview", e, R.string.novel_import_failed)
            }
        }
    }

    fun confirmNovelImport(title: String, author: String, description: String) {
        val c = client ?: return
        val file = _state.value.novelFile ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                busy = true,
                error = null,
                progress = UiText.of(R.string.novel_importing),
            )
            try {
                val result = c.importNovel(
                    file = file,
                    bookTitle = title.trim(),
                    author = author.trim(),
                    description = description.trim(),
                )
                DenovaLog.i(
                    "Books",
                    "novel imported workspace=${result.workspace} chapters=${result.chapterCount} chars=${result.totalChars}",
                )
                _state.value = _state.value.copy(
                    busy = false,
                    progress = null,
                    novelPreview = null,
                    novelFile = null,
                    notice = UiText.of(R.string.novel_imported, result.chapterCount),
                )
                val refreshed = loadAndReturn()
                refreshed.firstOrNull { it.path == result.workspace }?.let {
                    _state.value = _state.value.copy(currentProjectId = it.projectId)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(progress = null)
                fail("novelImport", e, R.string.novel_import_failed)
            }
        }
    }

    // ---- 角色卡导入（BOK-002）----

    fun previewCard(file: UploadFile) {
        val c = client ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                busy = true,
                error = null,
                progress = UiText.of(R.string.card_import_previewing),
            )
            try {
                val preview = c.previewCharacterCard(file)
                DenovaLog.i("Books", "card preview name=${preview.name} entries=${preview.entryCount}")
                _state.value = _state.value.copy(
                    busy = false,
                    progress = null,
                    cardPreview = preview,
                    cardFile = file,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(progress = null)
                fail("cardPreview", e, R.string.card_import_failed)
            }
        }
    }

    fun confirmCardImport(title: String) {
        val c = client ?: return
        val file = _state.value.cardFile ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                busy = true,
                error = null,
                progress = UiText.of(R.string.card_importing),
            )
            try {
                val result: CharacterCardImportResult = c.importCharacterCardAsNewBook(file, title.trim())
                DenovaLog.i(
                    "Books",
                    "card imported project=${result.projectId} name=${result.name} entries=${result.entryCount}",
                )
                _state.value = _state.value.copy(
                    busy = false,
                    progress = null,
                    cardPreview = null,
                    cardFile = null,
                    currentProjectId = result.projectId.ifBlank { _state.value.currentProjectId },
                    notice = UiText.of(R.string.card_imported, result.name.ifBlank { title }),
                )
                load(force = true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(progress = null)
                fail("cardImport", e, R.string.card_import_failed)
            }
        }
    }

    fun uploadCover(path: String, file: UploadFile) {
        val c = client ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(progress = UiText.of(R.string.home_coverUploading), error = null)
            try {
                c.uploadBookCover(path, file)
                _state.value = _state.value.copy(
                    progress = null,
                    notice = UiText.of(R.string.books_cover_updated),
                )
                load(force = true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(progress = null)
                fail("coverUpload", e, R.string.home_coverUploadError)
            }
        }
    }

    fun dismissNovelPreview() {
        _state.value = _state.value.copy(novelPreview = null, novelFile = null)
    }

    fun dismissCardPreview() {
        _state.value = _state.value.copy(cardPreview = null, cardFile = null)
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun consumeError() {
        _state.value = _state.value.copy(error = null)
    }

    /** 重新读取列表并直接返回结果（供需要按 workspace 反查 project_id 的调用方）。 */
    private suspend fun loadAndReturn(): List<BookRecord> {
        val c = client ?: return _state.value.books
        return try {
            val resp = c.bookshelf()
            _state.value = _state.value.copy(
                books = resp.books,
                sortMode = resp.sortMode.ifBlank { _state.value.sortMode },
                error = null,
            )
            resp.books
        } catch (e: Exception) {
            DenovaLog.w("Books", "reload failed", e)
            _state.value.books
        }
    }

    private fun fail(tag: String, e: Exception, fallback: Int) {
        DenovaLog.w("Books", "$tag failed", e)
        _state.value = _state.value.copy(
            busy = false,
            error = e.message?.takeIf { it.isNotBlank() }?.let { UiText.raw(it) } ?: UiText.of(fallback),
        )
    }
}
