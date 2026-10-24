package com.denova.novara.core.persistence

import kotlinx.coroutines.flow.Flow

/** 轻量界面偏好抽象：主题、语言。便于 SettingsViewModel 单元测试注入 fake。 */
interface Preferences {
    val darkTheme: Flow<Boolean>
    suspend fun setDarkTheme(enabled: Boolean)
    val language: Flow<String>
    suspend fun setLanguage(tag: String)
}
