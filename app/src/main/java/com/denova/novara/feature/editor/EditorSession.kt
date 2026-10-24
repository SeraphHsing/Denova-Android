package com.denova.novara.feature.editor

import com.denova.novara.core.editor.MergeResult
import com.denova.novara.core.editor.ThreeWayMerge
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.persistence.Draft
import com.denova.novara.core.persistence.DraftStorage
import com.denova.novara.core.save.FileSaveCoordinator
import com.denova.novara.core.save.SaveOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 打开文件的决定：用服务器内容，还是恢复未提交草稿。 */
sealed interface OpenDecision {
    data class Server(val content: String, val revision: String) : OpenDecision

    /** 存在未提交草稿（内容 ≠ 服务器当前内容），恢复草稿；沿用草稿的 base 以在下次保存时检测服务器变化。 */
    data class RestoredDraft(
        val content: String,
        val baseRevision: String,
        val baseContent: String,
    ) : OpenDecision
}

object EditorOpen {
    /** 进程重建恢复：有未提交草稿（内容 ≠ 服务器当前内容）则恢复草稿，否则用服务器内容。 */
    fun decide(serverContent: String, serverRevision: String, draft: Draft?): OpenDecision =
        if (draft == null || draft.content == serverContent) {
            OpenDecision.Server(serverContent, serverRevision)
        } else {
            OpenDecision.RestoredDraft(draft.content, draft.baseRevision, draft.baseContent)
        }
}

/** 保存回执应用结果；[reschedule] 为 true 表示期间有新编辑，回执只确认快照，需重新调度保存。 */
data class ReceiptResult(
    val state: EditorUiState,
    val reschedule: Boolean,
)

object ReceiptPolicy {
    /** 无冲突保存成功。期间继续编辑则保持 dirty（不标「已保存」），否则标已保存。 */
    fun applySaved(snapshot: String, newRevision: String, current: EditorUiState): ReceiptResult {
        val dirty = current.content != snapshot
        return ReceiptResult(
            current.copy(
                baseRevision = newRevision,
                baseContent = snapshot,
                saveStatus = if (dirty) SaveStatus.IDLE else SaveStatus.SAVED,
                message = if (dirty) null else "已保存",
            ),
            reschedule = dirty,
        )
    }

    /**
     * 合并后保存成功。期间继续编辑时，把新编辑 re-apply 到已合并结果上，避免覆盖服务器修改。
     */
    fun applyMerged(snapshot: String, outcome: SaveOutcome.MergedAndSaved, current: EditorUiState): ReceiptResult {
        if (current.content == snapshot) {
            return ReceiptResult(
                current.copy(
                    content = outcome.mergedText,
                    baseRevision = outcome.revision,
                    baseContent = outcome.mergedText,
                    saveStatus = SaveStatus.MERGED,
                    message = "已自动合并并保存",
                    contentEpoch = current.contentEpoch + 1,
                ),
                reschedule = false,
            )
        }
        // 期间继续编辑：base=快照、ours=当前编辑、theirs=合并结果（已含服务器修改），重新三方合并。
        val remerged = ThreeWayMerge.merge(snapshot, current.content, outcome.mergedText)
        return when (remerged) {
            is MergeResult.Clean -> ReceiptResult(
                current.copy(
                    content = remerged.text,
                    baseRevision = outcome.revision,
                    baseContent = outcome.mergedText,
                    saveStatus = SaveStatus.IDLE,
                    contentEpoch = current.contentEpoch + 1,
                ),
                reschedule = true,
            )
            is MergeResult.Conflicts -> ReceiptResult(
                current.copy(
                    content = remerged.text,
                    baseRevision = outcome.revision,
                    baseContent = outcome.mergedText,
                    saveStatus = SaveStatus.CONFLICT,
                    conflictText = remerged.text,
                    message = "发现冲突，请手动解决后保存",
                    contentEpoch = current.contentEpoch + 1,
                ),
                reschedule = false,
            )
        }
    }

    fun applyConflict(outcome: SaveOutcome.Conflict, current: EditorUiState): EditorUiState =
        current.copy(
            content = outcome.mergedText,
            baseRevision = outcome.serverRevision,
            baseContent = outcome.mergedText,
            saveStatus = SaveStatus.CONFLICT,
            conflictText = outcome.mergedText,
            message = "发现冲突，请手动解决后保存",
        )

    fun applyError(outcome: SaveOutcome.Error, current: EditorUiState): EditorUiState =
        current.copy(
            saveStatus = SaveStatus.ERROR,
            message = "保存失败：${outcome.exception.message}",
        )
}

/**
 * 编辑器保存状态机（G3）：快照与当前编辑内容分离，回执只确认当时快照，期间继续编辑仍为 dirty；
 * 冲突时暂停自动保存，保留冲突材料等待手动解决；打开时优先恢复未提交草稿。
 *
 * 纯 Kotlin + 协程，注入 [FileSaveCoordinator] 与 [DraftStorage] 即可做 JVM 单测；[EditorViewModel] 只做 Android 胶水。
 */
