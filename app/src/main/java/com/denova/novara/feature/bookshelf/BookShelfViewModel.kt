package com.denova.novara.feature.bookshelf

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
    val error: String? = null,
    val books: List<BookRecord> = emptyList(),
    /** 当前书籍（工作区）；`null` 表示尚未选择。 */
    val currentProjectId: String? = null,
    val currentBookName: String = "",
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
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
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
            _state.value = _state.value.copy(error = e.message ?: "切换失败")
            false
        }
    }
}
