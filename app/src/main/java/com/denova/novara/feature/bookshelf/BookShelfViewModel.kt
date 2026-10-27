package com.denova.novara.feature.bookshelf

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.BookRecord
import com.denova.novara.core.network.DenovaClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BookShelfUiState(
    val loading: Boolean = false,
    val error: UiText? = null,
    val books: List<BookRecord> = emptyList(),
    /** 当前书籍（工作区）；`null` 表示尚未选择。 */
    val currentProjectId: String? = null,
    val currentBookName: String = "",
    /** 建书（WRI-001）进行中：按钮禁用，避免重复提交。 */
    val creating: Boolean = false,
    val createMessage: UiText? = null,
    /**
     * 本次会话刚创建的书名。引导的 AGENT 步据此出现——放在 ViewModel 里而不是界面 remember，
     * 否则建书后跳到 Agent 再返回，页面重组就把这一步丢了（真机复现过）。
     */
    val justCreatedName: String = "",
)

/**
 * 书架 / 当前书籍切换（P3 链路起点）：加载 `GET /api/books`，选择后把 `project_id` 提供给
 * 编辑器与项目目录。书籍切换只改变工作区上下文，不改变当前功能页。
 */
class BookShelfViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null

    private val _state = MutableStateFlow(BookShelfUiState())
    val state: StateFlow<BookShelfUiState> = _state.asStateFlow()

    fun bindClient(client: DenovaClient) {
        this.client = client
    }

    /** 强制重读（书籍管理页新建 / 导入 / 移除后同步抽屉里的书籍列表）。 */
    fun refresh() {
        _state.value = _state.value.copy(books = emptyList())
        load()
    }

    fun load() {
        val c = client ?: return
        if (_state.value.books.isNotEmpty() || _state.value.loading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val resp = c.bookshelf()
                _state.value = _state.value.copy(loading = false, books = resp.books)
                DenovaLog.i("BookShelf", "loaded books=${resp.books.size}")
            } catch (e: Exception) {
                DenovaLog.w("BookShelf", "load failed", e)
                _state.value = _state.value.copy(loading = false, error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.books_load_failed))
            }
        }
    }

    /**
     * 建书（WRI-001）：`POST /api/books/create`，只提交 `title/author/description`。
     *
     * 该接口在服务端会**切换当前 workspace**，所以成功后直接把当前书籍指向新 project_id，
     * 不必再走一次 `workspace/switch`；返回 null 表示失败（错误已写进 state）。
     */
    suspend fun createBook(title: String, author: String = "", description: String = ""): BookRecord? {
        val c = client ?: return null
        val t = title.trim()
        if (t.isEmpty()) {
            _state.value = _state.value.copy(error = UiText.of(R.string.books_title_required))
            return null
        }
        _state.value = _state.value.copy(creating = true, error = null, createMessage = null)
        return try {
            val resp = c.createBook(t, author, description)
            val record = BookRecord(
                projectId = resp.projectId,
                name = resp.bookMeta.title.ifBlank { t },
                path = resp.workspace,
                author = resp.bookMeta.author,
            )
            _state.value = _state.value.copy(
                creating = false,
                error = null,
                books = _state.value.books.filterNot { it.projectId == record.projectId } + record,
                currentProjectId = record.projectId,
                currentBookName = record.name,
                createMessage = UiText.of(R.string.books_created, record.name),
                justCreatedName = record.name,
            )
            DenovaLog.i("BookShelf", "created project=${record.projectId} workspace=${resp.workspace}")
            record
        } catch (e: Exception) {
            DenovaLog.w("BookShelf", "create failed title=$t", e)
            _state.value = _state.value.copy(creating = false, error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.books_create_failed))
            null
        }
    }

    /** 建书提示只显示一次。 */
    fun clearCreateMessage() {
        _state.value = _state.value.copy(createMessage = null)
    }

    /** 引导的 AGENT 步已使用（填入示例）或已跳过：这一步不再出现。 */
    fun clearJustCreated() {
        _state.value = _state.value.copy(justCreatedName = "")
    }

    /**
     * 切换书籍：先调服务端 `POST /api/workspace/switch`，成功后再更新客户端当前书籍。
     * 返回 false 表示服务端切换失败（客户端保持原状态，不假装已切换）。
     */
    suspend fun switch(book: BookRecord): Boolean {
        val c = client ?: return false
        return try {
            val resp = c.switchWorkspace(book.path)
            val nextWorkspace = resp.workspace.ifBlank { book.path }
            _state.value = _state.value.copy(
                currentProjectId = book.projectId,
                currentBookName = book.name,
                error = null,
            )
            DenovaLog.i("BookShelf", "switched name=${book.name} project=${book.projectId} workspace=$nextWorkspace")
            true
        } catch (e: Exception) {
            DenovaLog.w("BookShelf", "switch failed name=${book.name}", e)
            _state.value = _state.value.copy(error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.books_switch_failed))
            false
        }
    }
}
