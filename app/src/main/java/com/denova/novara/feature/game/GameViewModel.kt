package com.denova.novara.feature.game

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.command.CommandRecord
import com.denova.novara.core.command.CommandStatus
import com.denova.novara.NovaraApplication
import com.denova.novara.core.command.CommandStore
import com.denova.novara.core.game.StoryCreation
import com.denova.novara.core.game.StoryCreationForm
import com.denova.novara.core.game.StoryCreationIssue
import com.denova.novara.core.game.StoryCreationResources
import com.denova.novara.core.game.GameChoices
import com.denova.novara.core.game.StoryLedger
import com.denova.novara.core.game.StoryLibrary
import com.denova.novara.core.game.StoryStateLedgerBuilder
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.BranchPlan
import com.denova.novara.core.network.BranchSummary
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.SendInteractiveChatRequest
import com.denova.novara.core.network.StorySummary
import com.denova.novara.core.stream.StoryPaging
import com.denova.novara.core.stream.StoryTurnText
import com.denova.novara.core.stream.TurnAnchor
import com.denova.novara.core.stream.TurnEvent
import com.denova.novara.core.network.characterLoreItems
import com.denova.novara.core.stream.GameActivity
import com.denova.novara.core.stream.GameEventType
import com.denova.novara.core.stream.GamePhase
import com.denova.novara.core.stream.GameStageReducer
import com.denova.novara.core.stream.GameStageState
import com.denova.novara.core.stream.GameStreamError
import com.denova.novara.core.stream.GameStreamEvent
import com.denova.novara.core.stream.DrawLatencyMeter
import com.denova.novara.core.stream.GameStreamRecoveryCoordinator
import com.denova.novara.core.stream.NarrativeBuilder
import com.denova.novara.core.stream.RehydrateRequest
import com.denova.novara.core.stream.buildCanonicalNarrative
import com.denova.novara.core.stream.collectWithIdleGuard
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** chunk 事件负载的最小解析（叙事累积用）。 */
@Serializable
private data class ChunkData(val content: String? = null, val subagent: Boolean? = null)

/** chunk 合批发布间隔：约 16 次/秒，兼顾流式观感与主线程负载。 */
private const val CHUNK_FLUSH_INTERVAL_MS = 60L

/** 默认分支 id（与 Web 一致；分支切换属 P4-A 后续切片）。 */
private const val DEFAULT_BRANCH = "main"

/** 轮次状态仍在校算：台账可先展示，稍后补取快照。 */
private const val STATE_STATUS_PENDING = "pending"

/** 向前翻页的每页轮次数（服务端默认 10，这里取 20 减少往返）。 */
private const val EARLIER_PAGE_SIZE = 20

/** 流式正文所属的伪轮次 id（不参与锚点跳转）。 */
private const val LIVE_TURN_ID = "__live__"

/**
 * 渐进式恢复：首屏只取最近若干轮，让「恢复可操作」不必等全量历史；
 * 全量随后台任务补全（history 耗时随 limit 近似线性：3 轮 ≈0.22s，50 轮 ≈2.1s）。
 */
private const val FAST_BASELINE_TURNS = 3
private const val FULL_BASELINE_TURNS = 50

/** 游戏 UI 状态：投影阶段 + 累积叙事 + 连接态 + 快照派生的行动选项。 */
data class GameUiState(
    val storyId: String = "",
    val branchId: String = "",
    val stage: GameStageState = GameStageState(),
    /** canonical 历史叙事基线（挂接前/重取时加载）。 */
    val baseline: String = "",
    val connected: Boolean = false,
    val sending: Boolean = false,
    val error: UiText? = null,
    /** 当前轮的推荐行动（点选只填入输入框，不直接发送，与 Web 一致）。 */
    val choices: List<String> = emptyList(),
    /** 快照是否已加载（决定台账与选项区是否可展示）。 */
    val snapshotLoaded: Boolean = false,
    /** 当前分支总轮次 / 快照窗口之前被省略的轮次数（GAM-007 分页用）。 */
    val turnCount: Int = 0,
    val turnStart: Int = 0,
    val hasEarlierTurns: Boolean = false,
    /** 分支数（`graph.branches`），供右侧控制台概览。 */
    val branchCount: Int = 0,
    /** 已加载轮次的跳转锚点（GAM-007），序号按分支总轮次换算。 */
    val turnAnchors: List<TurnAnchor> = emptyList(),
    /** 是否还有更早的历史可翻页。 */
    val historyHasMore: Boolean = false,
    val loadingEarlier: Boolean = false,
    /** 分支列表（快照 `graph.branches` 或 `GET /branches`）。 */
    val branches: List<BranchSummary> = emptyList(),
    val branchPlan: BranchPlan? = null,
    val branchBusy: Boolean = false,
    val branchError: UiText? = null,
)

/**
 * 故事库状态（P4-A / GAM-002）：服务端列表 + 当前选择 + 操作态。
 * 选择必须先在服务端成功（`POST .../select`），本地不自行改 `current_story_id`。
 */
data class StoryLibraryState(
    val stories: List<StorySummary> = emptyList(),
    val currentStoryId: String = "",
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: UiText? = null,
)

/** 新建故事对话框状态（P4-A / GAM-001）：表单 + 资源目录 + 提交态。 */
data class StoryCreationState(
    val visible: Boolean = false,
    val form: StoryCreationForm = StoryCreationForm(),
    val resources: StoryCreationResources = StoryCreationResources(),
    val loadingResources: Boolean = false,
    val submitting: Boolean = false,
    val error: UiText? = null,
)

