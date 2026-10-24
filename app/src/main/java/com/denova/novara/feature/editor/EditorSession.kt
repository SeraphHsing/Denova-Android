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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /**
     * 冲突落地：正文换成带标记的合并结果，但 **base 必须取服务器当前内容**。
     *
     * 早先这里把 base 记成 `mergedText`（带冲突标记的本地合成结果），后果是：
     * 「已解决，保存」时 content == baseContent 被当成无改动而静默跳过；
     * 即使发出去，base 也不等于服务器内容，三方合并会算错（WRI-011 真机发现）。
     */
    fun applyConflict(outcome: SaveOutcome.Conflict, current: EditorUiState): EditorUiState =
        current.copy(
            content = outcome.mergedText,
            baseRevision = outcome.serverRevision,
            baseContent = outcome.serverContent,
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

        /** flush 的最多连做轮数：保存期间用户继续输入时多补几轮，避免切换丢字。 */
        const val MAX_FLUSH_ROUNDS = 3
    }

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** 防抖计时器：到期后启动一次真正的保存。 */
    private var debounceJob: Job? = null

    /** 真正在跑的保存（含网络往返）；切文件时需要能取消它。 */
    private var saveJob: Job? = null

    /**
     * 保存串行化：同一时刻只有一个 PUT。
     *
     * 不在新编辑时取消在途保存——否则每次按键都会中断请求，回执永远到不了，
     * 「旧快照回执不清新 dirty」的策略也就无从生效（WRI-011 / G3-c）。
     * 新编辑只排一次新的保存，等在途的结束后再写最新内容。
     */
    private val saveMutex = Mutex()

    /**
     * 文件切换世代。每次 [open] 递增；保存回执只在世代与文件 key 都未变时才能落到状态上，
     * 否则旧文件的 revision / base 会把新文件覆盖成错误归属（G3-d 切文件）。
     */
    private var openEpoch = 0L

    suspend fun open(projectId: String, path: String, serverContent: String, serverRevision: String) {
        val key = "$projectId\u0000$path"
        // 切换文件：取消在途保存并推进世代，旧文件的回执不得写进新文件状态（G3-d）。
        cancelPendingSaves()
        openEpoch++
        // 重新打开同一个文件时正文可能变（例如服务器被别处改回），必须让界面重建编辑态，
        // 否则输入框还留着旧文本，下一次保存会把旧内容写回服务器（contentEpoch 是界面重置键）。
        val epoch = _state.value.contentEpoch + 1
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
                    contentEpoch = epoch,
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
                    contentEpoch = epoch,
                )
            }
        }
    }

    fun failOpen(message: String) {
        _state.value = _state.value.copy(loaded = false, saveStatus = SaveStatus.ERROR, message = message)
    }

    /**
     * 关闭最后一个标签：取消在途保存、推进世代并清空编辑器（无打开文件）。
     * 调用方负责先 [flush]，避免丢弃未提交内容。
     */
    fun clear() {
        cancelPendingSaves()
        openEpoch++
        _state.value = EditorUiState()
    }

    /** 取消防抖计时与在途保存（切文件 / 关闭标签 / 清空时用）。 */
    private fun cancelPendingSaves() {
        debounceJob?.cancel()
        debounceJob = null
        saveJob?.cancel()
        saveJob = null
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

    /**
     * 手动保存（WRI-011）：跳过防抖立刻落盘。
     * 没有改动时只给「已保存」反馈，不发空 PUT；正在保存时再点也只会排队一次。
     */
    fun saveNow() {
        val s = _state.value
        if (!s.loaded) return
        if (s.saveStatus == SaveStatus.CONFLICT) return
        debounceJob?.cancel()
        debounceJob = null
        if (s.content == s.baseContent) {
            _state.value = s.copy(saveStatus = SaveStatus.SAVED, message = "已保存")
            return
        }
        startSave()
    }

    /**
     * 切换书籍 / 导航前 flush：保存当前未提交编辑并等待完成。
     * 返回 false 表示保存失败或冲突未解决，调用方应中止切换，避免丢数据。
     *
     * 保存期间用户可能还在输入，因此最多连做 3 轮，直到内容与 base 一致；
     * 否则切换会丢掉这期间的最后几次按键。
     */
    suspend fun flush(): Boolean {
        val s = _state.value
        if (!s.loaded) return true
        if (s.saveStatus == SaveStatus.CONFLICT) return false
        debounceJob?.cancel()
        debounceJob = null
        var rounds = 0
        while (rounds < MAX_FLUSH_ROUNDS) {
            val cur = _state.value
            if (cur.saveStatus == SaveStatus.CONFLICT) return false
            if (cur.content == cur.baseContent) return true
            performSave()
            val after = _state.value
            if (after.saveStatus == SaveStatus.ERROR || after.saveStatus == SaveStatus.CONFLICT) return false
            // 期间文件被换掉：回执已丢弃，不能用新文件的状态冒充「已保存」。
            if (after.filePath != cur.filePath || after.projectId != cur.projectId) return false
            if (after.content == after.baseContent) {
                return after.saveStatus == SaveStatus.SAVED || after.saveStatus == SaveStatus.MERGED
            }
            rounds++
        }
        val final = _state.value
        return final.content == final.baseContent &&
            (final.saveStatus == SaveStatus.SAVED || final.saveStatus == SaveStatus.MERGED)
    }

    fun resolveConflict() {
        _state.value = _state.value.copy(conflictText = null, saveStatus = SaveStatus.IDLE)
        saveNow()
    }

    /**
     * 排一次防抖保存。只重排计时器，**不取消**在途保存：
     * 在途请求照常返回，回执按快照判断是否还 dirty，再排下一轮（G3-c）。
     */
    private fun scheduleSave() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMillis)
            startSave()
        }
    }

    /** 启动一次保存并保留句柄，供切文件时取消。 */
    private fun startSave() {
        saveJob = scope.launch { performSave() }
    }

    /** 返回回执是否落到当前文件；保存期间切换文件时回执被丢弃并返回 false。 */
    private suspend fun performSave(): Boolean = saveMutex.withLock {
        val s = _state.value
        performSaveLocked(s)
    }

    private suspend fun performSaveLocked(s: EditorUiState): Boolean {
        if (!s.loaded || s.saveStatus == SaveStatus.CONFLICT) return false
        // 排队期间内容已被上一次保存覆盖：不再发空 PUT。
        if (s.content == s.baseContent && s.baseRevision.isNotEmpty()) return false
        val epoch = openEpoch
        val key = fileKey(s)
        val startedAt = System.currentTimeMillis()
        val draftWindow = EditorPerf.pendingEditMillis()
        val snapshot = s.content
        _state.value = s.copy(saveStatus = SaveStatus.SAVING)
        val outcome = coordinator.save(s.projectId, s.filePath, s.baseRevision, s.baseContent, snapshot)
        // 作用域校验：回执必须属于发起保存的文件，否则丢弃（不写 revision / base / 冲突材料）。
        if (openEpoch != epoch || fileKey(_state.value) != key) {
            DenovaLog.w("Editor", "save receipt discarded path=${s.filePath} reason=file changed")
            EditorPerf.markDraftPersisted()
            EditorPerf.endSave("discarded", System.currentTimeMillis() - startedAt, draftWindow)
            return false
        }
        currentCoroutineContext().ensureActive()
        var kind = "saved"
        when (outcome) {
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
        return true
    }

    private fun fileKey(s: EditorUiState): String = "${s.projectId}\u0000${s.filePath}"
}
