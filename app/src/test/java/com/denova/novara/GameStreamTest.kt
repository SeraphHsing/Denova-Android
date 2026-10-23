package com.denova.novara

import com.denova.novara.core.stream.GameEventType
import com.denova.novara.core.stream.GameStreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStreamTest {

    @Test
    fun `contract has 25 handled and 10 ignored plus unknown`() {
        val handled = GameEventType.entries.filter { it.handled && it != GameEventType.UNKNOWN }
        val ignored = GameEventType.entries.filter { !it.handled && it != GameEventType.UNKNOWN }
        assertEquals(25, handled.size)
        assertEquals(10, ignored.size)
    }

    @Test
    fun `checkpoint events are flagged`() {
        assertTrue(GameEventType.TASK_CHECKPOINT.checkpoint)
        assertTrue(GameEventType.TASK_CHECKPOINT_COMMITTED.checkpoint)
        assertTrue(GameEventType.TASK_REHYDRATE_REQUIRED.checkpoint)
        assertFalse(GameEventType.CHUNK.checkpoint)
    }

    @Test
    fun `fromWireName maps snake_case to enum`() {
        assertEquals(GameEventType.AGENT_CYCLE_STARTED, GameEventType.fromWireName("agent_cycle_started"))
        assertEquals(GameEventType.TASK_CHECKPOINT_COMMITTED, GameEventType.fromWireName("task_checkpoint_committed"))
        assertEquals(GameEventType.UNKNOWN, GameEventType.fromWireName("nonexistent_event"))
    }

    @Test
    fun `checkpoint committed records cursor`() {
        val state = GameStreamState()
        state.onEvent(null, GameEventType.TASK_CHECKPOINT)
        assertTrue(state.replayingCheckpoint)
        state.onEvent("cursor-123", GameEventType.TASK_CHECKPOINT_COMMITTED)
        assertFalse(state.replayingCheckpoint)
        assertEquals("cursor-123", state.cursor)
    }

    @Test
    fun `rehydrate required sets flag`() {
        val state = GameStreamState()
        state.onEvent(null, GameEventType.TASK_REHYDRATE_REQUIRED)
        assertTrue(state.rehydrationRequired)
    }

    @Test
    fun `normal events do not change state`() {
        val state = GameStreamState()
        state.onEvent("id-1", GameEventType.CHUNK)
        state.onEvent("id-2", GameEventType.DONE)
        assertNull(state.cursor)
        assertFalse(state.replayingCheckpoint)
        assertFalse(state.rehydrationRequired)
    }
}
