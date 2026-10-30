package com.denova.novara.feature.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.NovaraApplication
import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.persistence.DraftKey
import com.denova.novara.core.persistence.DraftStore
import com.denova.novara.core.persistence.EditorTabsStore
import com.denova.novara.core.save.FallbackConflictArchive
import com.denova.novara.core.save.FileSaveCoordinator
import com.denova.novara.core.save.LocalConflictArchive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class SaveStatus { IDLE, SAVING, SAVED, MERGED, CONFLICT, ERROR }

/**
 * 外部内容冲突（WRI-013）：冲突材料的四份正文 + 归档回执。
 *
 * 对应 Web `ExternalContentConflict`：界面据此给出「保留本地 / 加载外部」二元选择，
 * [recoveryId] 指向服务端（或本地兜底）落盘的冲突记录，供事后回查。
 */
data class ExternalConflict(
    val localContent: String = "",
    val externalContent: String = "",
    val externalRevision: String = "",
    val mergedContent: String = "",
    val recoveryId: String? = null,
    val recoveryPath: String? = null,
)

data class EditorUiState(
    val projectId: String = "",
    val filePath: String = "",
    val content: String = "",
    val baseRevision: String = "",
    val baseContent: String = "",
    val loaded: Boolean = false,
    val saveStatus: SaveStatus = SaveStatus.IDLE,
    val message: UiText? = null,
    val conflictText: String? = null,
    /** 冲突选择（WRI-013）；非空即「等用户选保留本地还是加载外部」。 */
    val conflict: ExternalConflict? = null,
    /** 外部改写正文（合并结果）时递增，用于让界面重建编辑态。 */
    val contentEpoch: Int = 0,
    /** 最近一次已知光标位置：撤销 / 重做按快照恢复选区（WRI-014）。 */
    val caret: Int = 0,
    val undoAvailable: Boolean = false,
    val redoAvailable: Boolean = false,
)

