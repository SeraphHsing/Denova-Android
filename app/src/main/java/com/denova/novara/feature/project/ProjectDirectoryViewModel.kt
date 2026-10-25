package com.denova.novara.feature.project

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.ProjectDirectoryPage
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
    /** 还有下一页的目录（界面在这些目录的子项末尾显示「加载更多」）。 */
    val pendingMore: Set<String> = emptySet(),
    /** 正在续页的目录（防止重复点击并显示加载态）。 */
    val loadingMore: Set<String> = emptySet(),
    /**
     * 调试用条目预算。真实项目通常远小于服务端默认上限（100000 条），
     * 因此 `children_state=partial` 只能用显式小预算构造；null 表示与 Web 一致不限制。
     */
    val entryBudget: Int? = null,
    /** 当前在编辑器打开的文件路径（用于树内高亮）。 */
    val selectedPath: String? = null,
)

/**
 * 右抽屉「项目目录」（WRI-002）：`/files/resolve` 递归铺开目录树，支持展开/折叠与刷新；
 * 目录被服务端截断（`children_state=partial`）时用 `continuation` 按需续页，而不是一次性拉全量。
 * 点击文件由界面回调交给编辑器打开（本类不持有编辑器状态）。
 */
class ProjectDirectoryViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null

    /** 目录缓存（path → 页），续页与重建可见行都基于它。 */
    private var directories: Map<String, ProjectDirectoryPage> = emptyMap()

    /** 请求世代：切项目 / 刷新后，迟到的续页结果不得写进新状态。 */
    private var epoch = 0L

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
            val currentEpoch = ++epoch
            val budget = _state.value.entryBudget
            _state.value = _state.value.copy(projectId = projectId, loading = true, error = null, loadingMore = emptySet())
            try {
                val resp = c.resolveProjectFiles(projectId, listOf(""), recursive = true, entryBudget = budget)
                if (currentEpoch != epoch) return@launch
                val root = resp.results.firstOrNull { it.path == "" }
                if (root == null || !root.ok) {
                    DenovaLog.w("ProjectDir", "resolve root failed code=${root?.code} error=${root?.error}")
                    _state.value = _state.value.copy(loading = false, error = root?.error ?: "无法加载项目目录")
                    return@launch
                }
                directories = root.directories.associateBy { it.path }
                val nodes = ProjectFileTree.build(directories)
                // 只默认展开「已经有页」的目录：被预算截断、还没解析过的分支保持折叠，
                // 否则用户看到 ▾ 却点开为空，第一次点击只会把它折叠掉（对齐 Web 的按需加载）。
                val expanded = ProjectFileTree.directoryPaths(nodes).filter { directories.containsKey(it) }.toSet()
                val pending = ProjectFileTree.pendingMore(directories)
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    nodes = nodes,
                    expanded = expanded,
                    pendingMore = pending,
                    rows = ProjectFileTree.visibleRows(nodes, expanded, pending),
                )
                DenovaLog.i(
                    "ProjectDir",
                    "loaded dirs=${directories.size} rows=${_state.value.rows.size} partial=${pending.size} budget=${budget ?: -1}",
                )
            } catch (e: Exception) {
                if (currentEpoch != epoch) return@launch
                DenovaLog.w("ProjectDir", "load failed", e)
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    /**
     * 目录续页（「加载更多」）：用该目录当前 `continuation` 取下一页并追加。
     *
     * 服务端返回 `cursor_stale` 说明目录在两次请求之间被改过，游标失效：整树重载而不是猜测合并。
     */
    fun loadMore(path: String) {
        val c = client ?: return
        val s = _state.value
        val cursor = directories[path]?.continuation
        if (cursor.isNullOrBlank() || s.projectId.isBlank() || path in s.loadingMore) return
        viewModelScope.launch {
            val currentEpoch = epoch
            val projectId = s.projectId
            _state.value = _state.value.copy(loadingMore = _state.value.loadingMore + path)
            try {
                val resp = c.resolveProjectDirectoryPage(projectId, path, cursor, _state.value.entryBudget)
                if (currentEpoch != epoch) return@launch
                val result = resp.results.firstOrNull { it.path == path }
                if (result == null || !result.ok) {
                    if (result?.code == "cursor_stale") {
                        DenovaLog.w("ProjectDir", "cursor stale path=$path; reloading tree")
                        _state.value = _state.value.copy(loadingMore = _state.value.loadingMore - path)
                        load(projectId, force = true)
                        return@launch
                    }
                    DenovaLog.w("ProjectDir", "load more failed path=$path code=${result?.code}")
                    _state.value = _state.value.copy(
                        loadingMore = _state.value.loadingMore - path,
                        error = result?.error ?: "加载更多失败",
                    )
                    return@launch
                }
                val previousSize = directories[path]?.entries?.size ?: 0
                applyPages(result.directories, appendPath = path, clearLoading = path)
                DenovaLog.i(
                    "ProjectDir",
                    "loaded more path=$path added=${(directories[path]?.entries?.size ?: 0) - previousSize} " +
                        "partial=${directories[path]?.hasMore == true}",
                )
            } catch (e: Exception) {
                if (currentEpoch != epoch) return@launch
                DenovaLog.w("ProjectDir", "load more failed path=$path", e)
                _state.value = _state.value.copy(
                    loadingMore = _state.value.loadingMore - path,
                    error = e.message ?: "加载更多失败",
                )
            }
        }
    }

    /**
     * 展开目录时按需解析该分支（对齐 Web `loadDirectory`）。
     *
     * 递归铺开受条目预算限制：预算在根页就用完的项目，子目录根本没有对应页，
     * 展开后必须单独 resolve 一次，否则用户只能看到空目录（且没有游标可续）。
     */
    private fun loadDirectory(path: String) {
        val c = client ?: return
        val s = _state.value
        if (s.projectId.isBlank() || path in s.loadingMore || directories.containsKey(path)) return
        viewModelScope.launch {
            val currentEpoch = epoch
            val projectId = s.projectId
            _state.value = _state.value.copy(loadingMore = _state.value.loadingMore + path)
            try {
                val resp = c.resolveProjectFiles(projectId, listOf(path), recursive = true, entryBudget = _state.value.entryBudget)
                if (currentEpoch != epoch) return@launch
                val result = resp.results.firstOrNull { it.path == path }
                if (result == null || !result.ok) {
                    DenovaLog.w("ProjectDir", "resolve dir failed path=$path code=${result?.code}")
                    _state.value = _state.value.copy(loadingMore = _state.value.loadingMore - path)
                    return@launch
                }
                applyPages(result.directories, clearLoading = path)
                DenovaLog.i(
                    "ProjectDir",
                    "resolved dir path=$path dirs=${result.directories.size} " +
                        "partial=${directories[path]?.hasMore == true}",
                )
            } catch (e: Exception) {
                if (currentEpoch != epoch) return@launch
                DenovaLog.w("ProjectDir", "resolve dir failed path=$path", e)
                _state.value = _state.value.copy(loadingMore = _state.value.loadingMore - path)
            }
        }
    }

    /** 把一次 resolve 的目录页并入缓存并重建可见行。 */
    private fun applyPages(
        pages: List<ProjectDirectoryPage>,
        appendPath: String? = null,
        clearLoading: String? = null,
    ) {
        directories = ProjectFileTree.merge(directories, pages, appendPath)
        val nodes = ProjectFileTree.build(directories)
        val pending = ProjectFileTree.pendingMore(directories)
        val state = _state.value
        _state.value = state.copy(
            loadingMore = if (clearLoading != null) state.loadingMore - clearLoading else state.loadingMore,
            nodes = nodes,
            pendingMore = pending,
            rows = ProjectFileTree.visibleRows(nodes, state.expanded, pending),
        )
    }

    /**
     * 调试入口：设置条目预算并重载（`null` 取消限制）。
     * 只用于构造分页边界——服务端默认上限远大于真实项目规模，没有小预算就拿不到 `partial`。
     */
    fun setDebugEntryBudget(budget: Int?) {
        val s = _state.value
        _state.value = s.copy(entryBudget = budget)
        if (s.projectId.isNotBlank()) load(s.projectId, force = true)
    }

    fun toggle(path: String) {
        val s = _state.value
        val expanding = path !in s.expanded
        val expanded = if (expanding) s.expanded + path else s.expanded - path
        _state.value = s.copy(expanded = expanded, rows = ProjectFileTree.visibleRows(s.nodes, expanded, s.pendingMore))
        // 该目录还没被解析过（预算截断的分支）→ 展开时单独拉一次。
        if (expanding && !directories.containsKey(path)) loadDirectory(path)
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
