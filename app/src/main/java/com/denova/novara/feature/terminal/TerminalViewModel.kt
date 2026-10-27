package com.denova.novara.feature.terminal

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText
import com.denova.novara.core.i18n.debugName

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.denova.novara.core.logging.DenovaLog
import com.denova.novara.core.network.DenovaApiException
import com.denova.novara.core.network.DenovaClient
import com.denova.novara.core.network.TerminalConnection
import com.denova.novara.core.terminal.AnsiParser
import com.denova.novara.core.terminal.TerminalFrame
import com.denova.novara.core.terminal.TerminalHistory
import com.denova.novara.core.terminal.TerminalHistoryStore
import com.denova.novara.core.terminal.TerminalPerf
import com.denova.novara.core.terminal.TerminalReconnectPolicy
import com.denova.novara.core.terminal.TerminalScreen
import com.denova.novara.core.terminal.TerminalSessionEnd
import com.denova.novara.core.terminal.TerminalSessionInfo
import com.denova.novara.core.terminal.maxScrollOffset
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 终端 ViewModel：创建/复用 PTY 会话 → attach WebSocket → 字节喂给 ANSI 解析器。
 *
 * 尺寸时序：先按服务器会话尺寸对齐网格，历史回放才不会折行错位；收到 `ready`
 * 后再切到画布尺寸并下发 `resize`。网格实例始终复用（`TerminalScreen.resize`
 * 保留内容），因此解析器不需要重建。
 *
 * 连接生命周期（本文件的核心）：
 * - **心跳**：每 [TerminalReconnectPolicy.PING_INTERVAL_MS] 发 `ping`，PTY 静默时也能判定链路死活。
 * - **空闲看门狗**：连续 [TerminalReconnectPolicy.IDLE_TIMEOUT_MS] 没有收到任何帧才判定断开，
 *   避免「没有输出」被误判成「连接已死」（复用 G4 idle guard 的思路）。
 * - **重连**：只有传输中断才重新 attach 同一会话（不新建 PTY、不丢历史）；`exit` / 用户关闭 /
 *   认证失败不重连。重连指数退避，成功收到 `ready` 后计数归零。
 *
 * 渲染解耦：PTY 输出只标记 dirty，由 16ms ticker 合并成一次 [UiState.version] 递增，
 * 避免 64KB/s 级输出把 Compose 重组打满（D03-P 的关键前提）。
 */
