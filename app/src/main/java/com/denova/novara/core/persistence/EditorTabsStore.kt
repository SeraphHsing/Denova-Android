package com.denova.novara.core.persistence

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.denova.novara.core.logging.DenovaLog
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 编辑器标签栏的轻量界面状态（WRI-003）：打开过的文件顺序、激活项、最近使用的项目。
 *
 * 归属 AGENTS §4「本地只持久化连接偏好、轻量界面状态、草稿…」中的界面状态一类，
 * 服务端仍是文件内容的唯一事实源；这里只用于进程重建或切页返回后恢复标签栏。
 *
 * 作用域：`服务器 origin + 项目 ID`。切换服务器不共享标签（AGENTS §4 作用域隔离）。
 */
class EditorTabsStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun tabsKey(scope: String) = stringPreferencesKey("editor_tabs:$scope")

    private fun activeKey(scope: String) = stringPreferencesKey("editor_active_tab:$scope")

    private fun lastProjectKey(origin: String) = stringPreferencesKey("editor_last_project:$origin")

    /** 读取某项目桶的（标签顺序, 激活路径）；损坏数据按空处理，不阻塞编辑器打开。 */
    suspend fun load(scope: String): Pair<List<String>, String> = runCatching {
        val prefs = context.novaraPreferences.data.first()
        val paths = prefs[tabsKey(scope)]
            ?.let { raw -> json.decodeFromString<List<String>>(raw) }
            ?: emptyList()
        val active = prefs[activeKey(scope)].orEmpty()
        paths.filter { it.isNotBlank() } to active
    }.getOrElse { e ->
        DenovaLog.w("Editor", "load tabs failed scope=$scope", e)
        emptyList<String>() to ""
    }

    suspend fun save(scope: String, paths: List<String>, activePath: String) {
        runCatching {
            context.novaraPreferences.edit { prefs ->
                prefs[tabsKey(scope)] = json.encodeToString(paths)
                if (activePath.isBlank()) prefs.remove(activeKey(scope))
                else prefs[activeKey(scope)] = activePath
            }
        }.onFailure { e -> DenovaLog.w("Editor", "save tabs failed scope=$scope", e) }
    }

    /** 最近一次打开文件所属项目：进程重建后据此定位标签桶（恢复锚点）。 */
    suspend fun lastProject(origin: String): String = runCatching {
        context.novaraPreferences.data.first()[lastProjectKey(origin)].orEmpty()
    }.getOrElse { "" }

    suspend fun saveLastProject(origin: String, projectId: String) {
        if (origin.isBlank() || projectId.isBlank()) return
        runCatching {
            context.novaraPreferences.edit { prefs -> prefs[lastProjectKey(origin)] = projectId }
        }.onFailure { e -> DenovaLog.w("Editor", "save last project failed", e) }
    }
}
