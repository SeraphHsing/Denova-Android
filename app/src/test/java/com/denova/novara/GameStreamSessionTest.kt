package com.denova.novara

import com.denova.novara.core.stream.GameEventType
import com.denova.novara.core.stream.GameStreamSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStreamSessionTest {

    @Test
    fun `parses and tracks cursor through checkpoint commit`() {
        val session = GameStreamSession()
        val events = session.onChunk(
            "event: task_checkpoint\ndata: {\"cursor\":41}\n\n" +
                "id: 41\nevent: task_checkpoint_committed\ndata: {\"cursor\":41}\n\n" +
                "id: 42\nevent: chunk\ndata: {\"content\":\"hi\"}\n\n",
        )
        assertEquals(3, events.size)
        assertEquals("42", session.cursor)
        assertFalse(session.replayingCheckpoint)
        assertFalse(session.rehydrationRequired)
    }

    @Test
    fun `enters replay window on checkpoint`() {
        val session = GameStreamSession()
        session.onChunk("event: task_checkpoint\ndata: {\"cursor\":41}\n\n")
        assertTrue(session.replayingCheckpoint)
    }

    @Test
    fun `sets rehydrate request on rehydrate required`() {
        val session = GameStreamSession()
        session.onChunk(
            "event: task_rehydrate_required\n" +
                "data: {\"task_id\":\"t1\",\"cursor\":41,\"settled\":false}\n\n",
        )
        assertTrue(session.rehydrationRequired)
        assertEquals("t1", session.rehydrateRequest?.taskId)
        assertEquals(41L, session.rehydrateRequest?.cursor)
        assertEquals(false, session.rehydrateRequest?.settled)
    }

    @Test
    fun `reset for rehydrate clears cursor and request`() {
        val session = GameStreamSession()
        session.onChunk("id: 42\nevent: chunk\ndata: {}\n\n")
        session.onChunk("event: task_rehydrate_required\ndata: {\"task_id\":\"t1\",\"cursor\":41}\n\n")
        session.resetForRehydrate()
        assertNull(session.cursor)
        assertNull(session.rehydrateRequest)
        assertFalse(session.rehydrationRequired)
    }

    @Test
    fun `ignores unknown event type but still tracks cursor`() {
        val session = GameStreamSession()
        val events = session.onChunk("id: 5\nevent: something_new\ndata: {}\n\n")
        assertEquals(1, events.size)
        assertEquals(GameEventType.UNKNOWN, events[0].type)
        assertEquals("5", session.cursor)
    }
}
