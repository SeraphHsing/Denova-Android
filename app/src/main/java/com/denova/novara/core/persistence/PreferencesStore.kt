package com.denova.novara.core.persistence

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 应用唯一的 Preferences DataStore 实例；同文件重复创建会抛「multiple DataStores active」。 */
internal val Context.novaraPreferences by preferencesDataStore(name = "preferences")

/** 轻量界面偏好（DataStore）：主题、语言等。服务器状态不存这里（见 AGENTS §4）。 */
class PreferencesStore(private val context: Context) : Preferences {

    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val LANGUAGE = stringPreferencesKey("language")

        /** 上次挂接的 Agent 会话：进程被杀后据此回到同一会话（服务端 active 仍是事实源）。 */
        val LAST_AGENT_SESSION = stringPreferencesKey("last_agent_session")
    }

    override val darkTheme: Flow<Boolean> = context.novaraPreferences.data
        .map { prefs -> prefs[Keys.DARK_THEME] ?: false }

    override suspend fun setDarkTheme(enabled: Boolean) {
        context.novaraPreferences.edit { prefs -> prefs[Keys.DARK_THEME] = enabled }
    }

    /** 语言标签（BCP-47），如 `en` / `zh`；未设置默认跟随系统。 */
    override val language: Flow<String> = context.novaraPreferences.data
        .map { prefs -> prefs[Keys.LANGUAGE] ?: "" }

    override suspend fun setLanguage(tag: String) {
        context.novaraPreferences.edit { prefs -> prefs[Keys.LANGUAGE] = tag }
    }

    /** 上次挂接的 Agent 会话 id（仅记录「回到哪个会话」这个指针，任务状态仍以服务端 active 为准）。 */
    suspend fun lastAgentSession(): String =
        context.novaraPreferences.data.map { it[Keys.LAST_AGENT_SESSION] ?: "" }.first()

    suspend fun setLastAgentSession(sessionId: String) {
        context.novaraPreferences.edit { prefs -> prefs[Keys.LAST_AGENT_SESSION] = sessionId }
    }
}
