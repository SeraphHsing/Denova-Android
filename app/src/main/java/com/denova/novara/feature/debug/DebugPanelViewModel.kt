package com.denova.novara.feature.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.logging.LogEntry
import com.denova.novara.core.logging.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DebugUiState(
    val entries: List<LogEntry> = emptyList(),
    val filter: String = "",
    val minLevel: LogLevel = LogLevel.DEBUG,
)

/** 运行时 Debug 面板：读取内存日志、按级别/tag 过滤、清空。 */
class DebugPanelViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(DebugUiState())
    val state: StateFlow<DebugUiState> = _state.asStateFlow()

    fun refresh() {
        val s = _state.value
        val filtered = DenovaLog.memory.snapshot()
            .filter { it.level.priority >= s.minLevel.priority }
            .filter {
                s.filter.isBlank() ||
                    it.tag.contains(s.filter, ignoreCase = true) ||
                    it.message.contains(s.filter, ignoreCase = true)
            }
        _state.value = s.copy(entries = filtered)
    }

    fun setFilter(text: String) {
        _state.value = _state.value.copy(filter = text)
        refresh()
    }

    fun setMinLevel(level: LogLevel) {
        _state.value = _state.value.copy(minLevel = level)
        refresh()
    }

    fun clear() {
        DenovaLog.memory.clear()
        refresh()
    }
}
