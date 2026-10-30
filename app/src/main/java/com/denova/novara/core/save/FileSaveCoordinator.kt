package com.denova.novara.core.save

import com.denova.novara.core.editor.MergeResult
import com.denova.novara.core.editor.ThreeWayMerge
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.ProjectFileDocument
import com.denova.novara.core.network.SaveFileResponse
import com.denova.novara.core.persistence.Draft
import com.denova.novara.core.persistence.DraftStorage
import kotlinx.coroutines.CancellationException
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

    /**
     * 发生冲突且无法自动合并，返回含冲突标记的文本。
     *
     * [serverContent] 是合并所用的服务器当前内容：调用方必须把它记为新的 base，
     * 否则「解决冲突后保存」会拿带标记的合并结果当 base，既不等于服务器内容，
     * 也会让下一次三方合并算错（WRI-011）。
     *
     * [preserved] 是归档回执（WRI-013）：冲突材料已落盘，界面据此给出可回指的恢复 id。
     */
    data class Conflict(
        val mergedText: String,
        val conflictCount: Int,
        val serverRevision: String,
        val serverContent: String,
        val preserved: PreservedConflict? = null,
    ) : SaveOutcome

    /** 非冲突错误（网络/权限等）。 */
    data class Error(val exception: DenovaApiException) : SaveOutcome
}

/**
 * 保存编排：先落本地草稿 → 保存；409 冲突则读服务器版本做三方合并，
 * 干净就重存、否则归档冲突材料并产出冲突选择。同一文件通过 [Mutex] 串行。
 */
class FileSaveCoordinator(
    private val remote: FileRemote,
    private val drafts: DraftStorage,
    /** 冲突材料归档端口；为空时只产出冲突选择、不落盘（单测里不需要网络与文件）。 */
    private val archive: ConflictArchive? = null,
    /**
     * 草稿 key 构造（AUTH-006）：必须与 [com.denova.novara.feature.editor.EditorSession] 用同一份身份，
     * 否则自动保存写旧 key、打开时读新 key，草稿会「写了却读不到」或跨服务器串用。
     */
    private val keyFor: (projectId: String, path: String) -> String = { projectId, path ->
        "$projectId\u0000$path"
    },
) {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun save(
        projectId: String,
        path: String,
        baseRevision: String,
        baseContent: String,
        content: String,
    ): SaveOutcome {
        val key = keyFor(projectId, path)
        return mutexes.getOrPut(key) { Mutex() }.withLock {
            // 先落草稿，保证进程被杀后未提交编辑可恢复。
            drafts.saveDraft(key, Draft(content, baseRevision, baseContent))
            try {
                val resp = remote.saveFile(projectId, path, content, baseRevision)
                drafts.clearDraft(key)
                SaveOutcome.Saved(resp.revision)
            } catch (e: CancellationException) {
                // 取消（切换文件 / ViewModel 销毁）必须继续向上传播，不能伪装成保存错误。
                throw e
            } catch (e: DenovaApiException) {
                if (e.status == 409) {
                    handleConflict(key, projectId, path, baseRevision, baseContent, content)
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
        baseRevision: String,
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
                is MergeResult.Conflicts -> SaveOutcome.Conflict(
                    mergedText = merged.text,
                    conflictCount = merged.conflictCount,
                    serverRevision = theirs.revision,
                    serverContent = serverContent,
                    // 先归档四份正文，再交给用户选「保留本地 / 加载外部」：
                    // 任何一侧的选择都不会让另一侧的版本无迹可寻（Web 同序）。
                    preserved = archiveConflict(projectId, path, baseRevision, baseContent, ours, theirs.revision, serverContent, merged.text),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DenovaApiException) {
            SaveOutcome.Error(e)
        } catch (e: Exception) {
            SaveOutcome.Error(DenovaApiException(0, null, e.message))
        }
    }

    /**
     * 归档一次重叠冲突。归档失败不阻断冲突选择（正文仍在本地草稿里），
     * 但会记日志，避免「以为留了档其实没有」。
     */
    private suspend fun archiveConflict(
        projectId: String,
        path: String,
        baseRevision: String,
        baseContent: String,
        ours: String,
        externalRevision: String,
        externalContent: String,
        mergedText: String,
    ): PreservedConflict? {
        val target = archive ?: return null
        return try {
            target.preserve(
                AutosaveConflictRecord(
                    scope = projectId,
                    id = path,
                    base = ConflictSnapshot(baseRevision, baseContent),
                    local = ConflictSnapshot(baseRevision, ours),
                    external = ConflictSnapshot(externalRevision, externalContent),
                    merged = ConflictSnapshot(externalRevision, mergedText),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DenovaLog.w("Editor", "conflict archive failed project=$projectId path=$path", e)
            null
        }
    }
}
