package com.denova.novara.feature.versions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.VersionEntry
import com.denova.novara.core.network.VersionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VersionsUiState(
    val projectId: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val status: VersionStatus? = null,
    val versions: List<VersionEntry> = emptyList(),
)

/**
 * 版本入口（WRI-016 的「版本」部分）：只读 `GET /versions/status` 与 `GET /versions`。
 *
 * 创建版本、diff 预览、恢复计划与恢复执行属于 VER-001 / VER-002，本类故意不提供写操作，
 * 避免在只做入口的切片里出现「看起来能用但语义未核对」的按钮。
 */
class VersionsViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null

    private val _state = MutableStateFlow(VersionsUiState())
    val state: StateFlow<VersionsUiState> = _state.asStateFlow()

    fun bindClient(client: DenovaClient) {
        this.client = client
    }

    fun load(projectId: String, force: Boolean = false) {
        val c = client ?: return
        if (projectId.isBlank()) return
        val current = _state.value
        if (!force && projectId == current.projectId && current.status != null) return
        viewModelScope.launch {
            _state.value = current.copy(projectId = projectId, loading = true, error = null)
            try {
                val status = c.versionStatus(projectId)
                val versions = c.versionHistory(projectId, limit = 20)
                _state.value = _state.value.copy(
                    loading = false,
                    status = status,
                    versions = versions,
                    error = null,
                )
                DenovaLog.i(
                    "Versions",
                    "loaded project=$projectId versions=${versions.size} clean=${status.clean} has=${status.hasVersions}",
                )
            } catch (e: Exception) {
                DenovaLog.w("Versions", "load failed project=$projectId", e)
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载版本失败")
            }
        }
    }
}
