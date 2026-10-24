package com.denova.novara.feature.settings

import android.app.Application
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

    init {
        viewModelScope.launch {
            store.darkTheme.collect { _darkTheme.value = it }
        }
    }

    fun toggleDarkTheme() {
        viewModelScope.launch { store.setDarkTheme(!_darkTheme.value) }
    }
}