class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val status: UiText = UiText.of(R.string.term_status_disconnected),
        val connected: Boolean = false,
        val sessionId: String? = null,
        val projectId: String? = null,
        val exited: Boolean = false,
        val error: UiText? = null,
        val cols: Int = DEFAULT_COLS,
        val rows: Int = DEFAULT_ROWS,
        /** 递增即触发一次重绘。 */
        val version: Int = 0,
        /** 相对屏幕底部上移的行数（0 = 贴底跟随输出）。 */
        val scrollOffset: Int = 0,
        /** 可回看的最大行数（备用屏为 0）。 */
        val scrollbackSize: Int = 0,
        /** 已重试次数，用于状态提示。 */
        val reconnectAttempt: Int = 0,
        /** 本地留存了最近的历史快照（会话被释放后仍可只读回看）。 */
        val historyAvailable: Boolean = false,
        /** 非空时展示历史对话框。 */
        val historyText: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var client: DenovaClient? = null
    private var connection: TerminalConnection? = null
    private var sessionJob: Job? = null
    private var renderJob: Job? = null

    /** 用户/页面的连接意图：取消协程时不代表不要连，只有它为 false 才停止重连。 */
    @Volatile
    private var wantConnected = false

    /** 画布尺寸（未连接时只记录，等 `ready` 后统一对齐）。 */
    private var fitCols: Int? = null
    private var fitRows: Int? = null

    var screen: TerminalScreen = TerminalScreen(DEFAULT_COLS, DEFAULT_ROWS)
        private set
    private val parser = AnsiParser(screen)

    @Volatile
    private var dirty = false

    /** 合帧计数，仅用于周期性输出 D03-P 汇总。 */
    private var perfFlushes = 0

    /** 上一次成功挂接的会话 id：用于识别「会话被其他端释放」，避免静默新建空 PTY。 */
    private var previousSessionId: String? = null

    /** G6 决策 B：会话结束/被释放时落盘的最近历史（只读回看用）。 */
    private val historyStore = TerminalHistoryStore(File(getApplication<Application>().filesDir, "terminal-history"))
    private var lastHistory: String? = null

    /** PTY 归属标识，默认「本机单终端」；工作台页签会改成按页签唯一（见 [bindOwner]）。 */
    private var ownerTabId: String = OWNER_TAB_ID

    fun bindClient(client: DenovaClient) {
        this.client = client
    }

    /**
     * 指定 PTY 归属标识（`owner_tab_id`）。
     *
     * 服务端以 `owner_tab_id` 做幂等键，且**同一个 owner 不能跨项目复用**（`ErrOwnerConflict`）。
     * 独立终端页是「本机一个终端」，用 [OWNER_TAB_ID]；工作台每个终端页签是独立 owner，
     * 必须按页签 id 传入，否则第二个项目的终端会撞 409。
     */
    fun bindOwner(ownerTabId: String) {
        if (ownerTabId.isBlank()) return
        this.ownerTabId = ownerTabId
    }

    /** 连接：优先复用同一项目下 owner 的存活会话（切页 / 重启不丢 PTY），否则新建。 */
    fun connect(projectId: String, cols: Int = screen.cols, rows: Int = screen.rows) {
        // 用户显式点「连接」= 明确要一个终端：允许在旧会话已消失时新建
        startSession(projectId, cols, rows, forgetPreviousSession = true)
    }

    /**
     * 回前台时的自动恢复（不做后台保活，只在解冻后立刻补挂）。
     *
     * 后台期间进程被冻结、socket 已被系统断开，期间无法感知；解冻后立即重挂，
     * 把「会话被 Web 回收」这类情况的暴露时间从心跳周期（25–45s）压到 1s 内。
     * 注意**保留** `previousSessionId`：会话若已被别的端释放，要走「如实提示」而不是静默新建。
     */
    fun onForeground() {
        if (!wantConnected) return
        if (_state.value.connected) return
        val projectId = _state.value.projectId ?: return
        DenovaLog.i("Terminal", "foreground re-attach project=$projectId")
        startSession(projectId, screen.cols, screen.rows, forgetPreviousSession = false, force = true)
    }

    private fun startSession(
        projectId: String,
        cols: Int,
        rows: Int,
        forgetPreviousSession: Boolean,
        force: Boolean = false,
    ) {
        val client = client ?: return
        val trimmed = projectId.trim()
        if (trimmed.isEmpty()) return
        // 已在连接/重连循环里就不要重复建会话（重复 POST 会撞 ownerConflict）
        if (!force && wantConnected && _state.value.projectId == trimmed && sessionJob?.isActive == true) return

        wantConnected = true
        if (forgetPreviousSession) {
            previousSessionId = null
            // 新终端从干净状态开始；上一份历史仍留在磁盘上，需要时可再读
            lastHistory = null
            _state.value = _state.value.copy(historyAvailable = false, historyText = null)
        }
        sessionJob?.cancel()
        // 旧 socket 立即释放：新连接要复用 owner，不能让两个 attach 同时存在
        connection?.close()
        connection = null

        sessionJob = viewModelScope.launch {
            runSessionLoop(client, trimmed, cols, rows)
        }
    }

    /**
     * 会话主循环：解析会话 → 挂接 → 结束后按结束原因决定重连或收尾。
     *
     * [attempt] 在收到 `ready` / 输出时由 [streamSession] 的 `onAlive` 归零，
     * 因此「连上又断」和「一直连不上」走不同的退避节奏。
     */
    private suspend fun runSessionLoop(
        client: DenovaClient,
        projectId: String,
        cols: Int,
        rows: Int,
    ) {
        var attempt = 0
        while (wantConnected) {
            _state.value = _state.value.copy(
                status = if (attempt == 0) UiText.of(R.string.term_status_connecting) else UiText.of(R.string.term_status_reconnecting, attempt),
                connected = false,
                exited = false,
                projectId = projectId,
                reconnectAttempt = attempt,
            )

            val info = try {
                resolveSession(client, projectId, cols, rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DenovaLog.w("Terminal", "resolve session failed project=$projectId", e)
                // 归属冲突 / token 失效 / 会话不存在：重试永远失败，直接停手并说明原因
                val fatal = (e as? DenovaApiException)
                    ?.let { TerminalReconnectPolicy.describeFatalApiError(it.status, it.code) }
                if (fatal != null) {
                    giveUp(fatal)
                    return
                }
                if (!isRetryable(e)) {
                    giveUp(e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.term_err_connect_failed))
                    return
                }
                val end = TerminalSessionEnd.Disconnected(e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.term_err_connect_failed))
                attempt++
                if (!TerminalReconnectPolicy.shouldRetry(end, attempt)) {
                    giveUp(end.reason)
                    return
                }
                _state.value = _state.value.copy(status = UiText.of(R.string.term_status_reconnect_failed), error = end.reason)
                delay(TerminalReconnectPolicy.backoffDelay(attempt - 1))
                continue
            }

            if (info == null) {
                giveUp(UiText.of(R.string.term_err_released))
                return
            }

            val end = streamSession(client, info) { attempt = 0 }
            when (end) {
                TerminalSessionEnd.Exited -> {
                    wantConnected = false
                    return
                }
                TerminalSessionEnd.UserClosed -> return
                is TerminalSessionEnd.Fatal -> {
                    giveUp(UiText.raw(end.message))
                    return
                }
                is TerminalSessionEnd.Disconnected -> {
                    attempt++
                    if (!TerminalReconnectPolicy.shouldRetry(end, attempt)) {
                        giveUp(end.reason)
                        return
                    }
                    DenovaLog.w("Terminal", "disconnected (${end.reason.debugName()}); retry #$attempt")
                    _state.value = _state.value.copy(
                        status = UiText.of(R.string.term_status_lost_retrying, attempt),
                        connected = false,
                        error = end.reason,
                        reconnectAttempt = attempt,
                    )
                    delay(TerminalReconnectPolicy.backoffDelay(attempt - 1))
                }
            }
        }
    }

    /**
     * 列出 owner 的存活会话；没有就新建。重连时同样走这里，因此不会丢 PTY。
     *
     * 返回 null 表示**上一次正在用的会话已经消失**（被其他端 DELETE、或服务端重置）：
     * 此时不能静默新建一个空 PTY 顶替——那会让用户以为历史内容丢了（G6 双端共存）。
     */
    private suspend fun resolveSession(
        client: DenovaClient,
        projectId: String,
        cols: Int,
        rows: Int,
    ): TerminalSessionInfo? {
        val sessions = client.terminalSessions().sessions
        val alive = sessions.firstOrNull {
            it.ownerTabId == ownerTabId && it.projectId == projectId && !it.exited
        }
        if (alive != null) return alive
        val previous = previousSessionId
        if (previous != null && sessions.none { it.id == previous }) {
            DenovaLog.w("Terminal", "previous session $previous is gone; refuse to recreate silently")
            return null
        }
        return client.createTerminalSession(projectId, ownerTabId, cols, rows)
    }

    /**
     * 挂接一次并消费帧流，直到会话结束。
     *
     * 返回值是**结束原因**而不是布尔值：UI 需要区分「进程退出」和「链路断了」，
     * 后者才允许重连。
     */
    private suspend fun streamSession(
        client: DenovaClient,
        info: TerminalSessionInfo,
        onAlive: () -> Unit,
    ): TerminalSessionEnd = coroutineScope {
        // 历史回放是原始 PTY 字节，必须按服务器端尺寸解释；先对齐再切画布尺寸。
        if (info.cols > 0 && info.rows > 0) applySize(info.cols, info.rows, notifyServer = false)

        val conn = client.openTerminal(info.id, info.token)
        connection = conn
        previousSessionId = info.id
        _state.value = _state.value.copy(
            status = UiText.of(R.string.term_status_connected),
            connected = true,
            sessionId = info.id,
            exited = false,
            error = null,
            cols = screen.cols,
            rows = screen.rows,
            reconnectAttempt = 0,
        )
        startRenderTicker()
        TerminalPerf.reset()
        DenovaLog.i("Terminal", "attached session=${info.id} size=${screen.cols}x${screen.rows}")

        // 结束原因与最后收帧时间来自两个协程（帧收集 / 心跳看门狗），用原子量而不是普通 var
        val endRef = AtomicReference<TerminalSessionEnd?>(null)
        val lastFrameAt = AtomicLong(System.currentTimeMillis())

        val heartbeat = launch {
            while (isActive) {
                delay(TerminalReconnectPolicy.PING_INTERVAL_MS)
                val idle = System.currentTimeMillis() - lastFrameAt.get()
                if (idle >= TerminalReconnectPolicy.IDLE_TIMEOUT_MS) {
                    DenovaLog.w("Terminal", "no frames for ${idle}ms; force re-attach")
                    endRef.set(TerminalSessionEnd.Disconnected(UiText.of(R.string.term_err_idle_timeout)))
                    // 关掉 socket 让上面的 collect 退出，再走重连
                    conn.close()
                    return@launch
                }
                // 双端共存对账：另一端 DELETE 是静默断 TCP，空闲时主动查一次会话是否还在
                if (idle >= TerminalReconnectPolicy.SESSION_PROBE_AFTER_MS) {
                    val alive = runCatching {
                        client.terminalSessions().sessions.any { it.id == info.id }
                    }.getOrNull()
                    if (alive == false) {
                        DenovaLog.w("Terminal", "session ${info.id} gone (released by another client)")
                        endRef.set(TerminalSessionEnd.Disconnected(UiText.of(R.string.term_err_released_short)))
                        conn.close()
                        return@launch
                    }
                }
                conn.ping()
            }
        }

        try {
            conn.frames.collect { frame ->
                lastFrameAt.set(System.currentTimeMillis())
                when (frame) {
                    is TerminalFrame.Output -> {
                        val parseStart = System.nanoTime()
                        parser.feed(frame.bytes)
                        TerminalPerf.onFrameParsed((System.nanoTime() - parseStart) / 1_000)
                        dirty = true
                        onAlive()
                    }
                    is TerminalFrame.Ready -> {
                        applyFit()
                        onAlive()
                        _state.value = _state.value.copy(status = UiText.of(R.string.term_status_ready), connected = true)
                    }
                    is TerminalFrame.Exit -> {
                        dirty = true
                        previousSessionId = null
                        // 进程结束也留一份快照：会话没了，但输出对用户还有价值
                        captureHistory()
                        endRef.compareAndSet(null, TerminalSessionEnd.Exited)
                        _state.value = _state.value.copy(
                            status = UiText.of(R.string.term_status_exited, frame.code),
                            connected = false,
                            exited = true,
                            error = frame.error?.let { UiText.raw(it) },
                        )
                    }
                    is TerminalFrame.Error -> {
                        endRef.compareAndSet(
                            null,
                            TerminalSessionEnd.Disconnected(frame.message?.let { UiText.raw(it) } ?: UiText.of(R.string.term_err_disconnected)),
                        )
                        _state.value = _state.value.copy(status = UiText.of(R.string.term_status_error), error = frame.message?.let { UiText.raw(it) })
                    }
                    is TerminalFrame.Closed -> {
                        endRef.compareAndSet(
                            null,
                            TerminalSessionEnd.Disconnected(
                                frame.reason?.takeIf { it.isNotBlank() }?.let { UiText.raw(it) } ?: UiText.of(R.string.term_status_closed_code, frame.code),
                            ),
                        )
                    }
                    TerminalFrame.Pong -> onAlive()
                }
            }
        } catch (e: CancellationException) {
            endRef.set(TerminalSessionEnd.UserClosed)
            throw e
        } catch (e: Exception) {
            DenovaLog.w("Terminal", "stream failed", e)
            endRef.compareAndSet(null, TerminalSessionEnd.Disconnected(e.message?.let { UiText.raw(it) } ?: UiText.of(R.string.term_err_disconnected)))
        } finally {
            heartbeat.cancel()
            conn.close()
            // 只有当前连接才允许收尾：重连时旧连接的收尾不能把新连接的状态覆盖掉
            if (connection === conn) {
                renderJob?.cancel()
                renderJob = null
                connection = null
                // 断开时补一次渲染，保证退出前的输出可见
                _state.value = _state.value.copy(
                    version = _state.value.version + 1,
                    connected = false,
                )
                DenovaLog.i("TerminalPerf", "final " + TerminalPerf.summary())
            }
        }

        endRef.get() ?: TerminalSessionEnd.Disconnected(UiText.of(R.string.term_err_ended))
    }

    private fun isRetryable(e: Exception): Boolean =
        !(e is DenovaApiException && TerminalReconnectPolicy.isFatalStatus(e.status))

    /** 终止重连并落到可见的失败态。 */
    private fun giveUp(message: UiText) {
        wantConnected = false
        // 放弃前先把现有屏幕落盘：会话可能已被其他端释放（PTY 已 kill），
        // 至少让用户还能回看命令与输出（G6 决策 B）。
        captureHistory()
        _state.value = _state.value.copy(
            status = UiText.of(R.string.term_status_failed),
            connected = false,
            error = message,
        )
    }

    /** 把当前屏幕（含回滚）落盘并标记「可回看」，内容截断到约 64KB。 */
    private fun captureHistory() {
        if (lastHistory == null) {
            val text = TerminalHistory.build(screen)
            if (text.isNotBlank()) lastHistory = text
        }
        val origin = client?.serverOrigin
        val project = _state.value.projectId
        if (lastHistory == null && origin != null && project != null) {
            lastHistory = historyStore.load(origin, project)
        }
        val text = lastHistory ?: return
        if (origin != null && project != null) historyStore.save(origin, project, text)
        _state.value = _state.value.copy(historyAvailable = true)
    }

    /** 打开只读历史（会话被释放后仍能看命令与输出）。 */
    fun showHistory() {
        val text = lastHistory ?: return
        _state.value = _state.value.copy(historyText = text)
    }

    fun hideHistory() {
        if (_state.value.historyText == null) return
        _state.value = _state.value.copy(historyText = null)
    }

    /** 收到 `ready` 后把 PTY 对齐到画布尺寸（内容按 [TerminalScreen.resize] 保留）。 */
    private fun applyFit() {
        if (!wantConnected) return
        val cols = fitCols ?: return
        val rows = fitRows ?: return
        if (cols == screen.cols && rows == screen.rows) return
        applySize(cols, rows, notifyServer = true)
    }

    private fun startRenderTicker() {
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            while (isActive) {
                delay(RENDER_INTERVAL_MS)
                if (dirty) {
                    dirty = false
                    val maxScroll = screen.maxScrollOffset
                    _state.value = _state.value.copy(
                        version = _state.value.version + 1,
                        scrollbackSize = maxScroll,
                        scrollOffset = _state.value.scrollOffset.coerceAtMost(maxScroll),
                    )
                    // D03-P：结算「字节到达 → 像素落屏」，并每 ~5s 输出一次累计汇总
                    TerminalPerf.onFlush()
                    if (++perfFlushes % PERF_LOG_EVERY_FLUSHES == 0) {
                        DenovaLog.i("TerminalPerf", TerminalPerf.windowSummaryAndReset())
                    }
                }
            }
        }
    }

    /**
     * 画布尺寸变化（旋转 / IME 弹出 / 分屏）。
     *
     * 未连接时只记录：网格要跟随服务器会话尺寸，回放才不会折行错位，
     * 真正切到画布尺寸发生在 `ready` 之后。
     */
    fun resizeTo(cols: Int, rows: Int) {
        val clampedCols = cols.coerceIn(MIN_COLS, MAX_COLS)
        val clampedRows = rows.coerceIn(MIN_ROWS, MAX_ROWS)
        fitCols = clampedCols
        fitRows = clampedRows
        if (!_state.value.connected) return
        if (clampedCols == screen.cols && clampedRows == screen.rows) return
        applySize(clampedCols, clampedRows, notifyServer = true)
    }

    private fun applySize(cols: Int, rows: Int, notifyServer: Boolean) {
        if (cols == screen.cols && rows == screen.rows) return
        val resizeStart = System.nanoTime()
        screen.resize(cols, rows)
        TerminalPerf.onResize((System.nanoTime() - resizeStart) / 1_000_000)
        // 退出后的会话不再收 resize：服务端已没有对应的 PTY
        if (notifyServer && _state.value.connected) connection?.resize(cols, rows)
        val maxScroll = screen.maxScrollOffset
        _state.value = _state.value.copy(
            cols = screen.cols,
            rows = screen.rows,
            version = _state.value.version + 1,
            scrollbackSize = maxScroll,
            scrollOffset = _state.value.scrollOffset.coerceAtMost(maxScroll),
        )
    }

    // ---- 滚动回看 ----

    /** 手势滚动：正值表示手指下滑（看更早的内容）。 */
    fun scrollBy(lines: Int) {
        if (lines == 0) return
        val maxScroll = screen.maxScrollOffset
        val next = (_state.value.scrollOffset + lines).coerceIn(0, maxScroll)
        if (next == _state.value.scrollOffset) return
        _state.value = _state.value.copy(scrollOffset = next, scrollbackSize = maxScroll)
    }

    fun scrollToBottom() {
        if (_state.value.scrollOffset == 0) return
        _state.value = _state.value.copy(scrollOffset = 0)
    }

    // ---- 输入 ----

    fun sendText(text: String) {
        if (text.isEmpty()) return
        TerminalPerf.markInputSent()
        connection?.input(text)
    }

    fun sendKey(vararg bytes: Int) {
        TerminalPerf.markInputSent()
        connection?.input(ByteArray(bytes.size) { bytes[it].toByte() })
    }

    /** 主动释放 PTY（关闭终端页并释放后端进程）。 */
    fun closeAndRelease() {
        val id = _state.value.sessionId
        val client = client ?: return
        wantConnected = false
        previousSessionId = null
        connection?.close()
        connection = null
        sessionJob?.cancel()
        sessionJob = null
        renderJob?.cancel()
        renderJob = null
        // 新会话从干净屏幕开始，避免上一个会话的内容残留
        screen.reset()
        _state.value = _state.value.copy(
            status = UiText.of(R.string.term_status_closed),
            connected = false,
            sessionId = null,
            projectId = null,
            exited = false,
            error = null,
            cols = screen.cols,
            rows = screen.rows,
            scrollOffset = 0,
            scrollbackSize = 0,
            reconnectAttempt = 0,
            version = _state.value.version + 1,
        )
        if (id != null) {
            viewModelScope.launch {
                runCatching { client.deleteTerminalSession(id) }
                    .onSuccess { DenovaLog.i("Terminal", "released session=$id") }
                    .onFailure { DenovaLog.w("Terminal", "delete session failed", it) }
            }
        }
    }

    override fun onCleared() {
        // 只断开连接，不删除会话：切页 / 重进应能挂回同一 PTY。
        wantConnected = false
        connection?.close()
        sessionJob?.cancel()
        renderJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** 本设备固定的 PTY 归属标识：重启后仍能找到同一会话。 */
        const val OWNER_TAB_ID = "android-main"
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        private const val MIN_COLS = 20
        private const val MAX_COLS = 200
        private const val MIN_ROWS = 5
        private const val MAX_ROWS = 100
        private const val RENDER_INTERVAL_MS = 16L
        /** 每 300 次合帧（≈5s）输出一次 D03-P 汇总。 */
        private const val PERF_LOG_EVERY_FLUSHES = 300
    }
}
