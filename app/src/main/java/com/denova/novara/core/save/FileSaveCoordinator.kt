package com.denova.novara.core.save

import com.denova.novara.core.editor.MergeResult
import com.denova.novara.core.editor.ThreeWayMerge
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.ProjectFileDocument
import com.denova.novara.core.network.SaveFileResponse
import com.denova.novara.core.persistence.Draft
import com.denova.novara.core.persistence.DraftStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** 文件远端读写抽象，便于注入 fake 做单测；[com.denova.novara.core.network.DenovaClient] 实现它。 */
interface FileRemote {
    suspend fun readFile(projectId: String, path: String): ProjectFileDocument
    suspend fun saveFile(
        projectId: String,
        path: String,
        content: String,
        baseRevision: String,
    ): SaveFileResponse
}

sealed interface SaveOutcome {
    /** 无冲突，直接保存成功。 */
    data class Saved(val revision: String) : SaveOutcome

    /**
     * 发生冲突但三方合并干净，已用服务器最新 revision 重存成功。
     *
     * [mergedText] 是实际写回服务端的合并结果：调用方必须用它替换本地正文，
     * 否则下一次以 `baseRevision` 保存时会用「本地旧内容」覆盖掉对端修改（静默数据丢失）。
     */
    data class MergedAndSaved(val revision: String, val mergedText: String) : SaveOutcome

    /** 发生冲突且无法自动合并，返回含冲突标记的文本。 */
    data class Conflict(val mergedText: String, val conflictCount: Int, val serverRevision: String) : SaveOutcome

    /** 非冲突错误（网络/权限等）。 */
    data class Error(val exception: DenovaApiException) : SaveOutcome
}

/**
 * 保存编排：先落本地草稿 → 保存；409 冲突则读服务器版本做三方合并，
 * 干净就重存、否则产出冲突材料。同一文件通过 [Mutex] 串行。
 */
class FileSaveCoordinator(
    private val remote: FileRemote,
    private val drafts: DraftStorage,
) {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun save(
        projectId: String,
        path: String,
        baseRevision: String,
        baseContent: String,
        content: String,
    ): SaveOutcome {
        val key = "$projectId\u0000$path"
        return mutexes.getOrPut(key) { Mutex() }.withLock {
            // 先落草稿，保证进程被杀后未提交编辑可恢复。
            drafts.saveDraft(key, Draft(content, baseRevision, baseContent))
            try {
                val resp = remote.saveFile(projectId, path, content, baseRevision)
                drafts.clearDraft(key)
                SaveOutcome.Saved(resp.revision)
            } catch (e: DenovaApiException) {
                if (e.status == 409) {
                    handleConflict(key, projectId, path, baseContent, content)
                } else {
                    SaveOutcome.Error(e)
                }
            } catch (e: Exception) {
                SaveOutcome.Error(DenovaApiException(0, null, e.message))
            }
        }
    }

    private suspend fun handleConflict(
        key: String,
        projectId: String,
        path: String,
        baseContent: String,
        ours: String,
    ): SaveOutcome {
        return try {
            val theirs = remote.readFile(projectId, path)
            val serverContent = theirs.content ?: ""
            when (val merged = ThreeWayMerge.merge(baseContent, ours, serverContent)) {
                is MergeResult.Clean -> {
                    val resp = remote.saveFile(projectId, path, merged.text, theirs.revision)
                    drafts.clearDraft(key)
                    SaveOutcome.MergedAndSaved(resp.revision, merged.text)
                }
                is MergeResult.Conflicts -> {
                    SaveOutcome.Conflict(merged.text, merged.conflictCount, theirs.revision)
                }
            }
        } catch (e: DenovaApiException) {
            SaveOutcome.Error(e)
        } catch (e: Exception) {
            SaveOutcome.Error(DenovaApiException(0, null, e.message))
        }
    }
}
