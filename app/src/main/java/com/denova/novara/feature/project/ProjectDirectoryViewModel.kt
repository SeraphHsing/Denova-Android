package com.denova.novara.feature.project

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.project.ProjectFileNode
import com.denova.novara.core.project.ProjectFileRow
import com.denova.novara.core.project.ProjectFileTree
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProjectDirectoryUiState(
    val projectId: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val nodes: List<ProjectFileNode> = emptyList(),
    val rows: List<ProjectFileRow> = emptyList(),
    val expanded: Set<String> = emptySet(),
    /** 当前在编辑器打开的文件路径（用于树内高亮）。 */
    val selectedPath: String? = null,
)

/**
 * 右抽屉「项目目录」（WRI-002）：`/files/resolve` 递归铺开目录树，支持展开/折叠与刷新。
 * 点击文件由界面回调交给编辑器打开（本类不持有编辑器状态）。
 */
class ProjectDirectoryViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null

    private val _state = MutableStateFlow(ProjectDirectoryUiState())
    val state: StateFlow<ProjectDirectoryUiState> = _state.asStateFlow()

    fun bindClient(client: DenovaClient) {
        this.client = client
    }

    fun load(projectId: String, force: Boolean = false) {
        val c = client ?: return
        if (projectId.isBlank()) return
        if (!force && projectId == _state.value.projectId && _state.value.nodes.isNotEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(projectId = projectId, loading = true, error = null)
            try {
                val resp = c.resolveProjectFiles(projectId, listOf(""), recursive = true)
                val root = resp.results.firstOrNull { it.path == "" }
                if (root == null || !root.ok) {
                    DenovaLog.w("ProjectDir", "resolve root failed code=${root?.code} error=${root?.error}")
                    _state.value = _state.value.copy(loading = false, error = root?.error ?: "无法加载项目目录")
                    return@launch
                }
                val directories = root.directories.associateBy { it.path }
                val nodes = ProjectFileTree.build(directories)
                val expanded = ProjectFileTree.directoryPaths(nodes)
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    nodes = nodes,
                    expanded = expanded,
                    rows = ProjectFileTree.visibleRows(nodes, expanded),
                )
                DenovaLog.i("ProjectDir", "loaded dirs=${directories.size} rows=${_state.value.rows.size}")
            } catch (e: Exception) {
                DenovaLog.w("ProjectDir", "load failed", e)
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    fun toggle(path: String) {
        val s = _state.value
        val expanded = if (path in s.expanded) s.expanded - path else s.expanded + path
        _state.value = s.copy(expanded = expanded, rows = ProjectFileTree.visibleRows(s.nodes, expanded))
    }

    fun refresh() {
        val s = _state.value
        if (s.projectId.isNotBlank()) load(s.projectId, force = true)
    }

    /** 记录编辑器当前打开的文件（树内高亮）。 */
    fun setSelected(path: String) {
        _state.value = _state.value.copy(selectedPath = path)
    }
}