class EditorSession(
    private val coordinator: FileSaveCoordinator,
    private val drafts: DraftStorage,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = AUTOSAVE_DEBOUNCE_MILLIS,
) {
    companion object {
        /** 自动保存防抖；也是「最后一次编辑 → 草稿落盘」的固定下限（G2 指标口径）。 */
        const val AUTOSAVE_DEBOUNCE_MILLIS = 800L
    }

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var saveJob: Job? = null

    suspend fun open(projectId: String, path: String, serverContent: String, serverRevision: String) {
        val key = "$projectId\u0000$path"
        when (val decision = EditorOpen.decide(serverContent, serverRevision, drafts.loadDraft(key))) {
            is OpenDecision.Server -> {
                drafts.clearDraft(key) // 清理残留草稿（内容已与服务器一致）
                _state.value = EditorUiState(
                    projectId = projectId,
                    filePath = path,
                    content = serverContent,
                    baseRevision = serverRevision,
                    baseContent = serverContent,
                    loaded = true,
                    saveStatus = SaveStatus.IDLE,
                )
            }
            is OpenDecision.RestoredDraft -> {
                // 保留草稿：若用户不保存即退出，下次仍可恢复；保存成功时由协调器清除。
                _state.value = EditorUiState(
                    projectId = projectId,
                    filePath = path,
                    content = decision.content,
                    baseRevision = decision.baseRevision,
                    baseContent = decision.baseContent,
                    loaded = true,
                    saveStatus = SaveStatus.IDLE,
                    message = "已恢复未保存的编辑",
                )
            }
        }
    }

    fun failOpen(message: String) {
        _state.value = _state.value.copy(loaded = false, saveStatus = SaveStatus.ERROR, message = message)
    }

    fun edit(newContent: String) {
        val s = _state.value
        if (!s.loaded) return
        // 冲突状态下暂停自动保存，等待用户手动解决（resolveConflict），避免盲目覆盖冲突材料。
        if (s.saveStatus == SaveStatus.CONFLICT) {
            _state.value = s.copy(content = newContent)
            return
        }
        _state.value = s.copy(content = newContent, saveStatus = SaveStatus.IDLE)
        scheduleSave()
    }

    fun saveNow() {
        if (!_state.value.loaded) return
        saveJob?.cancel()
        saveJob = scope.launch { performSave() }
    }

    /**
     * 切换书籍 / 导航前 flush：保存当前未提交编辑并等待完成。
     * 返回 false 表示保存失败或冲突未解决，调用方应中止切换，避免丢数据。
     */
    suspend fun flush(): Boolean {
        val s = _state.value
        if (!s.loaded) return true
        if (s.saveStatus == SaveStatus.CONFLICT) return false
        if (s.content == s.baseContent) return true
        saveJob?.cancel()
        performSave()
        return _state.value.saveStatus == SaveStatus.SAVED || _state.value.saveStatus == SaveStatus.MERGED
    }

    fun resolveConflict() {
        _state.value = _state.value.copy(conflictText = null, saveStatus = SaveStatus.IDLE)
        saveNow()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(debounceMillis)
            performSave()
        }
    }

    private suspend fun performSave() {
        val s = _state.value
        if (!s.loaded || s.saveStatus == SaveStatus.CONFLICT) return
        val startedAt = System.currentTimeMillis()
        val draftWindow = EditorPerf.pendingEditMillis()
        val snapshot = s.content
        _state.value = s.copy(saveStatus = SaveStatus.SAVING)
        var kind = "saved"
        when (val outcome = coordinator.save(s.projectId, s.filePath, s.baseRevision, s.baseContent, snapshot)) {
            is SaveOutcome.Saved -> {
                DenovaLog.i("Editor", "saved path=${s.filePath} revision=${outcome.revision}")
                val r = ReceiptPolicy.applySaved(snapshot, outcome.revision, _state.value)
                _state.value = r.state
                if (r.reschedule) scheduleSave()
            }
            is SaveOutcome.MergedAndSaved -> {
                kind = "merged"
                DenovaLog.i("Editor", "merged+saved path=${s.filePath} revision=${outcome.revision}")
                val r = ReceiptPolicy.applyMerged(snapshot, outcome, _state.value)
                _state.value = r.state
                if (r.reschedule) scheduleSave()
            }
            is SaveOutcome.Conflict -> {
                kind = "conflict"
                DenovaLog.w("Editor", "conflict path=${s.filePath} count=${outcome.conflictCount}")
                _state.value = ReceiptPolicy.applyConflict(outcome, _state.value)
            }
            is SaveOutcome.Error -> {
                kind = "error"
                _state.value = ReceiptPolicy.applyError(outcome, _state.value)
            }
        }
        EditorPerf.markDraftPersisted()
        EditorPerf.endSave(kind, System.currentTimeMillis() - startedAt, draftWindow)
    }
}
