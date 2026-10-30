package com.denova.novara

import com.denova.novara.core.auth.SessionExpiryConfirmer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迟到 401 的二次确认（AUTH-004）：只有 status 明确「未认证」才降级。
 */
class SessionExpiryConfirmerTest {

    @Test
    fun `expired when status reports unauthenticated`() = runBlocking {
        assertTrue(SessionExpiryConfirmer { false }.isExpired())
    }

    @Test
    fun `late 401 is ignored when status still authenticated`() = runBlocking {
        // 登录成功后的旧请求迟到 401：会话仍然有效，不能把新登录顶掉。
        assertFalse(SessionExpiryConfirmer { true }.isExpired())
    }

    @Test
    fun `probe failure keeps the current session`() = runBlocking {
        // 网络异常无法确认时保守保持；下一次真实请求的 401 会再触发确认。
        assertFalse(SessionExpiryConfirmer { null }.isExpired())
    }

    @Test
    fun `probe runs once per confirmation`() = runBlocking {
        var calls = 0
        val confirmer = SessionExpiryConfirmer {
            calls++
            false
        }
        assertTrue(confirmer.isExpired())
        assertTrue(confirmer.isExpired())
        assertTrue(calls == 2)
    }
}
