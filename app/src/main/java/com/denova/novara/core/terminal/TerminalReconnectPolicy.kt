package com.denova.novara.core.terminal

import com.denova.novara.R
import com.denova.novara.core.i18n.UiText

/**
 * 一次 attach 会话的结束原因，决定要不要重新挂接。
 *
 * 与 G4 的流恢复同理：**不能盲目重连**。PTY 已终止（`exit`）或用户主动关闭时重连
 * 只会得到一个新的空 shell，属于静默丢状态；只有传输层中断才值得重新 attach 同一会话。
 */
sealed interface TerminalSessionEnd {
    /** 服务端下发 `exit`：PTY 进程已结束。 */
    data object Exited : TerminalSessionEnd

    /** 用户主动关闭 / 离开页面 / 释放会话。 */
    data object UserClosed : TerminalSessionEnd

    /** 传输中断（socket 关闭、网络失败、空闲超时）：可重新 attach 同一 PTY。 */
    data class Disconnected(val reason: UiText) : TerminalSessionEnd

    /** 不可恢复（认证 / 权限失效）：重连也会失败。 */
    data class Fatal(val message: String) : TerminalSessionEnd
}

/**
 * 终端连接的心跳与重连参数。
 *
 * 复用 G4 idle guard 的思路：把「没有数据」和「连接已死」区分开——
 * 空闲先主动 `ping` 探活，探活仍然没有回音才判定断开并重连，避免静默挂死。
 */
object TerminalReconnectPolicy {
    /** 心跳间隔：PTY 静默（`sleep 300`、等输入）时也保持链路可判定。 */
    const val PING_INTERVAL_MS = 25_000L

    /** 连续无任何帧（含 `pong`）超过此时长即判定链路死亡。 */
    const val IDLE_TIMEOUT_MS = 45_000L

    /**
     * 流安静超过此时长就顺手对账一次会话列表。
     *
     * 双端共存：另一端 DELETE 时服务端是**静默断 TCP**（无 `exit` 帧），
     * 只靠 idle 看门狗要等满 [IDLE_TIMEOUT_MS] 才发现，体感是「界面还显示已连接」。
     * 空闲时每秒最多一次 REST 对账，把发现时间压到一个心跳周期内。
     */
    const val SESSION_PROBE_AFTER_MS = 20_000L

    /** 单次断线最多重试次数；超过后停下并提示，不再无限重连。 */
    const val MAX_ATTEMPTS = 6

    private const val BASE_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 15_000L

    /** 指数退避：1s / 2s / 4s / 8s / 15s（封顶）。 */
    fun backoffDelay(attempt: Int): Long =
        (BASE_DELAY_MS shl attempt.coerceIn(0, 4)).coerceAtMost(MAX_DELAY_MS)

    fun shouldRetry(end: TerminalSessionEnd, attempt: Int): Boolean =
        end is TerminalSessionEnd.Disconnected && attempt < MAX_ATTEMPTS

    /** 认证 / 权限失败重连无意义。 */
    fun isFatalStatus(status: Int): Boolean = status == 401 || status == 403

    /**
     * 把服务端错误映射成人能看懂的终止原因；返回 null 表示「可重试」。
     *
     * 双端共存（G6）里这几类错误必须**立刻停手并如实告知**，否则会表现为
     * 「一直转圈重连」或更糟的「静默新建一个空 PTY 顶替原来的会话」。
     * 服务端错误键形如 `api.terminal.ownerConflict`。
     */
    fun describeFatalApiError(status: Int, code: String?): UiText? = when {
        code == null -> if (isFatalStatus(status)) UiText.of(R.string.term_fatal_auth) else null
        code.endsWith("ownerConflict") ->
            UiText.of(R.string.term_fatal_owner)
        code.endsWith("tokenInvalid") -> UiText.of(R.string.term_fatal_token)
        code.endsWith("notFound") -> UiText.of(R.string.term_fatal_not_found)
        code.endsWith("tooMany") -> UiText.of(R.string.term_fatal_too_many)
        code.endsWith("disabled") -> UiText.of(R.string.term_fatal_disabled)
        else -> if (isFatalStatus(status)) UiText.of(R.string.term_fatal_auth) else null
    }
}
