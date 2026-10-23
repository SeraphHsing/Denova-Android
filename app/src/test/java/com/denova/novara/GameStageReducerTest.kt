package com.denova.novara

import com.denova.novara.core.stream.GameActivity
import com.denova.novara.core.stream.GameEventType
import com.denova.novara.core.stream.GamePhase
import com.denova.novara.core.stream.GameStageReducer
import com.denova.novara.core.stream.GameStageState
import com.denova.novara.core.stream.GameStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStageReducerTest {

    @Test
    fun `checkpoint resets projection`() {
        val state = GameStageState(
            phase = GamePhase.DONE,
            activity = GameActivity.IDLE,
            receivedPersistedTurn = true,
            streamFailed = true,
        )
        val next = GameStageReducer.reduce(state, GameStreamEvent(null, GameEventType.TASK_CHECKPOINT))
        assertEquals(GamePhase.RUNNING, next.phase)
        assertEquals(GameActivity.RECOVERING, next.activity)
        assertFalse(next.receivedPersistedTurn)
        assertFalse(next.streamFailed)
    }

    @Test
    fun `terminal events set phase`() {
        assertEquals(GamePhase.DONE, GameStageReducer.reduce(GameStageState(), GameStreamEvent(null, GameEventType.DONE)).phase)
        assertEquals(GamePhase.ABORTED, GameStageReducer.reduce(GameStageState(), GameStreamEvent(null, GameEventType.ABORTED)).phase)
        assertEquals(GamePhase.SUSPENDED, GameStageReducer.reduce(GameStageState(), GameStreamEvent(null, GameEventType.SUSPENDED)).phase)
        assertEquals(GamePhase.ERROR, GameStageReducer.reduce(GameStageState(), GameStreamEvent(null, GameEventType.ERROR)).phase)
    }

    @Test
    fun `error marks stream failed`() {
        val next = GameStageReducer.reduce(GameStageState(), GameStreamEvent(null, GameEventType.ERROR))
        assertTrue(next.streamFailed)
    }

    @Test
    fun `agent cycle started transitions to running thinking`() {
        val next = GameStageReducer.reduce(GameStageState(), GameStreamEvent(null, GameEventType.AGENT_CYCLE_STARTED))
        assertEquals(GamePhase.RUNNING, next.phase)
        assertEquals(GameActivity.THINKING, next.activity)
    }

    @Test
    fun `tool events set tool activity`() {
        val toolTypes = listOf(GameEventType.TOOL_CALL, GameEventType.TOOL_STARTED, GameEventType.TOOL_PROGRESS)
        for (type in toolTypes) {
            assertEquals(GameActivity.TOOL, GameStageReducer.reduce(GameStageState(), GameStreamEvent(null, type)).activity)
        }
    }

    @Test
    fun `ask pending and resolved toggle`() {
        val pending = GameStageReducer.reduce(GameStageState(), GameStreamEvent(null, GameEventType.ASK_PENDING))
        assertTrue(pending.pendingAsk)
        val resolved = GameStageReducer.reduce(pending, GameStreamEvent(null, GameEventType.ASK_RESOLVED))
        assertFalse(resolved.pendingAsk)
    }

    @Test
    fun `turn persisted marks received`() {
        val next = GameStageReducer.reduce(GameStageState(), GameStreamEvent(null, GameEventType.INTERACTIVE_TURN_PERSISTED))
        assertTrue(next.receivedPersistedTurn)
    }

    @Test
    fun `ignored events leave state unchanged`() {
        val state = GameStageState(phase = GamePhase.RUNNING)
        val ignoredTypes = listOf(
            GameEventType.GOAL_EVALUATION_FAILED,
            GameEventType.WORKSPACE_CHANGE,
            GameEventType.CONTEXT_CLEANUP,
            GameEventType.SUBAGENT_ARTIFACT,
        )
        for (type in ignoredTypes) {
            assertEquals(state, GameStageReducer.reduce(state, GameStreamEvent(null, type)))
        }
    }
}