/** 游戏流 ViewModel：绑定 client → 恢复协调器 → 投影 + 叙事累积。 */
class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val json = Json { ignoreUnknownKeys = true }
    private val commands = CommandStore((application as NovaraApplication).container.database.commandDao())
    private var client: DenovaClient? = null
    private var coordinator: GameStreamRecoveryCoordinator? = null
    private var streamJob: Job? = null
    private var sendJob: Job? = null

    // D03 性能埋点：事件投影耗时 + 合批频次累计统计（不影响功能）。
    private var perfEventCount = 0
    private var perfTotalNanos = 0L
    private var perfMaxNanos = 0L
    private var perfFlushCount = 0
    private var perfCharsPerFlush = 0

    // 叙事增量分块 + chunk 合批（60ms 发布一次，避免每个 chunk 触发重组）。
    private val narrative = NarrativeBuilder()
    private val pendingChunks = StringBuilder()

    /** 本次挂接后由流追加的正文；全量基线到达时用它重建，避免覆盖流增量。 */
    private val streamTail = StringBuilder()
    private var flushJob: Job? = null

    // 「事件接收 → 投影可见」延迟采样（gfxinfo 在本机不可用，改用直接埋点）。
    private val drawLatency = DrawLatencyMeter()
    @Volatile
    private var lastEventNanos = 0L

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    /** 故事库：进入游戏页时加载；切换 / 改名 / 删除成功后按服务端结果更新。 */
    private val _library = MutableStateFlow(StoryLibraryState())
    val library: StateFlow<StoryLibraryState> = _library.asStateFlow()
    private var libraryJob: Job? = null

    /** 状态台账（GAM-006）：只由快照重建，独立成流避免高频叙事影响右侧抽屉。 */
    private val _ledger = MutableStateFlow(StoryLedger())
    val ledger: StateFlow<StoryLedger> = _ledger.asStateFlow()
    private var snapshotJob: Job? = null

    /** 快照请求期间又有新请求（turn persisted / 流结束）时，当前请求结束后补一次。 */
    private var snapshotQueued = false

    // ---- 叙事分页状态（GAM-007）：已加载轮次「旧 → 新」 ----
    private var loadedTurns: List<TurnEvent> = emptyList()
    private var historyCursor: String? = null
    private var historyHasMore = false
    private var branchJob: Job? = null

    /** 本次发送是否为「从某轮重生成」：收尾后需要整段重取历史。 */
    private var pendingRegeneration = false

    /** 故事世代：切换故事即推进，迟到回包据此丢弃（GAM-002 任务隔离）。 */
    private var storyEpoch = 0L

    /** 新建故事：资源目录加载与提交分开两条 job，避免提交取消已完成的目录加载。 */
    private val _creation = MutableStateFlow(StoryCreationState())
    val creation: StateFlow<StoryCreationState> = _creation.asStateFlow()
    private var creationResourceJob: Job? = null
    private var creationSubmitJob: Job? = null

    /**
     * 叙事分块单独成流：高频（~16 次/秒）更新只驱动叙事面板重组，
     * 不会连带重组状态行与输入框（后者含 IME 布局，代价高）。
     */
    private val _blocks = MutableStateFlow<List<String>>(emptyList())
    val blocks: StateFlow<List<String>> = _blocks.asStateFlow()

    /** 一次性滚动请求（叙事块索引）；UI 消费后调 [onScrollTargetHandled] 清空。 */
    private val _scrollTarget = MutableStateFlow<Int?>(null)
    val scrollTarget: StateFlow<Int?> = _scrollTarget.asStateFlow()

    /** 登录后绑定当前连接的 client，建立恢复协调器。 */
    fun bindClient(client: DenovaClient) {
        this.client = client
        this.coordinator = GameStreamRecoveryCoordinator(
            transport = client,
            reloadCanonical = ::reloadCanonical,
        )
        // 登录后预取故事列表；进入游戏页时页面再按需刷新（不重复挂接）。
        if (_library.value.stories.isEmpty()) loadStories()
    }

    // ---- 轮次版本与重生成（P4-A / GAM-010）----

    /**
     * 切换轮次版本：只换展示版本，不触发新生成。
     * 版本切换会改变规范状态与该轮之后的叙事，因此完成后重取历史与快照（丢弃未落库的流式尾部）。
     */
    fun switchTurnVersion(turnId: String, direction: Int) {
        val client = client ?: return
        val state = _uiState.value
        if (state.storyId.isBlank() || state.branchBusy) return
        val turn = loadedTurns.firstOrNull { it.id == turnId }
        if (turn == null) {
            DenovaLog.w("Game", "switch version turn not loaded turn=$turnId")
            return
        }
        val target = StoryPaging.versionTarget(turn, direction)
        if (target == null) {
            DenovaLog.w("Game", "no ${if (direction < 0) "older" else "newer"} version turn=$turnId")
            return
        }
        _uiState.value = state.copy(branchBusy = true, branchError = null)
        branchJob?.cancel()
        branchJob = viewModelScope.launch {
            try {
                client.switchInteractiveTurnVersion(state.storyId, state.branchId, turnId, target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Game", "switch turn version failed turn=$turnId", e)
                _uiState.value = _uiState.value.copy(
                    branchBusy = false,
                    branchError = e.message?.let { UiText.raw(it) },
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(branchBusy = false)
            DenovaLog.i("Game", "turn version switched turn=$turnId to=$target")
            dropPendingChunks()
            streamTail.setLength(0)
            loadBaseline(state.storyId, state.branchId, FULL_BASELINE_TURNS, "version")
            refreshSnapshot()
        }
    }

    /**
     * 从指定轮重生成：重放该轮的玩家输入并带 `regenerate_from_turn_id`。
     * 服务端会回退到该轮再重跑，收尾后必须重取历史（本地 turns 已失效）。
     */
    fun regenerateTurn(turnId: String) {
        val state = _uiState.value
        if (state.storyId.isBlank() || state.sending) return
        val turn = loadedTurns.firstOrNull { it.id == turnId }
        if (turn == null) {
            DenovaLog.w("Game", "regenerate turn not loaded turn=$turnId")
            return
        }
        val input = StoryPaging.replayInput(turn)
        if (input == null) {
            _uiState.value = state.copy(branchError = UiText.of(R.string.game_regenerate_no_input))
            return
        }
        pendingRegeneration = true
        DenovaLog.i("Game", "regenerate turn=$turnId")
        send(state.storyId, state.branchId, input, regenerateFromTurnId = turnId)
    }

    // ---- 分支与分支方案（P4-A / GAM-008、GAM-009）----

    /** 重新拉取分支列表（切换 / 创建 / 删除后对齐服务端）。 */
    fun loadBranches() {
        val client = client ?: return
        val storyId = _uiState.value.storyId
        if (storyId.isBlank()) return
        branchJob?.cancel()
        branchJob = viewModelScope.launch {
            val branches = try {
                client.interactiveBranches(storyId).branches
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Game", "load branches failed", e)
                _uiState.value = _uiState.value.copy(
                    branchBusy = false,
                    branchError = e.message?.let { UiText.raw(it) },
                )
                return@launch
            }
            if (_uiState.value.storyId != storyId) return@launch
            _uiState.value = _uiState.value.copy(
                branches = branches,
                branchCount = branches.size,
                branchBusy = false,
            )
            DenovaLog.i("Game", "branches loaded count=${branches.size}")
        }
    }

    /** 切换分支：服务端切换成功后才重挂舞台（当前分支身份必须与服务端一致）。 */
    fun switchBranch(branchId: String) {
        val client = client ?: return
        val state = _uiState.value
        if (branchId.isBlank() || branchId == state.branchId) return
        if (state.storyId.isBlank() || state.branchBusy) return
        _uiState.value = state.copy(branchBusy = true, branchError = null)
        branchJob?.cancel()
        branchJob = viewModelScope.launch {
            try {
                client.switchInteractiveBranch(state.storyId, branchId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Game", "switch branch failed id=$branchId", e)
                _uiState.value = _uiState.value.copy(
                    branchBusy = false,
                    branchError = e.message?.let { UiText.raw(it) },
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(branchBusy = false, branchId = branchId)
            DenovaLog.i("Game", "branch switched id=$branchId")
            start(state.storyId, branchId)
        }
    }

    /** 从指定轮创建分支，成功即切过去（与 Web `handleCreateBranch` 一致）。 */
    fun createBranchFromTurn(turnId: String, title: String) {
        val client = client ?: return
        val state = _uiState.value
        if (turnId.isBlank() || state.storyId.isBlank() || state.branchBusy) return
        _uiState.value = state.copy(branchBusy = true, branchError = null)
        branchJob?.cancel()
        branchJob = viewModelScope.launch {
            val branch = try {
                client.createInteractiveBranch(state.storyId, turnId, title.trim())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Game", "create branch failed turn=$turnId", e)
                _uiState.value = _uiState.value.copy(
                    branchBusy = false,
                    branchError = e.message?.let { UiText.raw(it) },
                )
                return@launch
            }
            DenovaLog.i("Game", "branch created id=${branch.id} from=$turnId")
            val merged = _uiState.value.branches + branch
            _uiState.value = _uiState.value.copy(
                branchBusy = false,
                branches = merged,
                branchCount = merged.size,
            )
            switchBranch(branch.id)
        }
    }

    /** 删除分支；删掉的正是当前分支时回到 `main`（服务端通常拒绝删当前分支，此处兜底）。 */
    fun deleteBranch(branchId: String) {
        val client = client ?: return
        val state = _uiState.value
        if (branchId.isBlank() || state.storyId.isBlank() || state.branchBusy) return
        _uiState.value = state.copy(branchBusy = true, branchError = null)
        branchJob?.cancel()
        branchJob = viewModelScope.launch {
            try {
                client.deleteInteractiveBranch(state.storyId, branchId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Game", "delete branch failed id=$branchId", e)
                _uiState.value = _uiState.value.copy(
                    branchBusy = false,
                    branchError = e.message?.let { UiText.raw(it) },
                )
                return@launch
            }
            DenovaLog.i("Game", "branch deleted id=$branchId")
            val remaining = _uiState.value.branches.filterNot { it.id == branchId }
            _uiState.value = _uiState.value.copy(
                branchBusy = false,
                branches = remaining,
                // 概览的分支数来自快照，删除后先本地对齐，等下次快照再核对。
                branchCount = remaining.size,
            )
            if (branchId == state.branchId) start(state.storyId, DEFAULT_BRANCH)
        }
    }

    /**
     * 保存分支方案：以快照里的 `branch_plan.revision` 作为 `base_revision`。
     * 409 冲突按服务端文案提示（不静默覆盖，也不自动重试）。
     */
    fun saveBranchPlan(markdown: String) {
        val client = client ?: return
        val state = _uiState.value
        if (markdown.isBlank() || state.storyId.isBlank() || state.branchBusy) return
        val revision = state.branchPlan?.revision.orEmpty()
        _uiState.value = state.copy(branchBusy = true, branchError = null)
        branchJob?.cancel()
        branchJob = viewModelScope.launch {
            val result = try {
                client.updateBranchPlan(state.storyId, state.branchId, markdown, revision)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: DenovaApiException) {
                DenovaLog.w("Game", "branch plan save rejected status=${e.status} code=${e.code}", e)
                _uiState.value = _uiState.value.copy(
                    branchBusy = false,
                    branchError = e.message?.let { UiText.raw(it) },
                )
                return@launch
            } catch (e: Exception) {
                DenovaLog.w("Game", "branch plan save failed", e)
                _uiState.value = _uiState.value.copy(
                    branchBusy = false,
                    branchError = e.message?.let { UiText.raw(it) },
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(branchBusy = false, branchPlan = result.branchPlan)
            DenovaLog.i("Game", "branch plan saved revision=${result.branchPlan.revision}")
        }
    }

    fun dismissBranchError() {
        if (_uiState.value.branchError != null) {
            _uiState.value = _uiState.value.copy(branchError = null)
        }
    }

    // ---- 故事库（P4-A / GAM-002）----

    /**
     * 拉取故事列表（`GET /api/interactive/stories`）。
     *
     * 服务端 `current_story_id` 命中且舞台尚未开始时自动挂接该故事；
     * 否则只更新列表，等用户点选——不替用户改动服务端的选择。
     */
    fun loadStories(autoAttach: Boolean = false) {
        val client = client ?: return
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            _library.value = _library.value.copy(loading = true, error = null)
            val index = try {
                client.interactiveStories()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Game", "load stories failed", e)
                _library.value = _library.value.copy(loading = false, error = e.message?.let { UiText.raw(it) })
                return@launch
            }
            val stories = StoryLibrary.visible(index.stories)
            val current = StoryLibrary.current(stories, index.currentStoryId)
            _library.value = StoryLibraryState(
                stories = stories,
                currentStoryId = current?.id.orEmpty(),
                loading = false,
            )
            DenovaLog.i("Game", "stories loaded count=${stories.size} current=${current?.id}")
            if (autoAttach && current != null && _uiState.value.storyId.isBlank()) {
                start(current.id, DEFAULT_BRANCH)
            }
        }
    }

    /**
     * 切换故事：先在服务端选中，成功后才更新本地并重新挂接。
     * `preview` 故事或不存在的 id 服务端返回 400，本地保持原选择。
     */
    fun selectStory(storyId: String) {
        val client = client ?: return
        val target = storyId.trim()
        if (target.isEmpty() || target == _library.value.currentStoryId) return
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            _library.value = _library.value.copy(busy = true, error = null)
            try {
                client.selectInteractiveStory(target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Game", "select story failed id=$target", e)
                _library.value = _library.value.copy(busy = false, error = e.message?.let { UiText.raw(it) })
                return@launch
            }
            _library.value = _library.value.copy(currentStoryId = target, busy = false)
            DenovaLog.i("Game", "story selected id=$target")
            start(target, DEFAULT_BRANCH)
        }
    }

    /** 重命名：空标题本地拒绝（不发请求），成功后按服务端返回的摘要原位替换。 */
    fun renameStory(storyId: String, title: String) {
        val client = client ?: return
        val name = title.trim()
        if (name.isEmpty()) {
            _library.value = _library.value.copy(error = UiText.of(R.string.game_story_rename_required))
            return
        }
        val existing = _library.value.stories.firstOrNull { it.id == storyId }
        if (existing != null && existing.title == name) return
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            _library.value = _library.value.copy(busy = true, error = null)
            val updated = try {
                client.renameInteractiveStory(storyId, name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Game", "rename story failed id=$storyId", e)
                _library.value = _library.value.copy(busy = false, error = e.message?.let { UiText.raw(it) })
                return@launch
            }
            _library.value = _library.value.copy(
                stories = StoryLibrary.merged(_library.value.stories, updated),
                busy = false,
            )
            DenovaLog.i("Game", "story renamed id=$storyId title=${updated.title}")
        }
    }

    /** 删除故事；删掉的正是当前故事时回退到列表首个并重新挂接。 */
    fun deleteStory(storyId: String) {
        val client = client ?: return
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            _library.value = _library.value.copy(busy = true, error = null)
            try {
                client.deleteInteractiveStory(storyId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Game", "delete story failed id=$storyId", e)
                _library.value = _library.value.copy(busy = false, error = e.message?.let { UiText.raw(it) })
                return@launch
            }
            val (remaining, next) = StoryLibrary.afterDelete(
                _library.value.stories,
                storyId,
                _library.value.currentStoryId,
            )
            val deletedCurrent = _library.value.currentStoryId == storyId
            // 删的是当前故事时先清空选择，再走一次真正的服务端 select（否则会被去重逻辑短路）。
            _library.value = _library.value.copy(
                stories = remaining,
                currentStoryId = if (deletedCurrent) "" else _library.value.currentStoryId,
                busy = false,
            )
            DenovaLog.i("Game", "story deleted id=$storyId next=$next")
            if (deletedCurrent) {
                stop()
                if (next.isNotBlank()) selectStory(next) else _uiState.value = GameUiState()
            }
        }
    }

    /** 清掉列表级错误提示（对话框关闭时调用）。 */
    fun dismissLibraryError() {
        _library.value = _library.value.copy(error = null)
    }

    // ---- 新建故事（P4-A / GAM-001）----

    /** 打开新建对话框并并行加载资源目录（六个导演模块 + 当前书的资料库角色）。 */
    fun openCreation(projectId: String) {
        if (_creation.value.visible) return
        _creation.value = StoryCreationState(visible = true, loadingResources = true)
        loadCreationResources(projectId)
    }

    fun closeCreation() {
        creationResourceJob?.cancel()
        creationSubmitJob?.cancel()
        _creation.value = StoryCreationState()
    }

    /** 表单更新统一走 transform，避免为几十个字段各写一个 setter。 */
    fun updateCreationForm(transform: (StoryCreationForm) -> StoryCreationForm) {
        val current = _creation.value
        if (!current.visible) return
        _creation.value = current.copy(form = transform(current.form), error = null)
    }

    private fun loadCreationResources(projectId: String) {
        val client = client ?: return
        creationResourceJob?.cancel()
        creationResourceJob = viewModelScope.launch {
            val resources = coroutineScope {
                val tellers = async { fetchOrNull("tellers") { client.interactiveTellers() } }
                val templates = async { fetchOrNull("planning") { client.gamePlanningTemplates() } }
                val events = async { fetchOrNull("events") { client.eventPackages() } }
                val rules = async { fetchOrNull("rules") { client.ruleSystems() } }
                val actors = async { fetchOrNull("actors") { client.actorStates() } }
                val images = async { fetchOrNull("images") { client.imagePresets() } }
                val lore = async {
                    if (projectId.isBlank()) null else fetchOrNull("lore") { client.projectLoreItems(projectId) }
                }
                val tellersRes = tellers.await()
                StoryCreationResources(
                    tellers = tellersRes?.tellers.orEmpty(),
                    defaultTellerId = tellersRes?.defaultId.orEmpty(),
                    planningTemplates = templates.await()?.templates.orEmpty(),
                    eventPackages = events.await()?.packages.orEmpty(),
                    ruleSystems = rules.await()?.systems.orEmpty(),
                    actorStates = actors.await()?.states.orEmpty(),
                    imagePresets = images.await()?.presets.orEmpty(),
                    loreCharacters = lore.await()?.items?.characterLoreItems().orEmpty(),
                )
            }
            val current = _creation.value
            if (!current.visible) return@launch
            _creation.value = current.copy(
                resources = resources,
                form = StoryCreation.applyDefaults(current.form, resources),
                loadingResources = false,
            )
            DenovaLog.i(
                "Game",
                "create resources tellers=${resources.tellers.size} templates=${resources.planningTemplates.size} " +
                    "events=${resources.eventPackages.size} rules=${resources.ruleSystems.size} " +
                    "actors=${resources.actorStates.size} presets=${resources.imagePresets.size} " +
                    "lore=${resources.loreCharacters.size}",
            )
        }
    }

    /** 单个目录失败不阻断其它项；取消照常传播。 */
    private suspend fun <T> fetchOrNull(label: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DenovaLog.w("Game", "create resource $label failed", e)
            null
        }

    /**
     * 提交创建：本地校验 → `POST /api/interactive/stories` → 插入列表 → 直接挂接。
     * 服务端创建时已把 `index.current_story_id` 指向新故事，因此不再调用 `select`。
     */
    fun createStory() {
        val client = client ?: return
        val state = _creation.value
        if (!state.visible || state.submitting) return
        val issue = StoryCreation.validate(state.form)
        if (issue != null) {
            _creation.value = state.copy(error = UiText.of(issue.messageRes()))
            return
        }
        val request = StoryCreation.build(state.form)
        _creation.value = state.copy(submitting = true, error = null)
        creationSubmitJob?.cancel()
        creationSubmitJob = viewModelScope.launch {
            val created = try {
                client.createInteractiveStory(request)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Game", "create story failed", e)
                _creation.value = _creation.value.copy(submitting = false, error = e.message?.let { UiText.raw(it) })
                return@launch
            }
            DenovaLog.i(
                "Game",
                "story created id=${created.id} title_source=${created.titleSource} " +
                    "protagonist=${created.protagonist.mode} opening=${created.opening.mode} " +
                    "choices=${created.choiceCount} turns=${created.turnCount}",
            )
            _creation.value = StoryCreationState()
            _library.value = _library.value.copy(
                stories = StoryLibrary.merged(_library.value.stories, created),
                currentStoryId = created.id,
                error = null,
            )
            start(created.id, DEFAULT_BRANCH)
        }
    }

    private fun StoryCreationIssue.messageRes(): Int = when (this) {
        StoryCreationIssue.CUSTOM_PROTAGONIST_NAME -> R.string.game_create_err_custom_name
        StoryCreationIssue.MISSING_LORE_ITEM -> R.string.game_create_err_lore
        StoryCreationIssue.EMPTY_PRESET_OPENING -> R.string.game_create_err_preset_opening
        StoryCreationIssue.EMPTY_CUSTOM_OPENING -> R.string.game_create_err_custom_opening
        StoryCreationIssue.CHOICE_COUNT_RANGE -> R.string.game_create_err_choice
        StoryCreationIssue.REPLY_CHARS_RANGE -> R.string.game_create_err_reply
        StoryCreationIssue.IMAGE_INTERVAL_RANGE -> R.string.game_create_err_interval
        StoryCreationIssue.DIFFICULTY_RANGE -> R.string.game_create_err_difficulty
        StoryCreationIssue.ROLL_MODIFIER_RANGE -> R.string.game_create_err_roll
    }

    /** 挂接指定 story/branch 的活动游戏流。 */
    fun start(storyId: String, branchId: String) {
        val c = coordinator ?: return
        DenovaLog.i("Game", "start story=$storyId branch=$branchId")
        // 切换故事：结束在途发送，并推进世代，使迟到回包不写入新故事的舞台状态。
        sendJob?.cancel()
        sendJob = null
        storyEpoch++
        dropPendingChunks()
        narrative.reset("")
        streamTail.setLength(0)
        _blocks.value = emptyList()
        resetPerfCounters()
        _uiState.value = GameUiState(storyId = storyId, branchId = branchId)
        // 旧故事的台账、行动选项与分页状态不得残留到新故事。
        _ledger.value = StoryLedger()
        loadedTurns = emptyList()
        historyCursor = null
        historyHasMore = false
        pendingRegeneration = false
        refreshSnapshot()
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            // 渐进恢复：先加载最近 FAST_BASELINE_TURNS 轮让界面立刻可操作，
            // 全量历史在后台补全（不阻塞挂接与首屏）。
            loadBaseline(storyId, branchId, FAST_BASELINE_TURNS, stage = "fast")
            launch {
                loadBaseline(
                    storyId,
                    branchId,
                    FULL_BASELINE_TURNS,
                    stage = "full",
                    mergeStreamTail = true,
                )
            }
            c.recover(storyId, branchId).collect { event ->
                onEvent(event)
            }
            finishStream()
        }
    }

    /**
     * 回前台：若上一轮流因系统中断（后台被掐连接）而异常结束，自动重挂恢复。
     * 正常 done/settle 不触发（[GameStageState.streamFailed] 为 false）。
     */
    fun onForeground() {
        val state = _uiState.value
        if (state.storyId.isBlank() || state.sending) return
        if (!state.stage.streamFailed) return
        DenovaLog.i("Game", "foreground re-attach after stream failure")
        start(state.storyId, state.branchId)
    }

    /** 发送玩家输入：先落本地事务（SUBMITTING）再 POST，流正常结束即 ACCEPTED。 */
    fun send(
        storyId: String,
        branchId: String,
        message: String,
        regenerateFromTurnId: String? = null,
    ) {
        val client = client ?: return
        val text = message.trim()
        if (text.isEmpty() || storyId.isBlank()) return
        val commandId = UUID.randomUUID().toString()
        val request = SendInteractiveChatRequest(
            commandId = commandId,
            storyId = storyId,
            branch = branchId.ifBlank { null },
            message = text,
            regenerateFromTurnId = regenerateFromTurnId,
        )
        val record = CommandRecord(
            commandId = commandId,
            target = "game:$storyId:$branchId",
            payload = json.encodeToString(SendInteractiveChatRequest.serializer(), request),
            status = CommandStatus.SUBMITTING,
            createdAt = System.currentTimeMillis(),
        )
        DenovaLog.i("Game", "send story=$storyId cmd=$commandId")
        // 结束当前观察流，改用 POST 实时流。
        streamJob?.cancel()
        streamJob = null
        sendJob?.cancel()
        _uiState.value = _uiState.value.copy(storyId = storyId, branchId = branchId, sending = true, error = null)
        val sendStartNanos = System.nanoTime()
        perfEventCount = 0
        perfTotalNanos = 0
        perfMaxNanos = 0
        resetPerfCounters()
        // 发送结果只在同一故事世代内写舞台：切换故事后丢弃迟到回包（命令状态仍照常落库）。
        val epoch = storyEpoch
        sendJob = viewModelScope.launch {
            commands.save(record) // 本地事务先落盘
            try {
                var accepted = false
                val idleTerminated = client.postInteractiveChat(request).collectWithIdleGuard(
                    isTaskFinished = {
                        try {
                            !client.active(storyId, branchId).active
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            true
                        }
                    },
                    onEvent = { event ->
                        if (epoch != storyEpoch) return@collectWithIdleGuard
                        if (!accepted) {
                            accepted = true
                            DenovaLog.i("GamePerf", "first-frame=${(System.nanoTime() - sendStartNanos) / 1_000_000.0}ms")
                            commands.updateStatus(commandId, CommandStatus.ACCEPTED)
                            _uiState.value = _uiState.value.copy(sending = false)
                        }
                        onEvent(event)
                    },
                )
                if (!accepted) {
                    commands.updateStatus(commandId, CommandStatus.ACCEPTED)
                    updateUiIfCurrent(epoch) { it.copy(sending = false) }
                }
                if (idleTerminated) {
                    DenovaLog.w("Game", "stream idle timeout; task finished")
                    updateUiIfCurrent(epoch) { it.copy(error = UiText.of(R.string.agent_flow_idle_timeout)) }
                }
                if (epoch == storyEpoch) finishStream()
            } catch (e: GameStreamError) {
                val rejected = e.status in 400..499 && e.code != null && e.code != "agent_runtime.stream_attached"
                commands.updateStatus(commandId, if (rejected) CommandStatus.REJECTED else CommandStatus.UNCERTAIN)
                DenovaLog.w("Game", "send failed code=${e.code} status=${e.status}")
                updateUiIfCurrent(epoch) { it.copy(error = e.message?.let { msg -> UiText.raw(msg) }) }
                if (epoch == storyEpoch) finishStream()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 切故事 / 主动停止会取消发送：命令状态由上层语义决定，这里不吞取消。
                throw e
            } catch (e: Exception) {
                commands.updateStatus(commandId, CommandStatus.UNCERTAIN)
                DenovaLog.w("Game", "send uncertain", e)
                updateUiIfCurrent(epoch) { it.copy(error = e.message?.let { msg -> UiText.raw(msg) }) }
                if (epoch == storyEpoch) finishStream()
            }
        }
    }

    /** 发送到舞台当前故事/分支：UI 不需要知道分支身份。 */
    fun sendCurrent(message: String) {
        val state = _uiState.value
        if (state.storyId.isBlank()) return
        send(state.storyId, state.branchId.ifBlank { DEFAULT_BRANCH }, message)
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        sendJob?.cancel()
        sendJob = null
        _uiState.value = _uiState.value.copy(connected = false)
    }

    private fun onEvent(event: GameStreamEvent) {
        val startNanos = System.nanoTime()
        val current = _uiState.value
        val stage = GameStageReducer.reduce(current.stage, event)

        when (event.type) {
            GameEventType.CHUNK -> {
                val data = runCatching { json.decodeFromString(ChunkData.serializer(), event.data ?: "") }.getOrNull()
                val content = data?.content.orEmpty()
                if (data != null && data.subagent != true && content.isNotEmpty()) {
                    pendingChunks.append(content)
                    scheduleFlush()
                }
                // 正文走合批；阶段变化（例如首帧进入运行中）仍需立即反映。
                if (stage != current.stage) {
                    _uiState.value = current.copy(stage = stage, connected = true, error = null)
                }
            }

            GameEventType.TASK_CHECKPOINT,
            GameEventType.TASK_REHYDRATE_REQUIRED -> {
                dropPendingChunks()
                streamTail.setLength(0)
                narrative.reset(current.baseline)
                _blocks.value = narrative.snapshot
                _uiState.value = current.copy(stage = stage, connected = true, error = null)
            }

            // 轮次落库即代表规范状态推进：重取快照刷新行动选项与台账。
            GameEventType.INTERACTIVE_TURN_PERSISTED -> {
                _uiState.value = current.copy(stage = stage, connected = true, error = null)
                refreshSnapshot()
            }

            else -> _uiState.value = current.copy(stage = stage, connected = true, error = null)
        }

        lastEventNanos = System.nanoTime()

        val costNanos = System.nanoTime() - startNanos
        perfEventCount++
        perfTotalNanos += costNanos
        if (costNanos > perfMaxNanos) perfMaxNanos = costNanos
        if (perfEventCount % 100 == 0) {
            DenovaLog.i(
                "GamePerf",
                "events=$perfEventCount avgMs=${perfTotalNanos / 1_000_000.0 / perfEventCount} maxMs=${perfMaxNanos / 1_000_000.0}",
            )
        }
    }

    /**
     * chunk 合批：把高频 chunk 攒进缓冲，按 [CHUNK_FLUSH_INTERVAL_MS] 合并发布一次。
     * 重组频率从「每 chunk」降到约 16 次/秒，避免 UI 线程被逐帧更新淹没。
     */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = viewModelScope.launch {
            delay(CHUNK_FLUSH_INTERVAL_MS)
            flushPendingChunks()
        }
    }

    private fun flushPendingChunks() {
        if (pendingChunks.isEmpty()) return
        val text = pendingChunks.toString()
        pendingChunks.setLength(0)
        narrative.append(text)
        streamTail.append(text)
        _blocks.value = narrative.snapshot

        perfFlushCount++
        perfCharsPerFlush += text.length
        if (perfFlushCount % 50 == 0) {
            DenovaLog.i(
                "GamePerf",
                "flushes=$perfFlushCount avgChars=${perfCharsPerFlush / perfFlushCount} blocks=${narrative.snapshot.size}",
            )
        }
    }

    /** 每次开始挂接/发送前重置性能计数，保证单轮测量独立可重复。 */
    private fun resetPerfCounters() {
        perfEventCount = 0
        perfTotalNanos = 0
        perfMaxNanos = 0
        perfFlushCount = 0
        perfCharsPerFlush = 0
        drawLatency.reset()
        lastEventNanos = 0
    }

    /** 叙事面板完成一次绘制：结算「最近事件接收 → 可见」的延迟。 */
    fun onNarrativeDrawn() {
        val last = lastEventNanos
        if (last == 0L) return
        lastEventNanos = 0L
        drawLatency.add((System.nanoTime() - last) / 1_000_000)
        if (drawLatency.size % 64 == 0) {
            DenovaLog.i("GamePerf", "latency ${drawLatency.summary()}")
        }
    }

    /** 丢弃未发布的 chunk（checkpoint/rehydrate 会整段重建，避免旧增量串进新基线）。 */
    private fun dropPendingChunks() {
        flushJob?.cancel()
        flushJob = null
        pendingChunks.setLength(0)
    }

    /** 只有当前故事世代的回包才允许写舞台状态。 */
    private inline fun updateUiIfCurrent(epoch: Long, block: (GameUiState) -> GameUiState) {
        if (epoch != storyEpoch) return
        _uiState.value = block(_uiState.value)
    }

    /** 流结束：若仍停在运行中，说明没收到 terminal 事件（流异常中断），标记断开。 */
    private fun finishStream() {
        flushPendingChunks()
        // 结束时无条件落一次延迟汇总，避免仅依赖周期性打点而丢失指标。
        DenovaLog.i("GamePerf", "latency(final) ${drawLatency.summary() ?: "n=0"}")
        val current = _uiState.value
        val stage = if (current.stage.phase == GamePhase.RUNNING) {
            current.stage.copy(phase = GamePhase.ERROR, activity = GameActivity.IDLE, streamFailed = true)
        } else {
            current.stage
        }
        _uiState.value = current.copy(stage = stage, connected = false, sending = false)
        val storyId = _uiState.value.storyId
        val branchId = _uiState.value.branchId
        // 重生成会回退并重跑该轮：本地 turns 已失效，必须整段重取（含叙事重建）。
        if (pendingRegeneration) {
            pendingRegeneration = false
            DenovaLog.i("Game", "regenerated; resync canonical history")
            viewModelScope.launch {
                dropPendingChunks()
                streamTail.setLength(0)
                loadBaseline(storyId, branchId, FULL_BASELINE_TURNS, "regen")
                refreshSnapshot()
                loadStories()
            }
            return
        }
        // 收尾刷新：有轮次落库时快照已由 `interactive_turn_persisted` 刷新，这里只重取列表
        // （更新 turn_count / updated_at）；无落库但确实跑过流时才补一次快照。
        if (current.stage.receivedPersistedTurn) {
            loadStories()
        } else if (current.stage.phase != GamePhase.IDLE) {
            refreshSnapshot()
        }
    }

    /**
     * 重取快照（GAM-003 / GAM-006）：行动选项与状态台账都只认快照。
     * 请求在途时只置「补一次」标记，避免轮次持久化与流结束时的重复请求堆积。
     */
    fun refreshSnapshot() {
        val state = _uiState.value
        if (state.storyId.isBlank()) return
        if (snapshotJob?.isActive == true) {
            snapshotQueued = true
            return
        }
        snapshotJob = viewModelScope.launch {
            var runOnce = true
            while (runOnce || snapshotQueued) {
                runOnce = false
                snapshotQueued = false
                val current = _uiState.value
                loadSnapshot(current.storyId, current.branchId)
            }
        }
    }

    private suspend fun loadSnapshot(storyId: String, branchId: String) {
        val client = client ?: return
        if (storyId.isBlank()) return
        var pendingRetries = 0
        while (true) {
            val snapshot = try {
                client.interactiveSnapshot(storyId, branchId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Game", "snapshot load failed", e)
                return
            }
            // 期间切了故事：丢弃迟到快照，不写新故事的台账与行动选项。
            if (_uiState.value.storyId != storyId) return
            val ledger = StoryStateLedgerBuilder.build(snapshot)
            _ledger.value = ledger
            val choices = GameChoices.of(snapshot.currentTurn)
            _uiState.value = _uiState.value.copy(
                choices = choices,
                snapshotLoaded = true,
                turnCount = snapshot.turnCount,
                turnStart = snapshot.turnStart,
                hasEarlierTurns = snapshot.hasEarlierTurns || snapshot.turnStart > 0,
                branchCount = snapshot.graph.branches.size,
                branches = snapshot.graph.branches,
                branchPlan = snapshot.branchPlan,
            )
            // 轮次总数可能刚变化：重算锚点序号（分块索引不变）。
            _uiState.value = _uiState.value.copy(turnAnchors = buildAnchors())
            // 轮次数以快照为准：列表项可能还是挂接前的旧值，先就地更新避免顶部行显示滞后。
            _library.value = _library.value.copy(
                stories = _library.value.stories.map {
                    if (it.id == storyId) it.copy(turnCount = snapshot.turnCount) else it
                },
            )
            DenovaLog.i(
                "Game",
                "snapshot turns=${snapshot.turns.size} total=${snapshot.turnCount} start=${snapshot.turnStart}" +
                    " actors=${ledger.actors.size} world=${ledger.world.size} changes=${ledger.changes.size}" +
                    " choices=${choices.size} stateStatus=${snapshot.currentTurn?.stateStatus.orEmpty()}",
            )
            // 状态仍在校算（pending）：稍后补取，避免台账停在半成品。
            if (snapshot.currentTurn?.stateStatus == STATE_STATUS_PENDING && pendingRetries < 2) {
                pendingRetries++
                delay(1500)
                continue
            }
            return
        }
    }

    private suspend fun loadBaseline(
        storyId: String,
        branchId: String,
        limit: Int = FULL_BASELINE_TURNS,
        stage: String = "full",
        mergeStreamTail: Boolean = false,
    ) {
        val client = client ?: return
        if (storyId.isBlank()) return
        val startedAt = System.nanoTime()
        try {
            val page = client.interactiveHistory(storyId, branchId, limit = limit)
            if (_uiState.value.storyId != storyId) return
            loadedTurns = page.turns
            historyCursor = page.beforeCursor
            historyHasMore = page.hasMore
            rebuildNarrative(keepStreamTail = mergeStreamTail)
            DenovaLog.i(
                "Game",
                "canonical baseline loaded stage=$stage turns=${page.turns.size} hasMore=${page.hasMore} " +
                    "len=${_uiState.value.baseline.length}",
            )
            DenovaLog.i("Perf", "baseline-$stage=${(System.nanoTime() - startedAt) / 1_000_000}ms")
        } catch (e: Exception) {
            DenovaLog.w("Game", "load canonical baseline failed stage=$stage", e)
        }
    }

    /**
     * 向前翻一页历史（GAM-007）：合并到 [loadedTurns] 前部并整段重建叙事分块，
     * 完成后请求 UI 把视图滚回「翻页前顶部的那一轮」，避免重建后跳回底部。
     */
    fun loadEarlierHistory() {
        val client = client ?: return
        val state = _uiState.value
        if (state.storyId.isBlank() || state.loadingEarlier || !historyHasMore) return
        val cursor = historyCursor ?: return
        val anchorTurnId = state.turnAnchors.firstOrNull()?.turnId
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingEarlier = true, error = null)
            val page = try {
                client.interactiveHistory(state.storyId, state.branchId, EARLIER_PAGE_SIZE, cursor)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Game", "load earlier history failed", e)
                _uiState.value = _uiState.value.copy(
                    loadingEarlier = false,
                    error = e.message?.let { UiText.raw(it) },
                )
                return@launch
            }
            // 期间切了故事或分支：丢弃该页。
            if (_uiState.value.storyId != state.storyId || _uiState.value.branchId != state.branchId) return@launch
            val before = loadedTurns.size
            loadedTurns = StoryPaging.mergeOlder(page.turns, loadedTurns)
            historyCursor = page.beforeCursor
            historyHasMore = page.hasMore
            rebuildNarrative()
            _uiState.value = _uiState.value.copy(loadingEarlier = false)
            _scrollTarget.value = anchorTurnId?.let { narrative.blockIndexFor(it) }
            DenovaLog.i(
                "Game",
                "earlier history loaded added=${loadedTurns.size - before} total=${loadedTurns.size} " +
                    "hasMore=${page.hasMore}",
            )
        }
    }

    /** 跳到指定轮次（GAM-007）：把该轮首块索引交给 UI 滚动。 */
    fun scrollToTurn(turnId: String) {
        val block = narrative.blockIndexFor(turnId)
        if (block == null) {
            DenovaLog.w("Game", "scroll target not found turn=$turnId")
            return
        }
        _scrollTarget.value = block
        DenovaLog.i("Game", "scroll to turn=$turnId block=$block")
    }

    /** UI 完成跳转滚动后清空请求，避免重复定位。 */
    fun onScrollTargetHandled() {
        if (_scrollTarget.value != null) _scrollTarget.value = null
    }

    /**
     * 用 [loadedTurns] 重建叙事分块与锚点。
     * [keepStreamTail] 为 true 时把本次挂接后累积的流式正文接在末尾（渐进式恢复用）。
     */
    private fun rebuildNarrative(keepStreamTail: Boolean = true) {
        val turns = loadedTurns
        narrative.resetTurns(turns.map { it.id to StoryTurnText.of(it) })
        if (keepStreamTail && streamTail.isNotEmpty()) {
            narrative.beginTurn(LIVE_TURN_ID)
            narrative.append(streamTail.toString())
        }
        _blocks.value = narrative.snapshot
        val baseline = buildCanonicalNarrative(turns)
        _uiState.value = _uiState.value.copy(
            baseline = baseline,
            historyHasMore = historyHasMore,
            turnAnchors = buildAnchors(),
        )
    }

    /** 锚点序号按分支总轮次换算：快照晚到时只要总数变化就刷新。 */
    private fun buildAnchors(): List<TurnAnchor> {
        val total = _uiState.value.turnCount
        val first = (total - loadedTurns.size + 1).coerceAtLeast(1)
        return StoryPaging.anchors(loadedTurns, first, narrative::blockIndexFor)
    }

    private suspend fun reloadCanonical(request: RehydrateRequest?) {
        val client = client
        val storyId = _uiState.value.storyId
        val branchId = _uiState.value.branchId
        DenovaLog.w("Game", "rehydrate required task=${request?.taskId} cursor=${request?.cursor}; reload canonical history")
        if (client != null && storyId.isNotBlank()) {
            try {
                val page = client.interactiveHistory(storyId, branchId, limit = FULL_BASELINE_TURNS)
                if (_uiState.value.storyId == storyId) {
                    loadedTurns = page.turns
                    historyCursor = page.beforeCursor
                    historyHasMore = page.hasMore
                }
            } catch (e: Exception) {
                DenovaLog.w("Game", "reload canonical history failed", e)
            }
        }
        dropPendingChunks()
        streamTail.setLength(0)
        rebuildNarrative(keepStreamTail = false)
        _uiState.value = _uiState.value.copy(stage = GameStageState())
    }

    override fun onCleared() {
        streamJob?.cancel()
        sendJob?.cancel()
        flushJob?.cancel()
        libraryJob?.cancel()
        creationResourceJob?.cancel()
        creationSubmitJob?.cancel()
        snapshotJob?.cancel()
        branchJob?.cancel()
        super.onCleared()
    }
}
