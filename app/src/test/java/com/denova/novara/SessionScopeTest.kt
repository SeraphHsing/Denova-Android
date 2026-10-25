package com.denova.novara

import com.denova.novara.core.stream.SessionScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AGT-011 会话切换迟到回包的作用域判定。
 *
 * 只靠 `Job.cancel()` 不够：取消在下一个挂起点才生效，已经拿到响应正在写状态的代码仍会执行，
 * 于是 A 会话的迟到回包会改写 B 会话的正文与按钮状态。
 */
class SessionScopeTest {

    @Test
    fun `generation starts current and stays current for the same session`() {
        val scope = SessionScope()
        val generation = scope.enter("session-a")
        assertTrue(scope.isCurrent(generation, "session-a"))
    }

    @Test
    fun `switching session retires the previous generation`() {
        val scope = SessionScope()
        val a = scope.enter("session-a")
        val b = scope.enter("session-b")

        assertFalse(scope.isCurrent(a, "session-a"))
        assertTrue(scope.isCurrent(b, "session-b"))
        // 旧世代即使携带新会话 id 也不作数（避免复用代号蒙过校验）
        assertFalse(scope.isCurrent(a, "session-b"))
    }

    @Test
    fun `same session id re-entered advances the generation`() {
        val scope = SessionScope()
        val first = scope.enter("default")
        val second = scope.enter("default")

        assertFalse(scope.isCurrent(first, "default"))
        assertTrue(scope.isCurrent(second, "default"))
    }

    @Test
    fun `generation works as a pure validity token`() {
        val scope = SessionScope()
        val generation = scope.currentGeneration()
        assertTrue(scope.isCurrent(generation, ""))
        scope.enter("session-a")
        assertFalse(scope.isCurrent(generation, ""))
    }
}
