package com.denova.novara.feature.settings

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.persistence.PreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 轻量界面偏好（主题/语言）的 ViewModel。 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = PreferencesStore(application)

    private val _darkTheme = MutableStateFlow(false)
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    private val _language = MutableStateFlow("")
    val language: StateFlow<String> = _language.asStateFlow()

    init {
        viewModelScope.launch {
            store.darkTheme.collect { _darkTheme.value = it }
        }
        viewModelScope.launch {
            store.language.collect { _language.value = it }
        }
    }

    fun toggleDarkTheme() {
        viewModelScope.launch { store.setDarkTheme(!_darkTheme.value) }
    }

    /** 切换应用语言（`en` / `zh`），写偏好并立即应用到 AppCompat locale。 */
    fun setLanguage(tag: String) {
        viewModelScope.launch {
            store.setLanguage(tag)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }
}
