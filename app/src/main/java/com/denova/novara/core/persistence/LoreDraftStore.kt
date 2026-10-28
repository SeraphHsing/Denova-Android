package com.denova.novara.core.persistence

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.lore.LoreDraft
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * 资料库编辑草稿的本地持久化（LOR-004）。
 *
 * 归属 AGENTS §4「本地只持久化…草稿」一类：服务端仍是条目正文的唯一事实源，
 * 这里只保存「正在编辑但还没保存」的表单，用于跨进程（进程被杀 / 升级安装）恢复。
 *
 * 作用域：`服务器 origin + 项目 ID`。切换服务器或作品时不共享草稿（AGENTS §4 作用域隔离）。
 * 保存成功后由调用方清除，避免下次进入页面又回到旧内容。
 */
class LoreDraftStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun key(scope: String) = stringPreferencesKey("lore_draft:$scope")

    /** 读取该校作用域的草稿；损坏数据按「没有草稿」处理，不阻塞资料库打开。 */
    suspend fun load(scope: String): LoreDraft? = runCatching {
        val raw = context.novaraPreferences.data.first()[key(scope)] ?: return@runCatching null
        json.decodeFromString(LoreDraft.serializer(), raw)
    }.getOrElse { e ->
        DenovaLog.w("Lore", "load draft failed scope=$scope", e)
        null
    }

    suspend fun save(scope: String, draft: LoreDraft) {
        runCatching {
            context.novaraPreferences.edit { prefs ->
                prefs[key(scope)] = json.encodeToString(LoreDraft.serializer(), draft)
            }
        }.onFailure { e -> DenovaLog.w("Lore", "save draft failed scope=$scope", e) }
    }

    suspend fun clear(scope: String) {
        runCatching {
            context.novaraPreferences.edit { prefs -> prefs.remove(key(scope)) }
        }.onFailure { e -> DenovaLog.w("Lore", "clear draft failed scope=$scope", e) }
    }
}
