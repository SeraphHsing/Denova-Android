package com.denova.novara.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.persistence.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 轻量界面偏好（主题/语言）的 ViewModel。依赖注入 [Preferences] 与 [LocaleApplier]。 */
class SettingsViewModel(
    private val preferences: Preferences,
    private val localeApplier: LocaleApplier,
) : ViewModel() {

    private val _darkTheme = MutableStateFlow(false)
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    private val _language = MutableStateFlow("")
    val language: StateFlow<String> = _language.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.darkTheme.collect { _darkTheme.value = it }
        }
        viewModelScope.launch {
            preferences.language.collect { _language.value = it }
        }
    }

    fun toggleDarkTheme() {
        viewModelScope.launch { preferences.setDarkTheme(!_darkTheme.value) }
    }

    /** 切换应用语言（`en` / `zh`），写偏好并立即应用。 */
    fun setLanguage(tag: String) {
        viewModelScope.launch {
            preferences.setLanguage(tag)
            localeApplier.applyLocale(tag)
        }
    }
}
