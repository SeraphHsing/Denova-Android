package com.denova.novara.core.persistence

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.draftDataStore by preferencesDataStore(name = "drafts")

@Serializable
data class Draft(
    val content: String,
    val baseRevision: String,
    val baseContent: String,
)

/** 草稿存取最小抽象，便于单测注入 fake。 */
interface DraftStorage {
    suspend fun saveDraft(fileKey: String, draft: Draft)
    suspend fun clearDraft(fileKey: String)
}

/** 本地草稿：按文件 key 保存未提交内容、base_revision 与 base 内容，用于进程恢复与三方合并。 */
class DraftStore(private val context: Context) : DraftStorage {

    private val json = Json { ignoreUnknownKeys = true }
    private val mapKey = stringPreferencesKey("draft_map")

    override suspend fun saveDraft(fileKey: String, draft: Draft) {
        val map = loadAll().toMutableMap()
        map[fileKey] = draft
        context.draftDataStore.edit { prefs ->
            prefs[mapKey] = json.encodeToString(map)
        }
    }

    suspend fun loadDraft(fileKey: String): Draft? = loadAll()[fileKey]

    override suspend fun clearDraft(fileKey: String) {
        val map = loadAll().toMutableMap()
        map.remove(fileKey)
        context.draftDataStore.edit { prefs ->
            prefs[mapKey] = json.encodeToString(map)
        }
    }

    private suspend fun loadAll(): Map<String, Draft> {
        val raw = context.draftDataStore.data.first()[mapKey] ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Draft>>(raw) }.getOrDefault(emptyMap())
    }
}
