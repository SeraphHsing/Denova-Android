package com.denova.novara.feature.chapters

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.book.ChapterRules
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 章节目录里的一行：状态与字数都来自服务端摘要，客户端不改写。 */
data class ChapterRow(
    val path: String,
    val title: String,
    val status: String,
    val confirmed: Boolean,
    val words: Int,
    val index: Int,
)

data class ChaptersUiState(
    val projectId: String = "",
    val bookTitle: String = "",
    val chapters: List<ChapterRow> = emptyList(),
    val chapterCount: Int = 0,
    val totalWords: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    /** 正在提交状态变更的章节路径；非空期间其余按钮禁用，避免连点。 */
    val pendingPath: String = "",
)

/**
 * 章节状态（WRI-015）：`GET /api/projects/:id/book/summary` 取目录与状态，
 * `PATCH …/book/chapter-status` 只写 `confirmed`，写完重新拉摘要——状态是服务端算出来的，客户端不本地推算。
 */
class ChaptersViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null
    private val _state = MutableStateFlow(ChaptersUiState())
    val state: StateFlow<ChaptersUiState> = _state.asStateFlow()

    /** 已加载的项目作用域：换项目必须重新拉，同一项目不重复请求。 */
    private var loadedProject: String = ""

    fun bindClient(client: DenovaClient) {
        this.client = client
        loadedProject = ""
    }

    fun load(projectId: String, force: Boolean = false) {
        val pid = projectId.trim()
        if (pid.isEmpty()) {
            _state.value = ChaptersUiState()
            return
        }
        if (!force && pid == loadedProject && _state.value.chapters.isNotEmpty()) return
        val c = client ?: return
        loadedProject = pid
        _state.value = _state.value.copy(projectId = pid, loading = true, error = null)
        viewModelScope.launch {
            try {
                val resp = c.bookSummary(pid)
                // 作用域校验：回包项目与请求不一致时丢弃，防止串项目（AGENTS §4）。
                if (resp.projectId.isNotBlank() && resp.projectId != pid) {
                    DenovaLog.w("Chapters", "summary scope mismatch want=$pid got=${resp.projectId}")
                    _state.value = _state.value.copy(loading = false, error = "项目作用域不一致")
                    return@launch
                }
                val summary = resp.summary
                _state.value = _state.value.copy(
                    projectId = pid,
                    bookTitle = summary.title,
                    chapters = summary.chapters.map {
                        ChapterRow(
                            path = it.path,
                            title = it.displayTitle.ifBlank { it.fileName },
                            status = ChapterRules.statusLabel(it.status, it.words),
                            confirmed = it.confirmed,
                            words = it.words,
                            index = it.index,
                        )
                    },
                    chapterCount = summary.chapterCount,
                    totalWords = summary.totalWords,
                    loading = false,
                    error = null,
                    pendingPath = "",
                )
                DenovaLog.i(
                    "Chapters",
                    "summary loaded project=$pid chapters=${summary.chapters.size} words=${summary.totalWords}",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Chapters", "summary failed project=$pid", e)
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载章节失败")
            }
        }
    }

    /** 切换确认位：空章不可确认、已确认才能标回初稿（规则见 [ChapterRules]）。 */
    fun toggleConfirmed(path: String) {
        val current = _state.value
        val row = current.chapters.firstOrNull { it.path == path } ?: return
        if (!ChapterRules.canConfirm(row.words, row.confirmed) && !ChapterRules.canMarkDraft(row.confirmed)) return
        if (current.pendingPath.isNotEmpty() || current.projectId.isBlank()) return
        val c = client ?: return
        val target = ChapterRules.nextConfirmed(row.confirmed)
        _state.value = current.copy(pendingPath = path, error = null, message = null)
        viewModelScope.launch {
            try {
                val resp = c.setChapterConfirmed(current.projectId, path, target)
                DenovaLog.i("Chapters", "status path=$path confirmed=${resp.confirmed}")
                // 状态由服务端重算，重新拉摘要而不是本地改字符串。
                loadedProject = ""
                load(current.projectId, force = true)
                _state.value = _state.value.copy(message = resp.message.ifBlank { "已更新章节状态" })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Chapters", "status failed path=$path", e)
                _state.value = _state.value.copy(pendingPath = "", error = e.message ?: "更新章节状态失败")
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }
}
