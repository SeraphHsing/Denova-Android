package com.denova.novara.feature.workbench

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.AgentChatProject
import com.denova.novara.core.network.AgentChatSession
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.workbench.WorkbenchGroup
import com.denova.novara.core.workbench.WorkbenchTab
import com.denova.novara.core.workbench.WorkbenchTabKind
import com.denova.novara.core.workbench.WorkbenchTabs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 一次待确认的破坏性动作（删除会话 / 移除项目），由界面弹确认框。 */
sealed interface WorkbenchConfirm {
    data class DeleteSession(val projectId: String, val sessionId: String, val title: String) : WorkbenchConfirm
    data class ArchiveProject(val projectId: String, val name: String) : WorkbenchConfirm
}

data class WorkbenchUiState(
    val loading: Boolean = false,
    val error: UiText? = null,
    /** 一次性提示（保存成功 / 服务端拒绝原因），界面展示后可清除。 */
    val notice: UiText? = null,
    val projects: List<AgentChatProject> = emptyList(),
    /** 正在运行的会话，键为 `projectId/sessionId`（来自 `/api/agent-chat/activity`）。 */
    val runningSessions: Set<String> = emptySet(),
    val selectedProjectId: String = "",
    /** 折叠的项目；新出现的非选中项目默认折叠（对齐基线自动展开规则）。 */
    val collapsedProjects: Set<String> = emptySet(),
    val tabs: List<WorkbenchTab> = emptyList(),
    val activeTabPrimary: String? = null,
    val activeTabSecondary: String? = null,
    /** 手机一次只显示一个区：主区 / 副区全屏切换（WB-003）。 */
    val activeGroup: WorkbenchGroup = WorkbenchGroup.PRIMARY,
    /** 项目 / 会话变更在途：禁用重复点按。 */
    val busy: Boolean = false,
    val confirm: WorkbenchConfirm? = null,
    /** 打开项目的宿主目录选择器在途（远程会被服务端拒绝，WB-010）。 */
    val hostDialogBusy: Boolean = false,
)

/**
 * 工作台（P4-B）：项目与会话树 + 页签 + 主副会话。
 *
 * 纪律：
 * - 所有请求都带显式 `projectId`，切换选中项目不改变在途请求的作用域；
 * - 服务端是唯一事实源，动作成功后**重新读取项目树**而不是本地拼状态；
 * - 「移除项目」是归档（`DELETE /api/agent-chat/projects/:id`），不删除磁盘目录。
 */
class WorkbenchViewModel(application: Application) : AndroidViewModel(application) {

    private var client: DenovaClient? = null

    private val _state = MutableStateFlow(WorkbenchUiState())
    val state: StateFlow<WorkbenchUiState> = _state.asStateFlow()

    fun bindClient(client: DenovaClient) {
        this.client = client
    }