/**
 * 编辑器：打开文件 → 编辑（防抖自动保存）→ revision 冲突合并/提示。保存状态机委托给 [EditorSession]。
 *
 * WRI-003 起同时持有文件标签栏：每个项目（书籍）一份标签桶，打开文件即入列，
 * 切换 / 关闭 / 重排都走 [EditorTabs] 的纯变换，并把顺序与激活项持久化到 [EditorTabsStore]，
 * 供进程重建或切页返回后恢复。服务端仍是文件内容的唯一事实源。
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val drafts = DraftStore((application as NovaraApplication).container.database.draftDao())
    private val tabsStore = EditorTabsStore(application)
    private var client: DenovaClient? = null
    private var session: EditorSession? = null

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _tabs = MutableStateFlow(EditorTabsState())
    val tabs: StateFlow<EditorTabsState> = _tabs.asStateFlow()

    private var collectJob: Job? = null

    /** 当前标签桶所属服务器 origin：切换服务器时标签必须隔离（AGENTS §4）。 */
    private var serverScope: String = ""

    /** 当前标签桶所属项目；与 [openFile] 的 projectId 不同即需要换桶。 */
    private var tabsProjectId: String = ""

    private val persistMutex = Mutex()

    /**
     * 登录后绑定当前连接的 client，建立保存编排与状态机。
     *
     * [restoreTabs] 为 false 时不恢复「上次项目」的标签桶：工作台文件页签有自己的项目作用域，
     * 恢复会把别的项目（或该 ViewModel 上一次使用过的项目）的正文带进来——屏幕上留着别人的文件，
     * 保存还会写回那个项目。文件页签一律按自己页签的项目显式打开文件。
     */
    fun bindClient(client: DenovaClient, restoreTabs: Boolean = true) {
        DenovaLog.i("Editor", "client bound")
        val origin = client.serverOrigin
        if (serverScope.isNotEmpty() && serverScope != origin) {
            // 换服务器：不继承上一个服务器的标签与文件状态。
            tabsProjectId = ""
            _tabs.value = EditorTabsState()
            session?.clear()
        }
        this.client = client
        this.serverScope = origin
        // 冲突材料：服务端日志优先，写不进去落本地兜底（对应 Web 的 localStorage 分支）。
        val archive = FallbackConflictArchive(
            remote = client,
            local = LocalConflictArchive(File(getApplication<Application>().filesDir, "conflicts")),
        )
        // 旧会话的在途保存必须先取消：否则它会在防抖到期后带着旧 client 继续 PUT。
        session?.cancelInFlight()
        val coordinator = FileSaveCoordinator(client, drafts, archive) { projectId, path ->
            DraftKey.of(origin, projectId, path)
        }
        // 草稿身份带服务器作用域（AUTH-006）：A/B 两个服务器上同名项目互不串草稿。
        val s = EditorSession(coordinator, drafts, viewModelScope, origin = origin)
        this.session = s
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            s.state.collect { _uiState.value = it }
        }
        if (restoreTabs) restoreTabs()
    }

    /**
     * 书籍（当前项目）切换：加载该项目的标签桶，并切到它上次激活的文件。
     *
     * 必须换掉编辑器状态：否则上一个项目的正文会留在屏上，自动保存还会写回上一个项目
     * 的文件（标签与正文不属于同一个作用域）。
     */
    fun setProject(projectId: String) {
        val pid = projectId.trim()
        if (pid.isEmpty() || pid == tabsProjectId) return
        viewModelScope.launch {
            loadBucket(pid)
            val active = _tabs.value.activePath
            when {
                active.isNotEmpty() -> openFile(pid, active)
                _uiState.value.filePath.isNotEmpty() -> session?.clear()
            }
        }
    }

    fun openFile(projectId: String, path: String) {
        val pid = projectId.trim()
        val p = path.trim()
        if (pid.isEmpty() || p.isEmpty()) return
        val c = client ?: return
        val s = session ?: return
        EditorPerf.beginOpen()
        viewModelScope.launch {
            loadBucket(pid)
            try {
                val doc = c.readFile(pid, p)
                DenovaLog.i("Editor", "opened project=$pid path=$p revision=${doc.revision}")
                s.open(pid, p, doc.content ?: "", doc.revision)
                EditorPerf.endOpen((doc.content ?: "").length)
                val state = _tabs.value
                _tabs.value = state.copy(paths = EditorTabs.open(state.paths, p), activePath = p)
                persist()
            } catch (e: Exception) {
                DenovaLog.w("Editor", "open failed project=$pid path=$p", e)
                s.failOpen(UiText.of(R.string.editor_open_failed, e.message ?: ""))
            }
        }
    }

    /**
     * 切换标签：先 flush 当前文件（对齐 Web `handleSelectFile`），再打开目标文件。
     * flush 失败（冲突 / 保存错误）时仍切换：未提交内容已在草稿里，回到该标签会恢复。
     */
    fun activateTab(path: String) {
        val state = _tabs.value
        if (path !in state.paths) return
        // 标签已是选中态且正文就是该文件时才无需重新读盘（换项目后可能只有选中态没有正文）。
        if (path == state.activePath && _uiState.value.filePath == path) return
        viewModelScope.launch {
            if (!flush()) DenovaLog.w("Editor", "flush before tab switch failed path=${state.activePath}")
            // 先切选中态再读文件：读失败时标签仍停在该文件并显示错误，不留半切换态。
            _tabs.value = state.copy(activePath = path)
            persist()
            openFile(state.projectId, path)
        }
    }

    /**
     * 关闭标签。关当前标签前先 flush，失败则中止（对齐 Web `handleCloseTab`）；
     * 关掉后按右邻居 → 左邻居 → 首个的顺序激活，最后一个关闭则清空编辑器。
     */
    fun closeTab(path: String) {
        val state = _tabs.value
        if (path !in state.paths) return
        viewModelScope.launch {
            val closingActive = path == state.activePath
            if (closingActive && !flush()) {
                DenovaLog.w("Editor", "flush before close failed path=$path")
                return@launch
            }
            val remaining = EditorTabs.remove(state.paths, path)
            if (!closingActive) {
                _tabs.value = state.copy(paths = remaining)
                persist()
                return@launch
            }
            val nextActive = EditorTabs.nextActiveAfterClose(state.paths, path)
            if (nextActive == null) {
                _tabs.value = state.copy(paths = emptyList(), activePath = "")
                session?.clear()
                persist()
                return@launch
            }
            _tabs.value = state.copy(paths = remaining, activePath = nextActive)
            persist()
            openFile(state.projectId, nextActive)
        }
    }

    /** 激活标签左右移动一位（对齐移动端 Web 标签菜单的「左移 / 右移」）。 */
    fun moveActiveTab(delta: Int) {
        val state = _tabs.value
        val from = state.activeIndex
        if (from < 0) return
        val moved = EditorTabs.move(state.paths, from, from + delta)
        if (moved == state.paths) return
        _tabs.value = state.copy(paths = moved)
        persist()
    }

    /** [composing] 来自 `TextFieldValue.composition != null`，组合态不排自动保存（WRI-008）。 */
    fun updateContent(text: String, composing: Boolean = false, caret: Int = text.length) {
        val s = session ?: return
        EditorPerf.markEditStart()
        s.edit(text, composing, caret)
    }

    fun save() {
        session?.saveNow()
    }

    /** 撤销一步（WRI-014）；正文变化后照常自动保存。 */
    fun undo() {
        session?.undo()
    }

    /** 重做一步（WRI-014）。 */
    fun redo() {
        session?.redo()
    }

    /** 冲突选择之一：保留本地（当前合并后的正文）并立即保存。 */
    fun keepLocalVersion() {
        session?.keepLocalVersion()
    }

    /** 冲突选择之二：丢弃本地改动，加载服务器当前版本（WRI-014）。 */
    fun loadExternalVersion() {
        session?.loadExternalVersion()
    }

    /** 切换书籍前 flush 编辑器草稿（保存并等待）；false 表示保存失败/冲突，应中止切换。 */
    suspend fun flush(): Boolean = session?.flush() ?: true

    /** 进程重建 / 重新登录后恢复：定位上次项目 → 载入标签桶 → 重新打开上次激活文件。 */
    private fun restoreTabs() {
        val origin = serverScope
        if (origin.isBlank()) return
        viewModelScope.launch {
            val projectId = _tabs.value.projectId.ifBlank { tabsStore.lastProject(origin) }
            if (projectId.isBlank()) return@launch
            loadBucket(projectId)
            val state = _tabs.value
            DenovaLog.i(
                "Editor",
                "tabs restored project=$projectId tabs=${state.paths.size} active=${state.activePath}",
            )
            if (state.activePath.isNotEmpty() && _uiState.value.filePath != state.activePath) {
                openFile(projectId, state.activePath)
            }
        }
    }

    /** 载入某个项目的标签桶；同一项目不重复读盘。 */
    private suspend fun loadBucket(projectId: String) {
        if (projectId == tabsProjectId) return
        val (paths, savedActive) = tabsStore.load(scopeKey(projectId))
        tabsProjectId = projectId
        val active = if (savedActive in paths) savedActive else paths.firstOrNull().orEmpty()
        _tabs.value = EditorTabsState(projectId = projectId, paths = paths, activePath = active)
        DenovaLog.i("Editor", "tabs loaded project=$projectId tabs=${paths.size} active=$active")
    }

    /** 写入最新标签快照；串行化避免连续开关标签时后写覆盖先写。 */
    private fun persist() {
        if (_tabs.value.projectId.isBlank()) return
        viewModelScope.launch {
            persistMutex.withLock {
                val state = _tabs.value
                if (state.projectId.isBlank()) return@withLock
                tabsStore.save(scopeKey(state.projectId), state.paths, state.activePath)
                tabsStore.saveLastProject(serverScope, state.projectId)
            }
        }
    }

    private fun scopeKey(projectId: String): String = "$serverScope|$projectId"
}
