package com.denova.novara.feature.trajectory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.AgentRunIssue
import com.denova.novara.core.network.AgentRunSummary
import com.denova.novara.core.network.AgentRunTrace
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.share.FileShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 轨迹页的三种视图（基线 `trajectory.view.records|timeline|raw`）。 */
enum class TrajectoryView { RECORDS, TIMELINE, RAW }

data class TrajectoryUiState(
    val loadingRuns: Boolean = false,
    val loadingTrace: Boolean = false,
    val error: UiText? = null,
    /** 服务端开发者模式关闭（`api.trajectory.disabled`）：入口本不该出现，直接进来时显示禁用说明。 */
    val disabled: Boolean = false,
    val runs: List<AgentRunSummary> = emptyList(),
    val issues: List<AgentRunIssue> = emptyList(),
    val agentFilter: String = "",
    val selectedKey: String = "",
    val trace: AgentRunTrace? = null,
    val analysis: TrajectoryAnalysis = TrajectoryAnalysis(),
    val content: TrajectoryContentAnalysis = TrajectoryContentAnalysis(),
    val organization: RunOrganization = RunOrganization.SESSION,
    val expandedSessions: Set<String> = emptySet(),
    val view: TrajectoryView = TrajectoryView.RECORDS,
    val timelineMode: TrajectoryTimelineMode = TrajectoryTimelineMode.ACTUAL,
    val exporting: Boolean = false,
    val exportMessage: UiText? = null,
    val exportError: UiText? = null,
) {
    val filterOptions: List<String> get() = agentOptions(runs)
    val filteredRuns: List<AgentRunSummary> get() = visibleRuns(runs, agentFilter)
    val projectGroups: List<ProjectRunGroup> get() = groupRunsByProject(filteredRuns)
    val selectedRun: AgentRunSummary?
        get() = runs.firstOrNull { it.selectionKey == selectedKey }
    val hasTrace: Boolean get() = trace != null
}

/**
 * 统计页状态机（TRJ-001…003）。
 *
 * 关键约束：
 * - 目录与详情分开加载：目录失败不清空已打开的详情，详情失败只影响内容区；
 * - 切换选中 Run 时取消上一条请求，并在写回前复核 `selectedKey`，避免迟到回包把详情换成别的 Run；
 * - 导出写 `cacheDir` 后经 `FileProvider` 分享，字节不留在内存状态里。
 */
class TrajectoryViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null
    private var traceJob: Job? = null
    private var loadedOnce = false

    private val _state = MutableStateFlow(TrajectoryUiState())
    val state: StateFlow<TrajectoryUiState> = _state.asStateFlow()

    fun bindClient(client: DenovaClient) {
        val changed = this.client !== client
        this.client = client
        if (changed) {
            loadedOnce = false
            _state.value = TrajectoryUiState()
        }
        if (!loadedOnce) load()
    }

    /** 加载全局目录（默认 100 条，与基线一致）。`force=false` 时已有目录不重复请求。 */
    fun load(force: Boolean = false) {
        val c = client ?: return
        val current = _state.value
        if (!force && (current.loadingRuns || current.runs.isNotEmpty())) return
        loadedOnce = true
        _state.value = current.copy(loadingRuns = true, error = null, disabled = false)
        viewModelScope.launch {
            try {
                val catalog = c.globalAgentRuns(limit = 100)
                val validKeys = catalog.runs.mapTo(HashSet()) { it.selectionKey }
                val keepKey = _state.value.selectedKey.takeIf { it in validKeys }
                val nextKey = keepKey ?: catalog.runs.firstOrNull()?.selectionKey.orEmpty()
                _state.value = _state.value.copy(
                    loadingRuns = false,
                    runs = catalog.runs,
                    issues = catalog.issues,
                    selectedKey = nextKey,
                    error = null,
                )
                DenovaLog.i(
                    "Trajectory",
                    "catalog loaded runs=${catalog.runs.size} issues=${catalog.issues.size} selected=${nextKey.take(24)}",
                )
                if (nextKey.isNotEmpty() && (keepKey == null || _state.value.trace == null)) {
                    loadTrace(nextKey)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DenovaApiException) {
                handleCatalogError(e)
            } catch (e: Exception) {
                DenovaLog.w("Trajectory", "catalog failed", e)
                _state.value = _state.value.copy(
                    loadingRuns = false,
                    error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.trajectory_load_failed),
                )
            }
        }
    }

    fun setAgentFilter(kind: String) {
        val current = _state.value
        val nextFilter = if (kind == current.agentFilter) "" else kind
        val visible = visibleRuns(current.runs, nextFilter)
        val stillVisible = visible.any { it.selectionKey == current.selectedKey }
        val nextKey = if (stillVisible) current.selectedKey else visible.firstOrNull()?.selectionKey.orEmpty()
        _state.value = current.copy(agentFilter = nextFilter, selectedKey = nextKey)
        if (nextKey.isNotEmpty() && nextKey != current.selectedKey) loadTrace(nextKey)
        if (nextKey.isEmpty()) {
            traceJob?.cancel()
            _state.value = _state.value.copy(trace = null, analysis = TrajectoryAnalysis(), content = TrajectoryContentAnalysis())
        }
    }

    fun setOrganization(organization: RunOrganization) {
        _state.value = _state.value.copy(organization = organization)
    }

    fun toggleSession(key: String) {
        val current = _state.value
        val next = current.expandedSessions.toMutableSet()
        if (!next.remove(key)) next.add(key)
        _state.value = current.copy(expandedSessions = next)
    }

    fun setView(view: TrajectoryView) {
        _state.value = _state.value.copy(view = view)
    }

    fun setTimelineMode(mode: TrajectoryTimelineMode) {
        _state.value = _state.value.copy(timelineMode = mode)
    }

    fun selectRun(key: String) {
        val current = _state.value
        if (key.isBlank()) return
        if (key == current.selectedKey && current.trace != null) return
        val run = current.runs.firstOrNull { it.selectionKey == key } ?: return
        val sessionKey = sessionGroupKey(run)
        _state.value = current.copy(
            selectedKey = key,
            expandedSessions = if (sessionKey in current.expandedSessions) {
                current.expandedSessions
            } else {
                current.expandedSessions + sessionKey
            },
        )
        loadTrace(key)
    }

    fun refresh() = load(force = true)

    fun clearMessages() {
        _state.value = _state.value.copy(exportMessage = null, exportError = null)
    }

    /** 导出当前选中 Run 的完整 JSONL 并调起系统分享。 */
    fun exportSelected() {
        val c = client ?: return
        val current = _state.value
        val run = current.selectedRun ?: return
        if (current.exporting || run.projectId.isBlank() || run.id.isBlank()) return
        _state.value = current.copy(exporting = true, exportMessage = null, exportError = null)
        viewModelScope.launch {
            try {
                val export = c.exportAgentRunTrace(run.projectId, run.id)
                val file = FileShare.writeToCache(
                    context = getApplication(),
                    folder = "trajectory-exports",
                    filename = export.filename,
                    bytes = export.bytes,
                )
                FileShare.share(getApplication(), file, "application/x-ndjson")
                _state.value = _state.value.copy(
                    exporting = false,
                    exportMessage = UiText.of(R.string.trajectory_export_success, export.filename),
                )
                DenovaLog.i(
                    "Trajectory",
                    "exported run=${run.id.take(24)} bytes=${export.bytes.size} file=${export.filename}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Trajectory", "export failed run=${run.id.take(24)}", e)
                _state.value = _state.value.copy(
                    exporting = false,
                    exportError = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.trajectory_export_failed),
                )
            }
        }
    }

    private fun loadTrace(key: String) {
        val c = client ?: return
        val run = _state.value.runs.firstOrNull { it.selectionKey == key } ?: return
        traceJob?.cancel()
        _state.value = _state.value.copy(
            loadingTrace = true,
            error = null,
            trace = null,
            analysis = TrajectoryAnalysis(),
            content = TrajectoryContentAnalysis(),
        )
        traceJob = viewModelScope.launch {
            try {
                val trace = c.agentRunTrace(run.projectId, run.id)
                if (_state.value.selectedKey != key) return@launch
                val analysis = analyzeTrajectory(trace)
                val content = analyzeTrajectoryContent(trace, analysis)
                _state.value = _state.value.copy(
                    loadingTrace = false,
                    trace = trace,
                    analysis = analysis,
                    content = content,
                )
                DenovaLog.i(
                    "Trajectory",
                    "trace loaded run=${run.id.take(24)} records=${trace.records.size} spans=${analysis.spans.size} " +
                        "requests=${content.requests.size} truncated=${trace.truncated} content=${content.available}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.selectedKey != key) return@launch
                DenovaLog.w("Trajectory", "trace failed run=${run.id.take(24)}", e)
                _state.value = _state.value.copy(
                    loadingTrace = false,
                    error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.trajectory_trace_failed),
                )
            }
        }
    }

    private fun handleCatalogError(e: DenovaApiException) {
        val disabled = e.code == "api.trajectory.disabled" || e.status == 404
        _state.value = _state.value.copy(
            loadingRuns = false,
            disabled = disabled,
            runs = emptyList(),
            selectedKey = "",
            trace = null,
            error = if (disabled) {
                UiText.of(R.string.trajectory_disabled)
            } else {
                e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.trajectory_load_failed)
            },
        )
        DenovaLog.w("Trajectory", "catalog rejected status=${e.status} code=${e.code} disabled=$disabled")
    }
}
