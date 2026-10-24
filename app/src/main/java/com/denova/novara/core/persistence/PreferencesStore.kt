package com.denova.novara.core.persistence

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.preferencesDataStore by preferencesDataStore(name = "preferences")

/** 轻量界面偏好（DataStore）：主题、语言等。服务器状态不存这里（见 AGENTS §4）。 */
class PreferencesStore(private val context: Context) {

    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val LANGUAGE = stringPreferencesKey("language")
    }

    val darkTheme: Flow<Boolean> = context.preferencesDataStore.data
        .map { prefs -> prefs[Keys.DARK_THEME] ?: false }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.preferencesDataStore.edit { prefs -> prefs[Keys.DARK_THEME] = enabled }
    }

    /** 语言标签（BCP-47），如 `en` / `zh`；未设置默认跟随系统。 */
    val language: Flow<String> = context.preferencesDataStore.data
        .map { prefs -> prefs[Keys.LANGUAGE] ?: "" }

    suspend fun setLanguage(tag: String) {
        context.preferencesDataStore.edit { prefs -> prefs[Keys.LANGUAGE] = tag }
    }
}
