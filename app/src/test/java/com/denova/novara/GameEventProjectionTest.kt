package com.denova.novara

import com.denova.novara.core.stream.GameActivity
import com.denova.novara.core.stream.GameEventType
import com.denova.novara.core.stream.GamePhase
import com.denova.novara.core.stream.GameStageReducer
import com.denova.novara.core.stream.GameStageState
import com.denova.novara.core.stream.GameStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 35 类事件的投影契约（P4-A / GAM-003）。
 *
 * 逐类型验证三件事：
 * 1. `handled=true` 的事件都能被 reducer 消费（不抛异常、阶段仍是合法枚举）——流不会因为没预料到的事件卡死；
 * 2. `handled=false` 的事件完全不改状态（Web 侧同样不投影，避免半成品 UI）；
 * 3. 终态与关键标志位按契约落位（done/aborted/error/suspended、轮次落库、提问挂起）。
 */
class GameEventProjectionTest {

    private fun event(type: GameEventType, data: String? = "{}") =
        GameStreamEvent(id = "e-${type.name}", type = type, data = data)

    @Test
    fun `all handled events project without leaving a legal state`() {
        val base = GameStageState(phase = GamePhase.RUNNING, activity = GameActivity.THINKING)
        val handled = GameEventType.entries.filter { it.handled }
        // 契约 35 类 + UNKNOWN 兜底 = 36 个枚举值；其中 25 类需要投影到 UI。
        assertEquals(36, GameEventType.entries.size)
        assertEquals(25, handled.size)

        handled.forEach { type ->
            val next = GameStageReducer.reduce(base, event(type))
            assertTrue("$type produced unexpected phase ${next.phase}", next.phase in GamePhase.entries)
            assertTrue("$type produced unexpected activity ${next.activity}", next.activity in GameActivity.entries)
        }
    }

    @Test
    fun `unhandled events leave the projection untouched`() {
        val base = GameStageState(
            phase = GamePhase.RUNNING,
            activity = GameActivity.THINKING,
            terminalReason = null,
            receivedPersistedTurn = false,
        )
        GameEventType.entries.filterNot { it.handled }.forEach { type ->
            assertEquals("$type must not change stage", base, GameStageReducer.reduce(base, event(type)))
        }
    }

    @Test
    fun `terminal and marker events match the contract`() {
        val running = GameStageState(phase = GamePhase.RUNNING, activity = GameActivity.THINKING)

        assertEquals(GamePhase.DONE, GameStageReducer.reduce(running, event(GameEventType.DONE)).phase)
        assertEquals(GamePhase.ABORTED, GameStageReducer.reduce(running, event(GameEventType.ABORTED)).phase)
        assertEquals(GamePhase.SUSPENDED, GameStageReducer.reduce(running, event(GameEventType.SUSPENDED)).phase)

        val failed = GameStageReducer.reduce(running, event(GameEventType.ERROR))
        assertEquals(GamePhase.ERROR, failed.phase)
        assertTrue(failed.streamFailed)

        val persisted = GameStageReducer.reduce(running, event(GameEventType.INTERACTIVE_TURN_PERSISTED))
        assertTrue(persisted.receivedPersistedTurn)

        val asked = GameStageReducer.reduce(running, event(GameEventType.ASK_PENDING))
        assertTrue(asked.pendingAsk)

        // checkpoint 会把运行态重置成「恢复中」，并清掉上一轮的落库标志。
        val checkpoint = GameStageReducer.reduce(persisted, event(GameEventType.TASK_CHECKPOINT))
        assertEquals(GameActivity.RECOVERING, checkpoint.activity)
        assertTrue(!checkpoint.receivedPersistedTurn)
    }

    @Test
    fun `unknown wire names fall back to UNKNOWN without projecting`() {
        val type = GameEventType.fromWireName("some_future_event")
        assertEquals(GameEventType.UNKNOWN, type)
        val base = GameStageState(phase = GamePhase.RUNNING, activity = GameActivity.TOOL)
        assertEquals(base, GameStageReducer.reduce(base, event(type)))
    }
}
