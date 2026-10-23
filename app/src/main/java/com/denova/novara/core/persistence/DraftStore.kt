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
data class Draft(val content: String, val baseRevision: String)

/** 本地草稿：按文件 key 保存未提交的内容与 base_revision，用于进程被杀后恢复未保存编辑。 */
class DraftStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val mapKey = stringPreferencesKey("draft_map")

    suspend fun saveDraft(fileKey: String, content: String, baseRevision: String) {
        val map = loadAll().toMutableMap()
        map[fileKey] = Draft(content, baseRevision)
        context.draftDataStore.edit { prefs ->
            prefs[mapKey] = json.encodeToString(map)
        }
    }

    suspend fun loadDraft(fileKey: String): Draft? = loadAll()[fileKey]

    suspend fun clearDraft(fileKey: String) {
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
