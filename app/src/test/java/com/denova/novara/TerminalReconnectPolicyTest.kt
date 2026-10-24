package com.denova.novara

import com.denova.novara.core.terminal.TerminalReconnectPolicy
import com.denova.novara.core.terminal.TerminalSessionEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断线重连的判定边界：重连错场景比不重连更糟——
 * PTY 已退出还去重连会悄悄给用户一个新 shell，让人以为历史丢了。
 */
class TerminalReconnectPolicyTest {

    @Test
    fun `backoff grows then caps`() {
        assertEquals(1_000L, TerminalReconnectPolicy.backoffDelay(0))
        assertEquals(2_000L, TerminalReconnectPolicy.backoffDelay(1))
        assertEquals(4_000L, TerminalReconnectPolicy.backoffDelay(2))
        assertEquals(8_000L, TerminalReconnectPolicy.backoffDelay(3))
        assertEquals(15_000L, TerminalReconnectPolicy.backoffDelay(4))
        assertEquals(15_000L, TerminalReconnectPolicy.backoffDelay(9))
    }

    @Test
    fun `disconnected session retries until the attempt cap`() {
        val end = TerminalSessionEnd.Disconnected("socket closed")

        assertTrue(TerminalReconnectPolicy.shouldRetry(end, 0))
        assertTrue(TerminalReconnectPolicy.shouldRetry(end, TerminalReconnectPolicy.MAX_ATTEMPTS - 1))
        assertFalse(TerminalReconnectPolicy.shouldRetry(end, TerminalReconnectPolicy.MAX_ATTEMPTS))
    }

    @Test
    fun `exited session never reconnects`() {
        assertFalse(TerminalReconnectPolicy.shouldRetry(TerminalSessionEnd.Exited, 0))
    }

    @Test
    fun `user close and fatal errors never reconnect`() {
        assertFalse(TerminalReconnectPolicy.shouldRetry(TerminalSessionEnd.UserClosed, 0))
        assertFalse(TerminalReconnectPolicy.shouldRetry(TerminalSessionEnd.Fatal("unauthorized"), 0))
    }

    @Test
    fun `only auth failures are fatal statuses`() {
        assertTrue(TerminalReconnectPolicy.isFatalStatus(401))
        assertTrue(TerminalReconnectPolicy.isFatalStatus(403))
        assertFalse(TerminalReconnectPolicy.isFatalStatus(404))
        assertFalse(TerminalReconnectPolicy.isFatalStatus(500))
    }

    @Test
    fun `ownership conflicts stop retrying with a readable reason`() {
        val message = TerminalReconnectPolicy.describeFatalApiError(409, "api.terminal.ownerConflict")

        assertTrue(message, message != null && message.contains("占用"))
    }

    @Test
    fun `token and session errors are terminal too`() {
        assertTrue(TerminalReconnectPolicy.describeFatalApiError(403, "api.terminal.tokenInvalid") != null)
        assertTrue(TerminalReconnectPolicy.describeFatalApiError(404, "api.terminal.notFound") != null)
        assertTrue(TerminalReconnectPolicy.describeFatalApiError(429, "api.terminal.tooMany") != null)
        assertTrue(TerminalReconnectPolicy.describeFatalApiError(503, "api.terminal.disabled") != null)
    }

    @Test
    fun `transient failures stay retryable`() {
        // 未知错误键 / 5xx：退回退避重连，不要误判为终止
        assertEquals(null, TerminalReconnectPolicy.describeFatalApiError(500, null))
        assertEquals(null, TerminalReconnectPolicy.describeFatalApiError(502, "api.common.unavailable"))
        assertEquals(null, TerminalReconnectPolicy.describeFatalApiError(409, "api.common.conflict"))
    }

    @Test
    fun `auth failures are terminal even without an error key`() {
        assertTrue(TerminalReconnectPolicy.describeFatalApiError(401, null) != null)
        assertTrue(TerminalReconnectPolicy.describeFatalApiError(403, null) != null)
    }

    @Test
    fun `session probe fires before the idle watchdog`() {
        // 另一端 DELETE 是静默断连：对账必须在 idle 看门狗之前触发，否则界面会长时间显示「已连接」
        assertTrue(
            TerminalReconnectPolicy.SESSION_PROBE_AFTER_MS <
                TerminalReconnectPolicy.IDLE_TIMEOUT_MS,
        )
    }

    @Test
    fun `idle timeout leaves room for a missed heartbeat`() {
        // 看门狗必须晚于至少一次 ping 的往返，否则静默会话会被反复误判重连
        assertTrue(TerminalReconnectPolicy.IDLE_TIMEOUT_MS > TerminalReconnectPolicy.PING_INTERVAL_MS)
    }
}
