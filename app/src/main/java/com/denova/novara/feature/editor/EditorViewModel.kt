package com.denova.novara.feature.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.NovaraApplication
import com.denova.novara.core.persistence.DraftStore
import com.denova.novara.core.save.FileSaveCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SaveStatus { IDLE, SAVING, SAVED, MERGED, CONFLICT, ERROR }

data class EditorUiState(
    val projectId: String = "",
    val filePath: String = "",
    val content: String = "",
    val baseRevision: String = "",
    val baseContent: String = "",
    val loaded: Boolean = false,
    val saveStatus: SaveStatus = SaveStatus.IDLE,
    val message: String? = null,
    val conflictText: String? = null,
    /** 外部改写正文（合并结果）时递增，用于让界面重建编辑态。 */
    val contentEpoch: Int = 0,
)

/** 编辑器：打开文件 → 编辑（防抖自动保存）→ revision 冲突合并/提示。保存状态机委托给 [EditorSession]。 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val drafts = DraftStore((application as NovaraApplication).container.database.draftDao())
    private var client: DenovaClient? = null
    private var session: EditorSession? = null

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var collectJob: Job? = null

    /** 登录后绑定当前连接的 client，建立保存编排与状态机。 */
    fun bindClient(client: DenovaClient) {
        DenovaLog.i("Editor", "client bound")
        this.client = client
        val coordinator = FileSaveCoordinator(client, drafts)
        val s = EditorSession(coordinator, drafts, viewModelScope)
        this.session = s
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            s.state.collect { _uiState.value = it }
        }
    }

    fun openFile(projectId: String, path: String) {
        val c = client ?: return
        val s = session ?: return
        EditorPerf.beginOpen()
        viewModelScope.launch {
            try {
                val doc = c.readFile(projectId, path)
                DenovaLog.i("Editor", "opened project=$projectId path=$path revision=${doc.revision}")
                s.open(projectId, path, doc.content ?: "", doc.revision)
                EditorPerf.endOpen((doc.content ?: "").length)
            } catch (e: Exception) {
                DenovaLog.w("Editor", "open failed project=$projectId path=$path", e)
                s.failOpen("打开文件失败：${e.message}")
            }
        }
    }

    fun updateContent(text: String) {
        val s = session ?: return
        EditorPerf.markEditStart()
        s.edit(text)
    }

    fun save() {
        session?.saveNow()
    }

    fun resolveConflict() {
        session?.resolveConflict()
    }
}