    fun refresh(selectProjectId: String = _state.value.selectedProjectId) {
        val c = client ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = _state.value.projects.isEmpty(), error = null)
            try {
                val projects = c.agentChatProjects()
                val running = runCatching { c.agentChatActivity() }.getOrDefault(emptyList())
                    .map { "${it.projectId}/${it.sessionId}" }
                    .toSet()
                val selected = selectProjectId.takeIf { id -> projects.any { it.id == id } }
                    ?: projects.firstOrNull { it.current }?.id
                    ?: projects.firstOrNull()?.id
                    .orEmpty()
                // 选中项目必须展开：用户点项目行就是要看它的会话。
                val collapsed = _state.value.collapsedProjects - selected
                _state.value = _state.value.copy(
                    loading = false,
                    projects = projects,
                    runningSessions = running,
                    selectedProjectId = selected,
                    collapsedProjects = collapsed,
                )
                DenovaLog.i("Workbench", "projects=${projects.size} running=${running.size} selected=${selected.take(12)}")
            } catch (e: Exception) {
                DenovaLog.w("Workbench", "refresh failed", e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.workbench_load_failed),
                )
            }
        }
    }

    fun selectProject(projectId: String) {
        _state.value = _state.value.copy(
            selectedProjectId = projectId,
            collapsedProjects = _state.value.collapsedProjects - projectId,
        )
    }

    fun toggleProject(projectId: String) {
        val collapsed = _state.value.collapsedProjects
        _state.value = _state.value.copy(
            collapsedProjects = if (projectId in collapsed) collapsed - projectId else collapsed + projectId,
            selectedProjectId = projectId,
        )
    }

    // ---- 页签与主副会话（WB-002 / WB-003）----

    /** 在项目里打开一个会话页签；已存在则只激活。 */
    fun openSession(projectId: String, sessionId: String) {
        if (projectId.isBlank() || sessionId.isBlank()) return
        val group = _state.value.activeGroup
        val tab = WorkbenchTab(
            id = agentTabId(projectId, sessionId),
            projectId = projectId,
            group = group,
            kind = WorkbenchTabKind.Agent(sessionId),
        )
        val tabs = WorkbenchTabs.open(_state.value.tabs, tab)
        _state.value = _state.value.copy(
            tabs = tabs,
            selectedProjectId = projectId,
            activeTabPrimary = if (group == WorkbenchGroup.PRIMARY) tab.id else _state.value.activeTabPrimary,
            activeTabSecondary = if (group == WorkbenchGroup.SECONDARY) tab.id else _state.value.activeTabSecondary,
        )
        DenovaLog.i("Workbench", "open session=${sessionId.take(12)} group=$group")
    }

    /** 打开终端页签（WB-007 / WB-008）：每个页签一份独立 PTY owner，关页签才释放。 */
    fun openTerminal(projectId: String, profileId: String = "shell", label: String = "") {
        if (projectId.isBlank()) return
        val group = _state.value.activeGroup
        val tab = WorkbenchTab(
            id = terminalTabId(),
            projectId = projectId,
            group = group,
            kind = WorkbenchTabKind.Terminal(profileId = profileId, label = label),
        )
        _state.value = _state.value.copy(
            tabs = WorkbenchTabs.open(_state.value.tabs, tab),
            selectedProjectId = projectId,
            activeTabPrimary = if (group == WorkbenchGroup.PRIMARY) tab.id else _state.value.activeTabPrimary,
            activeTabSecondary = if (group == WorkbenchGroup.SECONDARY) tab.id else _state.value.activeTabSecondary,
        )
        DenovaLog.i("Workbench", "open terminal tab=${tab.id.take(12)} project=${projectId.take(12)} profile=$profileId")
    }

    fun activateTab(tabId: String) {
        val tab = WorkbenchTabs.find(_state.value.tabs, tabId) ?: return
        _state.value = _state.value.copy(
            activeGroup = tab.group,
            activeTabPrimary = if (tab.group == WorkbenchGroup.PRIMARY) tabId else _state.value.activeTabPrimary,
            activeTabSecondary = if (tab.group == WorkbenchGroup.SECONDARY) tabId else _state.value.activeTabSecondary,
            selectedProjectId = tab.projectId,
        )
    }

    fun closeTab(tabId: String) {
        val tab = WorkbenchTabs.find(_state.value.tabs, tabId) ?: return
        val next = WorkbenchTabs.activeAfterClose(_state.value.tabs, tabId)
        val tabs = WorkbenchTabs.close(_state.value.tabs, tabId)
        _state.value = _state.value.copy(
            tabs = tabs,
            activeTabPrimary = if (tab.group == WorkbenchGroup.PRIMARY) next else _state.value.activeTabPrimary,
            activeTabSecondary = if (tab.group == WorkbenchGroup.SECONDARY) next else _state.value.activeTabSecondary,
        )
    }

    /** 区内重排页签（不改变激活页签）。 */
    fun moveTabWithinGroup(tabId: String, delta: Int) {
        _state.value = _state.value.copy(tabs = WorkbenchTabs.move(_state.value.tabs, tabId, delta))
    }

    /** 手机端主区 / 副区全屏切换：两区各自的激活页签与对话状态互不影响。 */
    fun switchGroup(group: WorkbenchGroup = _state.value.activeGroup.opposite()) {
        _state.value = _state.value.copy(activeGroup = group)
    }

    /** 把页签送到另一区（对齐 Web 页签菜单的「送到副区 / 主区」）。 */
    fun moveTabToOtherGroup(tabId: String) {
        val tab = WorkbenchTabs.find(_state.value.tabs, tabId) ?: return
        val target = tab.group.opposite()
        val tabs = WorkbenchTabs.moveToGroup(_state.value.tabs, tabId, target)
        _state.value = _state.value.copy(
            tabs = tabs,
            activeTabPrimary = if (target == WorkbenchGroup.PRIMARY) tabId else _state.value.activeTabPrimary,
            activeTabSecondary = if (target == WorkbenchGroup.SECONDARY) tabId else _state.value.activeTabSecondary,
            activeGroup = target,
        )
        DenovaLog.i("Workbench", "tab moved to $target")
    }

    fun activeTabId(): String? = when (_state.value.activeGroup) {
        WorkbenchGroup.PRIMARY -> _state.value.activeTabPrimary
        WorkbenchGroup.SECONDARY -> _state.value.activeTabSecondary
    }

    // ---- 会话管理（WB-002）----

    /** 新建会话：服务端创建后刷新项目树，并直接在新会话里开页签。 */
    fun createSession(projectId: String) {
        val c = client ?: return
        if (projectId.isBlank() || _state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, notice = null)
            try {
                val session = c.projectAgentCreateSession(projectId)
                refresh(selectProjectId = projectId)
                openSession(projectId, session.id)
                DenovaLog.i("Workbench", "created session=${session.id.take(12)} project=${projectId.take(12)}")
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, notice = failureText(e, R.string.workbench_session_create_failed))
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun renameSession(projectId: String, sessionId: String, title: String) {
        val c = client ?: return
        val name = title.trim()
        if (name.isEmpty() || projectId.isBlank() || sessionId.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, notice = null)
            try {
                c.projectAgentRenameSession(projectId, sessionId, name)
                refresh(selectProjectId = projectId)
                DenovaLog.i("Workbench", "renamed session=${sessionId.take(12)}")
            } catch (e: Exception) {
                _state.value = _state.value.copy(notice = failureText(e, R.string.workbench_session_rename_failed))
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun confirmDeleteSession(projectId: String, session: AgentChatSession) {
        _state.value = _state.value.copy(
            confirm = WorkbenchConfirm.DeleteSession(projectId, session.id, session.title),
        )
    }

    /** 删除会话：服务端对「运行中」与「最后一个会话」会拒绝，原因原样展示。 */
    fun deleteSession(projectId: String, sessionId: String) {
        val c = client ?: return
        _state.value = _state.value.copy(confirm = null, busy = true, notice = null)
        viewModelScope.launch {
            try {
                c.projectAgentDeleteSession(projectId, sessionId)
                closeTab(agentTabId(projectId, sessionId))
                refresh(selectProjectId = projectId)
                DenovaLog.i("Workbench", "deleted session=${sessionId.take(12)}")
            } catch (e: Exception) {
                _state.value = _state.value.copy(notice = failureText(e, R.string.workbench_session_delete_failed))
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    // ---- 项目管理（WB-001 / WB-010）----

    /**
     * 通过宿主目录选择器添加项目：`POST /api/host/dialogs/directory` 受本地限制，
     * 远程客户端会收到 403 `api.access.localHostEffect`——把原因如实展示，且**不**用手机目录替代。
     */
    fun addProjectFromHost() {
        val c = client ?: return
        if (_state.value.hostDialogBusy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(hostDialogBusy = true, notice = null)
            try {
                val selection = c.selectHostDirectory()
                if (selection.canceled || selection.path.isBlank()) return@launch
                c.agentChatAddProject(selection.path)
                refresh()
                DenovaLog.i("Workbench", "project added path=${selection.path.takeLast(24)}")
            } catch (e: Exception) {
                val remote = e is DenovaApiException && e.code == "api.access.localHostEffect"
                _state.value = _state.value.copy(
                    notice = when {
                        remote -> UiText.of(R.string.workbench_host_directory_remote)
                        else -> failureText(e, R.string.workbench_project_add_failed)
                    },
                )
                DenovaLog.w("Workbench", "host directory unavailable remote=$remote", e)
            } finally {
                _state.value = _state.value.copy(hostDialogBusy = false)
            }
        }
    }

    /** 在宿主上新建 General 项目目录（远程可用，由服务端在宿主上创建）。 */
    fun createDirectoryProject(name: String, parentDirectory: String = "") {
        val c = client ?: return
        val clean = name.trim()
        if (clean.isEmpty() || _state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, notice = null)
            try {
                val project = c.createAgentChatDirectory(clean, parentDirectory.trim())
                refresh(selectProjectId = project.id)
                DenovaLog.i("Workbench", "directory project created=${project.id.take(12)}")
            } catch (e: Exception) {
                _state.value = _state.value.copy(notice = failureText(e, R.string.workbench_project_create_failed))
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun renameProject(projectId: String, name: String) {
        val c = client ?: return
        val clean = name.trim()
        if (clean.isEmpty() || projectId.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, notice = null)
            try {
                c.agentChatRenameProject(projectId, clean)
                refresh(selectProjectId = projectId)
                DenovaLog.i("Workbench", "renamed project=${projectId.take(12)}")
            } catch (e: Exception) {
                _state.value = _state.value.copy(notice = failureText(e, R.string.workbench_project_rename_failed))
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun confirmArchiveProject(project: AgentChatProject) {
        _state.value = _state.value.copy(
            confirm = WorkbenchConfirm.ArchiveProject(project.id, project.name.ifBlank { project.path }),
        )
    }

    /** 移除（归档）项目：关闭它的全部页签，保留磁盘目录与会话记录。 */
    fun archiveProject(projectId: String) {
        val c = client ?: return
        _state.value = _state.value.copy(confirm = null, busy = true, notice = null)
        viewModelScope.launch {
            try {
                c.agentChatArchiveProject(projectId)
                val tabs = WorkbenchTabs.closeProject(_state.value.tabs, projectId)
                _state.value = _state.value.copy(
                    tabs = tabs,
                    activeTabPrimary = _state.value.activeTabPrimary?.takeIf { id -> tabs.any { it.id == id } },
                    activeTabSecondary = _state.value.activeTabSecondary?.takeIf { id -> tabs.any { it.id == id } },
                )
                refresh()
                DenovaLog.i("Workbench", "archived project=${projectId.take(12)}")
            } catch (e: Exception) {
                _state.value = _state.value.copy(notice = failureText(e, R.string.workbench_project_archive_failed))
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /**
     * 调整项目顺序：先在本地移动，再整表提交 `POST /agent-chat/projects/reorder`。
     * 失败时重新读取，避免界面与服务端顺序长期不一致。
     */
    fun moveProject(projectId: String, delta: Int) {
        val c = client ?: return
        val current = _state.value.projects
        val from = current.indexOfFirst { it.id == projectId }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, current.lastIndex)
        if (to == from) return
        val reordered = current.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        _state.value = _state.value.copy(projects = reordered, notice = null)
        viewModelScope.launch {
            try {
                c.agentChatReorderProjects(reordered.map { it.id })
                DenovaLog.i("Workbench", "reordered projects=$projectId delta=$delta")
            } catch (e: Exception) {
                _state.value = _state.value.copy(notice = failureText(e, R.string.workbench_project_reorder_failed))
                refresh()
            }
        }
    }

    fun dismissConfirm() {
        _state.value = _state.value.copy(confirm = null)
    }

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    /** 页签标题来源：所有项目里的会话标题（服务端最近一次读取的结果）。 */
    fun sessionTitles(): Map<String, String> = _state.value.projects
        .flatMap { it.sessions }
        .associate { it.id to it.title }

    fun isRunning(projectId: String, sessionId: String): Boolean =
        "${projectId}/${sessionId}" in _state.value.runningSessions

    private fun failureText(error: Exception, fallback: Int): UiText {
        val message = (error as? DenovaApiException)?.message?.takeIf { it.isNotBlank() }
        return message?.let { UiText.raw(it) } ?: UiText.of(fallback)
    }

    companion object {
        fun agentTabId(projectId: String, sessionId: String): String = "agent:$projectId:$sessionId"

        /**
         * 终端页签 id 每次新建都唯一：同一项目可以并存多个终端（每个都是独立 owner），
         * 不能像会话那样按「项目 + 会话」去重。
         */
        fun terminalTabId(): String = "term:" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    }
}
