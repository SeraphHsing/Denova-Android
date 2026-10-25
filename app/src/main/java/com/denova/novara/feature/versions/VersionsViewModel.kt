package com.denova.novara.feature.versions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.VersionDiff
import com.denova.novara.core.network.VersionEntry
import com.denova.novara.core.network.VersionRestorePlan
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
    /** 创建版本的说明输入（VER-001）。 */
    val createText: String = "",
    val creating: Boolean = false,
    /** 一次写操作的结果提示：创建 / 恢复成功后的服务端 message。 */
    val actionMessage: String? = null,
    val actionError: String? = null,
    /** 正在查看 diff 的版本；空表示没有打开。 */
    val selectedVersionId: String = "",
    val diff: VersionDiff? = null,
    val diffLoading: Boolean = false,
    val diffError: String? = null,
    /** 待恢复路径（默认全选）；空集表示用户一个都没选。 */
    val selectedPaths: Set<String> = emptySet(),
    val plan: VersionRestorePlan? = null,
    val planLoading: Boolean = false,
    val restoring: Boolean = false,
) {
    /** 全部路径都选中时按「整本范围」下发（服务端约定：空 paths = workspace）。 */
    val allPathsSelected: Boolean
        get() {
            val total = diff?.changes?.size ?: 0
            return total > 0 && selectedPaths.size == total
        }
}

/**
 * 版本（VER-001 创建 / VER-002 diff 与恢复）。
 *
 * 恢复是破坏性写操作，因此必须两步：先 `restore-plan` 拿服务端的范围与风险提示，用户确认后才 `restore`；
 * 下发的 `paths` 只来自用户在 diff 列表里勾选的路径，不客户端自行扩大范围。
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

    fun setCreateText(text: String) {
        _state.value = _state.value.copy(createText = text)
    }

    fun clearActionResult() {
        _state.value = _state.value.copy(actionMessage = null, actionError = null)
    }

    /** 创建版本：服务端在没有变更时返回「当前工作区没有可保存的变更」，原样作为提示展示。 */
    fun createVersion() {
        val c = client ?: return
        val current = _state.value
        val projectId = current.projectId
        if (projectId.isBlank() || current.creating) return
        // 上一次的提示（可能是失败原因）不能带到这次结果里，真机上出现过「已恢复所选文件」与旧错误同屏。
        _state.value = current.copy(creating = true, actionError = null, actionMessage = null)
        viewModelScope.launch {
            try {
                val result = c.versionCreate(projectId, current.createText)
                _state.value = _state.value.copy(
                    creating = false,
                    createText = "",
                    actionMessage = result.message.ifBlank { "已创建版本" },
                )
                DenovaLog.i(
                    "Versions",
                    "created project=$projectId version=${result.version?.id.orEmpty().take(12)} message=${result.message}",
                )
                load(projectId, force = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Versions", "create failed project=$projectId", e)
                _state.value = _state.value.copy(
                    creating = false,
                    actionError = e.message ?: "创建版本失败",
                )
            }
        }
    }

    /** 打开某个版本：拉 `comparison=workspace` 的 diff（相对当前工作区），默认全选待恢复路径。 */
    fun openVersion(versionId: String) {
        val c = client ?: return
        val projectId = _state.value.projectId
        if (projectId.isBlank() || versionId.isBlank()) return
        _state.value = _state.value.copy(
            selectedVersionId = versionId,
            diff = null,
            diffLoading = true,
            diffError = null,
            plan = null,
            selectedPaths = emptySet(),
            actionMessage = null,
            actionError = null,
        )
        viewModelScope.launch {
            try {
                val diff = c.versionDiff(projectId, versionId, comparison = "workspace")
                _state.value = _state.value.copy(
                    diff = diff,
                    diffLoading = false,
                    selectedPaths = diff.changes.mapTo(LinkedHashSet()) { it.path },
                )
                DenovaLog.i(
                    "Versions",
                    "diff loaded version=$versionId changes=${diff.changes.size} files=${diff.files.size} complete=${diff.filesComplete}",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Versions", "diff failed version=$versionId", e)
                _state.value = _state.value.copy(
                    diffLoading = false,
                    diffError = e.message ?: "加载版本差异失败",
                )
            }
        }
    }

    fun closeVersion() {
        _state.value = _state.value.copy(
            selectedVersionId = "",
            diff = null,
            diffLoading = false,
            diffError = null,
            selectedPaths = emptySet(),
            plan = null,
            planLoading = false,
            restoring = false,
        )
    }

    fun togglePath(path: String) {
        val current = _state.value
        if (path.isBlank()) return
        val next = current.selectedPaths.toMutableSet()
        if (!next.remove(path)) next.add(path)
        // 勾选变化后旧计划作废：恢复范围必须与用户看到并确认的计划一致。
        _state.value = current.copy(selectedPaths = next, plan = null)
    }

    fun toggleAllPaths() {
        val current = _state.value
        val all = current.diff?.changes.orEmpty().map { it.path }
        val next = if (current.allPathsSelected) emptySet() else all.toSet()
        _state.value = current.copy(selectedPaths = next, plan = null)
    }

    /** 恢复计划（VER-002 第一步）：范围按「全选 = 整本 workspace、否则只恢复勾选路径」下发。 */
    fun loadPlan() {
        val c = client ?: return
        val current = _state.value
        val projectId = current.projectId
        val versionId = current.selectedVersionId
        if (projectId.isBlank() || versionId.isBlank() || current.selectedPaths.isEmpty()) return
        val paths = restorePaths(current)
        _state.value = current.copy(planLoading = true, diffError = null, actionMessage = null, actionError = null)
        viewModelScope.launch {
            try {
                val plan = c.versionRestorePlan(projectId, versionId, paths)
                _state.value = _state.value.copy(plan = plan, planLoading = false)
                DenovaLog.i(
                    "Versions",
                    "restore plan version=$versionId scope=${plan.scope} paths=${plan.paths.size} " +
                        "backup=${plan.willCreateBackup} dirty=${plan.currentDirty} warnings=${plan.warnings.size}",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Versions", "restore plan failed version=$versionId", e)
                _state.value = _state.value.copy(
                    planLoading = false,
                    diffError = e.message ?: "预览恢复计划失败",
                )
            }
        }
    }

    /** 执行恢复（VER-002 第二步）：只有确认过计划后才允许调用。 */
    fun confirmRestore() {
        val c = client ?: return
        val current = _state.value
        val projectId = current.projectId
        val versionId = current.selectedVersionId
        if (projectId.isBlank() || versionId.isBlank() || current.restoring) return
        if (current.plan == null) return
        val paths = restorePaths(current)
        _state.value = current.copy(restoring = true, diffError = null, actionMessage = null, actionError = null)
        viewModelScope.launch {
            try {
                val result = c.versionRestore(projectId, versionId, paths)
                DenovaLog.i(
                    "Versions",
                    "restored version=$versionId scope=${result.scope} paths=${result.restoredPaths.size} " +
                        "backup=${result.backupVersion?.id.orEmpty().take(12)} message=${result.message}",
                )
                _state.value = _state.value.copy(
                    restoring = false,
                    actionMessage = result.message.ifBlank { "已恢复到该版本" },
                )
                closeVersion()
                load(projectId, force = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Versions", "restore failed version=$versionId", e)
                _state.value = _state.value.copy(
                    restoring = false,
                    diffError = e.message ?: "恢复失败",
                )
            }
        }
    }

    private fun restorePaths(state: VersionsUiState): List<String> =
        if (state.allPathsSelected) emptyList() else state.selectedPaths.sorted()
}
