package com.denova.novara.core.persistence

import com.denova.novara.core.db.DraftDao
import com.denova.novara.core.db.DraftEntity

data class Draft(
    val content: String,
    val baseRevision: String,
    val baseContent: String,
)

/** 草稿存取最小抽象，便于单测注入 fake。 */
interface DraftStorage {
    suspend fun saveDraft(fileKey: String, draft: Draft)
    suspend fun loadDraft(fileKey: String): Draft?
    suspend fun clearDraft(fileKey: String)
}

/** 本地草稿：按文件 key 保存未提交内容、base_revision 与 base 内容，用于进程恢复与三方合并（Room 实现）。 */
class DraftStore(private val dao: DraftDao) : DraftStorage {

    override suspend fun saveDraft(fileKey: String, draft: Draft) {
        dao.upsert(DraftEntity(fileKey, draft.content, draft.baseRevision, draft.baseContent))
    }

    override suspend fun loadDraft(fileKey: String): Draft? =
        dao.get(fileKey)?.let { Draft(it.content, it.baseRevision, it.baseContent) }

    override suspend fun clearDraft(fileKey: String) {
        dao.delete(fileKey)
    }
}
