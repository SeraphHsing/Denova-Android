package com.denova.novara.feature.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.NovaraApplication
import com.denova.novara.core.persistence.DraftStore
import com.denova.novara.core.save.FileSaveCoordinator
import com.denova.novara.core.save.SaveOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/** 编辑器：打开文件 → 编辑（防抖自动保存）→ revision 冲突合并/提示。 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** 自动保存防抖；也是「最后一次编辑 → 草稿落盘」的固定下限（G2 指标口径）。 */
        const val AUTOSAVE_DEBOUNCE_MILLIS = 800L
    }

    private val drafts = DraftStore((application as NovaraApplication).container.database.draftDao())
    private var client: DenovaClient? = null
    private var coordinator: FileSaveCoordinator? = null

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var saveJob: Job? = null

    /** 登录后绑定当前连接的 client，建立保存编排。 */
    fun bindClient(client: DenovaClient) {
        DenovaLog.i("Editor", "client bound")
        this.client = client
        this.coordinator = FileSaveCoordinator(client, drafts)
    }

    fun openFile(projectId: String, path: String) {
        val c = client ?: return
        EditorPerf.beginOpen()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loaded = false, saveStatus = SaveStatus.SAVING, message = null)
            try {
                val doc = c.readFile(projectId, path)
                DenovaLog.i("Editor", "opened project=$projectId path=$path revision=${doc.revision}")
                _uiState.value = EditorUiState(
                    projectId = projectId,
                    filePath = path,
                    content = doc.content ?: "",
                    baseRevision = doc.revision,
                    baseContent = doc.content ?: "",
                    loaded = true,
                    saveStatus = SaveStatus.IDLE,
                )
                EditorPerf.endOpen((doc.content ?: "").length)
            } catch (e: Exception) {
                DenovaLog.w("Editor", "open failed project=$projectId path=$path", e)
                _uiState.value = _uiState.value.copy(
                    loaded = false,
                    saveStatus = SaveStatus.ERROR,
                    message = "打开文件失败：${e.message}",
                )
            }
        }
    }

    fun updateContent(text: String) {
        val current = _uiState.value
        if (!current.loaded) return
        EditorPerf.markEditStart()
        _uiState.value = current.copy(content = text, saveStatus = SaveStatus.IDLE)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MILLIS)
            save()
        }
    }

    fun save() {
        val c = coordinator ?: return
        val s = _uiState.value
        if (!s.loaded) return
        val startedAt = System.currentTimeMillis()
        // 草稿在保存事务开始即落盘，因此这个窗口就是「最后一次编辑 → 草稿落盘」的上限。
        val draftWindow = EditorPerf.pendingEditMillis()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(saveStatus = SaveStatus.SAVING)
            var kind = "saved"
            when (val outcome = c.save(s.projectId, s.filePath, s.baseRevision, s.baseContent, s.content)) {
                is SaveOutcome.Saved -> {
                    DenovaLog.i("Editor", "saved path=${s.filePath} revision=${outcome.revision}")
                    _uiState.value = _uiState.value.copy(
                        baseRevision = outcome.revision,
                        baseContent = s.content,
                        saveStatus = SaveStatus.SAVED,
                        message = "已保存",
                    )
                }
                is SaveOutcome.MergedAndSaved -> {
                    DenovaLog.i("Editor", "merged+saved path=${s.filePath} revision=${outcome.revision}")
                    kind = "merged"
                    // 必须采纳合并结果；否则下一次保存会用本地旧内容覆盖对端修改。
                    _uiState.value = _uiState.value.copy(
                        content = outcome.mergedText,
                        baseRevision = outcome.revision,
                        baseContent = outcome.mergedText,
                        saveStatus = SaveStatus.MERGED,
                        message = "已自动合并并保存",
                        contentEpoch = _uiState.value.contentEpoch + 1,
                    )
                }
                is SaveOutcome.Conflict -> {
                    DenovaLog.w("Editor", "conflict path=${s.filePath} count=${outcome.conflictCount}")
                    kind = "conflict"
                    _uiState.value = _uiState.value.copy(
                        content = outcome.mergedText,
                        baseRevision = outcome.serverRevision,
                        baseContent = outcome.mergedText,
                        saveStatus = SaveStatus.CONFLICT,
                        conflictText = outcome.mergedText,
                        message = "发现冲突，请手动解决后保存",
                    )
                }
                is SaveOutcome.Error -> {
                    DenovaLog.w("Editor", "save failed path=${s.filePath}", outcome.exception)
                    kind = "error"
                    _uiState.value = _uiState.value.copy(
                        saveStatus = SaveStatus.ERROR,
                        message = "保存失败：${outcome.exception.message}",
                    )
                }
            }
            EditorPerf.markDraftPersisted()
            EditorPerf.endSave(kind, System.currentTimeMillis() - startedAt, draftWindow)
        }
    }

    fun resolveConflict() {
        _uiState.value = _uiState.value.copy(conflictText = null, saveStatus = SaveStatus.IDLE)
        save()
    }
}
